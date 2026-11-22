// Concern: validates private credential imports | Non-concern: camera decoding | IO: (JSON) -> validated credentials
package com.pyano.sync

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.Strictness
import java.io.StringReader
import java.net.InetAddress
import java.net.URI

const val MAX_PROVISIONING_BYTES = 16 * 1024

fun parseCloudProvisioning(json: String): CloudCredentials {
    try {
        require(json.length <= MAX_PROVISIONING_BYTES && json.toByteArray(Charsets.UTF_8).size <= MAX_PROVISIONING_BYTES)
        JsonReader(StringReader(json)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.beginObject()
            val fields = mutableMapOf<String, String>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(name in setOf("version", "endpoint", "ownerId", "token") && name !in fields)
                if (name == "version") {
                    require(reader.peek() == JsonToken.NUMBER && reader.nextString() == "1")
                    fields[name] = "1"
                } else {
                    require(reader.peek() == JsonToken.STRING)
                    fields[name] = reader.nextString()
                }
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && fields.size == 4)
            val result = CloudCredentials(fields.getValue("endpoint"), fields.getValue("ownerId"), fields.getValue("token"))
            validateProvisionedCloudSettings(result.endpoint, result.ownerId, result.token)
            return result
        }
    } catch (_: Exception) {
        throw IllegalArgumentException("Invalid provisioning JSON. Use the version 1 JSON from Cloud device provisioning.")
    }
}

fun validateProvisionedCloudSettings(endpoint: String, ownerId: String, token: String) {
    require(endpoint.length <= 2048 && endpoint.none { it <= ' ' || it == '\u007f' }) { "Use a public HTTPS cloud origin" }
    val uri = runCatching { URI(endpoint) }.getOrNull()
    require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "Use a public HTTPS cloud origin" }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
        "Use a cloud origin without credentials, query, or path"
    }
    require(uri.rawAuthority.matches(Regex("(?:\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9.-]+)(?::[0-9]+)?"))) { "Use a valid HTTPS origin" }
    require(uri.port == -1 || uri.port in 1..65535) { "Use a valid HTTPS port" }
    val host = uri.host.lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
    require(host != "localhost" && !host.endsWith(".localhost") && !host.endsWith(".local")) { "Use a public cloud host" }
    if (host.contains(':') || host.matches(Regex("[0-9.]+"))) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        require(address != null && !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress) { "Use a public cloud host" }
        val bytes = address.address
        require(bytes.size != 16 || (bytes[0].toInt() and 0xfe) != 0xfc) { "Use a public cloud host" }
    }
    require(ownerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "Enter the owner ID from device provisioning" }
    require(token.length in 1..4096 && token.all { it in '!'..'~' }) { "Enter a valid device token" }
}

class ProvisioningSession {
    var pending: CloudCredentials? = null
        private set
    private var generation = 0
    private var scanning: Int? = null

    fun beginScan(): Int = (++generation).also { scanning = it; pending = null }
    fun scanActive(ticket: Int) = scanning == ticket
    fun scanned(ticket: Int, text: String?): Boolean {
        if (!scanActive(ticket)) return false
        scanning = null
        if (text != null) pending = parseCloudProvisioning(text)
        return text != null
    }
    fun pasted(text: String) { cancel(); pending = parseCloudProvisioning(text) }
    fun clearPending() { pending = null }
    fun cancel() { pending = null; scanning = null; generation++ }
}

fun validateCloudSettings(endpoint: String, ownerId: String, token: String) {
    canonicalCloudOrigin(endpoint)
    require(ownerId.trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "Enter the owner ID from device provisioning" }
    require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Enter the device token" }
}
