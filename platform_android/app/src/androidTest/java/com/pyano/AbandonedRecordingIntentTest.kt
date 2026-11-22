// Concern: verifies abandoned save intent cannot bind foreign audio | Non-concern: live network | IO: (collision and abort) -> explicit scoped upload
package com.pyano

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.WorkManagerTestInitHelper
import com.pyano.audio.RecordingInfo
import com.pyano.audio.recordingFileProof
import com.pyano.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AbandonedRecordingIntentTest {
    class NoNetworkWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }

    @Test fun abandonedCollisionAllowsForeignRecordingToBeExplicitlyUploadedUnderItsOwnChosenOwner() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "synthetic-abandon-${UUID.randomUUID()}"
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
            val ownerA = credentials.save("https://music.example.invalid", "owner_a", "synthetic-a")
            val ownerB = credentials.save("https://music.example.invalid", "owner_b", "synthetic-b")
            val status = RecordingCloudStatus(context)
            val scheduler = RecordingUploadScheduler(context, status)
            val staged = File(root, ".pyano-take-unpublished.part").apply { writeText("abandoned take") }
            val foreign = File(root, "Collision.wav")
            try {
                saveAndScheduleTake({ intent ->
                    val proof = recordingFileProof(staged)
                    intent(foreign.path, proof, null)
                    foreign.writeText("independent foreign recording")
                    intent.abandon(foreign.path, proof, null)
                    throw java.nio.file.FileAlreadyExistsException(foreign.path)
                }, status, scheduler, ownerA)
                fail("Expected publication collision")
            } catch (_: java.nio.file.FileAlreadyExistsException) { }
            assertTrue(status.pendingPaths().isEmpty())
            status.requirePendingBundle(foreign.path)
            val id = status.id(foreign.path)
            assertFalse(status.unverifiedOwnerOrigin(id))
            val titles = RecordingTitleSync(context)
            assertNull(titles.binding(id))
            scheduler.enqueue(RecordingInfo("Collision", foreign.path, 1000, 1000, foreign.length()), ownerB)
            assertEquals(ownerB, titles.binding(id)!!.profileId)
            assertEquals(1, manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get().size)
            assertEquals("independent foreign recording", foreign.readText())
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
