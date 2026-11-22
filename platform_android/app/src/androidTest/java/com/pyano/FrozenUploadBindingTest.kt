// Concern: verifies production upload preflight rejects changed bundles | Non-concern: real cloud | IO: (rotated profile and edited MIDI) -> refusal
package com.pyano

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.pyano.audio.RecordingBundleFiles
import com.pyano.audio.RecordingInfo
import com.pyano.audio.recordingFileProof
import com.pyano.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID

class FrozenUploadBindingTest {
    @Test fun schedulerAndWorkerRejectChangedMidiAfterSameOwnerTokenRotationBeforeNetworkOrReceipt() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "synthetic-frozen-${UUID.randomUUID()}"
        val root = File(base.cacheDir, namespace).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$namespace-$name", mode)
        }
        val previousManager = androidx.work.impl.WorkManagerImpl.getInstance()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val manager = WorkManager.getInstance(context)
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 100 }
        try {
            val credentials = CloudCredentialsStore(context) {}
            val endpoint = "https://127.0.0.1:${listener.localPort}"
            val originalProfile = credentials.save(endpoint, "owner_a", "synthetic-original-token")
            val wav = File(root, "Practice.wav").apply { writeText("original audio") }
            val midi = RecordingBundleFiles.midi(wav).apply { writeText("original MIDI") }
            val status = RecordingCloudStatus(context)
            val id = status.id(wav.absolutePath)
            val originalRemote = CloudSyncClient(root).stableId(wav, midi)
            val titles = RecordingTitleSync(context)
            val binding = titles.bind(id, originalProfile, originalRemote, "Practice")
            status.pending(wav.absolutePath, recordingFileProof(wav), originalProfile)
            val rotatedProfile = credentials.save(endpoint, "owner_a", "synthetic-rotated-token")
            assertNotEquals(originalProfile, rotatedProfile)
            midi.writeText("changed MIDI after original upload binding")
            val scheduler = RecordingUploadScheduler(context, status)
            val rejected = assertThrows(CloudSyncException::class.java) {
                runBlocking { scheduler.enqueue(RecordingInfo("Practice", wav.absolutePath, 1000, 1000, wav.length()), rotatedProfile) }
            }
            assertFalse(rejected.retryable)
            assertTrue(manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get().isEmpty())
            assertEquals(binding, titles.binding(id))
            assertFalse(status.synced(id))
            credentials.save(endpoint, "owner_b", "synthetic-other-owner-token")
            assertEquals("synthetic-rotated-token", credentials.recordingCredentials(originalProfile)?.token)
            val snapshot = CloudUploadQueue(File(root, "cloud-upload-queue")).snapshot(wav, rotatedProfile, id)
            val input = Data.Builder().putString(CloudUploadWorker.KEY_PATH, snapshot.wav.absolutePath)
                .putLong(CloudUploadWorker.KEY_DURATION_MS, 1000).putString(CloudUploadWorker.KEY_PROFILE_ID, originalProfile)
                .putString(CloudUploadWorker.KEY_RECORDING_ID, id).putString(CloudUploadWorker.KEY_TITLE, "Practice")
                .putString(CloudUploadWorker.KEY_CAPTURED_AT, "2026-11-21T00:00:00Z").build()
            val worker = TestListenableWorkerBuilder<CloudUploadWorker>(context).setInputData(input).build()
            val result = worker.doWork()
            assertTrue(result is ListenableWorker.Result.Failure)
            val detail = (result as ListenableWorker.Result.Failure).outputData.getString(CloudUploadWorker.KEY_ERROR)
            assertTrue(checkNotNull(detail).contains("original Cloud upload"))
            assertFalse(status.synced(id))
            assertEquals(binding, titles.binding(id))
            assertEquals("changed MIDI after original upload binding", midi.readText())
            assertThrows(SocketTimeoutException::class.java) { listener.accept() }
        } finally {
            listener.close()
            try { manager.cancelAllWork().result.get() }
            finally {
                try { WorkManagerTestInitHelper.closeWorkDatabase() }
                finally { androidx.work.impl.WorkManagerImpl.setDelegate(previousManager) }
            }
            root.deleteRecursively()
            listOf("pyano_cloud_credentials", "recording_title_sync", "recording_cloud_status").forEach {
                base.deleteSharedPreferences("$namespace-$it")
            }
        }
    }
}
