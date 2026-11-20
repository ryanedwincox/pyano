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
        val store = CloudCredentialsStore(storage, cipher)
        val queuedProfile = store.save("https://first.example.com", "owner_01", "first-test-token")
        val nextProfile = store.save("https://second.example.com/", "owner_02", "second-test-token")
        assertNotEquals(queuedProfile, nextProfile)
        assertEquals(nextProfile, store.currentProfileId())
        assertEquals(CloudSelection(true, "https://second.example.com", "owner_02"), store.selection())
        assertFalse(storage.values.values.contains("first-test-token"))
        assertFalse(storage.values.values.contains("second-test-token"))
        val restarted = CloudCredentialsStore(storage, cipher)
        assertEquals("first-test-token", restarted.load(queuedProfile)?.token)
        assertEquals("second-test-token", restarted.load(nextProfile)?.token)
        assertEquals(store.selection(), restarted.selection())
        restarted.selectLocal("http://127.0.0.1:8765", "")
        assertEquals(false, restarted.selection()?.cloud)
        assertEquals("first-test-token", restarted.load(queuedProfile)?.token)
    }

    @Test fun validationEncryptionAndCommitFailuresPreserveSelectedProfile() {
        val storage = MemoryStorage()
        val cipher = TestCipher()
        val store = CloudCredentialsStore(storage, cipher)
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
        val store = CloudCredentialsStore(MemoryStorage(), TestCipher())
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
        val store = CloudCredentialsStore(PreferenceCredentialStorage(prefs), cipher)
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

}
