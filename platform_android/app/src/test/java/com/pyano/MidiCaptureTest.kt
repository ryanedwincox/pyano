// Concern: tests recorded MIDI timing and state | Non-concern: native audio | IO: (performance events) -> MIDI assertions
package com.pyano

import com.pyano.audio.MidiCapture
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class MidiCaptureTest {
    @Test
    fun capturesInitialStateNotesSustainAndCleanupOnOneTimeline() {
        var now = 9_000_000_000L
        val capture = MidiCapture { now }
        capture.record(0xB0, 64, 0)
        capture.record(0xC0, 5)
        capture.start()
        now += 100_000_000
        capture.record(0x90, 60, 110)
        capture.record(0x90, 64, 80)
        now += 250_000_000
        capture.record(0xB0, 64, 127)
        capture.record(0x80, 60, 0)
        now += 650_000_000
        val bytes = requireNotNull(capture.finish())
        val sequence = decode(bytes)
        assertEquals(500, sequence.resolution)
        assertEquals(1_000_000L, sequence.durationUs)
        val track = sequence.events
        val notes = track.filter { it.command == 0x90 }
        assertEquals(listOf(100L, 100L), notes.map { it.tick })
        assertEquals(listOf(110, 80), notes.map { it.second })
        val controllers = track.filter { it.command == 0xB0 }
        assertTrue(controllers.any { it.tick == 350L && it.second == 127 })
        assertTrue(controllers.any { it.tick == 1000L && it.first == 64 && it.second == 0 })
        assertTrue(controllers.any { it.tick == 1000L && it.first == 123 })
        assertNull(capture.finish())
    }

    @Test
    fun cancellationDoesNotLeakNotesIntoNextTake() {
        var now = 0L
        val capture = MidiCapture { now }
        capture.start()
        capture.record(0x90, 60, 90)
        capture.cancel()
        assertNull(capture.finish())
        capture.start()
        now += 200_000_000L
        val sequence = decode(requireNotNull(capture.finish()))
        val track = sequence.events
        assertFalse(track.any { it.command == 0x90 })
        assertEquals(200_000L, sequence.durationUs)
    }

    private data class Event(val tick: Long, val command: Int, val first: Int, val second: Int)
    private data class Decoded(val resolution: Int, val durationUs: Long, val events: List<Event>)

    // Android compile classpaths omit java.desktop; reflection uses the host JDK's independent SMF decoder at test runtime.
    private fun decode(bytes: ByteArray): Decoded {
        val system = Class.forName("javax.sound.midi.MidiSystem")
        val sequenceType = Class.forName("javax.sound.midi.Sequence")
        val trackType = Class.forName("javax.sound.midi.Track")
        val eventType = Class.forName("javax.sound.midi.MidiEvent")
        val messageType = Class.forName("javax.sound.midi.MidiMessage")
        val sequence = system.getMethod("getSequence", InputStream::class.java).invoke(null, ByteArrayInputStream(bytes))
        val tracks = sequenceType.getMethod("getTracks").invoke(sequence) as Array<*>
        val track = tracks.single()
        val count = trackType.getMethod("size").invoke(track) as Int
        val events = (0 until count).map { index ->
            val event = trackType.getMethod("get", Int::class.javaPrimitiveType).invoke(track, index)
            val message = eventType.getMethod("getMessage").invoke(event)
            val data = messageType.getMethod("getMessage").invoke(message) as ByteArray
            Event(
                eventType.getMethod("getTick").invoke(event) as Long,
                data[0].toInt() and 0xF0,
                data.getOrNull(1)?.toInt()?.and(127) ?: 0,
                data.getOrNull(2)?.toInt()?.and(127) ?: 0
            )
        }
        return Decoded(sequenceType.getMethod("getResolution").invoke(sequence) as Int,
            sequenceType.getMethod("getMicrosecondLength").invoke(sequence) as Long, events)
    }
}
