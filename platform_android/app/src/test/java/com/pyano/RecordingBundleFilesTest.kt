// Concern: tests paired-file operations | Non-concern: capture and upload | IO: (temporary recordings) -> filesystem assertions
package com.pyano

import com.pyano.audio.RecordingBundleFiles
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class RecordingBundleFilesTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun renameAndDeleteKeepMidiWithWav() {
        val wav = folder.newFile("take.wav").apply { writeText("wave") }
        RecordingBundleFiles.midi(wav).writeText("notes")
        val renamed = RecordingBundleFiles.rename(wav, "kept take")
        assertFalse(wav.exists())
        assertEquals("wave", renamed.readText())
        assertEquals("notes", RecordingBundleFiles.midi(renamed).readText())
        RecordingBundleFiles.delete(renamed)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun conflictingOrInvalidNamesPreserveOriginals() {
        val wav = folder.newFile("take.wav")
        val midi = RecordingBundleFiles.midi(wav).apply { writeText("notes") }
        folder.newFile("occupied.mid")
        assertTrue(runCatching { RecordingBundleFiles.rename(wav, "occupied") }.isFailure)
        assertTrue(runCatching { RecordingBundleFiles.rename(wav, "../escape") }.isFailure)
        assertTrue(wav.exists())
        assertEquals("notes", midi.readText())
    }

    @Test fun destinationSidecarCreatedAfterCheckNeverOverwritesIt() {
        val wav = folder.newFile("original.wav").apply { writeText("wave") }
        val midi = RecordingBundleFiles.midi(wav).apply { writeText("original midi") }
        val foreign = File(folder.root, "Occupied.mid")
        assertThrows(IOException::class.java) {
            RecordingBundleFiles.rename(wav, "Occupied") { foreign.writeText("foreign midi") }
        }
        assertEquals("foreign midi", foreign.readText())
        assertEquals("original midi", midi.readText())
        assertEquals("wave", wav.readText())
    }

    @Test
    fun failedWavMoveRollsMidiBack() {
        val missing = File(folder.root, "missing.wav")
        val midi = RecordingBundleFiles.midi(missing).apply { writeText("recoverable") }
        assertTrue(runCatching { RecordingBundleFiles.rename(missing, "new") }.isFailure)
        assertTrue(runCatching { RecordingBundleFiles.delete(missing) }.isFailure)
        assertEquals("recoverable", midi.readText())
        assertFalse(File(folder.root, "new.mid").exists())
        assertFalse(folder.root.listFiles().orEmpty().any { it.name.startsWith(".deleted-") })
    }

    @Test
    fun failedSidecarWriteNeverPublishesPartialMidi() {
        val wav = folder.newFile("take.wav")
        val failure = runCatching {
            RecordingBundleFiles.saveMidi(wav) { staging ->
                staging.writeText("MThd")
                throw IOException("Simulated full disk")
            }
        }
        assertTrue(failure.isFailure)
        assertFalse(RecordingBundleFiles.midi(wav).exists())
        assertEquals(listOf("take.wav"), folder.root.listFiles().orEmpty().map { it.name })
        RecordingBundleFiles.saveMidi(wav) { it.writeText("complete MIDI") }
        assertEquals("complete MIDI", RecordingBundleFiles.midi(wav).readText())
        assertTrue(runCatching { RecordingBundleFiles.saveMidi(wav) { it.writeText("replacement") } }.isFailure)
        assertEquals("complete MIDI", RecordingBundleFiles.midi(wav).readText())
    }

    @Test
    fun legacyWavNeedsNoMidi() {
        val wav = folder.newFile("old.wav")
        val renamed = RecordingBundleFiles.rename(wav, "renamed")
        RecordingBundleFiles.delete(renamed)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }
}
