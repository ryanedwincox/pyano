// Concern: recovers interrupted paired take publication | Non-concern: upload scheduling | IO: (private journal and staged files) -> saved bundle
package com.pyano.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

typealias PublicationIntentRelocation = (String, String, String, String?) -> Unit

class RecordingTakePublication(
    private val journals: File,
    private val recordings: File,
    private val removeFile: (File) -> Boolean = { it.delete() },
    private val beforeAbort: () -> Unit = {},
) {
    private data class Part(val staged: File, val destination: File, val identity: String)

    fun beginDraft(wav: File, midi: File?, destination: File, pcmBytes: Long, sampleRate: Int, midiBytes: ByteArray?): File =
        synchronized(RecordingBundleFiles) {
            check(journals.mkdirs() || journals.isDirectory) { "Could not prepare take recovery" }
            check(pcmBytes > 0 && pcmBytes % 4 == 0L)
            val values = Properties()
            fun put(role: String, source: File, target: File) {
                check(source.parentFile.canonicalFile == recordings.canonicalFile && target.parentFile.canonicalFile == recordings.canonicalFile)
                values.setProperty("$role.source", source.name)
                values.setProperty("$role.target", target.name)
                values.setProperty("$role.created", fileKey(source))
            }
            put("wav", wav, destination)
            values.setProperty("wav.dataBytes", (pcmBytes / 2).toString())
            values.setProperty("wav.sampleRate", sampleRate.toString())
            if (midi != null) {
                put("midi", midi, RecordingBundleFiles.midi(destination))
                values.setProperty("midi.sha256", hex(checkNotNull(midiBytes)))
            }
            File(journals, "${UUID.randomUUID()}.draft").also { writeJournal(values, it, false) }
        }

    private fun fileKey(file: File) = checkNotNull(Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()).toString()
    private fun hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun draftPart(values: Properties, role: String): Pair<File, File>? {
        val source = values.getProperty("$role.source") ?: return null
        val target = checkNotNull(values.getProperty("$role.target"))
        check(source == File(source).name && source.startsWith(".pyano-take-") && source.endsWith(".part"))
        check(target == File(target).name && target.endsWith(if (role == "wav") ".wav" else ".mid"))
        return File(recordings, source) to File(recordings, target)
    }

    fun armDraft(draft: File): File = synchronized(RecordingBundleFiles) {
        val values = readJournal(draft)
        check(values.getProperty("abort") != "true") { "Discarded take cannot be published" }
        val (wav, destination) = checkNotNull(draftPart(values, "wav"))
        check(fileKey(wav) == values.getProperty("wav.created")) { "Staged take ownership changed" }
        val header = readWavHeader(wav)
        check(header.dataSize == values.getProperty("wav.dataBytes").toLong() && header.dataOffset + header.dataSize == wav.length() &&
            header.sampleRate == values.getProperty("wav.sampleRate").toInt() && header.channels == 2 && header.bitsPerSample == 16) {
            "Interrupted take encoding is incomplete; its staged files were preserved"
        }
        val midi = draftPart(values, "midi")?.first
        if (midi != null) check(fileKey(midi) == values.getProperty("midi.created") &&
            recordingProofDigest(recordingFileProof(midi)) == values.getProperty("midi.sha256")) {
            "Interrupted MIDI encoding is incomplete; its staged files were preserved"
        }
        prepare(wav, midi, destination, File(journals, draft.nameWithoutExtension + ".journal"))
    }

    fun prepare(wav: File, midi: File?, destination: File, journal: File = File(journals, "${UUID.randomUUID()}.journal")): File = synchronized(RecordingBundleFiles) {
        check(journals.mkdirs() || journals.isDirectory) { "Could not prepare take recovery" }
        val values = Properties()
        fun put(role: String, source: File, target: File) {
            check(source.parentFile.canonicalFile == recordings.canonicalFile && target.parentFile.canonicalFile == recordings.canonicalFile)
            values.setProperty("$role.source", source.name)
            values.setProperty("$role.target", target.name)
            values.setProperty("$role.identity", recordingFileProof(source))
        }
        put("wav", wav, destination)
        if (midi != null) put("midi", midi, RecordingBundleFiles.midi(destination))
        val target = targetManifest(journal)
        writeJournal(Properties().apply {
            setProperty("wav.target", destination.name)
            setProperty("wav.identity", values.getProperty("wav.identity"))
        }, target, false)
        try { writeJournal(values, journal, false) }
        catch (error: Exception) { target.delete(); throw error }
        journal
    }

    private fun targetManifest(journal: File) = File(journals, journal.nameWithoutExtension + ".target")

    private fun targetFallback(journal: File, ownedOnly: Boolean = false): List<String> {
        val sources = listOf(targetManifest(journal), journal)
        for (source in sources) {
            val values = runCatching {
                check(source.length() <= 4096)
                readJournal(source)
            }.getOrNull() ?: continue
            val name = values.getProperty("wav.target")
            if (name != null && name == File(name).name && name.endsWith(".wav")) {
                val file = File(recordings, name)
                if (!ownedOnly || values.getProperty("wav.identity")?.let { matches(file, it) } == true) return listOf(file.absolutePath)
            }
        }
        return emptyList()
    }

    private fun clearJournal(journal: File) {
        val draft = File(journals, journal.nameWithoutExtension + ".draft")
        check(!draft.exists() || draft.delete()) { "Could not finish take recovery" }
        check(journal.delete()) { "Could not finish take recovery" }
        targetManifest(journal).delete()
    }

    private fun readJournal(journal: File): Properties {
        check(journal.parentFile.canonicalFile == journals.canonicalFile)
        return Properties().apply { journal.inputStream().use(::load) }
    }

    private fun writeJournal(values: Properties, journal: File, replace: Boolean) {
        val staging = File.createTempFile(".publication-", ".part", journals)
        try {
            FileOutputStream(staging).use { output -> values.store(output, null); output.fd.sync() }
            if (replace) Files.move(staging.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else Files.move(staging.toPath(), journal.toPath())
        } finally { staging.delete() }
    }

    private fun parts(journal: File): List<Part> {
        val values = readJournal(journal)
        fun part(role: String): Part? {
            val source = values.getProperty("$role.source") ?: return null
            val target = checkNotNull(values.getProperty("$role.target"))
            check(source == File(source).name && source.startsWith(".pyano-take-") && source.endsWith(".part"))
            check(target == File(target).name && target.endsWith(if (role == "wav") ".wav" else ".mid"))
            return Part(File(recordings, source), File(recordings, target), checkNotNull(values.getProperty("$role.identity")))
        }
        return listOfNotNull(part("midi"), checkNotNull(part("wav")))
    }

    private fun matches(file: File, identity: String) = runCatching { recordingFileProof(file) == identity }.getOrDefault(false)

    fun publish(journal: File, afterMidi: () -> Unit = {}): Unit = synchronized(RecordingBundleFiles) {
        val state = readJournal(journal)
        if (state.getProperty("abort") == "true") {
            rollback(journal)
            return@synchronized
        }
        check(state.getProperty("retarget.to") == null || state.getProperty("retarget.ready") == "true") {
            "Take recovery needs confirmation of its original Cloud upload intent"
        }
        for (part in parts(journal)) {
            if (part.destination.exists()) {
                if (!matches(part.destination, part.identity)) throw java.nio.file.FileAlreadyExistsException(part.destination.path)
            } else {
                check(matches(part.staged, part.identity)) { "Take recovery could not verify its staged file" }
                Files.move(part.staged.toPath(), part.destination.toPath())
            }
            if (part.destination.extension == "mid") afterMidi()
        }
        clearJournal(journal)
    }

    fun rollback(journal: File): Unit = synchronized(RecordingBundleFiles) {
        if (journal.extension == "draft") {
            val armed = File(journals, journal.nameWithoutExtension + ".journal")
            if (armed.exists()) { rollback(armed); return@synchronized }
            val values = readJournal(journal)
            beforeAbort()
            values.setProperty("abort", "true")
            writeJournal(values, journal, true)
            for (role in listOf("midi", "wav")) draftPart(values, role)?.first?.let { staged ->
                val owned = runCatching { fileKey(staged) == values.getProperty("$role.created") }.getOrDefault(false)
                check(!owned || removeFile(staged)) { "Could not remove incomplete take; retry Save or Discard" }
            }
            check(journal.delete()) { "Could not finish discarded take recovery" }
            return@synchronized
        }
        val values = readJournal(journal)
        if (values.getProperty("abort") != "true") {
            beforeAbort()
            values.setProperty("abort", "true")
            writeJournal(values, journal, true)
        }
        for (part in parts(journal)) for (file in listOf(part.destination, part.staged)) {
            check(!matches(file, part.identity) || removeFile(file)) { "Could not remove incomplete take; retry Save or Discard" }
        }
        clearJournal(journal)
    }

    private fun retarget(journal: File, relocateIntent: PublicationIntentRelocation) = synchronized(RecordingBundleFiles) {
        val values = readJournal(journal)
        check(values.getProperty("abort") != "true") { "An aborted take cannot be recovered as a saved recording" }
        val original = parts(journal)
        for (part in original) {
            if (part.staged.exists()) check(matches(part.staged, part.identity)) { "Staged recovery files changed" }
            else {
                check(matches(part.destination, part.identity)) { "The original take file is unavailable for recovery" }
                Files.move(part.destination.toPath(), part.staged.toPath())
            }
        }
        val audio = original.single { it.destination.extension == "wav" }
        val midi = original.firstOrNull { it.destination.extension == "mid" }
        val continuing = values.getProperty("retarget.to") != null && values.getProperty("retarget.ready") != "true"
        val from = if (continuing) File(recordings, values.getProperty("retarget.from")) else audio.destination
        val destination = if (continuing) File(recordings, values.getProperty("retarget.to"))
            else RecordingNames.destination(recordings, RecordingNames.suggestion(audio.destination.nameWithoutExtension))
        check(from.parentFile.canonicalFile == recordings.canonicalFile && destination.parentFile.canonicalFile == recordings.canonicalFile)
        if (!continuing) {
            values.setProperty("retarget.from", from.name)
            values.setProperty("retarget.to", destination.name)
            values.setProperty("retarget.ready", "false")
            writeJournal(values, journal, true)
        }
        relocateIntent(from.absolutePath, destination.absolutePath, audio.identity, midi?.identity)
        writeJournal(Properties().apply {
            setProperty("wav.target", destination.name)
            setProperty("wav.identity", audio.identity)
        }, targetManifest(journal), true)
        values.setProperty("wav.target", destination.name)
        if (midi != null) values.setProperty("midi.target", RecordingBundleFiles.midi(destination).name)
        values.setProperty("retarget.ready", "true")
        writeJournal(values, journal, true)
        publish(journal)
    }

    fun unresolvedTargets(ownedOnly: Boolean = true): Set<String> = synchronized(RecordingBundleFiles) {
        journals.listFiles()?.filter { it.extension in setOf("journal", "draft") }?.flatMap { journal ->
            if (journal.extension == "draft") return@flatMap if (ownedOnly) emptyList() else targetFallback(journal)
            try {
                val audio = parts(journal).single { it.destination.extension == "wav" }
                val planned = readJournal(journal).getProperty("retarget.to")
                    ?.takeIf { it == File(it).name && it.endsWith(".wav") }?.let { File(recordings, it) }
                listOfNotNull(audio.destination, planned).filter { !ownedOnly || matches(it, audio.identity) }.map { it.absolutePath }
            }
            catch (_: Exception) { targetFallback(journal, ownedOnly) }
        }?.toSet().orEmpty()
    }

    fun reservedTargets(): Set<String> = unresolvedTargets(ownedOnly = false)

    fun recoveryKey(): String? = synchronized(RecordingBundleFiles) {
        val names = journals.listFiles()?.filter { it.extension in setOf("journal", "draft") }?.map { it.name }?.sorted().orEmpty()
        if (names.isEmpty()) null else java.security.MessageDigest.getInstance("SHA-256")
            .digest(names.joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun automaticUploadBlocked(): Boolean = synchronized(RecordingBundleFiles) {
        journals.listFiles()?.filter { it.extension in setOf("journal", "draft") }?.any { journal ->
            if (journal.extension == "draft") return@any targetFallback(journal).isEmpty()
            runCatching { parts(journal); false }.getOrElse { targetFallback(journal).isEmpty() }
        } == true
    }

    fun recover(recoverConflicts: Boolean = false, relocateIntent: PublicationIntentRelocation? = null,
        excludedJournals: Set<String> = emptySet()): String? = synchronized(RecordingBundleFiles) {
        var error: String? = null
        journals.listFiles()?.filter { it.extension == "target" }?.forEach { manifest ->
            if (!File(journals, manifest.nameWithoutExtension + ".journal").exists()) manifest.delete()
        }
        journals.listFiles()?.filter { it.extension == "draft" }?.forEach { draft ->
            if (File(journals, draft.nameWithoutExtension + ".journal").exists()) return@forEach
            try {
                check(draft.absolutePath !in excludedJournals) { "Finish saving or discarding the current take before recovering it." }
                if (readJournal(draft).getProperty("abort") == "true") rollback(draft)
                else {
                    check(recoverConflicts) { "An interrupted save needs recovery." }
                    armDraft(draft)
                }
            } catch (_: Exception) { error = "An interrupted save needs recovery. Its staged files have been preserved." }
        }
        journals.listFiles()?.filter { it.extension == "journal" }?.forEach { journal ->
            if (journal.absolutePath in excludedJournals) {
                error = "Finish saving or discarding the current take before recovering it."
                return@forEach
            }
            try { publish(journal) }
            catch (_: Exception) {
                try {
                    check(recoverConflicts && relocateIntent != null)
                    retarget(journal, checkNotNull(relocateIntent))
                } catch (_: Exception) { error = "A saved take needs recovery. Check recording files before retrying." }
            }
        }
        error
    }
}
