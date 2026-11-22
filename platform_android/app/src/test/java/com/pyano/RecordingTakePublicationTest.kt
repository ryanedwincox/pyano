// Concern: verifies process-interrupted paired publication | Non-concern: synth capture | IO: (staged bundle and crash point) -> recovered files
package com.pyano

import com.pyano.audio.RecordingTakePublication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RecordingTakePublicationTest {
    @get:Rule val root = TemporaryFolder()
    private class SimulatedProcessDeath : Error()

    @Test fun draftExistsBeforeEncodingAndIncompleteStagesRemainVisibleForRecovery() {
        val recordings = root.newFolder("recordings")
        val journals = root.newFolder("journals")
        val audio = File(recordings, ".pyano-take-a.part").apply { createNewFile() }
        val midi = File(recordings, ".pyano-take-midi-a.part").apply { createNewFile() }
        val destination = File(recordings, "Practice.wav")
        val publisher = RecordingTakePublication(journals, recordings)
        val draft = publisher.beginDraft(audio, midi, destination, 8, 48000, byteArrayOf(1, 2))
        assertTrue(draft.isFile)
        assertFalse(journals.listFiles().orEmpty().any { it.extension == "journal" })
        val restarted = RecordingTakePublication(journals, recordings)
        assertNotNull(restarted.recover(recoverConflicts = true))
        assertNotNull(restarted.recoveryKey())
        assertTrue(destination.absolutePath in restarted.reservedTargets())
        assertTrue(audio.isFile)
        assertTrue(midi.isFile)
        assertFalse(destination.exists())
        restarted.rollback(draft)
        assertFalse(audio.exists())
        assertFalse(midi.exists())
        assertTrue(journals.listFiles().orEmpty().isEmpty())
    }

    @Test fun freshInstanceFinishesWavAfterProcessDiesFollowingMidiMove() {
        val recordings = root.newFolder("recordings")
        val journals = root.newFolder("journals")
        val audio = File(recordings, ".pyano-take-a.part").apply { writeText("new audio") }
        val midi = File(recordings, ".pyano-take-midi-a.part").apply { writeText("new MIDI") }
        val wav = File(recordings, "Practice.wav")
        val publisher = RecordingTakePublication(journals, recordings)
        val journal = publisher.prepare(audio, midi, wav)
        try { publisher.publish(journal) { throw SimulatedProcessDeath() }; fail("Expected interrupted process") }
        catch (_: SimulatedProcessDeath) { }
        assertTrue(audio.isFile)
        assertFalse(wav.exists())
        assertEquals("new MIDI", File(recordings, "Practice.mid").readText())
        assertNull(RecordingTakePublication(journals, recordings).recover())
        assertEquals("new audio", wav.readText())
        assertEquals("new MIDI", File(recordings, "Practice.mid").readText())
        assertFalse(audio.exists())
        assertFalse(midi.exists())
        assertTrue(journals.listFiles().orEmpty().isEmpty())
    }

    @Test fun failedRollbackKeepsStagesAndRestartCompletesAbortInsteadOfPublishing() {
        val recordings = root.newFolder("recordings")
        val journals = root.newFolder("journals")
        val audio = File(recordings, ".pyano-take-a.part").apply { writeText("new audio") }
        val midi = File(recordings, ".pyano-take-midi-a.part").apply { writeText("new MIDI") }
        val wav = File(recordings, "Practice.wav")
        val publisher = RecordingTakePublication(journals, recordings, removeFile = { false })
        val journal = publisher.prepare(audio, midi, wav)
        try { publisher.rollback(journal); fail("Expected rollback failure") }
        catch (_: IllegalStateException) { }
        assertTrue(journal.exists())
        assertTrue(audio.exists())
        assertTrue(midi.exists())
        assertNull(RecordingTakePublication(journals, recordings).recover())
        assertFalse(wav.exists())
        assertFalse(File(recordings, "Practice.mid").exists())
        assertFalse(audio.exists())
        assertFalse(midi.exists())
        assertFalse(journal.exists())
    }

    @Test fun recoveryNeverReplacesForeignFileAndRollbackRemovesOnlyOwnedPartialBundle() {
        val recordings = root.newFolder("recordings")
        val journals = root.newFolder("journals")
        val audio = File(recordings, ".pyano-take-a.part").apply { writeText("new audio") }
        val midi = File(recordings, ".pyano-take-midi-a.part").apply { writeText("new MIDI") }
        val wav = File(recordings, "Practice.wav")
        val publisher = RecordingTakePublication(journals, recordings)
        val journal = publisher.prepare(audio, midi, wav)
        try { publisher.publish(journal) { throw SimulatedProcessDeath() }; fail("Expected interrupted process") }
        catch (_: SimulatedProcessDeath) { }
        wav.writeText("unrelated audio")
        val restarted = RecordingTakePublication(journals, recordings)
        assertNotNull(restarted.recover())
        assertFalse(wav.absolutePath in restarted.unresolvedTargets())
        assertEquals("unrelated audio", wav.readText())
        publisher.rollback(journal)
        assertEquals("unrelated audio", wav.readText())
        assertFalse(File(recordings, "Practice.mid").exists())
        assertFalse(audio.exists())
        assertFalse(journal.exists())
        assertTrue(restarted.unresolvedTargets().isEmpty())
    }
}
