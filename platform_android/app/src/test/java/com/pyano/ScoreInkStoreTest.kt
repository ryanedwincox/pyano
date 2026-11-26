// Concern: verifies durable offline strokes and conflict-free undo | Non-concern: Android rendering | IO: (private files, sync snapshots) -> assertions
package com.pyano

import com.pyano.markup.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScoreInkStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val first = InkStroke("00000000-0000-0000-0000-000000000001", listOf(listOf(.1f, .2f), listOf(.3f, .4f)))
    private fun store(owner: String = "owner", hash: String = "a".repeat(64)) = ScoreInkStore(directory.root, owner, "score", hash)
    @Test fun persistsAcrossReopenAndIsolatesOwnersPagesAndPdfContents() {
        store().edit(0, first)
        assertEquals(listOf(first), store().read(0))
        assertTrue(store().read(1).isEmpty())
        assertTrue(store("other").read(0).isEmpty())
        assertTrue(store(hash = "b".repeat(64)).read(0).isEmpty())
        assertFalse(directory.root.walk().any { it.extension == "part" })
    }
    @Test fun mergesDevicesWithoutLosingLocalEditsAndAcknowledgesOnlyTheSentRevision() {
        val store = store()
        store.edit(0, first)
        val second = first.copy(id = first.id.replaceLast(), pending = false)
        store.merge(0, listOf(second))
        assertEquals(2, store.read(0).size)
        assertTrue(store.read(0).first { it.id == first.id }.pending)
        store.edit(0, first.copy(deleted = true))
        store.merge(0, listOf(first.copy(pending = false)), first)
        assertTrue(store.read(0).first { it.id == first.id }.deleted)
        assertTrue(store.read(0).first { it.id == first.id }.pending)
        store.merge(0, listOf(first.copy(deleted = true, pending = false)))
        assertFalse(store.read(0).first { it.id == first.id }.pending)
        store.merge(0, listOf(first.copy(pending = false)))
        assertTrue(store.read(0).first { it.id == first.id }.deleted)
    }
    @Test fun undoBeforeSaveOrLateAcknowledgementCannotResurrectInk() {
        val store = store()
        store.edit(0, first.copy(deleted = true))
        store.edit(0, first)
        assertTrue(store.read(0).single().deleted)
        store.merge(0, listOf(first.copy(pending = false)), first)
        assertTrue(store.read(0).single().deleted)
    }
    @Test fun invalidEditsAndCorruptFilesAreNeverSilentlyOverwritten() {
        val store = store()
        store.edit(0, first)
        assertThrows(IllegalArgumentException::class.java) { store.edit(0, first.copy(points = listOf(listOf(Float.NaN, 0f)))) }
        assertEquals(listOf(first), store.read(0))
        val saved = directory.root.walk().first { it.name == "0.json" }
        saved.writeText("broken")
        assertThrows(Exception::class.java) { store.edit(0, first) }
        assertEquals("broken", saved.readText())
    }
    @Test fun markedExportNamesFitUtf8FilesystemLimitsWithoutSplittingCharacters() {
        val name = markedScoreFilename("曲".repeat(100))
        assertTrue(name.toByteArray(Charsets.UTF_8).size < 240)
        assertTrue(name.endsWith(" marked.pdf"))
        assertFalse(name.contains('\uFFFD'))
        assertEquals("Score marked.pdf", markedScoreFilename("..."))
    }
    private fun String.replaceLast() = dropLast(1) + "2"
}
