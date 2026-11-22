// Concern: verifies keep/discard UI and durable sync indicators | Non-concern: live credentials | IO: (decisions and receipts) -> assertions
package com.pyano

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.pyano.sync.RecordingCloudState
import com.pyano.sync.RecordingCloudStatus
import com.pyano.ui.RecordingCloudIndicator
import com.pyano.ui.SaveTakeDialog
import com.pyano.ui.SaveTakeFlow
import com.pyano.ui.UploadWarning
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class RecordingWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun showTestUi() {
        compose.activity.runOnUiThread {
            if (Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true); compose.activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }
    @Test fun saveAndDiscardAreExplicitAndBusyPreventsDuplicateDecisions() {
        var saved = 0
        var discarded = 0
        val busy = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { MaterialTheme { SaveTakeDialog(busy.value, { saved++ }, { discarded++ }) } }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertEquals(1, saved); assertEquals(1, discarded); busy.value = true }
        compose.onNodeWithText("Discard").assertIsNotEnabled()
        compose.onNodeWithText("Working…").assertIsNotEnabled()
    }
    @Test fun busyRenameKeepsProposedNameAndCanBeRetried() {
        var accepting = false
        var submitted = ""
        compose.setContent { MaterialTheme {
            com.pyano.ui.RecordingRow(com.pyano.audio.RecordingInfo("Original", "/unused.wav", 1000, 0, 100),
                RecordingCloudState.PENDING, { name -> submitted = name; accepting }, {}, {}, {}, true)
        } }
        compose.onNodeWithContentDescription("Rename").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Second name")
        compose.onNodeWithText("Rename", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Rename Recording").assertIsDisplayed()
        compose.onNodeWithText("Second name").assertIsDisplayed()
        compose.onNodeWithText("Wait for the current recording operation to finish, then retry.").assertIsDisplayed()
        compose.runOnIdle { accepting = true }
        compose.onNodeWithText("Rename", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Rename Recording").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Second name", submitted) }
    }
    @Test fun clearingRecoveryWarningKeepsIndependentRecoveryAction() {
        val warning = androidx.compose.runtime.mutableStateOf<com.pyano.sync.RecordingUploadWarning?>(
            com.pyano.sync.RecordingUploadWarning("take-publication-recovery:journal", "Interrupted take"))
        var recovered = 0
        compose.setContent { MaterialTheme {
            com.pyano.ui.RecordingsListCard(emptyList(), {}, warning.value, { warning.value = null },
                { recovered++ }, true, emptyMap(), { _, _ -> true }, {}, {}, {}, true)
        } }
        compose.onNodeWithContentDescription("Recording warning").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.onNodeWithContentDescription("Recording warning").assertDoesNotExist()
        compose.onNodeWithContentDescription("Recover interrupted takes").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, recovered) }
    }
    @Test fun namingSecondModalUsesScoreTitleAndCancelRetainsTake() {
        var saved: String? = null
        var discarded = 0
        compose.setContent { MaterialTheme { SaveTakeFlow(false, "Star Wars Theme", { saved = it }, { discarded++ }) } }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Name recording").assertIsDisplayed()
        compose.onNodeWithText("Star Wars Theme").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save recording?").assertIsDisplayed()
        compose.runOnIdle { assertNull(saved); assertEquals(0, discarded) }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Recording name").performTextReplacement("")
        compose.onNodeWithText("Save recording").assertIsNotEnabled()
        compose.onNodeWithText("Recording name").performTextReplacement("Star Wars practice")
        compose.onNodeWithText("Save recording").performClick()
        compose.runOnIdle { assertEquals("Star Wars practice", saved); assertEquals(0, discarded) }
    }
    @Test fun rejectedUnpublishedNameStaysEditableWhenRecorderConfirmsRetryIsSafe() {
        val error = androidx.compose.runtime.mutableStateOf<String?>(null)
        var submitted = ""
        compose.setContent { MaterialTheme {
            SaveTakeFlow(false, "First", { submitted = it; error.value = "Could not finish take" }, {},
                error.value, retryNameLocked = false)
        } }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save recording").performClick()
        compose.onNodeWithText("Recording name").assertIsEnabled().performTextReplacement("Corrected")
        compose.onNodeWithText("Save recording").performClick()
        compose.runOnIdle { assertEquals("Corrected", submitted) }
    }

    @Test fun reservedTakeNameRemainsLockedAfterSaveFailure() {
        val error = androidx.compose.runtime.mutableStateOf<String?>(null)
        compose.setContent { MaterialTheme {
            SaveTakeFlow(false, "Reserved", { error.value = "Could not finish take" }, {}, error.value)
        } }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save recording").performClick()
        compose.onNodeWithText("Recording name").assertIsNotEnabled()
        compose.onNodeWithText("Retry keeps this name to avoid saving the take twice.").assertIsDisplayed()
    }

    @Test fun viewerRecordingButtonTogglesStartAndStop() {
        val recording = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { MaterialTheme {
            com.pyano.ui.ScoreViewerContent(com.pyano.viewer.ScoreViewerState(), {}, {}, { _, _, _ -> }, {},
                recording.value, true, onRecord = { recording.value = !recording.value })
        } }
        compose.onNodeWithContentDescription("Start recording").performClick()
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }
    @Test fun viewerCountdownReplacesRecordButton() {
        val seconds = androidx.compose.runtime.mutableStateOf(5)
        compose.setContent { MaterialTheme {
            com.pyano.ui.ScoreViewerContent(com.pyano.viewer.ScoreViewerState(), {}, {}, { _, _, _ -> }, {},
                countdown = seconds.value, onRecord = {})
        } }
        compose.onNodeWithText("5").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start recording").assertDoesNotExist()
        compose.runOnIdle { seconds.value = 0 }
        compose.onNodeWithText("5").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start recording").assertIsDisplayed()
    }

    @Test fun uploadWarningKeepsDetailsBehindDialog() {
        val error = "Audio checksum did not match the declared asset"
        var cleared = 0
        compose.setContent { MaterialTheme { UploadWarning(error, "failed-job") { cleared++ } } }
        compose.onNodeWithText(error, substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Recording warning").performClick()
        compose.onNodeWithText(error, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Review the recording before retrying the action.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Recording needs attention").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, cleared) }
        compose.onNodeWithContentDescription("Recording warning").performClick()
        compose.onNodeWithText("Clear").performClick()
        compose.runOnIdle { assertEquals(1, cleared) }
        compose.onNodeWithText("Recording needs attention").assertDoesNotExist()
    }
    @Test fun clearingWarningPersistsOnlyForAcknowledgedAttempt() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "test-warning-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(file, Context.MODE_PRIVATE)
        }
        try {
            val store = RecordingCloudStatus(context)
            val retained = java.io.File(base.cacheDir, file).apply { writeText("retained") }
            val id = store.id(retained.absolutePath)
            store.pending(retained.absolutePath)
            store.dismissWarning("failed-job")
            val restored = RecordingCloudStatus(context)
            assertTrue(restored.warningDismissed("failed-job"))
            assertFalse(restored.warningDismissed("retry-job"))
            assertEquals(id, restored.existingId(retained.absolutePath))
            assertFalse(restored.synced(id))
            assertEquals(setOf(java.io.File(base.cacheDir, file).absolutePath), restored.pendingPaths())
        } finally { base.deleteSharedPreferences(file); java.io.File(base.cacheDir, file).delete() }
    }
    @Test fun cloudStatesAreDistinctAndSyncedCannotQueueAgain() {
        val state = androidx.compose.runtime.mutableStateOf(RecordingCloudState.PENDING)
        var retries = 0
        compose.setContent { MaterialTheme { RecordingCloudIndicator(state.value, true) { retries++ } } }
        compose.onNodeWithContentDescription("Not uploaded / pending; tap to upload").performClick()
        compose.runOnIdle { state.value = RecordingCloudState.UPLOADING }
        compose.onNodeWithContentDescription("Uploading to Cloud").assertExists()
        compose.runOnIdle { state.value = RecordingCloudState.SYNCED }
        compose.onNodeWithContentDescription("Synced to Cloud").assertExists()
        compose.runOnIdle { assertEquals(1, retries) }
    }
    @Test fun receiptPendingIntentAndRenameSurviveStoreRecreation() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val file = "test-recording-status-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(file, Context.MODE_PRIVATE)
        }
        try {
            val store = RecordingCloudStatus(context)
            val original = java.io.File(base.cacheDir, "$file-original.wav").apply { writeText("original") }
            val renamed = java.io.File(base.cacheDir, "$file-renamed.wav")
            val id = store.id(original.absolutePath)
            store.pending(original.absolutePath)
            store.complete(id)
            store.rename(original.absolutePath, original.absolutePath)
            assertEquals(id, store.existingId(original.absolutePath))
            check(original.renameTo(renamed))
            store.rename(original.absolutePath, renamed.absolutePath)
            val restored = RecordingCloudStatus(context)
            assertEquals(id, restored.existingId(renamed.absolutePath))
            assertTrue(restored.synced(id))
            assertEquals(setOf(renamed.absolutePath), restored.pendingPaths())
            restored.queued(renamed.absolutePath)
            assertTrue(restored.pendingPaths().isEmpty())
            restored.delete(renamed.absolutePath)
            val replacement = restored.id(renamed.absolutePath)
            assertNotEquals(id, replacement)
            assertFalse(restored.synced(replacement))
        } finally {
            base.deleteSharedPreferences(file)
            java.io.File(base.cacheDir, "$file-original.wav").delete()
            java.io.File(base.cacheDir, "$file-renamed.wav").delete()
        }
    }
    @Test fun interruptedTakeWarningOffersRecoveryWithoutClearingTheFailure() {
        var recovered = 0
        var cleared = 0
        compose.setContent {
            MaterialTheme { UploadWarning("A saved take needs recovery", "take-publication-recovery", onRecover = { recovered++ }) { cleared++ } }
        }
        compose.onNodeWithContentDescription("Recording warning").performClick()
        compose.onNodeWithText("Recover").performClick()
        compose.runOnIdle { assertEquals(1, recovered); assertEquals(0, cleared) }
        compose.onNodeWithText("Recording needs attention").assertDoesNotExist()
    }

    @Test fun savingTakeReachesWorkManagerWithItsOwnIdentity() = kotlinx.coroutines.runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val unique = "save-queue-test-${UUID.randomUUID()}"
        val root = java.io.File(base.cacheDir, unique).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(unique, Context.MODE_PRIVATE)
        }
        val status = RecordingCloudStatus(context)
        val manager = androidx.work.WorkManager.getInstance(base)
        var job: java.util.UUID? = null
        try {
            val scheduler = com.pyano.sync.RecordingUploadScheduler(context, status) { true }
            val wav = java.io.File(root, "synthetic.wav")
            val result = com.pyano.sync.saveAndScheduleTake({ beforePublish ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = java.nio.ByteBuffer.allocate(1964).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bytes.put("RIFF".toByteArray()).putInt(1956).put("WAVEfmt ".toByteArray()).putInt(16)
                    bytes.putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
                    bytes.put("data".toByteArray()).putInt(1920)
                    val staging = java.io.File(root, "synthetic.part").apply { writeBytes(bytes.array()) }
                    beforePublish(wav.absolutePath, com.pyano.audio.recordingFileProof(staging), null)
                    java.nio.file.Files.move(staging.toPath(), wav.toPath())
                }
                wav.absolutePath
            }, status, scheduler, "nonexistent-test-profile-${UUID.randomUUID()}")
            val id = status.existingId(result.path)!!
            val jobs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get()
            }
            assertEquals(1, jobs.size)
            job = jobs.single().id
            assertTrue(status.pendingPaths().isEmpty())
            assertTrue(result.message.contains("queued"))
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { job?.let { manager.cancelWorkById(it).result.get() } }
            root.deleteRecursively()
            base.deleteSharedPreferences(unique)
        }
    }

}
