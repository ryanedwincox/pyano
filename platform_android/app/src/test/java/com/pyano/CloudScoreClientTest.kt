// Concern: verifies bounded authenticated score transport | Non-concern: live cloud availability | IO: (HTTP fixtures) -> assertions
package com.pyano

import com.pyano.scores.*
import com.pyano.sync.CloudCredentials
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CloudScoreClientTest {
    private val profile = ScoreProfile("profile_test", CloudCredentials("https://music.example.com", "owner", "private-fixture-token"))
    private val entry = """{"scoreId":"s_1","title":"Sonata","version":1,"sizeBytes":9,"sha256":"${"a".repeat(64)}"}"""
    private fun page(entries: String = entry, cursor: String = "null") = """{"scores":[$entries],"nextCursor":$cursor}"""
    private class Response(
        val bytes: ByteArray,
        override val status: Int = 200,
        override val contentType: String = "application/json",
        val stream: InputStream = ByteArrayInputStream(bytes),
    ) : ScoreResponse {
        var closed = false
        override fun body() = stream
        override fun close() { closed = true; stream.close() }
    }

    @Test fun readsAllPagesAndEncodesCursorWithoutSendingOwnerOrTokenInUrl() = runBlocking {
        val paths = mutableListOf<String>()
        val responses = mutableListOf<Response>()
        val client = CloudScoreClient { selected, path ->
            assertSame(profile, selected)
            paths.add(path)
            Response(if (paths.size == 1) page(cursor = "\"x/y+z\"").toByteArray() else page(entry.replace("s_1", "s_2")).toByteArray())
                .also { responses.add(it) }
        }
        assertEquals(listOf("s_1", "s_2"), client.list(profile).map { it.scoreId })
        assertEquals("/api/v1/scores?limit=100&cursor=x%2Fy%2Bz", paths.last())
        assertTrue(paths.none { it.contains("owner") || it.contains(profile.credentials.token) })
        assertTrue(responses.all { it.closed })
    }

    @Test fun rejectsMalformedTypesTraversalLimitsAndDuplicateIdentities() = runBlocking {
        val invalid = listOf(
            "{}", "[]", page(entry.replace("s_1", "../bad")), page(entry.replace("9,", "0,")),
            page(entry.replace("9,", "26214401,")), page(entry.replace("9,", "\"9\",")),
            page(entry.replace(":1,", ":0,")), page(entry.replace("Sonata", "t".repeat(201))),
            page(entry.replace("a".repeat(64), "z".repeat(64))), page("$entry,$entry"),
            page((1..101).joinToString(",") { entry.replace("s_1", "s_$it") }), "x".repeat(1024 * 1024 + 1)
        )
        for (json in invalid) {
            val client = CloudScoreClient { _, _ -> Response(json.toByteArray()) }
            try { client.list(profile); fail("invalid metadata accepted") } catch (_: ScoreFailure) { }
        }
    }

    @Test fun rejectsRepeatedPaginationCursorsAndDoesNotReturnPartialList() = runBlocking {
        var calls = 0
        val client = CloudScoreClient { _, _ ->
            calls++
            Response(page(entry.replace("s_1", "s_$calls"), "\"same\"").toByteArray())
        }
        try { client.list(profile); fail("cursor loop accepted") } catch (_: ScoreFailure) { }
        assertEquals(2, calls)
    }

    @Test fun scopeFailuresRedirectsAndWrongTypesAreActionableWithoutResponseBodies() = runBlocking {
        for (status in listOf(401, 403, 404, 302, 500)) {
            val response = Response("private-fixture-token remote diagnostics".toByteArray(), status)
            try { CloudScoreClient { _, _ -> response }.list(profile); fail("HTTP failure accepted") }
            catch (e: ScoreFailure) {
                assertFalse(e.message!!.contains("private-fixture-token"))
                assertFalse(e.message!!.contains("remote diagnostics"))
                if (status in listOf(401, 403)) assertTrue(e.message!!.contains("scores:read"))
            }
            assertTrue(response.closed)
        }
        try {
            CloudScoreClient { _, _ -> Response(page().toByteArray(), contentType = "text/html") }.list(profile)
            fail("wrong type accepted")
        } catch (_: ScoreFailure) { }
    }

    @Test fun downloadsUseFixedPathAndEnforceStreamingLimit() = runBlocking {
        val metadata = Score("s_1", "Title", "1", 5, "a".repeat(64))
        var path = ""
        val client = CloudScoreClient { _, request ->
            path = request
            Response("%PDF-oversize".toByteArray(), contentType = "application/pdf")
        }
        try { client.download(profile, metadata, ByteArrayOutputStream()); fail("oversize accepted") } catch (_: ScoreFailure) { }
        assertEquals("/api/v1/scores/s_1/download", path)
    }

    @Test fun cancellationClosesBlockedResponseAndUnblocksReader() = runBlocking {
        val reading = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val input = object : InputStream() {
            override fun read(): Int { reading.countDown(); check(closed.await(5, TimeUnit.SECONDS)); return -1 }
            override fun close() { closed.countDown() }
        }
        val response = Response(byteArrayOf(), stream = input)
        val work = launch(Dispatchers.Default) { CloudScoreClient { _, _ -> response }.list(profile) }
        assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
        withTimeout(2000) { work.cancelAndJoin() }
        assertEquals(0, closed.count.toInt())
        assertTrue(response.closed)
    }
    @Test fun malformedUtf8CannotBecomeReplacementCharactersInPersistedMetadata() = runBlocking {
        val text = page()
        val index = text.indexOf("Sonata")
        val malformed = text.substring(0, index).toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + text.substring(index + 6).toByteArray()
        try { CloudScoreClient { _, _ -> Response(malformed) }.list(profile); fail("invalid UTF-8 accepted") }
        catch (e: ScoreFailure) { assertTrue(e.message!!.contains("Invalid score metadata")) }
        val unicode = page(entry.replace("Sonata", "夜曲"))
        assertEquals("夜曲", CloudScoreClient { _, _ -> Response(unicode.toByteArray()) }.list(profile).single().title)
    }

}
