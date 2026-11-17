// Concern: manages paired recording files | Non-concern: audio capture | IO: (recording paths) -> file operations
package com.pyano.audio

import java.io.File
import java.io.IOException
import java.util.UUID

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

    fun rename(wav: File, name: String): File {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.none { it in "/\\<>:\"|?*" })
        val destination = File(wav.parentFile, "$name.wav")
        if (wav == destination) return wav
        check(!destination.exists() && !midi(destination).exists()) { "That name already exists" }
        val sourceMidi = midi(wav)
        val hasMidi = sourceMidi.exists()
        if (hasMidi && !sourceMidi.renameTo(midi(destination))) throw IOException("Could not rename MIDI")
        if (!wav.renameTo(destination)) {
            if (hasMidi && !midi(destination).renameTo(sourceMidi)) {
                throw IOException("WAV rename failed; MIDI remains at ${midi(destination).name}")
            }
            throw IOException("Could not rename WAV")
        }
        return destination
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
