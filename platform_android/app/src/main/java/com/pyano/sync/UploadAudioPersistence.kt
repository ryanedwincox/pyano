// Concern: preserves encoded bytes across retries | Non-concern: audio encoding | IO: (destination, encoder) -> persistent audio
package com.pyano.sync

import java.io.File

internal suspend fun persistUploadAudio(destination: File, encode: suspend (File) -> Unit) {
    if (destination.isFile) return
    val staging = File(destination.parentFile, destination.name + ".part")
    try {
        encode(staging)
        check(staging.isFile && staging.length() > 0) { "No compressed upload audio was produced" }
        check(staging.renameTo(destination)) { "Could not persist compressed upload audio" }
    } finally {
        staging.delete()
    }
}

internal fun boundedUploadError(message: String): String = message.take(1000)
