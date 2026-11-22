// Concern: verifies title API request and receipts | Non-concern: live cloud mutation | IO: (HTTP fixture) -> assertions
package com.pyano

import com.pyano.sync.CloudSyncClient
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection

class CloudTitleTransportTest {
    private fun identity(owner: String = "owner"): HttpURLConnection = mock(HttpURLConnection::class.java).also {
        `when`(it.responseCode).thenReturn(200)
        `when`(it.inputStream).thenReturn(ByteArrayInputStream("""{"deviceId":"device_fixture","ownerId":"$owner","scopes":["recordings:write"]}""".toByteArray()))
    }
    private fun verified(connection: HttpURLConnection) = CloudSyncClient(File(".")) { uri ->
        if (uri.path == "/api/v1/device") identity() else connection
    }

    @Test fun patchesTitleOnlyWithBearerAndChecksReceiptWithoutSendingOwnerInUrl() {
        val id = "a".repeat(64)
        val output = ByteArrayOutputStream()
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(output)
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(ByteArrayInputStream("""{"recordingId":"$id","title":"Renamed"}""".toByteArray()))
        val client = CloudSyncClient(File(".")) { uri ->
            if (uri.path == "/api/v1/device") identity() else {
                assertEquals("https://music.example.com/api/v1/recordings/$id", uri.toString())
                connection
            }
        }
        assertTrue(client.rename("https://music.example.com", "owner", "private-test-token", id, "Renamed"))
        verify(connection).setRequestMethod("PATCH")
        verify(connection).setRequestProperty("Authorization", "Bearer private-test-token")
        verify(connection).setInstanceFollowRedirects(false)
        assertEquals("""{"title":"Renamed"}""", output.toString())
        verify(connection).disconnect()
    }

    @Test fun revokedTitleCredentialsGiveReconfigurationGuidance() {
        for (status in listOf(401, 403)) {
            val connection = mock(HttpURLConnection::class.java)
            `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
            `when`(connection.responseCode).thenReturn(status)
            val client = verified(connection)
            val error = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
                client.rename("https://music.example.com", "owner", "revoked-token", "a".repeat(64), "Renamed")
            }
            assertTrue(error.message!!.contains("Reconfigure Cloud"))
            assertFalse(error.retryable)
            verify(connection).disconnect()
        }
    }

    @Test fun numericTitleReceiptCannotAcknowledgeStringTitle() {
        val id = "a".repeat(64)
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(ByteArrayInputStream("""{"recordingId":"$id","title":123}""".toByteArray()))
        val client = verified(connection)
        assertThrows(IllegalStateException::class.java) { client.rename("https://music.example.com", "owner", "test-token", id, "123") }
        verify(connection).disconnect()
    }

    @Test fun oversizedReceiptIsRejectedAndDisconnected() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(200)
        `when`(connection.inputStream).thenReturn(ByteArrayInputStream(ByteArray(9000) { 32 }))
        val client = verified(connection)
        assertThrows(IllegalStateException::class.java) {
            client.rename("https://music.example.com", "owner", "test-token", "a".repeat(64), "Changed")
        }
        verify(connection).disconnect()
    }

    @Test fun missingRecordingDoesNotClaimSuccessfulRename() {
        val connection = mock(HttpURLConnection::class.java)
        `when`(connection.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(connection.responseCode).thenReturn(404)
        val client = verified(connection)
        assertFalse(client.rename("https://music.example.com", "owner", "test-token", "a".repeat(64), "Changed"))
        verify(connection).disconnect()
    }
    @Test fun wrongTokenOwnerCannotPatchTitleOrUseUploadCompletionShortcut() = kotlinx.coroutines.runBlocking {
        val requests = mutableListOf<String>()
        val client = CloudSyncClient(File(".")) { uri ->
            requests.add(uri.path)
            assertEquals("/api/v1/device", uri.path)
            identity("actual-owner-b")
        }
        val title = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            client.rename("https://music.example.com", "claimed-owner-a", "owner-b-token", "a".repeat(64), "Never send")
        }
        assertFalse(title.retryable)
        assertTrue(title.message!!.contains("different Cloud owner"))
        assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            kotlinx.coroutines.runBlocking {
                client.upload(File("unopened.wav"), "Never send", 1000, "2026-11-21T00:00:00Z",
                    "https://music.example.com", "claimed-owner-a", "owner-b-token")
            }
        }
        assertEquals(listOf("/api/v1/device", "/api/v1/device"), requests)
    }

    @Test fun missingIdentityEndpointFailsClosedWithUpdateGuidance() {
        val response = mock(HttpURLConnection::class.java)
        `when`(response.responseCode).thenReturn(404)
        val client = CloudSyncClient(File(".")) { response }
        val error = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            client.verifyDeviceOwner("https://music.example.com", "owner", "token")
        }
        assertFalse(error.retryable)
        assertTrue(error.message!!.contains("Update the Cloud service"))
        verify(response).disconnect()
    }

    @Test fun hostileHttpErrorBodyNeverReachesIdentityTitleOrUploadFailureText() = kotlinx.coroutines.runBlocking {
        val secret = "synthetic-private-bearer-token-123456"
        val reads = java.util.concurrent.atomic.AtomicInteger()
        fun failure(): HttpURLConnection = mock(HttpURLConnection::class.java).also {
            `when`(it.responseCode).thenReturn(500)
            `when`(it.outputStream).thenReturn(ByteArrayOutputStream())
            `when`(it.errorStream).thenAnswer {
                reads.incrementAndGet()
                ByteArrayInputStream("""{"error":{"message":"Authorization: Bearer $secret"}}""".toByteArray())
            }
        }
        val identityClient = CloudSyncClient(File(".")) { failure() }
        val identity = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            identityClient.verifyDeviceOwner("https://music.example.com", "owner", secret)
        }
        val title = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            verified(failure()).rename("https://music.example.com", "owner", secret, "a".repeat(64), "Changed")
        }
        val upload = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            kotlinx.coroutines.runBlocking {
                identityClient.upload(File("unopened.wav"), "Never send", 1000, "2026-11-21T00:00:00Z",
                    "https://music.example.com", "owner", secret)
            }
        }
        for (error in listOf(identity, title, upload)) {
            assertTrue(error.retryable)
            assertFalse(checkNotNull(error.message).contains(secret))
            assertFalse(com.pyano.sync.boundedUploadError(error.message!!).contains(secret))
            assertTrue(error.message!!.contains("temporarily unavailable"))
        }
        assertEquals(0, reads.get())
    }

    @Test fun malformedSuccessTitleReceiptCannotEchoServerPayloadIntoFailure() {
        val secret = "synthetic-private-bearer-token-123456"
        val response = mock(HttpURLConnection::class.java)
        `when`(response.responseCode).thenReturn(200)
        `when`(response.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(response.inputStream).thenReturn(ByteArrayInputStream("\"Bearer $secret\"".toByteArray()))
        val error = assertThrows(com.pyano.sync.CloudSyncException::class.java) {
            verified(response).rename("https://music.example.com", "owner", secret, "a".repeat(64), "Changed")
        }
        assertFalse(error.retryable)
        assertFalse(error.message!!.contains(secret))
        assertTrue(error.message!!.contains("could not be verified"))
    }

}
