// Concern: verifies token rotation preserves upload work identity | Non-concern: real network | IO: (same-owner profiles and frozen snapshot) -> one chain
package com.pyano

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.pyano.audio.RecordingBundleFiles
import com.pyano.audio.RecordingInfo
import com.pyano.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class UploadRotationWorkTest {
    class NoNetworkWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.failure()
    }
    @Test fun rotationAndRepeatedRetryRetainOriginalSnapshotAndWorkWhileDistinctCopiesRemainIndependent() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "synthetic-rotation-${UUID.randomUUID()}"
        val root = File(base.cacheDir, namespace).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = root
            override fun getCacheDir(): File = root
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$namespace-$name", mode)
        }
        val previous = androidx.work.impl.WorkManagerImpl.getInstance()
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, className: String, workerParameters: WorkerParameters): ListenableWorker? =
                if (className == CloudUploadWorker::class.java.name) NoNetworkWorker(appContext, workerParameters) else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setWorkerFactory(factory).build())
        val manager = WorkManager.getInstance(context)
        try {
            val credentials = CloudCredentialsStore(context) {}
            val originalProfile = credentials.save("https://music.example.invalid", "owner_a", "synthetic-original-token")
            val wav = File(root, "Practice.wav").apply { writeText("unchanged audio") }
            val midi = RecordingBundleFiles.midi(wav).apply { writeText("unchanged MIDI") }
            val status = RecordingCloudStatus(context)
            val id = status.id(wav.absolutePath)
            val titles = RecordingTitleSync(context)
            titles.bind(id, originalProfile, CloudSyncClient(root).stableId(wav, midi), "Practice")
            val queueRoot = File(root, "cloud-upload-queue")
            val queue = CloudUploadQueue(queueRoot)
            val original = queue.snapshot(wav, originalProfile, id)
            val workName = CloudUploadQueue.uniqueWorkName(original.id)
            val work = OneTimeWorkRequestBuilder<NoNetworkWorker>().setInitialDelay(1, TimeUnit.DAYS)
                .addTag("pyano-cloud-upload").addTag(RecordingCloudStatus.TAG_PREFIX + id).build()
            manager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, work).result.get()
            val rotated = credentials.save("https://MUSIC.EXAMPLE.invalid:443/", "owner_a", "synthetic-rotated-token")
            assertNotEquals(originalProfile, rotated)
            val scheduler = RecordingUploadScheduler(context, status)
            val recording = RecordingInfo("Practice", wav.absolutePath, 1000, 1000, wav.length())
            scheduler.enqueue(recording, rotated)
            scheduler.enqueue(recording, rotated)
            assertEquals(listOf(work.id), manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get().map { it.id })
            assertEquals(1, queueRoot.listFiles()!!.size)
            manager.cancelAllWork().result.get()
            manager.pruneWork().result.get()
            scheduler.enqueue(recording, rotated)
            assertEquals(1, manager.getWorkInfosForUniqueWork(workName).get().size)
            assertEquals(1, queueRoot.listFiles()!!.size)
            assertFalse(queue.snapshot(wav, originalProfile, id).created)
            val copy = File(root, "Separate copy.wav").also { wav.copyTo(it) }
            midi.copyTo(RecordingBundleFiles.midi(copy))
            val copyId = status.id(copy.absolutePath)
            scheduler.enqueue(RecordingInfo("Separate copy", copy.absolutePath, 1000, 1000, copy.length()), rotated)
            assertNotEquals(id, copyId)
            assertEquals(1, manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + copyId).get().size)
            assertEquals(1, manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get().size)
        } finally {
            try { manager.cancelAllWork().result.get() }
            finally {
                try { WorkManagerTestInitHelper.closeWorkDatabase() }
                finally { androidx.work.impl.WorkManagerImpl.setDelegate(previous) }
            }
            root.deleteRecursively()
            listOf("pyano_cloud_credentials", "recording_title_sync", "recording_cloud_status").forEach {
                base.deleteSharedPreferences("$namespace-$it")
            }
        }
    }
}
