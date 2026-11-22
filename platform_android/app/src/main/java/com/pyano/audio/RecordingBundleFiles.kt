// Concern: manages paired recording files | Non-concern: audio capture | IO: (recording paths) -> file operations
package com.pyano.audio

import java.io.File
import java.io.IOException
import java.util.UUID
import java.nio.file.Files

object RecordingBundleFiles {
    fun midi(wav: File) = File(wav.parentFile, wav.nameWithoutExtension + ".mid")

    fun saveMidi(wav: File, write: (File) -> Unit) {
        val destination = midi(wav)
        check(!destination.exists()) { "MIDI sidecar already exists" }
        val staging = File.createTempFile(".pyano-midi-", ".part", wav.parentFile)
        try {
            write(staging)
            if (!staging.renameTo(destination)) throw IOException("Could not publish MIDI sidecar")
        } finally {
            staging.delete()
        }
    }

    @Synchronized fun rename(wav: File, name: String, before: (File) -> Unit = {}): File {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it in "/\\<>:\"|?*" })
        val destination = File(wav.parentFile, "$name.wav")
        if (wav == destination) { before(destination); return wav }
        check(!destination.exists() && !midi(destination).exists()) { "That name already exists" }
        check(wav.isFile) { "Recording is no longer available" }
        before(destination)
        val sourceMidi = midi(wav)
        val hasMidi = sourceMidi.exists()
        if (hasMidi) Files.move(sourceMidi.toPath(), midi(destination).toPath())
        try { Files.move(wav.toPath(), destination.toPath()) }
        catch (error: IOException) {
            if (hasMidi) try { Files.move(midi(destination).toPath(), sourceMidi.toPath()) }
            catch (rollback: IOException) { throw IOException("WAV rename failed; MIDI remains at ${midi(destination).name}", rollback) }
            throw IOException("Could not rename WAV", error)
        }
        return destination
    }

    @Synchronized fun recoverRename(source: File, destination: File, hadMidi: Boolean, wavIdentity: String, midiIdentity: String?) {
        check(source == destination || !(source.exists() && destination.exists())) { "Rename recovery found conflicting recording files" }
        val wav = source.takeIf { it.isFile } ?: destination.takeIf { it.isFile }
        check(wav != null && recordingFileProof(wav) == wavIdentity) { "Rename recovery WAV ownership could not be verified" }
        val oldMidi = midi(source)
        val newMidi = midi(destination)
        check(source == destination || !(oldMidi.exists() && newMidi.exists())) { "Rename recovery found conflicting MIDI files" }
        if (hadMidi) {
            val midi = oldMidi.takeIf { it.isFile } ?: newMidi.takeIf { it.isFile }
            check(midi != null && midiIdentity != null && recordingFileProof(midi) == midiIdentity) {
                "Rename recovery MIDI ownership could not be verified"
            }
        } else check(!oldMidi.exists() && !newMidi.exists()) { "Rename recovery found unexpected MIDI files" }
        if (source == destination) return
        if (hadMidi && oldMidi.exists()) Files.move(oldMidi.toPath(), newMidi.toPath())
        if (source.exists()) Files.move(source.toPath(), destination.toPath())
    }

    fun delete(wav: File) {
        val holding = File(wav.parentFile, ".deleted-${UUID.randomUUID()}")
        check(holding.mkdir()) { "Could not prepare deletion" }
        val sourceMidi = midi(wav)
        val heldMidi = File(holding, sourceMidi.name)
        if (sourceMidi.exists() && !sourceMidi.renameTo(heldMidi)) {
            holding.delete()
            throw IOException("Could not move MIDI for deletion")
        }
        if (!wav.renameTo(File(holding, wav.name))) {
            if (heldMidi.exists() && !heldMidi.renameTo(sourceMidi)) {
                throw IOException("Deletion failed; MIDI can be recovered from ${holding.name}")
            }
            holding.delete()
            throw IOException("Could not move WAV for deletion")
        }
        if (!holding.deleteRecursively()) throw IOException("Some deleted files remain in ${holding.name}")
    }
}
