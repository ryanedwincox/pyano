// Concern: verifies responsive piano hit testing and note ownership | Non-concern: audio DSP | IO: (pointer fixtures) -> note assertions
package com.pyano

import com.pyano.keyboard.PianoKeys
import com.pyano.keyboard.PianoTouches
import org.junit.Assert.*
import org.junit.Test

class PianoKeysTest {
    @Test fun responsiveRangeUsesPlayableWidths() {
        assertEquals(1, PianoKeys.octavesForWidth(320f))
        assertEquals(2, PianoKeys.octavesForWidth(800f))
        assertEquals(3, PianoKeys.octavesForWidth(1280f))
        assertEquals((60..83).toSet(), (PianoKeys(2).whiteNotes + PianoKeys(2).blackNotes).toSet())
    }

    @Test fun upperBlackKeyWinsAndLowerWhiteKeysRemainPlayable() {
        val keys = PianoKeys(2)
        assertEquals(60, keys.noteAt(10f, 90f, 700f, 100f))
        assertEquals(61, keys.noteAt(49f, 10f, 700f, 100f))
        assertEquals(60, keys.noteAt(49f, 90f, 700f, 100f))
        assertEquals(64, keys.noteAt(149f, 10f, 700f, 100f))
        assertNull(keys.noteAt(-1f, 0f, 700f, 100f))
        assertNull(keys.noteAt(700f, 0f, 700f, 100f))
        assertNull(keys.noteAt(1f, 100f, 700f, 100f))
    }

    @Test fun polyphonyGlissandoAndPointerCancellationBalanceNotes() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val touches = PianoTouches { note, on -> events.add(note to on) }
        touches.update(listOf(60, 60, 64))
        touches.update(listOf(60, 64))
        touches.update(listOf(62, 64))
        touches.release()
        touches.release()
        assertEquals(listOf(60 to true, 64 to true, 60 to false, 62 to true, 62 to false, 64 to false), events)
    }
}
