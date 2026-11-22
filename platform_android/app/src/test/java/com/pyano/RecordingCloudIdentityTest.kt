// Concern: verifies receipt identity follows owned files | Non-concern: live cloud | IO: (replacement and rename) -> isolated receipts
package com.pyano

import android.content.Context
import android.content.SharedPreferences
import com.pyano.audio.recordingFileProof
import com.pyano.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Files

class RecordingCloudIdentityTest {
    @get:Rule val directory = TemporaryFolder()
    private val values = mutableMapOf<String, Any>()
    private fun status(): RecordingCloudStatus {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        `when`(context.getSharedPreferences("recording_cloud_status", Context.MODE_PRIVATE)).thenReturn(prefs)
        `when`(prefs.edit()).thenReturn(editor)
        `when`(prefs.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] as? String }
        `when`(prefs.getBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] as? Boolean ?: false }
        `when`(prefs.getStringSet("pending", emptySet())).thenAnswer { @Suppress("UNCHECKED_CAST") (values["pending"] as? Set<String>) ?: emptySet<String>() }
        `when`(editor.putString(anyString(), nullable(String::class.java))).thenAnswer {
            val key = it.getArgument<String>(0)
            val value = it.getArgument<String?>(1)
            if (value == null) values.remove(key) else values[key] = value
            editor
        }
        `when`(editor.putBoolean(anyString(), anyBoolean())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<Boolean>(1); editor }
        `when`(editor.putStringSet(anyString(), anySet())).thenAnswer { values[it.getArgument<String>(0)] = it.getArgument<Set<String>>(1).toSet(); editor }
        `when`(editor.remove(anyString())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
        `when`(editor.commit()).thenReturn(true)
        return RecordingCloudStatus(context)
    }

    @Test fun deliberateNewTakeAtReusedNameCannotInheritReceiptOrTitleBinding() {
        val status = status()
        val wav = File(directory.root, "Practice.wav").apply { writeText("first recording") }
        val firstId = status.id(wav.path)
        status.complete(firstId)
        val titleValues = mutableMapOf<String, String>()
        val titles = RecordingTitleSync(object : TitleStorage {
            override fun read(key: String) = titleValues[key]
            override fun write(values: Map<String, String?>) { values.forEach { (key, value) -> if (value == null) titleValues.remove(key) else titleValues[key] = value } }
        })
        titles.bind(firstId, "profile-a", "a".repeat(64), "First")
        assertTrue(wav.delete())
        val staged = File(directory.root, "second.part").apply { writeText("new separate recording") }
        status.pending(wav.path, recordingFileProof(staged), "profile-a")
        Files.move(staged.toPath(), wav.toPath())
        val nextId = status().id(wav.path)
        assertNotEquals(firstId, nextId)
        assertFalse(status.synced(nextId))
        assertNull(titles.binding(nextId))
        assertFalse(status.unverifiedOwnerOrigin(nextId))
        status.queued(wav.path)
        val renamed = File(directory.root, "Renamed.wav")
        check(wav.renameTo(renamed))
        status.rename(wav.path, renamed.path)
        assertEquals(nextId, status().existingId(renamed.path))
    }

    @Test fun replayedRenamePreservesMigratedIdentityAndPendingMarker() {
        val status = status()
        val original = File(directory.root, "Original.wav").apply { writeText("original take") }
        val destination = File(directory.root, "Renamed.wav")
        val id = status.id(original.path)
        status.complete(id)
        status.pending(original.path, recordingFileProof(original), "profile-a")
        check(original.renameTo(destination))
        status.rename(original.path, destination.path)
        status().rename(original.path, destination.path)
        assertEquals(id, status().existingId(destination.path))
        assertTrue(status.synced(id))
        assertEquals(setOf(destination.path), status.pendingPaths())
        assertEquals("profile-a", status.pendingProfile(destination.path))
        status.queued(destination.path)
        status().rename(original.path, destination.path)
        assertEquals(id, status().existingId(destination.path))
        assertTrue(status.pendingPaths().isEmpty())
    }

    @Test fun capturedAbsenceOfMidiRejectsSidecarAddedBeforeUpload() {
        val status = status()
        val wav = File(directory.root, "Audio only.wav").apply { writeText("audio") }
        status.pending(wav.path)
        assertEquals(setOf(wav.path), status.pendingPaths())
        File(directory.root, "Audio only.mid").writeText("new external MIDI")
        assertTrue(status.pendingPaths().isEmpty())
        val error = assertThrows(CloudSyncException::class.java) { status.requirePendingBundle(wav.path) }
        assertTrue(error.message!!.contains("MIDI"))
        assertFalse(error.retryable)
    }

    @Test fun unpublishedCollisionDoesNotReplaceForeignReceiptIdentity() {
        val status = status()
        val foreign = File(directory.root, "Practice.wav").apply { writeText("foreign recording") }
        val id = status.id(foreign.path)
        status.complete(id)
        val staged = File(directory.root, "new.part").apply { writeText("new unsaved take") }
        status.pending(foreign.path, recordingFileProof(staged), "profile-b")
        assertEquals(id, status().id(foreign.path))
        assertTrue(status.synced(id))
        assertTrue(status.pendingPaths().isEmpty())
    }

    @Test fun unprovenLegacyOrExternalReplacementKeepsUnknownOwnerProvenance() {
        val status = status()
        val wav = File(directory.root, "Legacy.wav").apply { writeText("legacy") }
        values["path:${wav.path}"] = "legacy-id"
        val local = status.id(wav.path)
        assertNotEquals("legacy-id", local)
        assertTrue(status.unverifiedOwnerOrigin(local))
        assertTrue(wav.delete())
        wav.writeText("externally replaced recording")
        val replacement = status.id(wav.path)
        assertNotEquals(local, replacement)
        assertTrue(status.unverifiedOwnerOrigin(replacement))
    }
    @Test fun legacyProofMigrationPreservesKnownReceiptOnlyForExactBoundBundle() = kotlinx.coroutines.runBlocking {
        for (changed in listOf("none", "wav", "midi")) {
            values.clear()
            val wav = File(directory.root, "Legacy-$changed.wav").apply { writeText("original audio") }
            val midi = com.pyano.audio.RecordingBundleFiles.midi(wav).apply { writeText("original midi") }
            val titleValues = mutableMapOf<String, String>()
            val titles = RecordingTitleSync(object : TitleStorage {
                override fun read(key: String) = titleValues[key]
                override fun write(values: Map<String, String?>) {
                    values.forEach { (key, value) -> if (value == null) titleValues.remove(key) else titleValues[key] = value }
                }
            })
            val remote = CloudSyncClient(directory.root).stableId(wav, midi)
            titles.bind("old-id", "original-owner-a", remote, "Legacy")
            values["path:${wav.path}"] = "old-id"
            values["synced:old-id"] = true
            if (changed == "wav") wav.writeText("different audio")
            if (changed == "midi") midi.writeText("different midi")
            val status = status()
            assertEquals(changed == "none", status.migrateLegacyIdentity(wav.path, titles))
            val id = status.id(wav.path)
            if (changed == "none") {
                assertEquals("old-id", id)
                assertTrue(status.synced(id))
                assertEquals("original-owner-a", titles.binding(id)!!.profileId)
                assertFalse(status.unverifiedOwnerOrigin(id))
            } else {
                assertNotEquals("old-id", id)
                assertFalse(status.synced(id))
                assertNull(titles.binding(id))
                assertTrue(status.unverifiedOwnerOrigin(id))
            }
        }
    }

    @Test fun unverifiedHistoricalReceiptRemainsVisibleAcrossRenameButNeverTransfersToNewTake() {
        val status = status()
        val wav = File(directory.root, "Old.wav").apply { writeText("legacy recording") }
        values["path:${wav.path}"] = "legacy-id"
        values["synced:legacy-id"] = true
        assertTrue(status.hasHistoricalReceipt(wav.path))
        assertEquals(RecordingCloudState.HISTORICAL_RECEIPT,
            recordingCloudState(emptyList(), false, status.hasHistoricalReceipt(wav.path)))
        val unknown = status.id(wav.path)
        assertNotEquals("legacy-id", unknown)
        assertFalse(status.synced(unknown))
        assertTrue(status.unverifiedOwnerOrigin(unknown))
        assertTrue(status.hasHistoricalReceipt(wav.path))
        val renamed = File(directory.root, "Renamed.wav")
        check(wav.renameTo(renamed))
        status.rename(wav.path, renamed.path)
        assertEquals(unknown, status.existingId(renamed.path))
        assertTrue(status.hasHistoricalReceipt(renamed.path))
        assertTrue(status.unverifiedOwnerOrigin(unknown))
        assertTrue(renamed.delete())
        val staged = File(directory.root, "new-take.part").apply { writeText("genuine new saved take") }
        status.pending(renamed.path, recordingFileProof(staged), "owner-b")
        Files.move(staged.toPath(), renamed.toPath())
        assertFalse(status.hasHistoricalReceipt(renamed.path))
        val newId = status.id(renamed.path)
        assertNotEquals(unknown, newId)
        assertFalse(status.hasHistoricalReceipt(renamed.path))
        assertFalse(status.synced(newId))
        assertFalse(status.unverifiedOwnerOrigin(newId))
    }

    @Test fun pathOnlyLegacyPendingIntentMovesWithLocalRenameAndStillWarnsAfterRestart() {
        val source = File(directory.root, "Legacy.wav").apply { writeText("legacy audio") }
        val midi = com.pyano.audio.RecordingBundleFiles.midi(source).apply { writeText("legacy MIDI") }
        values["pending"] = setOf(source.absolutePath)
        val status = status()
        val destination = com.pyano.audio.RecordingBundleFiles.rename(source, "Renamed legacy")
        status.rename(source.absolutePath, destination.absolutePath)
        val restarted = status()
        assertEquals(setOf(destination.absolutePath), restarted.legacyPendingPaths())
        assertTrue(restarted.pendingPaths().isEmpty())
        assertNull(restarted.pendingProfile(destination.absolutePath))
        assertEquals("legacy audio", destination.readText())
        assertEquals("legacy MIDI", com.pyano.audio.RecordingBundleFiles.midi(destination).readText())
        assertFalse(source.exists())
        assertFalse(midi.exists())
        val unknown = restarted.id(destination.absolutePath)
        assertTrue(restarted.unverifiedOwnerOrigin(unknown))
        assertFalse(restarted.synced(unknown))
        assertEquals(setOf(destination.absolutePath), status().legacyPendingPaths())
        assertTrue(status().pendingPaths().isEmpty())
    }

    @Test fun abandoningOnlyMatchingUnpublishedIntentPreservesForeignReceiptAndRejectsWrongProof() {
        val status = status()
        val foreign = File(directory.root, "Foreign.wav").apply { writeText("foreign audio") }
        val foreignId = status.id(foreign.path)
        status.complete(foreignId)
        val staged = File(directory.root, "take.part").apply { writeText("new take") }
        val stagedMidi = File(directory.root, "take-midi.part").apply { writeText("new MIDI") }
        val wavProof = recordingFileProof(staged)
        val midiProof = recordingFileProof(stagedMidi)
        status.pending(foreign.path, wavProof, "owner-a", midiProof)
        val before = values.toMap()
        assertFalse(status.abandonUnpublishedIntent(foreign.path, "wrong proof", midiProof))
        assertEquals(before, values)
        assertTrue(status.abandonUnpublishedIntent(foreign.path, wavProof, midiProof))
        assertEquals(foreignId, status.existingId(foreign.path))
        assertTrue(status.synced(foreignId))
        assertTrue(status.pendingPaths().isEmpty())
        assertTrue(status.legacyPendingPaths().isEmpty())
        assertEquals("foreign audio", foreign.readText())
    }

    @Test fun abandonedReservationDoesNotQuarantineIndependentForeignFileWithoutAnyPriorIdentity() {
        val status = status()
        val destination = File(directory.root, "Collision.wav")
        val staged = File(directory.root, "take.part").apply { writeText("intended take") }
        val proof = recordingFileProof(staged)
        status.pending(destination.path, proof, "owner-a", null)
        val reservedId = values["pending-id:${destination.path}"]
        destination.writeText("different foreign file")
        val foreignId = status.id(destination.path)
        assertNotEquals(reservedId, foreignId)
        assertFalse(status.unverifiedOwnerOrigin(foreignId))
        assertTrue(status.abandonUnpublishedIntent(destination.path, proof, null))
        assertEquals(foreignId, status().existingId(destination.path))
        assertFalse(status().unverifiedOwnerOrigin(foreignId))
        assertTrue(status().pendingPaths().isEmpty())
    }

    @Test fun completedHistoricalOrUnverifiedReservationCannotBeAbandonedByTakeCleanup() {
        val status = status()
        val destination = File(directory.root, "Reserved.wav")
        val staged = File(directory.root, "take.part").apply { writeText("take") }
        val proof = recordingFileProof(staged)
        status.pending(destination.path, proof, "owner-a", null)
        val id = values["pending-id:${destination.path}"] as String
        for (prefix in listOf("synced", "historical-receipt", "unverified-owner")) {
            values["$prefix:$id"] = true
            val before = values.toMap()
            assertFalse(status.abandonUnpublishedIntent(destination.path, proof, null))
            assertEquals(before, values)
            values.remove("$prefix:$id")
        }
    }

    @Test fun restoredTimestampSameLengthEditsCannotReusePendingOwnerOrReceipt() {
        for (part in listOf("wav", "midi")) {
            values.clear()
            val wav = File(directory.root, "Preserved-$part.wav").apply { writeText("original wave") }
            val midi = com.pyano.audio.RecordingBundleFiles.midi(wav).apply { writeText("original midi") }
            val status = status()
            val id = status.id(wav.path)
            status.complete(id)
            status.pending(wav.path, recordingFileProof(wav), "original-owner", recordingFileProof(midi))
            val changed = if (part == "wav") wav else midi
            val originalMetadata = com.pyano.audio.recordingFileIdentity(changed)
            val modified = Files.getLastModifiedTime(changed.toPath())
            changed.writeText(if (part == "wav") "modified wave" else "modified midi")
            Files.setLastModifiedTime(changed.toPath(), modified)
            assertEquals(originalMetadata, com.pyano.audio.recordingFileIdentity(changed))
            assertThrows(CloudSyncException::class.java) { status().requirePendingBundle(wav.path) }
            assertTrue(status().pendingPaths().isEmpty())
            if (part == "wav") {
                assertNull(status().existingId(wav.path))
                val replacement = status().id(wav.path)
                assertNotEquals(id, replacement)
                assertTrue(status().unverifiedOwnerOrigin(replacement))
            }
        }
    }

    @Test fun snapshotBytesAreCheckedAgainstSavedProofEvenWhenSourceStillMatches() {
        val wav = File(directory.root, "Source.wav").apply { writeText("original wave") }
        val midi = com.pyano.audio.RecordingBundleFiles.midi(wav).apply { writeText("original midi") }
        val status = status()
        status.pending(wav.path, recordingFileProof(wav), "owner-a", recordingFileProof(midi))
        val queue = CloudUploadQueue(File(directory.root, "queue"))
        val first = queue.snapshot(wav, "owner-a", "recording-id")
        first.wav.writeText("injected wave")
        assertThrows(CloudSyncException::class.java) {
            verifiedPendingSnapshot(queue, status, wav, "owner-a", "recording-id")
        }
        assertEquals("original wave", wav.readText())
        assertEquals("original midi", midi.readText())
        assertEquals(setOf(wav.path), status.pendingPaths())
    }

    @Test fun metadataOnlyOldProofMigratesOnlyWhenBoundContentMatches() = kotlinx.coroutines.runBlocking {
        for (changed in listOf(false, true)) {
            values.clear()
            val wav = File(directory.root, "Metadata-$changed.wav").apply { writeText("original wave") }
            val data = mutableMapOf<String, String>()
            val titles = RecordingTitleSync(object : TitleStorage {
                override fun read(key: String) = data[key]
                override fun write(values: Map<String, String?>) {
                    values.forEach { (key, value) -> if (value == null) data.remove(key) else data[key] = value }
                }
            })
            titles.bind("old-id", "owner-a", CloudSyncClient(directory.root).stableId(wav, null), "Original")
            values["path:${wav.path}"] = "old-id"
            values["file:${wav.path}"] = com.pyano.audio.recordingFileIdentity(wav)
            values["pending"] = setOf(wav.path)
            values["pending-id:${wav.path}"] = "old-id"
            values["pending-file:${wav.path}"] = com.pyano.audio.recordingFileIdentity(wav)
            values["pending-midi:${wav.path}"] = "absent"
            values["pending-profile:${wav.path}"] = "owner-a"
            if (changed) {
                val modified = Files.getLastModifiedTime(wav.toPath())
                wav.writeText("modified wave")
                Files.setLastModifiedTime(wav.toPath(), modified)
            }
            assertEquals(!changed, status().migrateLegacyIdentity(wav.path, titles))
            if (!changed) {
                assertEquals("old-id", status().existingId(wav.path))
                assertEquals(setOf(wav.path), status().pendingPaths())
                assertEquals("owner-a", status().pendingProfile(wav.path))
            } else {
                assertNull(status().existingId(wav.path))
                assertTrue(status().pendingPaths().isEmpty())
            }
        }
    }

}
