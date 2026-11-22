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

internal fun validateUploadPartSize(value: Any?): Int {
    val number = (value as? Number)?.takeUnless { it is Double || it is Float }?.toString()?.toBigDecimalOrNull()
    require(number != null && number >= (5 * 1024 * 1024).toBigDecimal() && number <= (16 * 1024 * 1024).toBigDecimal() &&
        number.stripTrailingZeros().scale() <= 0) { "Cloud service returned an unsafe upload part size" }
    return number.toInt()
}

class CloudSyncClient(
    private val cache: File,
    private val openTitleConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) {
    suspend fun upload(
        wav: File,
        title: String,
        durationMs: Long,
        capturedAt: String,
        endpoint: String,
        ownerId: String,
        token: String,
    ) {
        try {
            val base = validateSettings(endpoint, ownerId, token)
            verifyDeviceOwner(endpoint, ownerId, token)
            val midi = RecordingBundleFiles.midi(wav).takeIf { it.isFile }
            val recordingId = stableId(wav, midi)
            if (isComplete(base, token, recordingId)) return
            val work = File(cache, "cloud-${java.util.UUID.randomUUID()}")
            check(work.mkdirs()) { "Could not prepare cloud upload" }
            try {
                val audio = File(wav.parentFile, "upload-audio.m4a")
                persistUploadAudio(audio) { CompressedAudio.encode(wav, it) }
                currentCoroutineContext().ensureActive()
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
                if (session.optString("state") in setOf("complete", "completed")) return
                val uploadId = session.getString("uploadId")
                val partSize = validateUploadPartSize(session.get("partSizeBytes"))
                val remoteAssets = session.getJSONArray("assets")
                assets.forEach { asset ->
                    val remote = (0 until remoteAssets.length()).map { remoteAssets.getJSONObject(it) }
                        .first { it.getString("role") == asset.role }
                    if (remote.optBoolean("uploaded")) return@forEach
                    val uploaded = remote.getJSONArray("uploadedParts").let { array ->
                        (0 until array.length()).map { array.getInt(it) }.toSet()
                    }
                    uploadParts(base, token, uploadId, asset, partSize, uploaded)
                }
                postJson(base.resolve("/api/v1/uploads/$uploadId/complete"), token, JSONObject(), null)
            } finally {
                work.deleteRecursively()
            }
        } catch (_: org.json.JSONException) {
            throw CloudSyncException("Cloud returned an invalid upload response. Keep the local recording and update Cloud before retrying.", false)
        } catch (_: java.io.IOException) {
            throw CloudSyncException("The Cloud upload connection failed. Check the connection and retry; local files were kept.", true)
        }
    }

    fun rename(endpoint: String, ownerId: String, token: String, recordingId: String, title: String): Boolean {
        require(title.isNotBlank() && title.length <= 200) { "Recording names must contain 1–200 characters" }
        val base = validateSettings(endpoint, ownerId, token)
        require(recordingId.matches(Regex("[a-f0-9]{64}")))
        verifyDeviceOwner(endpoint, ownerId, token)
        val connection = openTitleConnection(base.resolve("/api/v1/recordings/$recordingId"))
        try {
            configure(connection, token)
            connection.requestMethod = "PATCH"
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = com.google.gson.JsonObject().apply { addProperty("title", title) }.toString().toByteArray()
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            if (connection.responseCode == 404) return false
            if (connection.responseCode in setOf(401, 403)) throw CloudSyncException(
                "Cloud title update needs valid device credentials with recording access. Reconfigure Cloud in Server settings, then retry the rename.", false)
            requireSuccess(connection)
            val response = connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(bytes.size() + count <= 8192) { "Cloud title receipt is too large" }
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
            val receipt = runCatching { com.google.gson.JsonParser.parseString(response).asJsonObject }.getOrNull()
                ?: throw CloudSyncException("Cloud title receipt could not be verified. Update Cloud before retrying.", false)
            val receiptId = receipt.get("recordingId")
            val receiptTitle = receipt.get("title")
            check(receiptId?.isJsonPrimitive == true && receiptId.asJsonPrimitive.isString &&
                receiptTitle?.isJsonPrimitive == true && receiptTitle.asJsonPrimitive.isString &&
                receiptId.asString == recordingId && receiptTitle.asString == title) {
                "Unexpected Cloud title receipt"
            }
            return true
        } catch (_: java.io.IOException) {
            throw CloudSyncException("The Cloud title-update connection failed. Check the connection and retry the rename.", true)
        } finally { connection.disconnect() }
    }

    fun verifyDeviceOwner(endpoint: String, ownerId: String, token: String) {
        val base = validateSettings(endpoint, ownerId, token)
        val connection = try { openTitleConnection(base.resolve("/api/v1/device")) }
        catch (_: java.io.IOException) { throw CloudSyncException("Connect to Cloud to verify device ownership, then retry Server settings.", true) }
        try {
            configure(connection, token)
            connection.requestMethod = "GET"
            when (connection.responseCode) {
                401, 403 -> throw CloudSyncException("Reconfigure Cloud with a valid device token that can write recordings.", false)
                404 -> throw CloudSyncException("Update the Cloud service before verifying this device, then retry Server settings.", false)
            }
            requireSuccess(connection)
            val body = connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 8192) throw CloudSyncException("Cloud device identity was too large to verify.", false)
                    bytes.write(buffer, 0, count)
                }
                bytes.toString(Charsets.UTF_8.name())
            }
            val identity = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: throw CloudSyncException("Cloud device identity could not be verified. Update Cloud and retry.", false)
            val owner = identity.get("ownerId")
            val device = identity.get("deviceId")
            if (owner?.isJsonPrimitive != true || !owner.asJsonPrimitive.isString || device?.isJsonPrimitive != true ||
                !device.asJsonPrimitive.isString || !device.asString.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) {
                throw CloudSyncException("Cloud device identity could not be verified. Update Cloud and retry.", false)
            }
            if (owner.asString != ownerId.trim()) throw CloudSyncException(
                "The device token belongs to a different Cloud owner. Scan the correct owner's QR code or enter its matching owner ID and token.", false)
        } catch (error: CloudSyncException) { throw error }
        catch (_: java.io.IOException) { throw CloudSyncException("Connect to Cloud to verify device ownership, then retry Server settings.", true) }
        finally { connection.disconnect() }
    }

    private fun isComplete(base: URI, token: String, recordingId: String): Boolean {
        val connection = base.resolve("/api/v1/recordings/$recordingId").toURL().openConnection() as HttpURLConnection
        try {
            configure(connection, token)
            if (connection.responseCode == 404) return false
            requireSuccess(connection)
            val receipt = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            check(receipt.getString("recordingId") == recordingId) { "Unexpected cloud recording receipt" }
            return receipt.getString("state") == "complete"
        } finally {
            connection.disconnect()
        }
    }

    private fun validateSettings(endpoint: String, ownerId: String, token: String): URI {
        validateCloudSettings(endpoint, ownerId, token)
        return URI(canonicalCloudOrigin(endpoint))
    }

    internal suspend fun stableId(wav: File, midi: File?): String {
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
        val status = connection.responseCode
        if (status in 200..299) return
        val message = when (status) {
            400 -> "Cloud rejected the request. Check the recording and update Cloud before retrying."
            401, 403 -> "Cloud access was denied. Reconfigure the correct owner's device token and retry."
            404 -> "The Cloud endpoint or recording is unavailable. Update Cloud before retrying."
            408 -> "The Cloud request timed out. Check the connection and retry."
            409 -> "Cloud has conflicting data for this recording. Keep the local original and review it before retrying."
            413 -> "The recording is too large for Cloud. Keep or export the local original."
            415, 422 -> "Cloud could not validate the recording's format, size, or checksum. Keep the local original and review it."
            429 -> "Cloud is receiving too many requests. Wait and retry."
            in 500..599 -> "The Cloud service is temporarily unavailable. Wait and retry; local recordings were kept."
            else -> "Cloud request failed (HTTP $status). Check the service and retry."
        }
        throw CloudSyncException(message, status == 408 || status == 429 || status >= 500)
    }

    private data class Asset(val role: String, val contentType: String, val file: File, val sha256: String) {
        fun json() = JSONObject().put("role", role).put("contentType", contentType)
            .put("sizeBytes", file.length()).put("sha256", sha256)
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

}
