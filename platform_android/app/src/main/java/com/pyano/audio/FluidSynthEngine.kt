// FluidSynthEngine: Kotlin binding over the native FluidSynth/Oboe engine — notes, effects, metronome, recording.
// NOT concerned with: MIDI byte parsing, DSP math, or UI state.
package com.pyano.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream

data class SfPreset(val bank: Int, val program: Int, val name: String)

/**
 * Output-stream health snapshot. [maxCallbackMicros] and [peakVoices] are the worst
 * values since the previous [FluidSynthEngine.getAudioStats] call.
 */
data class AudioStats(
    val xruns: Int,
    val bufferFrames: Int,
    val burstFrames: Int,
    val callbackFrames: Int,
    val maxCallbackMicros: Int,
    val peakVoices: Int,
    val sampleRate: Int,
    /** Synth events discarded because the native event queue was full. */
    val droppedEvents: Int,
    /** Callbacks that output silence because a SoundFont load held the synth. */
    val lockedOutCallbacks: Int,
) {
    val bufferMs: Float get() = if (sampleRate > 0) bufferFrames * 1000f / sampleRate else 0f

    /** Worst callback duration as a fraction of the time one callback's frames last. */
    val callbackLoad: Float
        get() = if (sampleRate > 0 && callbackFrames > 0) {
            maxCallbackMicros / (callbackFrames * 1_000_000f / sampleRate)
        } else 0f
}

class FluidSynthEngine {
    private var enginePtr: Long = 0
    var soundFontId: Int = -1
        private set

    companion object {
        private const val TAG = "Pyano"
        init {
            System.loadLibrary("pyano-native")
        }
    }

    /** [bufferFrames] is the output buffer size in frames; 0 = automatic tuning. */
    fun create(sampleRate: Int = 48000, bufferFrames: Int = 0): Boolean {
        enginePtr = nativeCreate(sampleRate, bufferFrames)
        return enginePtr != 0L
    }

    fun destroy() {
        if (enginePtr != 0L) {
            nativeDestroy(enginePtr)
            enginePtr = 0
        }
    }

    fun startAudio(): Boolean {
        if (enginePtr == 0L) return false
        return nativeStartAudio(enginePtr)
    }

    fun stopAudio() {
        if (enginePtr != 0L) nativeStopAudio(enginePtr)
    }

    fun loadSoundFont(path: String): Int {
        if (enginePtr == 0L) return -1
        soundFontId = nativeLoadSoundFont(enginePtr, path)
        Log.i(TAG, "loadSoundFont: $path -> id=$soundFontId")
        return soundFontId
    }

    fun programSelect(channel: Int, sfId: Int, bank: Int, preset: Int) {
        if (enginePtr != 0L) nativeProgramSelect(enginePtr, channel, sfId, bank, preset)
    }

    fun noteOn(channel: Int, note: Int, velocity: Int) {
        if (enginePtr != 0L) nativeNoteOn(enginePtr, channel, note, velocity)
    }

    fun noteOff(channel: Int, note: Int) {
        if (enginePtr != 0L) nativeNoteOff(enginePtr, channel, note)
    }

    fun cc(channel: Int, ctrl: Int, value: Int) {
        if (enginePtr != 0L) nativeCC(enginePtr, channel, ctrl, value)
    }

    fun setGain(gain: Float) {
        if (enginePtr != 0L) nativeSetGain(enginePtr, gain)
    }

    fun setReverb(roomsize: Float, damping: Float, width: Float, level: Float) {
        if (enginePtr != 0L) nativeSetReverb(enginePtr, roomsize, damping, width, level)
    }

    /** Off disables reverb processing entirely, not just its level. */
    fun setReverbOn(on: Boolean) {
        if (enginePtr != 0L) nativeSetReverbOn(enginePtr, on)
    }

    /** Output buffer size in frames; 0 = automatic tuning. Restarts the stream. */
    fun setBufferSize(bufferFrames: Int): Boolean {
        if (enginePtr == 0L) return false
        return nativeSetBufferSize(enginePtr, bufferFrames)
    }

    fun setAudioDevice(deviceId: Int): Boolean {
        if (enginePtr == 0L) return false
        return nativeSetAudioDevice(enginePtr, deviceId)
    }

    fun getPeakLevel(): Float {
        if (enginePtr == 0L) return 0f
        return nativeGetPeakLevel(enginePtr)
    }

    fun getAudioStats(): AudioStats? {
        if (enginePtr == 0L) return null
        val v = IntArray(9)
        if (nativeGetAudioStats(enginePtr, v) < v.size) return null
        return AudioStats(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8])
    }

    // --- Metronome ---

    fun startMetronome() {
        if (enginePtr != 0L) nativeStartMetronome(enginePtr)
    }

    fun stopMetronome() {
        if (enginePtr != 0L) nativeStopMetronome(enginePtr)
    }

    fun setMetronomeBpm(bpm: Int) {
        if (enginePtr != 0L) nativeSetMetronomeBpm(enginePtr, bpm)
    }

    fun setMetronomeTimeSig(beats: Int) {
        if (enginePtr != 0L) nativeSetMetronomeTimeSig(enginePtr, beats)
    }

    fun getMetronomeBeat(): Int {
        if (enginePtr == 0L) return 0
        return nativeGetMetronomeBeat(enginePtr)
    }

    fun setMetronomeClickNotes(downbeatNote: Int, subbeatNote: Int) {
        if (enginePtr != 0L) nativeSetMetronomeClickNotes(enginePtr, downbeatNote, subbeatNote)
    }

    fun setMetronomeVolume(volume: Float) {
        if (enginePtr != 0L) nativeSetMetronomeVolume(enginePtr, volume)
    }

    fun loadMetronomeDrumKit(path: String): Int {
        if (enginePtr == 0L) return -1
        return nativeLoadMetronomeDrumKit(enginePtr, path)
    }

    // --- Recording ---

    fun startRecording() {
        if (enginePtr != 0L) nativeStartRecording(enginePtr)
    }

    fun stopRecording() {
        if (enginePtr != 0L) nativeStopRecording(enginePtr)
    }

    fun readRecordingBuffer(buffer: FloatArray, maxFloats: Int): Int {
        if (enginePtr == 0L) return 0
        return nativeReadRecordingBuffer(enginePtr, buffer, maxFloats)
    }

    fun getRecordingSampleRate(): Int {
        if (enginePtr == 0L) return 48000
        return nativeGetRecordingSampleRate(enginePtr)
    }

    fun getPresets(sfId: Int): List<SfPreset> {
        if (enginePtr == 0L) return emptyList()
        val raw = nativeGetPresets(enginePtr, sfId)
        return raw.mapNotNull { entry ->
            val parts = entry.split(":", limit = 3)
            if (parts.size == 3) {
                SfPreset(parts[0].toInt(), parts[1].toInt(), parts[2])
            } else null
        }.sortedWith(compareBy({ it.bank }, { it.program }))
    }

    // Copy bundled soundfont from assets to internal storage
    fun copySoundFontFromAssets(context: Context, assetName: String): String? {
        val outDir = File(context.filesDir, "soundfonts")
        outDir.mkdirs()
        val outFile = File(outDir, assetName)

        if (outFile.exists()) {
            Log.i(TAG, "SoundFont already cached: ${outFile.absolutePath}")
            return outFile.absolutePath
        }

        return try {
            Log.i(TAG, "Copying soundfont from assets: $assetName")
            context.assets.open("soundfonts/$assetName").use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "SoundFont copied to: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy soundfont: $e")
            null
        }
    }

    // Copy soundfont from content URI to internal storage
    fun copySoundFontFromUri(context: Context, inputStream: InputStream, fileName: String): String? {
        val outDir = File(context.filesDir, "soundfonts")
        outDir.mkdirs()
        val outFile = File(outDir, fileName)

        return try {
            inputStream.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 8192)
                }
            }
            Log.i(TAG, "SoundFont imported to: ${outFile.absolutePath}")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import soundfont: $e")
            null
        }
    }

    // Native methods
    private external fun nativeCreate(sampleRate: Int, bufferFrames: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeStartAudio(ptr: Long): Boolean
    private external fun nativeStopAudio(ptr: Long)
    private external fun nativeLoadSoundFont(ptr: Long, path: String): Int
    private external fun nativeProgramSelect(ptr: Long, channel: Int, sfId: Int, bank: Int, preset: Int)
    private external fun nativeNoteOn(ptr: Long, channel: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(ptr: Long, channel: Int, note: Int)
    private external fun nativeCC(ptr: Long, channel: Int, ctrl: Int, value: Int)
    private external fun nativeSetGain(ptr: Long, gain: Float)
    private external fun nativeSetReverb(ptr: Long, roomsize: Float, damping: Float, width: Float, level: Float)
    private external fun nativeSetReverbOn(ptr: Long, on: Boolean)
    private external fun nativeSetBufferSize(ptr: Long, bufferFrames: Int): Boolean
    private external fun nativeSetAudioDevice(ptr: Long, deviceId: Int): Boolean
    private external fun nativeGetPeakLevel(ptr: Long): Float
    private external fun nativeGetAudioStats(ptr: Long, out: IntArray): Int
    private external fun nativeGetPresets(ptr: Long, sfId: Int): Array<String>
    private external fun nativeStartMetronome(ptr: Long)
    private external fun nativeStopMetronome(ptr: Long)
    private external fun nativeSetMetronomeBpm(ptr: Long, bpm: Int)
    private external fun nativeSetMetronomeTimeSig(ptr: Long, beats: Int)
    private external fun nativeGetMetronomeBeat(ptr: Long): Int
    private external fun nativeSetMetronomeClickNotes(ptr: Long, downbeatNote: Int, subbeatNote: Int)
    private external fun nativeSetMetronomeVolume(ptr: Long, volume: Float)
    private external fun nativeLoadMetronomeDrumKit(ptr: Long, path: String): Int
    private external fun nativeStartRecording(ptr: Long)
    private external fun nativeStopRecording(ptr: Long)
    private external fun nativeReadRecordingBuffer(ptr: Long, buffer: FloatArray, maxFloats: Int): Int
    private external fun nativeGetRecordingSampleRate(ptr: Long): Int
}
