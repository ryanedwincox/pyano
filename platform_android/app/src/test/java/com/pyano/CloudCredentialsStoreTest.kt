// Concern: verifies atomic encrypted credential selection | Non-concern: Android Keystore internals | IO: (storage failures) -> assertions
package com.pyano

import com.pyano.sync.*
import android.content.SharedPreferences
import org.mockito.Mockito.*
import org.mockito.ArgumentMatchers.*
import org.junit.Assert.*
import org.junit.Test

class CloudCredentialsStoreTest {
    private class MemoryStorage : CredentialStorage {
        val values = mutableMapOf<String, String>()
        var fail = false
        override fun read(key: String) = values[key]
        override fun keys() = values.keys
        override fun commit(values: Map<String, String>): Boolean {
            if (fail) return false
            this.values.putAll(values)
            return true
        }
    }

    private class TestCipher : CredentialCipher {
        private val encrypted = mutableMapOf<String, String>()
        var fail = false
        override fun encrypt(token: String): Pair<String, String> {
            check(!fail) { "test-only secret that must never become a UI error" }
            val ciphertext = "encrypted-${encrypted.size}"
            encrypted[ciphertext] = token
            return "test-iv" to ciphertext
        }
        override fun decrypt(iv: String, encrypted: String) = this.encrypted.getValue(encrypted)
    }

    @Test fun selectionAndCiphertextCommitTogetherAndOldQueuedProfileStillLoads() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher) {}
        val queuedProfile = store.save("https://first.example.com", "owner_01", "first-test-token")
        val nextProfile = store.save("https://second.example.com/", "owner_02", "second-test-token")
        assertNotEquals(queuedProfile, nextProfile)
        assertEquals(nextProfile, store.currentProfileId())
        assertEquals(CloudSelection(true, "https://second.example.com", "owner_02"), store.selection())
        assertFalse(storage.values.values.contains("first-test-token"))
        assertFalse(storage.values.values.contains("second-test-token"))
        val restarted = CloudCredentialsStore(storage, cipher) {}
        assertEquals("first-test-token", restarted.load(queuedProfile)?.token)
        assertEquals("second-test-token", restarted.load(nextProfile)?.token)
        assertEquals(store.selection(), restarted.selection())
        restarted.selectLocal("http://127.0.0.1:8765", "")
        assertEquals(false, restarted.selection()?.cloud)
        assertEquals("first-test-token", restarted.load(queuedProfile)?.token)
    }

    @Test fun unchangedSaveRetainsProfileAndOfflineCacheNamespace() {
        val store = CloudCredentialsStore(MemoryStorage(), TestCipher()) {}
        val profile = store.save("https://music.example.com", "owner_01", "same-token")
        assertEquals(profile, store.save("https://music.example.com/", " owner_01 ", " same-token "))
        assertEquals(profile, store.currentProfileId())
        assertNotEquals(profile, store.save("https://music.example.com", "owner_01", "rotated-token"))
    }

    @Test fun recordingTitleCredentialsRotateOnlyInsideSameOwner() {
        val store = CloudCredentialsStore(MemoryStorage(), TestCipher()) {}
        val original = store.save("https://first.example.com", "owner_01", "old-token")
        val rotated = store.save("https://first.example.com/", "owner_01", "new-token")
        assertEquals("new-token", store.recordingCredentials(original)?.token)
        assertEquals(rotated, store.currentProfileId())
        store.save("https://first.example.com", "owner_02", "other-owner-token")
        assertEquals("new-token", store.recordingCredentials(original)?.token)
        val otherOrigin = store.save("https://other.example.com", "owner_01", "other-origin-token")
        assertEquals("new-token", store.recordingCredentials(original)?.token)
        assertEquals("other-origin-token", store.recordingCredentials(otherOrigin)?.token)
    }

    @Test fun validationEncryptionAndCommitFailuresPreserveSelectedProfile() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher) {}
        store.save("https://first.example.com", "owner_01", "first-test-token")
        val before = storage.values.toMap()
        assertThrows(IllegalArgumentException::class.java) { store.save("http://bad.example.com", "owner_02", "new-test-token") }
        assertEquals(before, storage.values)
        cipher.fail = true
        assertThrows(IllegalStateException::class.java) { store.save("https://next.example.com", "owner_02", "new-test-token") }
        assertEquals(before, storage.values)
        cipher.fail = false
        storage.fail = true
        assertThrows(IllegalStateException::class.java) { store.save("https://next.example.com", "owner_02", "new-test-token") }
        assertEquals(before, storage.values)
        assertThrows(IllegalStateException::class.java) { store.selectLocal("http://127.0.0.1:8765", "") }
        assertEquals(before, storage.values)
    }

    @Test fun manualWhitespaceAndPrivateHttpsRemainCompatibleWithQueuedProfiles() {
        val store = CloudCredentialsStore(MemoryStorage(), TestCipher()) {}
        val profile = store.save(" https://192.168.1.20/ ", " owner_01 ", " test-token ")
        val loaded = requireNotNull(store.load(profile))
        assertEquals("https://192.168.1.20", loaded.endpoint)
        assertEquals("owner_01", loaded.ownerId)
        assertEquals("test-token", loaded.token)
        validateCloudSettings(loaded.endpoint, loaded.ownerId, loaded.token)
        assertThrows(IllegalArgumentException::class.java) {
            validateProvisionedCloudSettings(loaded.endpoint, loaded.ownerId, loaded.token)
        }
    }

    @Test fun failedPreferenceCommitRollsBackAlreadyMutatedMemoryAndSelection() {
        val values = mutableMapOf<String, String>()
        var failNext = false
        var failedAfterMutation = false
        val prefs = mock(SharedPreferences::class.java)
        `when`(prefs.all).thenAnswer { values.toMap() }
        `when`(prefs.getString(anyString(), isNull())).thenAnswer { values[it.getArgument<String>(0)] }
        `when`(prefs.edit()).thenAnswer {
            val editor = mock(SharedPreferences.Editor::class.java)
            val changes = mutableMapOf<String, String>()
            var clear = false
            `when`(editor.putString(anyString(), anyString())).thenAnswer {
                changes[it.getArgument(0)] = it.getArgument(1)
                editor
            }
            `when`(editor.clear()).thenAnswer { clear = true; editor }
            `when`(editor.commit()).thenAnswer {
                if (clear) values.clear()
                values.putAll(changes)
                if (failNext) {
                    failedAfterMutation = values["selectedEndpoint"] == "https://second.example.com"
                    failNext = false
                    false
                } else true
            }
            editor
        }
        val cipher = TestCipher()
        val store = CloudCredentialsStore(PreferenceCredentialStorage(prefs), cipher) {}
        val oldProfile = store.save("https://first.example.com", "owner_01", "first-test-token")
        val before = values.toMap()
        failNext = true
        assertThrows(IllegalStateException::class.java) {
            store.save("https://second.example.com", "owner_02", "second-test-token")
        }
        assertTrue(failedAfterMutation)
        assertEquals(before, values)
        assertEquals(oldProfile, store.currentProfileId())
        assertEquals("first-test-token", store.load(oldProfile)?.token)
        assertEquals(CloudSelection(true, "https://first.example.com", "owner_01"), store.selection())
    }

    @Test fun canonicalOriginsPreserveLegacyProfilesAndRotateAcrossExplicitHttpsPort() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher) {}
        val original = store.save("https://music.example.com", "owner_a", "original-token")
        storage.values["$original.endpoint"] = "https://MUSIC.example.com:443/"
        storage.values["selectedEndpoint"] = "https://MUSIC.example.com:443/"
        val oldCache = com.pyano.scores.ScoreProfile(original, store.load(original)!!).namespace
        assertEquals(original, store.save("HTTPS://music.example.com/", "owner_a", "original-token"))
        assertEquals(oldCache, com.pyano.scores.ScoreProfile(original, store.load(original)!!).namespace)
        val rotated = store.save("https://music.example.com:443", "owner_a", "rotated-token")
        assertEquals(store.recordingOwnerScope(original), store.recordingOwnerScope(rotated))
        store.save("https://music.example.com", "owner_b", "other-owner")
        val restarted = CloudCredentialsStore(storage, cipher) {}
        assertEquals("rotated-token", restarted.recordingCredentials(original)!!.token)
        val alternate = restarted.save("https://music.example.com:8443", "owner_a", "alternate-port")
        assertEquals("rotated-token", restarted.recordingCredentials(original)!!.token)
        assertEquals("alternate-port", restarted.recordingCredentials(alternate)!!.token)
        assertFalse(sameCloudOwner(restarted.load(original)!!, restarted.load(alternate)!!))
    }

    @Test fun legacyRawOriginOwnerPointerStillResolvesRotatedCredentials() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher) {}
        val original = store.save("https://music.example.com", "owner_a", "old-token")
        val rotated = store.save("https://music.example.com", "owner_a", "rotated-token")
        val rawOrigin = "https://MUSIC.example.com:443"
        storage.values["$original.endpoint"] = rawOrigin
        storage.values["$rotated.endpoint"] = rawOrigin
        storage.values.keys.filter { it.startsWith("latest-owner:") }.forEach { storage.values.remove(it) }
        val rawKey = "latest-owner:" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("$rawOrigin\u0000owner_a".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        storage.values[rawKey] = rotated
        val other = store.save("https://music.example.com", "owner_b", "other-owner-token")
        val restarted = CloudCredentialsStore(storage, cipher) {}
        assertEquals("rotated-token", restarted.recordingCredentials(original)!!.token)
        storage.values[rawKey] = other
        assertNull(restarted.recordingCredentials(original))
    }

    @Test fun latestPerOwnerCredentialSurvivesAccountSwitchAndRestartWithoutCrossOriginFallback() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher) {}
        val originalA = store.save("https://first.example.com", "owner_a", "old-a")
        val rotatedA = store.save("https://first.example.com/", "owner_a", "rotated-a")
        val profileB = store.save("https://first.example.com", "owner_b", "token-b")
        val restarted = CloudCredentialsStore(storage, cipher) {}
        assertEquals(profileB, restarted.currentProfileId())
        assertEquals("rotated-a", restarted.recordingCredentials(originalA)?.token)
        assertEquals("rotated-a", restarted.recordingCredentials(rotatedA)?.token)
        assertEquals("token-b", restarted.recordingCredentials(profileB)?.token)
        val otherOriginA = restarted.save("https://other.example.com", "owner_a", "other-origin-a")
        assertEquals("rotated-a", restarted.recordingCredentials(originalA)?.token)
        assertEquals("other-origin-a", restarted.recordingCredentials(otherOriginA)?.token)
        val pointerA = storage.values.entries.single { it.key.startsWith("latest-owner:") && it.value == rotatedA }.key
        storage.values[pointerA] = profileB
        assertNull(restarted.recordingCredentials(originalA))
    }

    @Test fun legacyMissingOwnerPointerUsesOnlyMatchingCurrentOrOriginalBoundProfile() {
        val storage = MemoryStorage()
        val store = CloudCredentialsStore(storage, TestCipher()) {}
        val original = store.save("https://first.example.com", "owner_a", "original-a")
        store.save("https://first.example.com", "owner_a", "current-a")
        storage.values.keys.filter { it.startsWith("latest-owner:") }.forEach { storage.values.remove(it) }
        assertEquals("current-a", store.recordingCredentials(original)?.token)
        store.save("https://first.example.com", "owner_b", "current-b")
        assertEquals("original-a", store.recordingCredentials(original)?.token)
    }

    @Test fun failedAuthenticatedOwnerProofCannotChangeStoredCredentialsOrRotationPointer() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher) { credentials ->
            val response = mock(java.net.HttpURLConnection::class.java)
            val actualOwner = if (credentials.token == "token-b") "owner_b" else "owner_a"
            `when`(response.responseCode).thenReturn(200)
            `when`(response.inputStream).thenReturn(java.io.ByteArrayInputStream(
                """{"deviceId":"device_fixture","ownerId":"$actualOwner","scopes":["recordings:write"]}""".toByteArray()))
            CloudSyncClient(java.io.File(".")) { response }.verifyDeviceOwner(credentials.endpoint, credentials.ownerId, credentials.token)
        }
        val original = store.save("https://music.example.com", "owner_a", "token-a")
        val before = storage.values.toMap()
        assertThrows(CloudSyncException::class.java) { store.save("https://music.example.com", "owner_a", "token-b") }
        assertEquals(before, storage.values)
        assertEquals(original, store.currentProfileId())
        assertEquals("token-a", store.recordingCredentials(original)!!.token)
    }

}
