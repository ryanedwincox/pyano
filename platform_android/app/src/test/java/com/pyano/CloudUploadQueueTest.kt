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
            val snapshot = queue.snapshot(source, "profile-a")
            val duplicate = queue.snapshot(source, "profile-a")
            val renamed = root.resolve("renamed.wav")
            assertTrue(source.renameTo(renamed))
            RecordingBundleFiles.midi(source).delete()
            renamed.delete()

            assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.wav.readBytes())
            assertArrayEquals(byteArrayOf(4, 5), RecordingBundleFiles.midi(snapshot.wav).readBytes())
            assertEquals(snapshot.id, duplicate.id)
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
            val first = queue.snapshot(source, "profile-a")
            val duplicate = queue.snapshot(source, "profile-a")
            val other = queue.snapshot(source, "profile-b")

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
            val snapshot = CloudUploadQueue(queueRoot).snapshot(source, "profile-a")

            assertArrayEquals(byteArrayOf(7, 8, 9), CloudUploadQueue(queueRoot).snapshot(source, "profile-a").wav.readBytes())
            CloudUploadQueue(queueRoot).remove(snapshot.wav)
            assertTrue(!snapshot.directory.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
