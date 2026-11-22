// Concern: verifies original-owner recovery from frozen legacy snapshots | Non-concern: real cloud | IO: (old snapshot layout) -> scoped retry
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
import java.security.MessageDigest
import java.util.UUID

class LegacySnapshotMigrationTest {
    class NoNetworkWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork(): Result = Result.success()
    }
    @Test fun exactLegacySnapshotRestoresOriginalOwnerButChangedOrAmbiguousBundlesStayBlocked() = runBlocking {
        for (mode in listOf("intact", "changed", "ambiguous")) {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val namespace = "synthetic-legacy-${UUID.randomUUID()}"
            val root = File(base.cacheDir, namespace).apply { mkdirs() }
            val context = object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this
                override fun getFilesDir(): File = root
                override fun getCacheDir(): File = root
                override fun getSharedPreferences(name: String, flags: Int) = base.getSharedPreferences("$namespace-$name", flags)
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
                val wav = File(root, "Practice.wav").apply { writeText("legacy audio") }
                val midi = RecordingBundleFiles.midi(wav).apply { writeText("legacy MIDI") }
                fun oldSnapshot(profile: String) {
                    val key = MessageDigest.getInstance("SHA-256").digest("${wav.canonicalPath}\u0000$profile".toByteArray())
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                    val directory = File(root, "cloud-upload-queue/$key").apply { mkdirs() }
                    wav.copyTo(File(directory, "recording.wav"))
                    midi.copyTo(File(directory, "recording.mid"))
                }
                oldSnapshot(ownerA)
                if (mode == "ambiguous") oldSnapshot(ownerB)
                if (mode == "changed") midi.writeText("different MIDI bytes")
                val legacyId = UUID.randomUUID().toString()
                context.getSharedPreferences("recording_cloud_status", Context.MODE_PRIVATE).edit()
                    .putString("path:${wav.absolutePath}", legacyId).putBoolean("synced:$legacyId", true).commit()
                val status = RecordingCloudStatus(context)
                val titles = RecordingTitleSync(context)
                val scheduler = RecordingUploadScheduler(context, status)
                val recording = RecordingInfo("Practice", wav.absolutePath, 1000, 1000, wav.length())
                assertThrows(RecordingOwnerUnverified::class.java) { runBlocking { scheduler.enqueue(recording, ownerB) } }
                if (mode == "intact") {
                    assertEquals(legacyId, status.existingId(wav.absolutePath))
                    assertTrue(status.synced(legacyId))
                    assertEquals(ownerA, titles.binding(legacyId)!!.profileId)
                    scheduler.enqueue(recording, ownerA)
                    assertEquals(ownerA, titles.binding(legacyId)!!.profileId)
                    assertEquals(1, manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + legacyId).get().size)
                } else {
                    assertNull(titles.binding(legacyId))
                    val currentId = status.existingId(wav.absolutePath)!!
                    assertNotEquals(legacyId, currentId)
                    assertTrue(status.unverifiedOwnerOrigin(currentId))
                    assertThrows(RecordingOwnerUnverified::class.java) { runBlocking { scheduler.enqueue(recording, ownerA) } }
                    assertTrue(manager.getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + currentId).get().isEmpty())
                }
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
}
