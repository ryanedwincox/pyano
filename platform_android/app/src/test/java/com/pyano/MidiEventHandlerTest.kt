// Role: JVM unit tests for the MIDI byte-stream parser in midi/MidiEventHandler.onSend.
// NOT concerned with the native audio engine or Android MIDI device plumbing.
//
// FluidSynthEngine loads a native .so in its companion init, so it cannot be
// constructed on the JVM. We mock it with Mockito (Objenesis-instantiated, so no
// constructor or static initializer runs) and observe parser behaviour both
// through verified engine calls and through the recordingListener seam.
package com.pyano

import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.MidiEventType
import com.pyano.midi.MidiEventHandler
import com.pyano.midi.MidiRecordingListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class MidiEventHandlerTest {

    // FluidSynthEngine's companion init runs System.loadLibrary("pyano-native").
    // The build compiles an empty stub .so onto java.library.path so the class can
    // initialize on the JVM. If that stub is unavailable (no host C compiler), the
    // class can't load and these tests are skipped rather than reported as failures.
    @Before
    fun nativeStubAvailable() {
        try {
            Class.forName("com.pyano.audio.FluidSynthEngine")
        } catch (e: Throwable) {
            assumeNoException("pyano-native stub not loadable on this host", e)
        }
    }

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private data class Event(
        val channel: Int,
        val type: MidiEventType,
        val note: Int,
        val velocity: Int
    )

    /** Builds a handler over a mocked engine plus a list capturing recordingListener events. */
    private fun newHandler(): Triple<MidiEventHandler, FluidSynthEngine, MutableList<Event>> {
        val engine = mock(FluidSynthEngine::class.java)
        val handler = MidiEventHandler(engine)
        val captured = mutableListOf<Event>()
        handler.recordingListener = object : MidiRecordingListener {
            override fun onMidiEvent(channel: Int, type: MidiEventType, note: Int, velocity: Int) {
                captured.add(Event(channel, type, note, velocity))
            }
        }
        return Triple(handler, engine, captured)
    }

    @Test
    fun normalNoteOnTriggersEngineAndListener() {
        val (handler, engine, captured) = newHandler()
        // Note On, channel 0, note 60, velocity 100.
        val data = bytes(0x90, 60, 100)
        handler.onSend(data, 0, data.size, 0L)

        verify(engine).noteOn(0, 60, 100)
        assertEquals(listOf(Event(0, MidiEventType.NOTE_ON, 60, 100)), captured)
        assertEquals(1, handler.activeNoteCount)
    }

    @Test
    fun noteOnWithZeroVelocityIsTreatedAsNoteOff() {
        val (handler, engine, captured) = newHandler()
        val data = bytes(0x90, 60, 0)
        handler.onSend(data, 0, data.size, 0L)

        verify(engine).noteOff(0, 60)
        verify(engine, never()).noteOn(anyInt(), anyInt(), anyInt())
        // Pyano forwards a NOTE_OFF (velocity 0) to the recording listener.
        assertEquals(listOf(Event(0, MidiEventType.NOTE_OFF, 60, 0)), captured)
        assertEquals(0, handler.activeNoteCount)
    }

    @Test
    fun explicitNoteOffDecrementsActiveCount() {
        val (handler, engine, _) = newHandler()
        handler.onSend(bytes(0x90, 64, 90), 0, 3, 0L) // on -> count 1
        assertEquals(1, handler.activeNoteCount)
        handler.onSend(bytes(0x80, 64, 0), 0, 3, 0L)  // off -> count 0
        assertEquals(0, handler.activeNoteCount)
        verify(engine).noteOff(0, 64)
    }

    @Test
    fun runningStatusReusesPreviousStatusByte() {
        val (handler, engine, captured) = newHandler()
        // One 0x90 status byte followed by TWO note/velocity pairs (status omitted on 2nd).
        val data = bytes(0x90, 60, 100, 62, 110)
        handler.onSend(data, 0, data.size, 0L)

        verify(engine).noteOn(0, 60, 100)
        verify(engine).noteOn(0, 62, 110)
        assertEquals(
            listOf(
                Event(0, MidiEventType.NOTE_ON, 60, 100),
                Event(0, MidiEventType.NOTE_ON, 62, 110)
            ),
            captured
        )
        assertEquals(2, handler.activeNoteCount)
    }

    @Test
    fun truncatedNoteOnDoesNotCrashOrEmit() {
        val (handler, engine, captured) = newHandler()
        // Status + a single data byte: not enough for a full NoteOn.
        val data = bytes(0x90, 60)
        handler.onSend(data, 0, data.size, 0L)

        verifyNoInteractions(engine)
        assertTrue(captured.isEmpty())
        assertEquals(0, handler.activeNoteCount)
    }

    @Test
    fun orphanDataByteWithoutRunningStatusIsIgnored() {
        val (handler, engine, captured) = newHandler()
        // Leading data bytes with no prior status byte and no running status.
        val data = bytes(40, 50)
        handler.onSend(data, 0, data.size, 0L)

        verifyNoInteractions(engine)
        assertTrue(captured.isEmpty())
    }

    @Test
    fun sysExIsSkippedEntirely() {
        val (handler, engine, captured) = newHandler()
        // F0 ... F7 SysEx, followed by a real NoteOn that must still be parsed.
        val data = bytes(0xF0, 0x7E, 0x00, 0x01, 0xF7, 0x90, 60, 100)
        handler.onSend(data, 0, data.size, 0L)

        // Only the trailing NoteOn reaches the engine; SysEx payload is ignored.
        verify(engine).noteOn(0, 60, 100)
        assertEquals(listOf(Event(0, MidiEventType.NOTE_ON, 60, 100)), captured)
    }

    @Test
    fun statusAndDataBytesAreMaskedFromSignedBytes() {
        val (handler, engine, captured) = newHandler()
        // 0x95 stored as a signed byte is negative; the parser must mask with 0xFF.
        // Channel nibble 0x05 -> channel 5; note 0x7F is the max data value.
        val data = bytes(0x95, 0x7F, 0x7F)
        assertTrue("status byte should be negative as a signed Byte", data[0] < 0)
        handler.onSend(data, 0, data.size, 0L)

        verify(engine).noteOn(5, 0x7F, 0x7F)
        assertEquals(listOf(Event(5, MidiEventType.NOTE_ON, 0x7F, 0x7F)), captured)
    }

    @Test
    fun controlChangeForwardsExceptSuppressedVolumeAndExpression() {
        val (handler, engine, captured) = newHandler()
        // CC 7 (volume) and CC 11 (expression) are suppressed; CC 1 (mod) passes.
        handler.onSend(bytes(0xB0, 7, 100), 0, 3, 0L)
        handler.onSend(bytes(0xB0, 11, 100), 0, 3, 0L)
        handler.onSend(bytes(0xB0, 1, 64), 0, 3, 0L)

        verify(engine).cc(0, 1, 64)
        verify(engine, never()).cc(0, 7, 100)
        verify(engine, never()).cc(0, 11, 100)
        assertEquals(listOf(Event(0, MidiEventType.CONTROL_CHANGE, 1, 64)), captured)
    }

    @Test
    fun listenerStaysOptionalWhenNotSet() {
        val engine = mock(FluidSynthEngine::class.java)
        val handler = MidiEventHandler(engine)
        // No listener attached: a NoteOn must still drive the engine without NPE.
        handler.onSend(bytes(0x90, 60, 100), 0, 3, 0L)
        verify(engine).noteOn(0, 60, 100)
        assertEquals(1, handler.activeNoteCount)
    }
}
