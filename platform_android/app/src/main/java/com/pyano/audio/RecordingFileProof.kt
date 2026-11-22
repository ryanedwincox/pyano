// Concern: proves recording content ownership | Non-concern: UI identity | IO: (recording file) -> stable digest proof
package com.pyano.audio

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

fun recordingFileProof(file: File): String {
    val before = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(before.isRegularFile) { "Recording file is unavailable" }
    val identity = recordingFileIdentity(file)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val after = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    check(after.isRegularFile && before.fileKey() == after.fileKey() && before.size() == after.size() &&
        before.lastModifiedTime() == after.lastModifiedTime() && identity == recordingFileIdentity(file)) {
        "Recording changed while verifying its saved content"
    }
    return identity + ":sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

internal fun isRecordingFileProof(value: String?): Boolean = value?.substringAfterLast(":sha256:", "")
    ?.let { it.length == 64 && it.all { char -> char in '0'..'9' || char in 'a'..'f' } } == true

internal fun recordingProofDigest(proof: String?): String? = proof?.takeIf(::isRecordingFileProof)?.substringAfterLast(":sha256:")
