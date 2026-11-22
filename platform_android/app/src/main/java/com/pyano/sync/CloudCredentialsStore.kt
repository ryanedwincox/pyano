// Concern: verifies owner and encrypts device credentials | Non-concern: token issuance | IO: (authenticated device token) -> encrypted profile
package com.pyano.sync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CredentialStorage {
    fun read(key: String): String?
    fun keys(): Set<String> = emptySet()
    fun commit(values: Map<String, String>): Boolean
}

internal interface CredentialCipher {
    fun encrypt(token: String): Pair<String, String>
    fun decrypt(iv: String, encrypted: String): String
}

class CloudCredentialsStore internal constructor(private val storage: CredentialStorage, private val cipher: CredentialCipher,
    private val verifyOwner: (CloudCredentials) -> Unit) {
    constructor(context: Context) : this(
        PreferenceCredentialStorage(context.getSharedPreferences("pyano_cloud_credentials", Context.MODE_PRIVATE)), KeystoreCredentialCipher(),
        { credentials -> CloudSyncClient(context.cacheDir).verifyDeviceOwner(credentials.endpoint, credentials.ownerId, credentials.token) }
    )
    internal constructor(context: Context, verifyOwner: (CloudCredentials) -> Unit) : this(
        PreferenceCredentialStorage(context.getSharedPreferences("pyano_cloud_credentials", Context.MODE_PRIVATE)), KeystoreCredentialCipher(), verifyOwner
    )

    fun save(endpoint: String, ownerId: String, token: String): String {
        validateCloudSettings(endpoint, ownerId, token)
        val origin = canonicalCloudOrigin(endpoint)
        verifyOwner(CloudCredentials(origin, ownerId.trim(), token.trim()))
        return synchronized(LOCK) {
            val selectedId = currentProfileId()
            val selected = selectedId?.let(::load)
            if (selected != null && canonicalCloudOrigin(selected.endpoint) == origin &&
                selected.ownerId == ownerId.trim() && selected.token == token.trim()) {
                check(storage.commit(mapOf("selectedMode" to "cloud", "selectedEndpoint" to origin,
                    "selectedOwnerId" to selected.ownerId, latestOwnerKey(selected.endpoint, selected.ownerId) to checkNotNull(selectedId)))) { "Could not save cloud settings" }
                return@synchronized selectedId
            }
            val profileId = UUID.randomUUID().toString()
            val (iv, encrypted) = cipher.encrypt(token.trim())
            check(storage.commit(mapOf(
                "$profileId.endpoint" to origin, "$profileId.ownerId" to ownerId.trim(),
                "$profileId.tokenIv" to iv, "$profileId.tokenCiphertext" to encrypted,
                "currentProfileId" to profileId, "selectedMode" to "cloud",
                "selectedEndpoint" to origin, "selectedOwnerId" to ownerId.trim(),
                latestOwnerKey(origin, ownerId.trim()) to profileId
            ))) { "Could not save cloud settings" }
            profileId
        }
    }

    fun selectLocal(endpoint: String, ownerId: String) = synchronized(LOCK) {
        check(storage.commit(mapOf("selectedMode" to "local", "selectedEndpoint" to endpoint, "selectedOwnerId" to ownerId))) {
            "Could not save local settings"
        }
    }

    fun selection(): CloudSelection? = synchronized(LOCK) {
        val mode = storage.read("selectedMode") ?: return@synchronized null
        CloudSelection(mode == "cloud", storage.read("selectedEndpoint").orEmpty(), storage.read("selectedOwnerId").orEmpty())
    }

    fun currentProfileId(): String? = synchronized(LOCK) { storage.read("currentProfileId") }

    fun load(profileId: String): CloudCredentials? = synchronized(LOCK) {
        runCatching {
            val endpoint = storage.read("$profileId.endpoint") ?: return@synchronized null
            val ownerId = storage.read("$profileId.ownerId") ?: return@synchronized null
            val iv = storage.read("$profileId.tokenIv") ?: return@synchronized null
            val encrypted = storage.read("$profileId.tokenCiphertext") ?: return@synchronized null
            CloudCredentials(endpoint, ownerId, cipher.decrypt(iv, encrypted))
        }.getOrNull()
    }

    fun recordingCredentials(boundProfileId: String): CloudCredentials? = synchronized(LOCK) {
        val endpoint = storage.read("$boundProfileId.endpoint") ?: return@synchronized null
        val owner = storage.read("$boundProfileId.ownerId") ?: return@synchronized null
        val latestId = storage.read(latestOwnerKey(endpoint, owner))
            ?: storage.read(latestOwnerKey(endpoint, owner, canonical = false))
        if (latestId != null) {
            return@synchronized load(latestId)?.takeIf { runCatching { canonicalCloudOrigin(it.endpoint) == canonicalCloudOrigin(endpoint) && it.ownerId == owner }.getOrDefault(false) }
        }
        val bound = load(boundProfileId) ?: return@synchronized null
        val current = currentProfileId()?.let(::load)
        if (current != null && sameCloudOwner(bound, current)) current else bound
    }

    private fun latestOwnerKey(endpoint: String, owner: String, canonical: Boolean = true): String {
        val origin = if (canonical) canonicalCloudOrigin(endpoint) else endpoint.trim().trimEnd('/')
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest("$origin\u0000$owner".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "latest-owner:$hash"
    }

    internal fun recordingOwnerScope(profileId: String): String? = synchronized(LOCK) {
        val endpoint = storage.read("$profileId.endpoint") ?: return@synchronized null
        val owner = storage.read("$profileId.ownerId") ?: return@synchronized null
        runCatching { "${canonicalCloudOrigin(endpoint)}\u0000$owner" }.getOrNull()
    }

    internal fun knownProfileIds(): List<String> = synchronized(LOCK) {
        storage.keys().filter { it.endsWith(".ownerId") }.map { it.removeSuffix(".ownerId") }
    }

    companion object { private val LOCK = Any() }
}

internal class PreferenceCredentialStorage(private val prefs: SharedPreferences) : CredentialStorage {
    override fun keys() = prefs.all.keys
    override fun read(key: String): String? = prefs.getString(key, null)
    override fun commit(values: Map<String, String>): Boolean {
        val old = prefs.all.mapValues { it.value as String }
        val editor = prefs.edit()
        values.forEach { (key, value) -> editor.putString(key, value) }
        if (editor.commit()) return true
        val rollback = prefs.edit().clear()
        old.forEach { (key, value) -> rollback.putString(key, value) }
        rollback.commit()
        return false
    }
}

private class KeystoreCredentialCipher : CredentialCipher {
    override fun encrypt(token: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    override fun decrypt(iv: String, encrypted: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "pyano-cloud-device-token"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

class CloudCredentials(val endpoint: String, val ownerId: String, val token: String) {
    override fun toString() = "CloudCredentials(redacted)"
}

data class CloudSelection(val cloud: Boolean, val endpoint: String, val ownerId: String)

internal fun canonicalCloudOrigin(endpoint: String): String {
    val uri = runCatching { java.net.URI(endpoint.trim().trimEnd('/')) }.getOrNull()
    require(uri?.scheme.equals("https", ignoreCase = true) && !uri?.host.isNullOrBlank()) { "Cloud service must use HTTPS" }
    require(uri!!.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
        "Use a cloud address without credentials, query, or path"
    }
    return java.net.URI("https", null, uri.host.lowercase(java.util.Locale.ROOT), if (uri.port == 443) -1 else uri.port, null, null, null).toASCIIString()
}

internal fun sameCloudOwner(first: CloudCredentials, second: CloudCredentials) = runCatching {
    canonicalCloudOrigin(first.endpoint) == canonicalCloudOrigin(second.endpoint) && first.ownerId == second.ownerId
}.getOrDefault(false)
