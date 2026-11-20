// Concern: verifies saving and discarding temporary audio takes | Non-concern: real synthesis | IO: (captured PCM and decision) -> files
package com.pyano

import android.content.Context
import com.pyano.audio.AudioRecorder
import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.MidiCapture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RecordingDecisionTest {
    @get:Rule val directory = TemporaryFolder()

    private suspend fun capture(scope: CoroutineScope): AudioRecorder {
        val context = mock(Context::class.java)
        `when`(context.cacheDir).thenReturn(directory.root)
        val engine = mock(FluidSynthEngine::class.java)
        val midi = mock(MidiCapture::class.java)
        `when`(engine.midiCapture).thenReturn(midi)
        `when`(engine.getRecordingSampleRate()).thenReturn(48000)
        `when`(midi.finish()).thenReturn(byteArrayOf(1, 2, 3))
        val drained = AtomicBoolean()
        `when`(engine.readRecordingBuffer((any(FloatArray::class.java) ?: FloatArray(0)), anyInt())).thenAnswer {
            if (drained.compareAndSet(false, true)) {
                val values = it.getArgument<FloatArray>(0)
                values[0] = 0.2f; values[1] = -0.2f
                2
            } else 0
        }
        val recorder = AudioRecorder(engine, context) { File(directory.root, "saved") }
        recorder.startRecording(scope)
        withTimeout(2000) { while (recorder.recordingDurationMs == 0L && !drained.get()) delay(5) }
        assertTrue(recorder.finishTake())
        return recorder
    }

    @Test fun stopDoesNotPublishAndDiscardNeverEncodesWav() = runBlocking {
        val recorder = capture(this)
        assertTrue(File(directory.root, "pyano_recording_temp.pcm").exists())
        assertFalse(directory.root.walkTopDown().any { it.extension in setOf("wav", "mid") })
        recorder.discardTake()
        assertFalse(File(directory.root, "pyano_recording_temp.pcm").exists())
    }

    @Test fun savePublishesPairAndRetryDoesNotDuplicateAfterQueueFailure() = runBlocking {
        val recorder = capture(this)
        try { recorder.saveTake { error("queue unavailable") }; fail("Expected queue failure") } catch (_: IllegalStateException) { }
        val path = recorder.saveTake { assertFalse(File(it).exists()) }!!
        assertTrue(File(path).isFile)
        assertTrue(File(path.replace(".wav", ".mid")).isFile)
        assertEquals(1, directory.root.walkTopDown().count { it.extension == "wav" })
        assertFalse(File(directory.root, "pyano_recording_temp.pcm").exists())
    }
    @Test fun failedFinalDrainStillAllowsDiscardAndAnotherTake() = runBlocking {
        val recorder = capture(this)
        val pcm = File(directory.root, "pyano_recording_temp.pcm")
        assertTrue(pcm.delete())
        assertTrue(pcm.mkdir())
        try { recorder.finishTake(); fail("Expected final drain failure") } catch (_: java.io.IOException) { }
        assertTrue(recorder.hasPendingTake)
        recorder.discardTake()
        assertFalse(recorder.hasPendingTake)
    }

}
