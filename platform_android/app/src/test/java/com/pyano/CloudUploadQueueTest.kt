// Concern: verifies durable cloud upload snapshots | Non-concern: network transfer | IO: none
package com.pyano

import com.pyano.audio.RecordingBundleFiles
import com.pyano.sync.CloudUploadQueue
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CloudUploadQueueTest {
    @Test
    fun snapshotSurvivesSourceRenameAndDeletionWithMidi() {
        val root = Files.createTempDirectory("pyano-queue-test").toFile()
        try {
            val source = root.resolve("take.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            RecordingBundleFiles.midi(source).writeBytes(byteArrayOf(4, 5))
            val queue = CloudUploadQueue(root.resolve("queue"))
            val snapshot = queue.snapshot(source, "profile-a", "recording-a")
            val duplicate = queue.snapshot(source, "profile-a", "recording-a")
            val renamed = root.resolve("renamed.wav")
            assertTrue(source.renameTo(renamed))
            RecordingBundleFiles.midi(source).delete()
            renamed.delete()

            assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.wav.readBytes())
            assertArrayEquals(byteArrayOf(4, 5), RecordingBundleFiles.midi(snapshot.wav).readBytes())
            assertEquals(snapshot.id, duplicate.id)
            assertTrue(snapshot.created)
            assertTrue(!duplicate.created)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun profileChangesSnapshotAndUniqueWorkIdentity() {
        val root = Files.createTempDirectory("pyano-queue-profile").toFile()
        try {
            val source = root.resolve("take.wav").apply { writeBytes(byteArrayOf(1)) }
            val queue = CloudUploadQueue(root.resolve("queue"))
            val first = queue.snapshot(source, "profile-a", "recording-a")
            val duplicate = queue.snapshot(source, "profile-a", "recording-a")
            val other = queue.snapshot(source, "profile-b", "recording-a")

            assertEquals(first.id, duplicate.id)
            assertTrue(first.id != other.id)
            assertEquals("pyano-cloud-${first.id}", CloudUploadQueue.uniqueWorkName(first.id))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun completedSnapshotSurvivesRestartUntilExplicitCleanup() {
        val root = Files.createTempDirectory("pyano-queue-restart").toFile()
        try {
            val source = root.resolve("take.wav").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val queueRoot = root.resolve("queue")
            val snapshot = CloudUploadQueue(queueRoot).snapshot(source, "profile-a", "recording-a")

            assertArrayEquals(byteArrayOf(7, 8, 9), CloudUploadQueue(queueRoot).snapshot(source, "profile-a", "recording-a").wav.readBytes())
            CloudUploadQueue(queueRoot).remove(snapshot.wav)
            assertTrue(!snapshot.directory.exists())
        } finally {
            root.deleteRecursively()
        }
    }
    @Test fun reusedFilenameNeverReusesAnotherRecordingsSnapshot() {
        val root = Files.createTempDirectory("pyano-queue-identity").toFile()
        try {
            val source = root.resolve("Practice.wav").apply { writeText("first take") }
            val queue = CloudUploadQueue(root.resolve("queue"))
            val old = queue.snapshot(source, "profile-a", "first-recording-id")
            val renamed = root.resolve("Renamed.wav")
            assertTrue(source.renameTo(renamed))
            val retry = queue.snapshot(renamed, "profile-a", "first-recording-id")
            assertEquals(old.id, retry.id)
            source.writeText("different take")
            val fresh = queue.snapshot(source, "profile-a", "second-recording-id")
            assertTrue(old.id != fresh.id)
            assertEquals("first take", old.wav.readText())
            assertEquals("different take", fresh.wav.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun snapshotCannotReadHalfOfBundleWhileRenameOwnsFilesystemLock() {
        val root = Files.createTempDirectory("pyano-queue-rename").toFile()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val source = root.resolve("Practice.wav").apply { writeText("audio") }
            RecordingBundleFiles.midi(source).writeText("midi")
            val queue = CloudUploadQueue(root.resolve("queue"))
            val started = java.util.concurrent.CountDownLatch(1)
            val future: java.util.concurrent.Future<*> = synchronized(RecordingBundleFiles) {
                val task = executor.submit<Any> { started.countDown(); queue.snapshot(source, "profile", "recording-id") }
                assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
                org.junit.Assert.assertThrows(java.util.concurrent.TimeoutException::class.java) {
                    task.get(50, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                RecordingBundleFiles.rename(source, "Renamed")
                task
            }
            val failure = org.junit.Assert.assertThrows(java.util.concurrent.ExecutionException::class.java) {
                future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            assertTrue(failure.cause is IllegalArgumentException)
            assertEquals("audio", root.resolve("Renamed.wav").readText())
            assertEquals("midi", root.resolve("Renamed.mid").readText())
            assertTrue(!root.resolve("queue").exists())
        } finally { executor.shutdownNow(); root.deleteRecursively() }
    }

    @Test fun legacySnapshotProvenanceOnlyMatchesOriginalPathAndKnownProfile() {
        val root = Files.createTempDirectory("pyano-legacy-queue").toFile()
        try {
            val source = root.resolve("Practice.wav").apply { writeText("audio") }
            val queueRoot = root.resolve("queue")
            val queue = CloudUploadQueue(queueRoot)
            queue.snapshot(source, "owner-b", "new-recording-id")
            assertTrue(!queue.hasLegacySnapshot(source, listOf("owner-b")))
            val oldId = java.security.MessageDigest.getInstance("SHA-256")
                .digest("${source.canonicalPath}\u0000owner-a".toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            queueRoot.resolve("$oldId/recording.wav").apply { parentFile.mkdirs(); writeText("older owner-a audio") }
            assertTrue(queue.hasLegacySnapshot(source, listOf("owner-a", "owner-b")))
            assertTrue(!queue.hasLegacySnapshot(source, listOf("owner-b")))
        } finally { root.deleteRecursively() }
    }

}
