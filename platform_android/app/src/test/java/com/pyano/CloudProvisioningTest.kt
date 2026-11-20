// Concern: verifies provisioning validation and cancellation | Non-concern: camera hardware | IO: (JSON fixtures) -> assertions
package com.pyano

import com.pyano.sync.*
import org.junit.Assert.*
import org.junit.Test

class CloudProvisioningTest {
    private val valid = """{"version":1,"endpoint":"https://music.example.com","ownerId":"owner_01","token":"test-only-bearer"}"""

    @Test fun acceptsExactV1AndRedactsCredentials() {
        val result = parseCloudProvisioning(valid)
        assertEquals("https://music.example.com", result.endpoint)
        assertEquals("owner_01", result.ownerId)
        assertEquals("test-only-bearer", result.token)
        assertFalse(result.toString().contains(result.token))
    }

    @Test fun rejectsShapeCoercionDuplicatesTrailingAndLargeInputWithoutEchoingSecret() {
        val invalid = listOf(
            "", "[]", valid + "{}", valid.replace("1,", "2,"), valid.replace("1,", "1.0,"), valid.replace("1,", "\"1\","),
            valid.replace("1,", "true,"), valid.replace("1,", "1,\"version\":1,"), valid.replace("1,", "1,\"extra\":true,"),
            valid.replace("\"owner_01\"", "null"), valid.replace("\"owner_01\"", "[]"), valid.dropLast(1) + ",}",
            " ".repeat(MAX_PROVISIONING_BYTES) + valid, valid.replace("test-only-bearer", "é".repeat(9000))
        )
        invalid.forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { parseCloudProvisioning(it) }
            assertEquals("Invalid provisioning JSON. Use the version 1 JSON from Cloud device provisioning.", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun rejectsUnsafeEndpointsIdentifiersAndTokens() {
        val endpoints = listOf(
            "http://music.example.com", "https://music.example.com/path", "https://user:pass@music.example.com",
            "https://music.example.com?token=secret", "https://music.example.com#secret", "https://localhost", "https://LOCALHOST.",
            "https://127.0.0.1", "https://127.1", "https://10.1.1.1", "https://169.254.1.1", "https://0.0.0.0",
            "https://[::1]", "https://[fc00::1]", "https://[fe80::1]", "https://music.example.com:", "https://music.example.com:0", "https://music.example.com:65536"
        )
        endpoints.forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) { validateProvisionedCloudSettings(endpoint, "owner", "bearer") }
        }
        listOf("", "bad owner", "a".repeat(129)).forEach { owner ->
            assertThrows(IllegalArgumentException::class.java) { validateProvisionedCloudSettings("https://music.example.com", owner, "bearer") }
        }
        listOf("", " ", "line\nbreak", "tab\t", "é", "t".repeat(4097)).forEach { token ->
            assertThrows(IllegalArgumentException::class.java) { validateProvisionedCloudSettings("https://music.example.com", "owner", token) }
        }
    }

    @Test fun importRequiresConfirmationAndDropsCanceledOrLateScans() {
        val session = ProvisioningSession()
        val first = session.beginScan()
        session.cancel()
        assertFalse(session.scanned(first, valid))
        assertNull(session.pending)
        val second = session.beginScan()
        assertTrue(session.scanned(second, valid))
        assertEquals("owner_01", session.pending?.ownerId)
        session.clearPending()
        assertNull(session.pending)
        session.pasted(valid)
        assertNotNull(session.pending)
        session.cancel()
        assertNull(session.pending)
    }

    @Test fun malformedPasteClearsPreviouslyPendingImport() {
        val session = ProvisioningSession()
        session.pasted(valid)
        assertThrows(IllegalArgumentException::class.java) { session.pasted("bad") }
        assertNull(session.pending)
    }
}
