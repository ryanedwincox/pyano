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
    @Test fun partSizeRejectsWrappedLongAndNonIntegerValuesBeforeNarrowing() {
        assertEquals(5 * 1024 * 1024, validateUploadPartSize(5242880L))
        assertEquals(16 * 1024 * 1024, validateUploadPartSize(16777216L))
        assertEquals(5 * 1024 * 1024, validateUploadPartSize(java.math.BigDecimal("5242880.0")))
        val invalid = listOf<Any?>(4300210176L, Long.MAX_VALUE, Long.MIN_VALUE, 5242880.5, 5242880.0, java.math.BigDecimal("5242880.0000000001"), Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, "5242880", true, null)
        for (value in invalid) assertThrows(IllegalArgumentException::class.java) { validateUploadPartSize(value) }
    }

}
