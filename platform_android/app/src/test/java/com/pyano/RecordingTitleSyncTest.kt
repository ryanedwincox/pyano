// Concern: verifies durable ordered title changes | Non-concern: real network uploads | IO: (queued edits and receipts) -> assertions
package com.pyano

import com.pyano.sync.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test

class RecordingTitleSyncTest {
    private class Storage : TitleStorage {
        val values = mutableMapOf<String, String>()
        var afterWrite: (() -> Unit)? = null
        override fun read(key: String) = values[key]
        override fun write(values: Map<String, String?>) {
            values.forEach { (key, value) -> if (value == null) this.values.remove(key) else this.values[key] = value }
            afterWrite?.invoke()
        }
    }

    @Test fun destinationCreatedDuringJournalWriteNeverReplacesForeignFilesOrSticksTheTake() {
        val root = java.nio.file.Files.createTempDirectory("rename-collision").toFile()
        try {
            val wav = java.io.File(root, "Original.wav").apply { writeText("original wave") }
            val midi = com.pyano.audio.RecordingBundleFiles.midi(wav).apply { writeText("original midi") }
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            val foreign = java.io.File(root, "Occupied.wav")
            storage.afterWrite = {
                if (storage.values["id.to"] != null) {
                    storage.afterWrite = null
                    foreign.writeText("foreign wave")
                }
            }
            assertThrows(java.io.IOException::class.java) { store.renameLocally("id", wav, "Occupied", null) { _, _ -> } }
            assertEquals("foreign wave", foreign.readText())
            assertEquals("original wave", wav.readText())
            assertEquals("original midi", midi.readText())
            assertTrue(store.unresolvedRenamePaths().isEmpty())
            val renamed = store.renameLocally("id", wav, "Safe", null) { _, _ -> }
            assertEquals("original wave", java.io.File(renamed).readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun restoredTimestampContentChangeCannotBeMovedByRenameRecovery() {
        val root = java.nio.file.Files.createTempDirectory("rename-edited").toFile()
        try {
            val wav = java.io.File(root, "Original.wav").apply { writeText("original wave") }
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            storage.afterWrite = { if (storage.values["transactions"] == "id") throw AssertionError("Process died") }
            assertThrows(AssertionError::class.java) { store.renameLocally("id", wav, "Renamed") { _, _ -> } }
            storage.afterWrite = null
            val modified = java.nio.file.Files.getLastModifiedTime(wav.toPath())
            wav.writeText("modified wave")
            java.nio.file.Files.setLastModifiedTime(wav.toPath(), modified)
            assertEquals(listOf("id"), store.recoverRenames { _, _ -> fail("Changed bytes cannot inherit the rename") })
            assertEquals("modified wave", wav.readText())
            assertFalse(java.io.File(root, "Renamed.wav").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun keepsOriginalManifestAndProfileAcrossRenamesAndProcessRecreation() = runBlocking {
        val storage = Storage()
        val first = RecordingTitleSync(storage) { it }
        first.bind("id", "owner-a-profile", "a".repeat(64), "Original")
        first.renamed("id", "New name")
        val restored = RecordingTitleSync(storage) { it }
        assertEquals(setOf("id"), restored.pending())
        assertEquals("Original", restored.binding("id")!!.uploadTitle)
        assertEquals("owner-a-profile", restored.bind("id", "owner-b-profile", "b".repeat(64), "New name").profileId)
        val sent = mutableListOf<String>()
        syncRecordingTitle(restored, "id") { binding, title ->
            assertEquals("owner-a-profile", binding.profileId)
            sent.add(title)
            true
        }
        assertEquals(listOf("New name"), sent)
        assertNull(restored.desired("id"))
        assertTrue(restored.pending().isEmpty())
    }

    @Test fun editsDuringRequestAreSentAfterOlderReceipt() = runBlocking {
        val store = RecordingTitleSync(Storage()) { it }
        store.bind("id", "profile", "a".repeat(64), "Original")
        store.renamed("id", "First")
        val sent = mutableListOf<String>()
        syncRecordingTitle(store, "id") { _, title ->
            sent.add(title)
            if (title == "First") store.renamed("id", "Newest")
            true
        }
        assertEquals(listOf("First", "Newest"), sent)
        assertNull(store.desired("id"))
    }

    @Test fun missingNotUploadedRecordingRetainsIntentUntilUploadCompletion() = runBlocking {
        val store = RecordingTitleSync(Storage()) { it }
        store.bind("id", "profile", "a".repeat(64), "Original")
        store.renamed("id", "After rename")
        syncRecordingTitle(store, "id") { _, _ -> false }
        assertEquals("After rename", store.desired("id"))
        syncRecordingTitle(store, "id") { _, title -> assertEquals("After rename", title); true }
        assertNull(store.desired("id"))
    }

    @Test fun failureRetainsIntentAndNoChangeDoesNotSendRequest() = runBlocking {
        val store = RecordingTitleSync(Storage()) { it }
        store.bind("id", "profile", "a".repeat(64), "Original")
        syncRecordingTitle(store, "id") { _, _ -> error("Should not send") }
        store.renamed("id", "Changed")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { syncRecordingTitle(store, "id") { _, _ -> error("Offline") } }
        }
        assertEquals("Changed", store.desired("id"))
    }
    @Test fun processDeathBeforeFileRenameRecoversLocalAndCloudTitlesTogether() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Old.wav").apply { writeText("audio") }
            com.pyano.audio.RecordingBundleFiles.midi(source).writeText("midi")
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            store.bind("id", "profile", "a".repeat(64), "Old")
            storage.afterWrite = { if (storage.values["transactions"] == "id") throw AssertionError("Process died") }
            assertThrows(AssertionError::class.java) { store.renameLocally("id", source, "New") { _, _ -> } }
            assertTrue(source.exists())
            assertNull(store.desired("id"))
            storage.afterWrite = null
            val mappings = mutableListOf<Pair<String, String>>()
            val restored = RecordingTitleSync(storage) { it }
            restored.recoverRenames { old, new -> mappings.add(old to new) }
            assertFalse(source.exists())
            assertEquals("audio", java.io.File(directory, "New.wav").readText())
            assertEquals("midi", java.io.File(directory, "New.mid").readText())
            assertEquals("New", restored.desired("id"))
            assertEquals(1, mappings.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedFileAndIdentityUpdatesRemainRecoverable() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Old.wav").apply { writeText("audio") }
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            store.bind("id", "profile", "a".repeat(64), "Old")
            assertThrows(AssertionError::class.java) {
                store.renameLocally("id", source, "New") { _, _ -> throw AssertionError("Process died before mapping") }
            }
            assertNull(store.desired("id"))
            val restored = RecordingTitleSync(storage) { it }
            restored.recoverRenames { _, _ -> }
            assertEquals("New", restored.desired("id"))
            assertEquals("audio", java.io.File(directory, "New.wav").readText())
            restored.recoverRenames { _, _ -> error("Already recovered") }
        } finally { directory.deleteRecursively() }
    }

    @Test fun collidingRenameDoesNotOverwriteAnotherRecording() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val a = java.io.File(directory, "A.wav").apply { writeText("first") }
            val b = java.io.File(directory, "B.wav").apply { writeText("second") }
            val store = RecordingTitleSync(Storage()) { it }
            store.renameLocally("a", a, "Target") { _, _ -> }
            assertThrows(IllegalStateException::class.java) { store.renameLocally("b", b, "Target") { _, _ -> } }
            assertEquals("first", java.io.File(directory, "Target.wav").readText())
            assertEquals("second", b.readText())
        } finally { directory.deleteRecursively() }
    }
    @Test fun occupiedDestinationDoesNotBindAnUnuploadedRecordingToCurrentOwner() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Practice.wav").apply { writeText("audio") }
            java.io.File(directory, "Occupied.wav").writeText("another take")
            val store = RecordingTitleSync(Storage()) { it }
            val proposed = RecordingTitleBinding("owner-a", "a".repeat(64), "Practice")
            assertThrows(IllegalStateException::class.java) {
                store.renameLocally("id", source, "Occupied", proposed) { _, _ -> }
            }
            assertNull(store.binding("id"))
            assertNull(store.desired("id"))
            assertEquals("owner-b", store.bind("id", "owner-b", "a".repeat(64), "Practice").profileId)
        } finally { directory.deleteRecursively() }
    }

    @Test fun newCloudBindingCommitsOnlyAfterLocalRenameAndRecoversAfterProcessDeath() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Practice.wav").apply { writeText("audio") }
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            val proposed = RecordingTitleBinding("owner-a", "a".repeat(64), "Practice")
            assertThrows(AssertionError::class.java) {
                store.renameLocally("id", source, "New", proposed) { _, _ ->
                    assertNull(store.binding("id"))
                    throw AssertionError("Process died before metadata commit")
                }
            }
            val restored = RecordingTitleSync(storage) { it }
            restored.recoverRenames { _, _ -> }
            assertEquals(proposed, restored.binding("id"))
            assertEquals("New", restored.desired("id"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun ordinaryFailureBeforeMovingFilesDiscardsOnlyProposedBinding() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Practice.wav").apply { writeText("audio") }
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            storage.afterWrite = {
                if (storage.values["transactions"] == "id") throw IllegalStateException("Could not continue rename")
            }
            assertThrows(IllegalStateException::class.java) {
                store.renameLocally("id", source, "New", RecordingTitleBinding("owner-a", "a".repeat(64), "Practice")) { _, _ -> }
            }
            storage.afterWrite = null
            assertNull(store.binding("id"))
            assertEquals("owner-b", store.bind("id", "owner-b", "a".repeat(64), "Practice").profileId)
            store.recoverRenames { _, _ -> error("Failed operation must not be replayed") }
        } finally { directory.deleteRecursively() }
    }

    @Test fun conflictingTransactionDoesNotBlockOtherRecoveryOrUnrelatedRename() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val storage = Storage()
            val blocked = java.io.File(directory, "Blocked.wav").apply { writeText("old") }
            val occupied = java.io.File(directory, "Occupied.wav").apply { writeText("unrelated") }
            storage.values.putAll(mapOf("transactions" to "blocked", "blocked.from" to blocked.absolutePath,
                "blocked.to" to occupied.absolutePath, "blocked.hadMidi" to "false"))
            val store = RecordingTitleSync(storage) { it }
            val other = java.io.File(directory, "Other.wav").apply { writeText("other") }
            store.renameLocally("other", other, "Other renamed") { _, _ -> }
            assertEquals(listOf("blocked"), store.recoverRenames { _, _ -> })
            assertEquals("other", java.io.File(directory, "Other renamed.wav").readText())
            assertEquals("old", blocked.readText())
            assertEquals("unrelated", occupied.readText())
            assertThrows(IllegalStateException::class.java) {
                store.renameLocally("blocked", blocked, "Third") { _, _ -> }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun renameKeepsCapturedPendingOwnerEvenAfterUserSelectsDifferentCloudOwner() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val wav = java.io.File(directory, "Practice.wav").apply { writeText("audio") }
            val profile = recordingRenameProfile("captured-owner-a", null) { _, _ -> false }
            assertEquals("captured-owner-a", profile)
            val store = RecordingTitleSync(Storage()) { it }
            store.renameLocally("id", wav, "Renamed", RecordingTitleBinding(profile!!, "a".repeat(64), "Practice")) { _, _ -> }
            assertEquals("captured-owner-a", store.binding("id")!!.profileId)
            assertThrows(IllegalStateException::class.java) {
                recordingRenameProfile("captured-owner-a", "bound-owner-b") { _, _ -> false }
            }
            assertNull(recordingRenameProfile(null, null) { _, _ -> false })
        } finally { directory.deleteRecursively() }
    }

    @Test fun unknownLegacyOwnerStillPermitsLocalOnlyRenameAndAutomaticResumeUsesCapturedOwner() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            assertNull(recordingRenameProfile(null, null) { _, _ -> false })
            val source = java.io.File(directory, "Old.wav").apply { writeText("audio") }
            val store = RecordingTitleSync(Storage()) { it }
            store.renameLocally("legacy", source, "Renamed") { _, _ -> }
            assertEquals("audio", java.io.File(directory, "Renamed.wav").readText())
            assertNull(store.binding("legacy"))
            assertNull(store.desired("legacy"))
            assertEquals("captured-a", automaticRecordingUploadProfile("captured-a", null, "selected-b"))
            assertEquals("bound-a", automaticRecordingUploadProfile(null, "bound-a", "selected-b"))
            assertEquals("selected-b", automaticRecordingUploadProfile(null, null, "selected-b"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun renameRecoveryRejectsReplacedWavOrMidiBeforeMovingAnyFiles() {
        for (replaceMidi in listOf(false, true)) {
            val directory = kotlin.io.path.createTempDirectory().toFile()
            try {
                val source = java.io.File(directory, "Old.wav").apply { writeText("original audio") }
                val midi = com.pyano.audio.RecordingBundleFiles.midi(source).apply { writeText("original midi") }
                val storage = Storage()
                val store = RecordingTitleSync(storage) { it }
                store.bind("id", "profile", "a".repeat(64), "Old")
                storage.afterWrite = { if (storage.values["transactions"] == "id") throw AssertionError("Process died") }
                assertThrows(AssertionError::class.java) { store.renameLocally("id", source, "New") { _, _ -> } }
                storage.afterWrite = null
                val replaced = if (replaceMidi) midi else source
                assertTrue(replaced.delete())
                replaced.writeText("foreign replacement with a different length")
                val restored = RecordingTitleSync(storage) { it }
                assertEquals(listOf("id"), restored.recoverRenames { _, _ -> error("Must not change path identity") })
                assertTrue(source.isFile)
                assertTrue(midi.isFile)
                assertFalse(java.io.File(directory, "New.wav").exists())
                assertFalse(java.io.File(directory, "New.mid").exists())
                assertNull(restored.desired("id"))
                assertEquals("foreign replacement with a different length", replaced.readText())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun unknownOwnerProvenanceSurvivesLocalRenameAndCannotCreateRemoteBinding() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Old.wav").apply { writeText("audio") }
            val storage = Storage()
            val store = RecordingTitleSync(storage) { it }
            store.markUnknownOwner("id")
            store.renameLocally("id", source, "Renamed") { _, _ -> }
            val restored = RecordingTitleSync(storage) { it }
            assertTrue(restored.unknownOwner("id"))
            assertNull(restored.binding("id"))
            assertThrows(IllegalStateException::class.java) { restored.bind("id", "owner-b", "a".repeat(64), "Renamed") }
            restored.renamed("id", "Another name")
            assertNull(restored.desired("id"))
            assertEquals("audio", java.io.File(directory, "Renamed.wav").readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun audioReceiptRemainsSuccessfulWhenTitleQueuePersistenceFails() = runBlocking {
        var completed = false
        var desired = "Edited title"
        val error = recordUploadCompletion({ completed = true }, { desired.isNotEmpty() }) { error("WorkManager persistence failed") }
        assertTrue(completed)
        assertTrue(error!!.contains("Audio was uploaded"))
        assertEquals("Edited title", desired)
        val retried = recordUploadCompletion({ completed = true }, { desired.isNotEmpty() }) { desired = "" }
        assertNull(retried)
        assertEquals("", desired)
    }

    @Test fun uploadWithoutPendingTitleDoesNotQueueMetadataWork() = runBlocking {
        var completed = false
        val error = recordUploadCompletion({ completed = true }, { false }) { error("No title work expected") }
        assertTrue(completed)
        assertNull(error)
    }

    @Test fun neverUploadedLocalRenameStaysUnboundUntilExplicitUploadChoosesAnOwner() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val source = java.io.File(directory, "Old.wav").apply { writeText("audio") }
            val store = RecordingTitleSync(Storage()) { it }
            val profile = recordingRenameProfile(null, null) { _, _ -> error("Unbound takes have no owner to compare") }
            assertNull(profile)
            store.renameLocally("id", source, "Renamed") { _, _ -> }
            assertNull(store.binding("id"))
            assertNull(store.desired("id"))
            assertEquals("explicit-owner-b", store.bind("id", "explicit-owner-b", "a".repeat(64), "Renamed").profileId)
        } finally { directory.deleteRecursively() }
    }

    @Test fun frozenUploadBindingRejectsEditedMidiAfterSameOwnerCredentialRotationBeforeSending() = runBlocking {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val wav = java.io.File(directory, "Practice.wav").apply { writeText("original audio") }
            val midi = com.pyano.audio.RecordingBundleFiles.midi(wav).apply { writeText("original midi") }
            val client = CloudSyncClient(directory)
            val original = client.stableId(wav, midi)
            val binding = RecordingTitleBinding("owner-a-old-token", original, "Practice")
            val sameOwner: (String, String) -> Boolean = { first, second -> first == "owner-a-old-token" && second == "owner-a-new-token" }
            validateRecordingUploadBinding(binding, "owner-a-new-token", original, sameOwner)
            midi.writeText("edited midi bytes")
            val changed = client.stableId(wav, midi)
            assertNotEquals(original, changed)
            val error = assertThrows(CloudSyncException::class.java) {
                validateRecordingUploadBinding(binding, "owner-a-new-token", changed, sameOwner)
            }
            assertFalse(error.retryable)
            assertTrue(error.message!!.contains("original Cloud upload"))
            val ownerError = assertThrows(RecordingOwnerUnverified::class.java) {
                validateRecordingUploadBinding(binding, "owner-b", original) { _, _ -> false }
            }
            assertTrue(ownerError.message!!.contains("original Cloud account"))
            assertEquals("original audio", wav.readText())
            assertEquals("edited midi bytes", midi.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun duplicateLocalCopiesShareDurableNewestTitleAcrossTokenRotationAndLateJobs() = runBlocking {
        val storage = Storage()
        val owners: (String) -> String? = { profile -> if (profile.startsWith("owner-a")) "https://cloud.example\u0000owner-a" else profile }
        val store = RecordingTitleSync(storage, owners)
        store.bind("copy-a", "owner-a-old-token", "a".repeat(64), "Original")
        store.bind("copy-b", "owner-a-new-token", "a".repeat(64), "Original")
        store.bind("other-owner", "owner-b", "a".repeat(64), "Other")
        assertEquals(store.remoteQueueKey("copy-a"), store.remoteQueueKey("copy-b"))
        assertNotEquals(store.remoteQueueKey("copy-a"), store.remoteQueueKey("other-owner"))
        store.renamed("copy-a", "Older edit")
        store.renamed("copy-b", "Newest edit")
        store.renamed("other-owner", "Independent owner edit")
        val restored = RecordingTitleSync(storage, owners)
        val sent = mutableListOf<String>()
        syncRecordingTitle(restored, "copy-a") { _, title -> sent.add(title); true }
        syncRecordingTitle(restored, "copy-b") { _, title -> sent.add(title); true }
        assertEquals(listOf("Newest edit"), sent)
        assertNull(restored.desired("copy-a"))
        assertNull(restored.desired("copy-b"))
        assertEquals("Independent owner edit", restored.desired("other-owner"))
        assertEquals(setOf("other-owner"), restored.pending())
    }

    @Test fun inFlightOlderCopyCannotFinishAfterNewerCopyAndRestoreItsTitle() = runBlocking {
        val store = RecordingTitleSync(Storage()) { "same-owner-scope" }
        store.bind("copy-a", "old-token", "a".repeat(64), "Original")
        store.bind("copy-b", "new-token", "a".repeat(64), "Original")
        store.renamed("copy-a", "Older")
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val sent = mutableListOf<String>()
        val oldJob = async {
            syncRecordingTitle(store, "copy-a") { _, title ->
                sent.add(title)
                if (title == "Older") { started.complete(Unit); release.await() }
                true
            }
        }
        started.await()
        store.renamed("copy-b", "Newest")
        val newJob = async { syncRecordingTitle(store, "copy-b") { _, title -> sent.add(title); true } }
        kotlinx.coroutines.yield()
        release.complete(Unit)
        oldJob.await()
        newJob.await()
        assertEquals(listOf("Older", "Newest"), sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun preGroupStaleIntentCannotBecomeNewestWhenItsWorkerRunsLate() = runBlocking {
        val storage = Storage()
        val store = RecordingTitleSync(storage) { "same-owner" }
        store.bind("old-copy", "profile", "a".repeat(64), "Original")
        store.bind("new-copy", "profile", "a".repeat(64), "Original")
        storage.values["old-copy.desired"] = "Unordered legacy edit"
        storage.values["pending"] = "old-copy"
        store.renamed("new-copy", "New ordered edit")
        val sent = mutableListOf<String>()
        syncRecordingTitle(store, "old-copy") { _, title -> sent.add(title); true }
        assertEquals(listOf("New ordered edit"), sent)
        assertTrue(store.pending().isEmpty())
    }

    @Test fun cachedRawOriginGroupMigratesWithItsMembersIntoCanonicalRotatedProfileGroup() = runBlocking {
        val storage = Storage()
        val old = RecordingTitleSync(storage) { "https://MUSIC.EXAMPLE.COM:443\u0000owner-a" }
        old.bind("old-copy", "old-profile", "a".repeat(64), "Original")
        old.renamed("old-copy", "Older raw-origin edit")
        val oldGroup = old.remoteQueueKey("old-copy")!!
        val current = RecordingTitleSync(storage) { "https://music.example.com\u0000owner-a" }
        current.bind("new-copy", "rotated-profile", "a".repeat(64), "Original")
        current.renamed("new-copy", "Newest canonical edit")
        val canonicalGroup = current.remoteQueueKey("new-copy")!!
        assertNotEquals(oldGroup, canonicalGroup)
        val sent = mutableListOf<String>()
        syncRecordingTitle(current, "old-copy") { _, title -> sent.add(title); true }
        syncRecordingTitle(current, "new-copy") { _, title -> sent.add(title); true }
        assertEquals(listOf("Newest canonical edit"), sent)
        assertEquals(canonicalGroup, current.remoteQueueKey("old-copy"))
        assertEquals(canonicalGroup, storage.values["old-copy.group"])
        assertNull(storage.values["group:$oldGroup.desired"])
        assertNull(storage.values["group:$oldGroup.members"])
        assertTrue(current.pending().isEmpty())
    }

    @Test fun canonicalAcknowledgedRevisionSuppressesOlderPersistedRawGroupIntent() = runBlocking {
        val storage = Storage()
        val old = RecordingTitleSync(storage) { "https://MUSIC.EXAMPLE.COM:443\u0000owner-a" }
        old.bind("old-copy", "old-profile", "a".repeat(64), "Original")
        old.renamed("old-copy", "Older pending edit")
        val current = RecordingTitleSync(storage) { "https://music.example.com\u0000owner-a" }
        current.bind("new-copy", "rotated-profile", "a".repeat(64), "Original")
        current.renamed("new-copy", "Newer acknowledged edit")
        val sent = mutableListOf<String>()
        syncRecordingTitle(current, "new-copy") { _, title -> sent.add(title); true }
        syncRecordingTitle(current, "old-copy") { _, title -> sent.add(title); true }
        assertEquals(listOf("Newer acknowledged edit"), sent)
        assertNull(current.desired("old-copy"))
        assertTrue(current.pending().isEmpty())
        current.renamed("old-copy", "Latest edit after migration")
        syncRecordingTitle(current, "new-copy") { _, title -> sent.add(title); true }
        assertEquals(listOf("Newer acknowledged edit", "Latest edit after migration"), sent)
    }

}
