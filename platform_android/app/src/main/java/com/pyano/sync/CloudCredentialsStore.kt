// Concern: encrypts cloud device credentials at rest | Non-concern: token authentication | IO: (device token) -> encrypted preferences
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
    fun commit(values: Map<String, String>): Boolean
}

internal interface CredentialCipher {
    fun encrypt(token: String): Pair<String, String>
    fun decrypt(iv: String, encrypted: String): String
}

class CloudCredentialsStore internal constructor(private val storage: CredentialStorage, private val cipher: CredentialCipher) {
    constructor(context: Context) : this(
        PreferenceCredentialStorage(context.getSharedPreferences("pyano_cloud_credentials", Context.MODE_PRIVATE)), KeystoreCredentialCipher()
    )

    fun save(endpoint: String, ownerId: String, token: String): String = synchronized(LOCK) {
        validateCloudSettings(endpoint, ownerId, token)
        val profileId = UUID.randomUUID().toString()
        val (iv, encrypted) = cipher.encrypt(token.trim())
        check(storage.commit(mapOf(
            "$profileId.endpoint" to endpoint.trim().trimEnd('/'), "$profileId.ownerId" to ownerId.trim(),
            "$profileId.tokenIv" to iv, "$profileId.tokenCiphertext" to encrypted,
            "currentProfileId" to profileId, "selectedMode" to "cloud",
            "selectedEndpoint" to endpoint.trim().trimEnd('/'), "selectedOwnerId" to ownerId.trim()
        ))) { "Could not save cloud settings" }
        profileId
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

    companion object { private val LOCK = Any() }
}

internal class PreferenceCredentialStorage(private val prefs: SharedPreferences) : CredentialStorage {
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
