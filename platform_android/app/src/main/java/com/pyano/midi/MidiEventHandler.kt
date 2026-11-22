// Concern: parses MIDI input for the synth and recorder | Non-concern: loop scheduling | IO: (MIDI bytes) -> engine events
package com.pyano.midi

import android.media.midi.MidiReceiver
import android.util.Log
import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.MidiEventType

/** Callback for recording live MIDI events. Set on [MidiEventHandler.recordingListener]. */
interface MidiRecordingListener {
    fun onMidiEvent(channel: Int, type: MidiEventType, note: Int, velocity: Int)
}

class MidiEventHandler(private val engine: FluidSynthEngine) : MidiReceiver() {
    companion object {
        const val MIN_TRANSPOSE = -24
        const val MAX_TRANSPOSE = 24
        private const val NO_SOUNDING_NOTE = -1
        private const val UNKNOWN_NOTE = -2
    }

    @Volatile var lastEventTimeMs: Long = 0L
        private set
    @Volatile var activeNoteCount: Int = 0
        private set

    /** When non-null, every NoteOn/NoteOff/CC event is forwarded here for loop recording. */
    @Volatile var recordingListener: MidiRecordingListener? = null

    /** Called on every played note (NoteOn with velocity > 0), after it is sent to the synth. */
    @Volatile var notePlayedListener: (() -> Unit)? = null

    @Volatile var transposeSemitones: Int = 0
        private set

    private val soundingNotes = IntArray(16 * 128) { UNKNOWN_NOTE }
    private val sustainedChannels = BooleanArray(16)

    // Running status — last channel-voice status byte seen (for MIDI running status support)
    private var runningStatus: Int = 0

    @Synchronized
    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
        if (count < 1) return
        lastEventTimeMs = System.currentTimeMillis()

        var pos = offset
        val end = offset + count

        while (pos < end) {
            var status = data[pos].toInt() and 0xFF
            if (status < 0x80) {
                // Data byte with no status — use running status if available
                if (runningStatus >= 0x80 && runningStatus < 0xF0) {
                    status = runningStatus
                    // Don't advance pos — reparse from this position with injected status
                } else {
                    Log.w("Pyano", "MIDI orphan data byte 0x${status.toString(16)} at pos=$pos (no running status)")
                    pos++; continue
                }
            } else {
                // Real status byte — consume it and update running status (for channel voice messages)
                if (status < 0xF0) runningStatus = status
                pos++
            }

            val type = status and 0xF0
            val channel = status and 0x0F

            when (type) {
                0x90 -> { // Note On (2 data bytes)
                    if (pos + 1 >= end) { Log.w("Pyano", "MIDI truncated NoteOn at pos=$pos"); break }
                    val note = data[pos].toInt() and 0x7F
                    val velocity = data[pos + 1].toInt() and 0x7F
                    if (velocity > 0) {
                        val transposedNote = note + transposeSemitones
                        if (transposedNote in 0..127) {
                            soundingNotes[channel * 128 + note] = transposedNote
                            engine.noteOn(channel, transposedNote, velocity)
                            activeNoteCount++
                            recordingListener?.onMidiEvent(channel, MidiEventType.NOTE_ON, transposedNote, velocity)
                            notePlayedListener?.invoke()
                        }
                    } else {
                        releaseNote(channel, note)
                    }
                    pos += 2
                }
                0x80 -> { // Note Off (2 data bytes)
                    if (pos + 1 >= end) { Log.w("Pyano", "MIDI truncated NoteOff at pos=$pos"); break }
                    val note = data[pos].toInt() and 0x7F
                    releaseNote(channel, note)
                    pos += 2
                }
                0xB0 -> { // Control Change (2 data bytes)
                    if (pos + 1 >= end) break
                    val ctrl = data[pos].toInt() and 0x7F
                    val value = data[pos + 1].toInt() and 0x7F
                    if (ctrl != 7 && ctrl != 11) {
                        if (ctrl == 64) sustainedChannels[channel] = value >= 64
                        engine.cc(channel, ctrl, value)
                        recordingListener?.onMidiEvent(channel, MidiEventType.CONTROL_CHANGE, ctrl, value)
                    }
                    pos += 2
                }
                0xC0 -> { pos += 1 } // Program Change (1 data byte)
                0xD0 -> { pos += 1 } // Channel Pressure (1 data byte)
                0xE0 -> { pos += 2 } // Pitch Bend (2 data bytes)
                0xF0 -> {
                    // System messages — system common (F0..F7) clears running status; real-time (F8..FF) does not
                    if (status < 0xF8) runningStatus = 0
                    if (status == 0xF0) {
                        // SysEx: scan for 0xF7 end
                        while (pos < end && (data[pos].toInt() and 0xFF) != 0xF7) pos++
                        if (pos < end) pos++ // skip F7
                    }
                    // Other system messages have 0–2 data bytes; we ignore them and let the loop continue
                }
                else -> { pos++ }
            }
        }
    }

    @Synchronized
    fun releaseAllNotes() {
        for (channel in 0 until 16) {
            for (note in 0 until 128) {
                if (soundingNotes[channel * 128 + note] >= 0) releaseNote(channel, note)
            }
            if (sustainedChannels[channel]) {
                engine.cc(channel, 64, 0)
                recordingListener?.onMidiEvent(channel, MidiEventType.CONTROL_CHANGE, 64, 0)
                sustainedChannels[channel] = false
            }
        }
        soundingNotes.fill(NO_SOUNDING_NOTE)
        activeNoteCount = 0
        runningStatus = 0
    }

    @Synchronized
    fun setTransposeSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(MIN_TRANSPOSE, MAX_TRANSPOSE)
        if (clamped == transposeSemitones) return
        if (activeNoteCount > 0) {
            for (channel in 0 until 16) engine.cc(channel, 123, 0)
            for (index in soundingNotes.indices) {
                val soundingNote = soundingNotes[index]
                if (soundingNote >= 0) {
                    recordingListener?.onMidiEvent(index / 128, MidiEventType.NOTE_OFF, soundingNote, 0)
                }
            }
        }
        soundingNotes.fill(NO_SOUNDING_NOTE)
        activeNoteCount = 0
        transposeSemitones = clamped
    }

    private fun releaseNote(channel: Int, inputNote: Int) {
        val index = channel * 128 + inputNote
        val storedNote = soundingNotes[index]
        if (storedNote == NO_SOUNDING_NOTE) return
        val soundingNote = if (storedNote == UNKNOWN_NOTE) inputNote + transposeSemitones else storedNote
        if (soundingNote !in 0..127) return
        soundingNotes[index] = NO_SOUNDING_NOTE
        engine.noteOff(channel, soundingNote)
        if (activeNoteCount > 0) activeNoteCount--
        recordingListener?.onMidiEvent(channel, MidiEventType.NOTE_OFF, soundingNote, 0)
    }
}
