// Concern: uploads recording assets to Pyano Cloud | Non-concern: local ZIP sync | IO: (WAV, MIDI, credentials) -> cloud receipt
package com.pyano.sync

import com.pyano.audio.RecordingBundleFiles
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class CloudSyncException(message: String, val retryable: Boolean) : Exception(message)

internal fun validateUploadPartSize(value: Int): Int {
    require(value in 5 * 1024 * 1024..16 * 1024 * 1024) { "Cloud service returned an unsafe upload part size" }
    return value
}

class CloudSyncClient(private val cache: File) {
    suspend fun upload(
        wav: File,
        title: String,
        durationMs: Long,
        capturedAt: String,
        endpoint: String,
        ownerId: String,
        token: String,
    ) {
        val base = validateSettings(endpoint, ownerId, token)
        val work = File(cache, "cloud-${java.util.UUID.randomUUID()}")
        check(work.mkdirs()) { "Could not prepare cloud upload" }
        try {
            val audio = File(work, "audio.m4a")
            CompressedAudio.encode(wav, audio)
            currentCoroutineContext().ensureActive()
            val midi = RecordingBundleFiles.midi(wav).takeIf { it.isFile }
            val recordingId = stableId(wav, midi)
            val assets = listOfNotNull(
                Asset("audio", "audio/mp4", audio, sha256(audio)),
                midi?.let { Asset("midi", "audio/midi", it, sha256(it)) },
            )
            val body = JSONObject()
                .put("contractVersion", 1)
                .put("ownerId", ownerId.trim())
                .put("recordingId", recordingId)
                .put("title", title)
                .put("durationMs", durationMs.coerceAtLeast(1))
                .put("capturedAt", capturedAt)
                .put("audioMidiOffsetMs", 0)
                .put("assets", JSONArray(assets.map { it.json() }))
            val session = postJson(base.resolve("/api/v1/recordings"), token, body, recordingId)
            if (session.optString("state") == "completed") return
            val uploadId = session.getString("uploadId")
            val partSize = validateUploadPartSize(session.getInt("partSizeBytes"))
            val remoteAssets = session.getJSONArray("assets")
            assets.forEach { asset ->
                val remote = (0 until remoteAssets.length()).map { remoteAssets.getJSONObject(it) }
                    .first { it.getString("role") == asset.role }
                val uploaded = remote.getJSONArray("uploadedParts").let { array ->
                    (0 until array.length()).map { array.getInt(it) }.toSet()
                }
                uploadParts(base, token, uploadId, asset, partSize, uploaded)
            }
            postJson(base.resolve("/api/v1/uploads/$uploadId/complete"), token, JSONObject(), null)
        } finally {
            work.deleteRecursively()
        }
    }

    private fun validateSettings(endpoint: String, ownerId: String, token: String): URI {
        validateCloudSettings(endpoint, ownerId, token)
        val uri = URI(endpoint.trim().trimEnd('/'))
        return uri
    }

    private suspend fun stableId(wav: File, midi: File?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOfNotNull(wav, midi).forEach { file -> updateDigest(digest, file) }
        return digest.digest().hex()
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigest(digest, file)
        return digest.digest().hex()
    }

    private suspend fun updateDigest(digest: MessageDigest, file: File) {
        file.inputStream().use { source ->
            val bytes = ByteArray(65_536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = source.read(bytes)
                if (read < 0) break
                digest.update(bytes, 0, read)
            }
        }
    }

    private suspend fun uploadParts(base: URI, token: String, uploadId: String, asset: Asset, partSize: Int, uploaded: Set<Int>) {
        RandomAccessFile(asset.file, "r").use { source ->
            val count = ((asset.file.length() + partSize - 1) / partSize).toInt()
            for (part in 1..count) {
                currentCoroutineContext().ensureActive()
                if (part in uploaded) continue
                val length = minOf(partSize.toLong(), asset.file.length() - (part - 1L) * partSize).toInt()
                val bytes = ByteArray(length)
                source.seek((part - 1L) * partSize)
                source.readFully(bytes)
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
                val url = base.resolve("/api/v1/uploads/$uploadId/assets/${asset.role}/parts/$part").toURL()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    configure(connection, token)
                    connection.requestMethod = "PUT"
                    connection.setRequestProperty("Content-Type", "application/octet-stream")
                    connection.setRequestProperty("X-Part-SHA256", hash)
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(length)
                    connection.outputStream.use { it.write(bytes) }
                    requireSuccess(connection)
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private fun postJson(uri: URI, token: String, body: JSONObject, idempotencyKey: String?): JSONObject {
        val bytes = body.toString().toByteArray()
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            configure(connection, token)
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            if (idempotencyKey != null) connection.setRequestProperty("Idempotency-Key", "${idempotencyKey}_v1")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            requireSuccess(connection)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun configure(connection: HttpURLConnection, token: String) {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Authorization", "Bearer ${token.trim()}")
    }

    private fun requireSuccess(connection: HttpURLConnection) {
        if (connection.responseCode in 200..299) return
        val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val message = runCatching { JSONObject(detail).getJSONObject("error").getString("message") }.getOrNull()
        val status = connection.responseCode
        throw CloudSyncException(message ?: "Cloud upload failed (HTTP $status)", status == 408 || status == 429 || status >= 500)
    }

    private data class Asset(val role: String, val contentType: String, val file: File, val sha256: String) {
        fun json() = JSONObject().put("role", role).put("contentType", contentType)
            .put("sizeBytes", file.length()).put("sha256", sha256)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

}
