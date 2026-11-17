// Concern: uploads selected recording bundles | Non-concern: audio capture | IO: (WAV and MIDI) -> local server receipt
package com.pyano.sync

import com.pyano.audio.RecordingBundleFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalSyncClient(private val cache: File) {
    suspend fun upload(wav: File, endpoint: String, token: String) {
        val uri = URI(endpoint.trim().trimEnd('/'))
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "Enter an HTTP(S) server address" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
            "Use a server address without credentials, query, or path"
        }
        require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Enter the device token" }
        val work = File(cache, "sync-${java.util.UUID.randomUUID()}")
        check(work.mkdirs()) { "Could not prepare upload" }
        try {
            val audio = File(work, "audio.m4a")
            val midi = RecordingBundleFiles.midi(wav).takeIf { it.isFile }
            val digest = MessageDigest.getInstance("SHA-256")
            listOfNotNull(wav, midi).forEach { file ->
                file.inputStream().use { source ->
                    val bytes = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(bytes)
                        if (read < 0) break
                        digest.update(bytes, 0, read)
                    }
                }
            }
            val id = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val url = URI(uri.scheme, null, uri.host, uri.port, "/api/recordings/$id", null, null).toURL()
            val existing = url.openConnection() as HttpURLConnection
            try {
                existing.instanceFollowRedirects = false
                existing.connectTimeout = 15000
                existing.readTimeout = 15000
                existing.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                when (existing.responseCode) {
                    200 -> {
                        val receipt = existing.inputStream.bufferedReader().use { it.readText() }
                        check(JSONObject(receipt).getString("id") == id) { "Unexpected server receipt" }
                        return
                    }
                    404 -> Unit
                    else -> error("Server unavailable (HTTP ${existing.responseCode})")
                }
            } finally {
                existing.disconnect()
            }
            CompressedAudio.encode(wav, audio)
            currentCoroutineContext().ensureActive()
            val metadata = JSONObject().put("version", 1).put("title", wav.nameWithoutExtension).toString().toByteArray()
            val archive = File(work, "bundle.zip")
            ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                listOfNotNull(audio, midi).forEach { file ->
                    zip.putNextEntry(ZipEntry(if (file == audio) "audio.m4a" else "performance.mid"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("metadata.json"))
                zip.write(metadata)
                zip.closeEntry()
            }
            require(archive.length() <= 128L * 1024 * 1024) { "Upload exceeds prototype's 128 MiB limit" }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.requestMethod = "PUT"
                connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                connection.setRequestProperty("Content-Type", "application/zip")
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(archive.length())
                connection.outputStream.use { output -> archive.inputStream().use { it.copyTo(output) } }
                currentCoroutineContext().ensureActive()
                if (connection.responseCode != 200) {
                    val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    val message = runCatching { JSONObject(detail.orEmpty()).optString("error") }.getOrNull()
                    error(message?.takeIf { it.isNotBlank() } ?: "Upload failed (HTTP ${connection.responseCode})")
                }
                val receipt = connection.inputStream.bufferedReader().use { it.readText() }
                check(JSONObject(receipt).getString("id") == id) { "Server returned an unexpected recording ID" }
            } finally {
                connection.disconnect()
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
