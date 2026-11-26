// Concern: verifies real request flow with quota and acknowledgement races | Non-concern: live credentials | IO: (fake HTTP, durable store) -> assertions
package com.pyano

import com.google.gson.Gson
import com.pyano.markup.*
import com.pyano.scores.ScoreProfile
import com.pyano.sync.CloudCredentials
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.io.ByteArrayOutputStream

class ScoreInkSyncTest {
    @get:Rule val directory = TemporaryFolder()
    private val hash = "a".repeat(64)
    private val profile = ScoreProfile("test", CloudCredentials("https://example.test", "owner", "pyano_test_000000000000000000000"))
    private val local = InkStroke("ffffffff-ffff-ffff-ffff-ffffffffffff", listOf(listOf(.2f,.3f)))
    private fun connection(uri: URI, status: () -> Int, response: () -> String): HttpURLConnection = object : HttpURLConnection(uri.toURL()) {
        override fun connect() { }
        override fun disconnect() { }
        override fun usingProxy() = false
        override fun getResponseCode() = status()
        override fun getInputStream() = response().byteInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }
    @Test fun fullCloudPageStillDownloadsAndPreservesAnExtraOfflineStroke() = runBlocking<Unit> {
        val store = ScoreInkStore(directory.root, "owner", "score", hash)
        store.edit(0, local)
        val remote = (0 until 500).map { InkStroke("00000000-0000-0000-0000-${it.toString().padStart(12,'0')}", listOf(listOf(.5f,.5f)), pending = false) }
        var reads = 0; var writes = 0
        try {
            syncInk(profile, "score", hash, 0, store) { uri ->
                if (uri.path.endsWith(local.id)) {
                    connection(uri, { writes++; 409 }, { "{}" })
                } else {
                    val cursor = uri.query?.substringAfter("cursor=")
                    val start = if (cursor == null) 0 else remote.indexOfFirst { it.id == cursor } + 1
                    val page = remote.drop(start).take(50)
                    connection(uri, { reads++; 200 }, { Gson().toJson(mapOf("strokes" to page,
                        "nextCursor" to if (start + page.size < 500) page.last().id else null)) })
                }
            }
            fail("Expected the new stroke to remain pending at the Cloud limit")
        } catch (error: InkSyncFailure) { assertEquals(409, error.status) }
        assertEquals(10, reads)
        assertEquals(1, writes)
        assertEquals(501, store.read(0).size)
        assertTrue(store.read(0).first { it.id == local.id }.pending)
        assertEquals(500, store.read(0).count { !it.pending })
    }
    @Test fun uploadAcknowledgementCannotClearUndoAddedWhileTheRequestWasRunning() = runBlocking<Unit> {
        val store = ScoreInkStore(directory.root, "owner", "score", hash)
        store.edit(0, local)
        syncInk(profile, "score", hash, 0, store) { uri ->
            if (uri.path.endsWith(local.id)) connection(uri, {
                store.edit(0, local.copy(deleted = true)); 200
            }, { Gson().toJson(local.copy(pending = false)) })
            else connection(uri, { 200 }, { "{\"strokes\":[],\"nextCursor\":null}" })
        }
        assertTrue(store.read(0).single().deleted)
        assertTrue(store.read(0).single().pending)
    }
    @Test fun malformedCursorCannotApplyADeletionBeforeValidationCompletes() = runBlocking<Unit> {
        val store = ScoreInkStore(directory.root, "owner", "score", hash)
        store.edit(0, local)
        try {
            syncInk(profile, "score", hash, 0, store) { uri -> connection(uri, { 200 }, {
                Gson().toJson(mapOf("strokes" to listOf(local.copy(deleted = true)), "nextCursor" to "malformed"))
            }) }
            fail("Expected malformed pagination to be rejected")
        } catch (_: IllegalArgumentException) { }
        assertFalse(store.read(0).single().deleted)
        assertTrue(store.read(0).single().pending)
    }

}
