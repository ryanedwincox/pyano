package com.pyano.midi

import android.media.midi.MidiReceiver
import android.util.Log
import com.pyano.audio.FluidSynthEngine

class MidiEventHandler(private val engine: FluidSynthEngine) : MidiReceiver() {
    @Volatile var lastEventTimeMs: Long = 0L
        private set
    @Volatile var activeNoteCount: Int = 0
        private set

    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
        if (count < 2) return
        lastEventTimeMs = System.currentTimeMillis()

        val status = data[offset].toInt() and 0xFF
        val type = status and 0xF0
        val channel = status and 0x0F

        when (type) {
            0x90 -> { // Note On
                val note = data[offset + 1].toInt() and 0x7F
                val velocity = if (count > 2) data[offset + 2].toInt() and 0x7F else 0
                if (velocity > 0) {
                    engine.noteOn(channel, note, velocity)
                    activeNoteCount++
                } else {
                    engine.noteOff(channel, note)
                    if (activeNoteCount > 0) activeNoteCount--
                }
            }
            0x80 -> { // Note Off
                val note = data[offset + 1].toInt() and 0x7F
                engine.noteOff(channel, note)
                if (activeNoteCount > 0) activeNoteCount--
            }
            0xB0 -> { // Control Change
                val ctrl = data[offset + 1].toInt() and 0x7F
                val value = if (count > 2) data[offset + 2].toInt() and 0x7F else 0
                // Ignore CC7 (volume) and CC11 (expression) — gain is UI-controlled
                if (ctrl != 7 && ctrl != 11) {
                    engine.cc(channel, ctrl, value)
                }
            }
        }
    }
}
