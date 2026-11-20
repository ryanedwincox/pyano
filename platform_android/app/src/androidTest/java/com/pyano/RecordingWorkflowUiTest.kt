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
            val id = store.id("test-original.wav")
            store.pending("test-original.wav")
            store.complete(id)
            store.rename("test-original.wav", "test-original.wav")
            assertEquals(id, store.existingId("test-original.wav"))
            store.rename("test-original.wav", "test-renamed.wav")
            val restored = RecordingCloudStatus(context)
            assertEquals(id, restored.existingId("test-renamed.wav"))
            assertTrue(restored.synced(id))
            assertEquals(setOf("test-renamed.wav"), restored.pendingPaths())
            restored.queued("test-renamed.wav")
            assertTrue(restored.pendingPaths().isEmpty())
            restored.delete("test-renamed.wav")
            val replacement = restored.id("test-renamed.wav")
            assertNotEquals(id, replacement)
            assertFalse(restored.synced(replacement))
        } finally { base.deleteSharedPreferences(file) }
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
                    beforePublish(wav.absolutePath)
                    val bytes = java.nio.ByteBuffer.allocate(1964).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bytes.put("RIFF".toByteArray()).putInt(1956).put("WAVEfmt ".toByteArray()).putInt(16)
                    bytes.putShort(1).putShort(2).putInt(48000).putInt(192000).putShort(4).putShort(16)
                    bytes.put("data".toByteArray()).putInt(1920)
                    wav.writeBytes(bytes.array())
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
