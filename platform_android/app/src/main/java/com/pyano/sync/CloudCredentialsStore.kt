// Concern: encrypts cloud device credentials at rest | Non-concern: token authentication | IO: (device token) -> encrypted preferences
package com.pyano.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.net.URI
import java.util.UUID

class CloudCredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pyano_cloud_credentials", Context.MODE_PRIVATE)

    fun save(endpoint: String, ownerId: String, token: String): String {
        validateCloudSettings(endpoint, ownerId, token)
        val profileId = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("$profileId.endpoint", endpoint.trim().trimEnd('/'))
            .putString("$profileId.ownerId", ownerId.trim())
            .putString("$profileId.tokenIv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("$profileId.tokenCiphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("currentProfileId", profileId)
            .apply()
        return profileId
    }

    fun currentProfileId(): String? = prefs.getString("currentProfileId", null)

    fun load(profileId: String): CloudCredentials? {
        val endpoint = prefs.getString("$profileId.endpoint", null) ?: return null
        val ownerId = prefs.getString("$profileId.ownerId", null) ?: return null
        val iv = prefs.getString("$profileId.tokenIv", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val encrypted = prefs.getString("$profileId.tokenCiphertext", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            CloudCredentials(endpoint, ownerId, String(cipher.doFinal(encrypted), Charsets.UTF_8))
        }.getOrNull()
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

fun validateCloudSettings(endpoint: String, ownerId: String, token: String) {
    val uri = runCatching { URI(endpoint.trim().trimEnd('/')) }.getOrNull()
    require(uri?.scheme == "https" && !uri.host.isNullOrBlank()) { "Cloud service must use HTTPS" }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in setOf("", "/")) {
        "Use a cloud address without credentials, query, or path"
    }
    require(ownerId.trim().matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))) { "Enter the owner ID from device provisioning" }
    require(token.isNotBlank() && token.none { it == '\r' || it == '\n' }) { "Enter the device token" }
}

data class CloudCredentials(val endpoint: String, val ownerId: String, val token: String)
