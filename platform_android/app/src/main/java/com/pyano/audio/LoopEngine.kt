// LoopEngine: MIDI loop recorder/player — stores timestamped MIDI events, plays back through FluidSynth.
// NOT concerned with: audio recording, JNI, UI state. Purely Kotlin-level event capture and replay.
package com.pyano.audio

import android.util.Log
import kotlinx.coroutines.*

enum class MidiEventType { NOTE_ON, NOTE_OFF, CONTROL_CHANGE }

data class TimestampedMidiEvent(
    val offsetNs: Long,
    val channel: Int,
    val type: MidiEventType,
    val note: Int,
    val velocity: Int
)

/**
 * MIDI loop engine with up to [MAX_LAYERS] overdub layers.
 *
 * Records live MIDI events as [TimestampedMidiEvent] with nanosecond offsets from loop start,
 * then replays them through [FluidSynthEngine] in a tight coroutine loop. Live playing and
 * loop playback coexist — both drive the same FluidSynth instance simultaneously.
 *
 * Timing model: wall-clock compensated coroutine [delay] between events. FluidSynth's own
 * audio buffer (typically 256+ samples @ 48 kHz ≈ 5 ms) dominates latency anyway.
 *
 * Thread safety: all layer access is synchronized on [layerLock].
 */
class LoopEngine(private val engine: FluidSynthEngine) {

    companion object {
        private const val TAG = "Pyano"
        const val MAX_LAYERS = 4
        const val BPM_MIN = 40
        const val BPM_MAX = 240
    }

    // --- Thread safety ---
    private val layerLock = Any()

    // --- Layer storage ---
    private val layers: Array<MutableList<TimestampedMidiEvent>> =
        Array(MAX_LAYERS) { mutableListOf() }
    private val layerMuted = BooleanArray(MAX_LAYERS) { false }
    private val layerNames = Array(MAX_LAYERS) { "Layer ${it + 1}" }

    // --- Tempo / length ---
    @Volatile var bpm: Int = 120
        private set
    @Volatile var timeSigBeats: Int = 4
        private set
    @Volatile var loopLengthBars: Int = 4
        private set

    /** Loop duration in nanoseconds, recomputed on any tempo/length change. */
    val loopDurationNs: Long
        get() = (loopLengthBars.toLong() * timeSigBeats * 60_000_000_000L) / bpm

    // --- Recording state ---
    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var recordingLayerIndex: Int = -1
        private set
    @Volatile var recordStartNs: Long = 0L
        private set

    // --- Playback state ---
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var currentPositionNs: Long = 0L
        private set
    private var playbackJob: Job? = null

    // --- Public API: tempo / length ---

    fun setBpm(value: Int) {
        bpm = value.coerceIn(BPM_MIN, BPM_MAX)
    }

    fun setTimeSigBeats(beats: Int) {
        timeSigBeats = beats.coerceIn(1, 12)
    }

    fun setLoopLengthBars(bars: Int) {
        loopLengthBars = bars.coerceIn(1, 16)
    }

    // --- Public API: recording ---

    /**
     * Start recording to the next empty layer. Returns the layer index, or -1 if all full.
     */
    fun startRecording(): Int {
        val idx = synchronized(layerLock) {
            layers.indexOfFirst { it.isEmpty() }
        }
        if (idx == -1) {
            Log.w(TAG, "LoopEngine: all $MAX_LAYERS layers full, cannot record")
            return -1
        }
        recordingLayerIndex = idx
        recordStartNs = System.nanoTime()
        isRecording = true
        Log.i(TAG, "LoopEngine: recording started on layer $idx")
        return idx
    }

    /**
     * Start recording to a specific layer, clearing it first if non-empty.
     * Returns the layer index on success, or -1 if index is out of range.
     */
    fun startRecordingOnLayer(index: Int): Int {
        if (index !in 0 until MAX_LAYERS) return -1
        synchronized(layerLock) {
            layers[index].clear()
        }
        layerMuted[index] = false
        recordingLayerIndex = index
        recordStartNs = System.nanoTime()
        isRecording = true
        Log.i(TAG, "LoopEngine: recording started on layer $index (re-record)")
        return index
    }

    /** Stop recording the current layer. */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        val count = synchronized(layerLock) {
            layers.getOrNull(recordingLayerIndex)?.size ?: 0
        }
        Log.i(TAG, "LoopEngine: recording stopped on layer $recordingLayerIndex ($count events)")
        recordingLayerIndex = -1
    }

    /**
     * Called by [MidiRecordingListener] bridge to capture a live MIDI event during recording.
     * Offset is computed relative to loop start, modulo loop duration for seamless looping.
     */
    fun recordEvent(channel: Int, type: MidiEventType, note: Int, velocity: Int) {
        if (!isRecording || recordingLayerIndex < 0) return
        val elapsed = System.nanoTime() - recordStartNs
        val duration = loopDurationNs
        val offset = if (duration > 0) elapsed % duration else elapsed
        synchronized(layerLock) {
            layers[recordingLayerIndex].add(
                TimestampedMidiEvent(offset, channel, type, note, velocity)
            )
        }
    }

    // --- Public API: playback ---

    /**
     * Start looped playback of all non-empty layers. Uses the provided [scope] for the
     * playback coroutine so cancellation is tied to the caller's lifecycle.
     */
    fun startPlayback(scope: CoroutineScope) {
        if (isPlaying) return
        isPlaying = true
        currentPositionNs = 0L
        playbackJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "LoopEngine: playback started")
            try {
                while (isActive) {
                    playOneLoop()
                }
            } finally {
                // Kill any sustained notes from playback on stop
                allNotesOff()
                isPlaying = false
                currentPositionNs = 0L
                Log.i(TAG, "LoopEngine: playback stopped")
            }
        }
    }

    /** Stop playback, cancel the coroutine, kill sustained notes. */
    fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        // isPlaying reset in the coroutine's finally block
    }

    /**
     * Check if recording should auto-stop at loop boundary.
     * Called from the ViewModel's polling loop. Works in both overdub mode
     * (playback + recording) and standalone recording mode.
     * Returns true if recording was auto-stopped.
     */
    fun checkAutoStopRecording(): Boolean {
        if (!isRecording) return false
        val elapsed = System.nanoTime() - recordStartNs
        if (elapsed >= loopDurationNs) {
            stopRecording()
            return true
        }
        return false
    }

    // --- Public API: recording position ---

    /**
     * Elapsed nanoseconds since recording started. Returns 0 if not recording.
     * Used by ViewModel to compute transport position during record-only mode (no playback).
     */
    fun getRecordingPositionNs(): Long {
        if (!isRecording) return 0L
        return System.nanoTime() - recordStartNs
    }

    // --- Public API: layer management ---

    fun clearLayer(index: Int) {
        if (index in 0 until MAX_LAYERS) {
            synchronized(layerLock) {
                layers[index].clear()
            }
            layerMuted[index] = false
            layerNames[index] = "Layer ${index + 1}"
            Log.i(TAG, "LoopEngine: cleared layer $index")
        }
    }

    fun clearAll() {
        synchronized(layerLock) {
            for (layer in layers) layer.clear()
        }
        layerMuted.fill(false)
        for (i in layerNames.indices) layerNames[i] = "Layer ${i + 1}"
        Log.i(TAG, "LoopEngine: cleared all layers")
    }

    fun getLayerEventCount(index: Int): Int = synchronized(layerLock) {
        layers.getOrNull(index)?.size ?: 0
    }

    fun hasAnyEvents(): Boolean = synchronized(layerLock) {
        layers.any { it.isNotEmpty() }
    }

    fun isLayerMuted(index: Int): Boolean =
        index in 0 until MAX_LAYERS && layerMuted[index]

    fun toggleLayerMuted(index: Int) {
        if (index in 0 until MAX_LAYERS) {
            layerMuted[index] = !layerMuted[index]
            Log.i(TAG, "LoopEngine: layer $index muted=${layerMuted[index]}")
        }
    }

    fun getLayerName(index: Int): String =
        layerNames.getOrElse(index) { "Layer ${index + 1}" }

    fun setLayerName(index: Int, name: String) {
        if (index in 0 until MAX_LAYERS) {
            layerNames[index] = name
        }
    }

    fun getLayerEvents(index: Int): List<TimestampedMidiEvent> = synchronized(layerLock) {
        layers.getOrNull(index)?.toList() ?: emptyList()
    }

    fun setLayerEvents(index: Int, events: List<TimestampedMidiEvent>) {
        if (index in 0 until MAX_LAYERS) {
            synchronized(layerLock) {
                layers[index].clear()
                layers[index].addAll(events)
            }
        }
    }

    // --- Internal: playback loop ---

    /**
     * Play through one full loop iteration. Merges all layer events, sorts by offset,
     * dispatches to FluidSynth with wall-clock compensated delays.
     */
    private suspend fun playOneLoop() {
        val duration = loopDurationNs
        if (duration <= 0) return

        // Snapshot all events under lock to avoid concurrent modification
        val merged = mutableListOf<TimestampedMidiEvent>()
        synchronized(layerLock) {
            for ((i, layer) in layers.withIndex()) {
                if (layer.isNotEmpty() && !layerMuted[i]) merged.addAll(layer)
            }
        }
        if (merged.isEmpty()) {
            // No events — just wait one loop duration and update position
            val stepNs = 10_000_000L // 10ms position update granularity
            var elapsed = 0L
            while (elapsed < duration) {
                val chunk = minOf(stepNs, duration - elapsed)
                delay(chunk / 1_000_000) // ns -> ms
                elapsed += chunk
                currentPositionNs = elapsed
            }
            return
        }
        merged.sortBy { it.offsetNs }

        // Wall-clock compensation: track actual elapsed time to correct for delay() jitter
        val loopStartWall = System.nanoTime()

        for (event in merged) {
            // Compute how long we should have been running at this event's offset
            val targetElapsedNs = event.offsetNs
            val actualElapsedNs = System.nanoTime() - loopStartWall
            val delayNs = targetElapsedNs - actualElapsedNs
            if (delayNs > 1_000_000L) { // >1ms worth delaying
                delay(delayNs / 1_000_000)
            }

            // Dispatch to FluidSynth
            dispatchEvent(event)

            // Update position for UI transport
            currentPositionNs = event.offsetNs
        }

        // Wait remaining time after last event to complete the loop
        val lastOffsetNs = merged.last().offsetNs
        val remainingNs = duration - lastOffsetNs
        if (remainingNs > 0) {
            val stepNs = 10_000_000L
            var targetNs = lastOffsetNs + stepNs
            while (targetNs < duration) {
                val actualElapsed = System.nanoTime() - loopStartWall
                val delayMs = (targetNs - actualElapsed) / 1_000_000
                if (delayMs > 0) delay(delayMs)
                currentPositionNs = targetNs
                targetNs += stepNs
            }
            // Final wait to exactly complete the loop
            val actualElapsed = System.nanoTime() - loopStartWall
            val finalDelay = (duration - actualElapsed) / 1_000_000
            if (finalDelay > 0) delay(finalDelay)
            currentPositionNs = duration
        }
    }

    private fun dispatchEvent(event: TimestampedMidiEvent) {
        when (event.type) {
            MidiEventType.NOTE_ON -> engine.noteOn(event.channel, event.note, event.velocity)
            MidiEventType.NOTE_OFF -> engine.noteOff(event.channel, event.note)
            MidiEventType.CONTROL_CHANGE -> engine.cc(event.channel, event.note, event.velocity)
        }
    }

    /** Send all-notes-off (CC 123) on all 16 MIDI channels to kill hanging notes. */
    private fun allNotesOff() {
        for (ch in 0..15) {
            engine.cc(ch, 123, 0)
        }
    }
}
