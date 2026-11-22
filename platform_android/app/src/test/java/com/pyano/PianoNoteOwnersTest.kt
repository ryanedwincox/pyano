// Concern: verifies shared touch-note lifetime | Non-concern: synth DSP | IO: (window-owned notes) -> balanced MIDI transitions
package com.pyano

import com.pyano.keyboard.PianoNoteOwners
import org.junit.Assert.assertEquals
import org.junit.Test

class PianoNoteOwnersTest {
    @Test fun stoppingOneWindowKeepsOtherWindowsHeldPitch() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val notes = PianoNoteOwners { note, down -> events.add(note to down) }
        val first = Any()
        val second = Any()
        notes.note(first, 60, true)
        notes.note(second, 60, true)
        notes.note(second, 64, true)
        notes.note(first, 60, false)
        notes.release(first)
        assertEquals(listOf(60 to true, 64 to true), events)
        notes.release(second)
        assertEquals(listOf(60 to true, 64 to true, 60 to false, 64 to false), events)
    }

    @Test fun inputSwitchReleasesSharedPitchOnceAndIgnoresLateWindowRelease() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val notes = PianoNoteOwners { note, down -> events.add(note to down) }
        val first = Any()
        val second = Any()
        notes.note(first, 60, true)
        notes.note(first, 60, true)
        notes.note(second, 60, true)
        notes.releaseAll()
        notes.note(second, 60, false)
        notes.release(first)
        assertEquals(listOf(60 to true, 60 to false), events)
        notes.note(second, 60, true)
        notes.release(second)
        assertEquals(listOf(60 to true, 60 to false, 60 to true, 60 to false), events)
    }
}
