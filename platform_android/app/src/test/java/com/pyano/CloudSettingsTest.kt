// Concern: verifies cloud setting validation | Non-concern: credential encryption | IO: none
package com.pyano

import com.pyano.sync.validateCloudSettings
import com.pyano.sync.validateUploadPartSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudSettingsTest {
    @Test
    fun acceptsProvisionedHttpsSettings() {
        validateCloudSettings("https://music.example.com", "owner_01", "pyano_device_token_123456789")
    }

    @Test
    fun rejectsInsecureOrAmbiguousSettings() {
        assertThrows(IllegalArgumentException::class.java) {
            validateCloudSettings("http://music.example.com", "owner_01", "pyano_device_token_123456789")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateCloudSettings("https://music.example.com/path", "owner_01", "pyano_device_token_123456789")
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateCloudSettings("https://music.example.com", "bad owner", "pyano_device_token_123456789")
        }
    }

    @Test
    fun rejectsUnsafeServerDirectedPartBuffers() {
        assertEquals(8 * 1024 * 1024, validateUploadPartSize(8 * 1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) { validateUploadPartSize(64 * 1024 * 1024) }
        assertThrows(IllegalArgumentException::class.java) { validateUploadPartSize(1024) }
    }
}
