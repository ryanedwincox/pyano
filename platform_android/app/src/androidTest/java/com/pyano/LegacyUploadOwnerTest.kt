// Concern: verifies legacy upload retries stay owner-bound | Non-concern: real media upload | IO: (isolated prior work) -> refusal
package com.pyano

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.pyano.audio.RecordingInfo
import com.pyano.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class LegacyUploadOwnerTest {
    class PreviousUpload(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.failure()
    }

    @Test fun unboundPriorUploadCannotBeRetriedUnderSelectedDifferentOwner() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "synthetic-owner-${UUID.randomUUID()}"
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
        try {
            val wav = File(root, "Practice.wav").apply { writeText("local audio") }
            val status = RecordingCloudStatus(context)
            val id = status.id(wav.absolutePath)
            val old = OneTimeWorkRequestBuilder<PreviousUpload>().setInitialDelay(1, TimeUnit.DAYS)
                .addTag("pyano-cloud-upload").addTag(RecordingCloudStatus.TAG_PREFIX + id).build()
            manager.enqueue(old).result.get()
            val scheduler = RecordingUploadScheduler(context, status) { true }
            assertThrows(RecordingOwnerUnverified::class.java) {
                runBlocking { scheduler.enqueue(RecordingInfo("Practice", wav.absolutePath, 1000, 1000, wav.length()), "owner-b") }
            }
            assertNull(RecordingTitleSync(context).binding(id))
            assertFalse(File(root, "cloud-upload-queue").exists())
            assertEquals("local audio", wav.readText())
            val titles = RecordingTitleSync(context)
            titles.markUnknownOwner(id)
            val renamed = titles.renameLocally(id, wav, "Renamed") { oldPath, newPath -> status.rename(oldPath, newPath) }
            manager.cancelAllWork().result.get()
            manager.pruneWork().result.get()
            assertTrue(manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get().isEmpty())
            val restored = RecordingTitleSync(context)
            assertTrue(restored.unknownOwner(id))
            assertEquals(id, status.existingId(renamed))
            assertThrows(RecordingOwnerUnverified::class.java) {
                runBlocking { scheduler.enqueue(RecordingInfo("Renamed", renamed, 1000, 1000, File(renamed).length()), "owner-b") }
            }
            assertNull(restored.binding(id))
            assertFalse(File(root, "cloud-upload-queue").exists())
            assertEquals("local audio", File(renamed).readText())
            val unpublishedTake = File(root, "New.wav").apply { writeText("new saved take") }
            status.pending(unpublishedTake.absolutePath, com.pyano.audio.recordingFileProof(unpublishedTake), "original-profile")
            assertEquals("original-profile", RecordingCloudStatus(context).pendingProfile(unpublishedTake.absolutePath))
            assertThrows(IllegalStateException::class.java) {
                runBlocking { scheduler.enqueue(RecordingInfo("New", unpublishedTake.absolutePath, 1000, 1000, unpublishedTake.length()), "owner-b") }
            }
            assertFalse(File(root, "cloud-upload-queue").exists())

        } finally {
            try { manager.cancelAllWork().result.get() }
            finally {
                try { WorkManagerTestInitHelper.closeWorkDatabase() }
                finally { androidx.work.impl.WorkManagerImpl.setDelegate(previousManager) }
            }
            root.deleteRecursively()
        }
    }
}
