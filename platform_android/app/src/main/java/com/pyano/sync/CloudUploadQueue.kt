// Concern: snapshots recordings for durable uploads | Non-concern: network transfer | IO: (WAV, MIDI, profile) -> queued files
package com.pyano.sync

import com.pyano.audio.RecordingBundleFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class CloudUploadSnapshot(val id: String, val directory: File, val wav: File, val created: Boolean = false)

internal data class LegacyUploadProof(val profileId: String, val remoteId: String, val ownerScope: String)

class CloudUploadQueue(private val root: File) {
    fun snapshot(source: File, profileId: String, recordingId: String): CloudUploadSnapshot = synchronized(RecordingBundleFiles) {
        require(recordingId.isNotBlank()) { "Recording identity is required" }
        require(source.isFile) { "Recording is no longer available" }
        val id = MessageDigest.getInstance("SHA-256")
            .digest("$recordingId\u0000$profileId".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val destination = File(root, id)
        val wav = File(destination, "recording.wav")
        if (wav.isFile) return@synchronized CloudUploadSnapshot(id, destination, wav)
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
        CloudUploadSnapshot(id, destination, wav, created = true)
    }

    internal fun bundleRemoteId(source: File): String = synchronized(RecordingBundleFiles) {
        require(source.isFile) { "Recording is no longer available" }
        digestFiles(listOfNotNull(source, RecordingBundleFiles.midi(source).takeIf { it.isFile }))
    }

    private fun legacyWav(source: File, profile: String): File {
        val oldId = MessageDigest.getInstance("SHA-256").digest("${source.canonicalPath}\u0000$profile".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(root, "$oldId/recording.wav")
    }
    fun hasLegacySnapshot(source: File, profileIds: List<String>): Boolean = synchronized(RecordingBundleFiles) {
        profileIds.any { legacyWav(source, it).isFile }
    }
    internal fun proveLegacyUpload(source: File, credentials: CloudCredentialsStore): LegacyUploadProof? =
        synchronized(RecordingBundleFiles) {
            runCatching {
                if (!source.isFile) return@runCatching null
                val midi = RecordingBundleFiles.midi(source).takeIf { it.isFile }
                val localFiles = listOfNotNull(source, midi)
                val localProofs = localFiles.associateWith { com.pyano.audio.recordingFileProof(it) }
                val remote = digestFiles(localFiles)
                val proven = mutableListOf<LegacyUploadProof>()
                for (profile in credentials.knownProfileIds()) {
                    val wav = legacyWav(source, profile)
                    if (!wav.exists()) {
                        if (wav.parentFile.exists()) return@runCatching null
                        continue
                    }
                    if (!wav.isFile || credentials.load(profile) == null) return@runCatching null
                    val owner = credentials.recordingOwnerScope(profile) ?: return@runCatching null
                    val queuedMidi = RecordingBundleFiles.midi(wav).takeIf { it.isFile }
                    if ((midi == null) != (queuedMidi == null) || wav.length() != source.length() ||
                        midi != null && midi.length() != queuedMidi!!.length()) continue
                    val queuedFiles = listOfNotNull(wav, queuedMidi)
                    val proofs = queuedFiles.associateWith { com.pyano.audio.recordingFileProof(it) }
                    val queuedRemote = digestFiles(queuedFiles)
                    if (queuedFiles.any { com.pyano.audio.recordingFileProof(it) != proofs[it] }) return@runCatching null
                    if (queuedRemote == remote) proven.add(LegacyUploadProof(profile, remote, owner))
                }
                if (localFiles.any { com.pyano.audio.recordingFileProof(it) != localProofs[it] } ||
                    (midi != null) != RecordingBundleFiles.midi(source).isFile || proven.map { it.ownerScope }.toSet().size != 1) null
                else proven.minByOrNull { it.profileId }
            }.getOrNull()
        }
    private fun digestFiles(files: List<File>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (file in files) file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun remove(snapshot: File) {
        require(snapshot.parentFile?.parentFile == root) { "Invalid upload snapshot" }
        snapshot.parentFile?.deleteRecursively()
    }

    companion object {
        fun uniqueWorkName(snapshotId: String) = "pyano-cloud-$snapshotId"
    }
}
