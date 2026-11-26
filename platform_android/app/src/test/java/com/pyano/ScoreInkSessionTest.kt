// Concern: verifies local autosave ownership across page changes | Non-concern: Android rendering | IO: (page navigation, pending edits) -> assertions
package com.pyano

import android.content.Context
import com.pyano.markup.*
import com.pyano.scores.*
import com.pyano.sync.CloudCredentials
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*

class ScoreInkSessionTest {
    @get:Rule val directory = TemporaryFolder()
    @Test fun changingPagesDuringAutosaveCannotAcceptAnOverlappingEdit() = runBlocking<Unit> {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.noBackupFilesDir).thenReturn(directory.root)
        val profile = ScoreProfile("test", CloudCredentials("https://example.test", "owner", "pyano_fake_000000000000000000000"))
        val score = Score("score", "Score", "1", 10, "a".repeat(64))
        val session = ScoreInkSession(context, profile, score, this,
            synchronize = { _, _ -> awaitCancellation() }, schedule = {}) { error("Unused") }
        val first = launch { session.watch(0) }
        var second: Job? = null
        try {
            withTimeout(5000) { while (!session.state.value.loaded) delay(5) }
            session.add(0, listOf(listOf(.1f, .2f)))
            assertTrue(session.state.value.saving)
            second = launch(start = CoroutineStart.UNDISPATCHED) { session.watch(1) }
            assertTrue(session.state.value.saving)
            session.add(1, listOf(listOf(.5f, .5f)))
            withTimeout(5000) { while (session.state.value.saving) delay(5) }
            val store = inkStore(context, profile, score.scoreId, score.sha256)
            assertEquals(1, store.read(0).size)
            assertTrue(store.read(1).isEmpty())
        } finally { first.cancelAndJoin(); second?.cancelAndJoin() }
    }
}
