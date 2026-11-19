// Concern: snapshots recordings for durable uploads | Non-concern: network transfer | IO: (WAV, MIDI, profile) -> queued files
package com.pyano.sync

import com.pyano.audio.RecordingBundleFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class CloudUploadSnapshot(val id: String, val directory: File, val wav: File)

class CloudUploadQueue(private val root: File) {
    fun snapshot(source: File, profileId: String): CloudUploadSnapshot {
        require(source.isFile) { "Recording is no longer available" }
        val id = MessageDigest.getInstance("SHA-256")
            .digest("${source.canonicalPath}\u0000$profileId".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val destination = File(root, id)
        val wav = File(destination, "recording.wav")
        if (wav.isFile) return CloudUploadSnapshot(id, destination, wav)
        check(root.mkdirs() || root.isDirectory) { "Could not prepare upload queue" }
        val staging = File(root, ".$id-${java.util.UUID.randomUUID()}.part")
        check(staging.mkdir()) { "Could not prepare recording snapshot" }
        try {
            source.copyTo(File(staging, "recording.wav"), overwrite = false)
            RecordingBundleFiles.midi(source).takeIf { it.isFile }?.copyTo(File(staging, "recording.mid"), overwrite = false)
            if (!staging.renameTo(destination)) throw IOException("Could not publish recording snapshot")
        } finally {
            staging.deleteRecursively()
        }
        return CloudUploadSnapshot(id, destination, wav)
    }

    fun remove(snapshot: File) {
        require(snapshot.parentFile?.parentFile == root) { "Invalid upload snapshot" }
        snapshot.parentFile?.deleteRecursively()
    }

    companion object {
        fun uniqueWorkName(snapshotId: String) = "pyano-cloud-$snapshotId"
    }
}
