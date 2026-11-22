// Concern: tracks private recording upload identity and receipts | Non-concern: publishing | IO: (paths and jobs) -> cloud status
package com.pyano.sync

import android.content.Context
import androidx.work.WorkInfo
import java.util.UUID
import java.io.File
import com.pyano.audio.recordingFileProof
import com.pyano.audio.isRecordingFileProof

data class RecordingUploadWarning(val jobId: String, val message: String)

enum class RecordingCloudState { PENDING, UPLOADING, SYNCED, HISTORICAL_RECEIPT, FAILED }

fun recordingCloudState(states: List<WorkInfo.State>, synced: Boolean, historicalReceipt: Boolean = false): RecordingCloudState = when {
    WorkInfo.State.RUNNING in states -> RecordingCloudState.UPLOADING
    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> RecordingCloudState.PENDING
    synced || WorkInfo.State.SUCCEEDED in states -> RecordingCloudState.SYNCED
    historicalReceipt -> RecordingCloudState.HISTORICAL_RECEIPT
    states.any { it == WorkInfo.State.FAILED || it == WorkInfo.State.CANCELLED } -> RecordingCloudState.FAILED
    else -> RecordingCloudState.PENDING
}

fun recordingUploadError(state: RecordingCloudState?, detail: String?): String? =
    if (state == RecordingCloudState.FAILED) detail ?: "Cloud upload failed. Tap the recording's cloud icon to retry." else null

class RecordingCloudStatus(private val context: Context) {
    private val prefs = context.getSharedPreferences("recording_cloud_status", Context.MODE_PRIVATE)
    private fun fileProof(path: String): String? = runCatching { recordingFileProof(File(path)) }.getOrNull()
    fun migrateLegacyIdentity(path: String, titles: RecordingTitleSync): Boolean {
        val candidate = synchronized(this) {
            val current = prefs.getString("file:$path", null)
            val pending = prefs.getString("pending-midi:$path", null)
            if (isRecordingFileProof(current) && (path !in rawPendingPaths() || pending == NO_MIDI || isRecordingFileProof(pending))) return false
            prefs.getString("path:$path", null) ?: return false
        }
        if (titles.unknownOwner(candidate) || unverifiedOwnerOrigin(candidate)) return false
        val binding = titles.binding(candidate) ?: run {
            val actual = fileProof(path) ?: return false
            if (prefs.getString("pending-id:$path", null) != null && prefs.getString("pending-file:$path", null) == actual) return false
            val root = context.filesDir ?: return false
            val proof = CloudUploadQueue(File(root, "cloud-upload-queue")).proveLegacyUpload(File(path), CloudCredentialsStore(context))
                ?: return false
            titles.bind(candidate, proof.profileId, proof.remoteId, File(path).nameWithoutExtension)
        }
        return runCatching { synchronized(com.pyano.audio.RecordingBundleFiles) {
            val wav = File(path)
            if (!wav.isFile) return@synchronized false
            val files = listOfNotNull(wav, com.pyano.audio.RecordingBundleFiles.midi(wav).takeIf { it.isFile })
            val proofs = files.associateWith { recordingFileProof(it) }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            files.forEach { file ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(65_536)
                    while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                }
            }
            val remote = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (remote != binding.remoteId || files.any { recordingFileProof(it) != proofs[it] }) return@synchronized false
            synchronized(this) {
                val proof = checkNotNull(proofs[wav])
                val existingProof = prefs.getString("file:$path", null)
                val pendingProof = prefs.getString("pending-file:$path", null)
                val preparedId = prefs.getString("pending-id:$path", null)
                fun compatible(stored: String?) = stored == null || stored == proof ||
                    !isRecordingFileProof(stored) && proof.startsWith("$stored:sha256:")
                if (prefs.getString("path:$path", null) != candidate || !compatible(existingProof) ||
                    preparedId != null && preparedId != candidate && compatible(pendingProof) ||
                    path in rawPendingPaths() && !compatible(pendingProof)) false
                else {
                    val edit = prefs.edit().putString("file:$path", proof)
                    if (path in rawPendingPaths()) {
                        edit.putString("pending-file:$path", proof).putString("pending-id:$path", candidate)
                            .putString("pending-midi:$path", proofs[com.pyano.audio.RecordingBundleFiles.midi(wav)] ?: NO_MIDI)
                            .putString("pending-profile:$path", prefs.getString("pending-profile:$path", null) ?: binding.profileId)
                    }
                    check(edit.commit()) { "Could not verify legacy recording identity" }
                    true
                }
            }
        } }.getOrDefault(false)
    }

    @Synchronized fun id(path: String): String {
        val actual = checkNotNull(fileProof(path)) { "Recording file is unavailable" }
        val prior = prefs.getString("path:$path", null)
        val priorProof = prefs.getString("file:$path", null)
        if (prior != null && priorProof == actual) return prior
        val prepared = prefs.getString("pending-id:$path", null)
            ?.takeIf { prefs.getString("pending-file:$path", null) == actual }
        val id = prepared ?: UUID.randomUUID().toString()
        val edit = prefs.edit().putString("path:$path", id).putString("file:$path", actual)
        val modernReservation = prefs.getString("pending-id:$path", null) != null &&
            prefs.getString("pending-file:$path", null) != null && prefs.getString("pending-midi:$path", null) != null
        if (prepared == null && (prior != null || path in rawPendingPaths() && !modernReservation)) {
            edit.putBoolean("unverified-owner:$id", true)
            if (prior != null && (synced(prior) || prefs.getBoolean("historical-receipt:$prior", false))) {
                edit.putBoolean("historical-receipt:$id", true)
            }
        }
        check(edit.commit()) { "Could not save upload identity" }
        return id
    }
    fun existingId(path: String): String? {
        val actual = fileProof(path) ?: return null
        return prefs.getString("path:$path", null)?.takeIf { prefs.getString("file:$path", null) == actual }
            ?: prefs.getString("pending-id:$path", null)?.takeIf { prefs.getString("pending-file:$path", null) == actual }
    }
    fun hasHistoricalReceipt(path: String): Boolean {
        val actual = fileProof(path) ?: return false
        val staged = prefs.getString("pending-id:$path", null)
        if (staged != null && prefs.getString("pending-file:$path", null) == actual) {
            return prefs.getBoolean("historical-receipt:$staged", false)
        }
        val id = prefs.getString("path:$path", null) ?: return false
        return prefs.getBoolean("historical-receipt:$id", false) ||
            prefs.getString("file:$path", null) != actual && synced(id)
    }

    fun unverifiedOwnerOrigin(id: String) = prefs.getBoolean("unverified-owner:$id", false)
    fun warningDismissed(jobId: String) = jobId in prefs.getStringSet("dismissed-warnings", emptySet()).orEmpty()
    @Synchronized fun dismissWarning(jobId: String) {
        val dismissed = prefs.getStringSet("dismissed-warnings", emptySet()).orEmpty() + jobId
        check(prefs.edit().putStringSet("dismissed-warnings", dismissed).commit()) { "Could not clear upload warning" }
    }
    fun synced(id: String) = prefs.getBoolean("synced:$id", false)
    fun complete(id: String) { check(prefs.edit().putBoolean("synced:$id", true).commit()) { "Could not save cloud receipt" } }
    @Synchronized fun pending(path: String, identity: String = recordingFileProof(File(path)), profileId: String? = null,
        midiIdentity: String? = com.pyano.audio.RecordingBundleFiles.midi(File(path)).takeIf { it.isFile }?.let(::recordingFileProof)) {
        val active = prefs.getString("path:$path", null)?.takeIf { prefs.getString("file:$path", null) == identity }
        val staged = prefs.getString("pending-id:$path", null)?.takeIf { prefs.getString("pending-file:$path", null) == identity }
        check(prefs.edit().putStringSet("pending", rawPendingPaths() + path).putString("pending-file:$path", identity)
            .putString("pending-id:$path", active ?: staged ?: UUID.randomUUID().toString())
            .putString("pending-profile:$path", profileId).putString("pending-midi:$path", midiIdentity ?: NO_MIDI).commit()) {
            "Could not queue automatic upload"
        }
    }
    @Synchronized fun abandonUnpublishedIntent(path: String, wavProof: String, midiProof: String?): Boolean {
        if (path !in rawPendingPaths() || prefs.getString("pending-file:$path", null) != wavProof ||
            prefs.getString("pending-midi:$path", null) != (midiProof ?: NO_MIDI)) return false
        val stagedId = prefs.getString("pending-id:$path", null) ?: return false
        if (synced(stagedId) || prefs.getBoolean("historical-receipt:$stagedId", false) || unverifiedOwnerOrigin(stagedId)) return false
        val edit = prefs.edit().putStringSet("pending", rawPendingPaths() - path)
        for (prefix in listOf("pending-file", "pending-midi", "pending-id", "pending-profile")) edit.remove("$prefix:$path")
        check(edit.commit()) { "Could not withdraw the abandoned take's upload intent; retry Discard" }
        return true
    }

    @Synchronized fun retargetPending(old: String, new: String, wavIdentity: String, midiIdentity: String?) {
        val midi = midiIdentity ?: NO_MIDI
        fun matches(path: String) = path in rawPendingPaths() && prefs.getString("pending-file:$path", null) == wavIdentity &&
            prefs.getString("pending-midi:$path", null) == midi && prefs.getString("pending-id:$path", null) != null
        if (!matches(old)) {
            check(matches(new)) { "The original Cloud upload intent is unavailable; keep the take local" }
            return
        }
        check(new !in rawPendingPaths() || matches(new)) { "The recovery name belongs to another pending take" }
        val id = checkNotNull(prefs.getString("pending-id:$old", null))
        val profile = prefs.getString("pending-profile:$old", null)
        val edit = prefs.edit().putStringSet("pending", rawPendingPaths() - old + new)
            .putString("pending-file:$new", wavIdentity).putString("pending-midi:$new", midi)
            .putString("pending-id:$new", id).putString("pending-profile:$new", profile)
        if (old != new) for (prefix in listOf("pending-file", "pending-midi", "pending-id", "pending-profile")) edit.remove("$prefix:$old")
        check(edit.commit()) { "Could not move the original Cloud upload intent to its recovery name" }
    }
    fun pendingProfile(path: String): String? = prefs.getString("pending-profile:$path", null)
    private fun rawPendingPaths() = prefs.getStringSet("pending", emptySet()).orEmpty().toSet()
    fun legacyPendingPaths(): Set<String> = rawPendingPaths().filterTo(mutableSetOf()) {
        prefs.getString("pending-file:$it", null) == null || prefs.getString("pending-midi:$it", null) == null
    }
    private fun currentMidiProof(path: String): String? {
        val midi = com.pyano.audio.RecordingBundleFiles.midi(File(path))
        return if (midi.exists()) fileProof(midi.absolutePath) else NO_MIDI
    }
    private fun pendingBundleIssue(path: String): String? {
        if (path !in rawPendingPaths()) return null
        val wav = prefs.getString("pending-file:$path", null)
        val midi = prefs.getString("pending-midi:$path", null)
        return when {
            wav == null || midi == null -> "The saved WAV/MIDI identity could not be verified. Keep or export this take locally; automatic upload is blocked."
            fileProof(path) != wav -> "The saved audio changed before upload. Restore the original WAV before retrying; local files were kept."
            currentMidiProof(path) != midi -> "The saved MIDI changed, was added, or is missing. Restore the original WAV/MIDI pair before retrying; local files were kept."
            else -> null
        }
    }
    fun pendingBundleWarnings(): Map<String, String> = rawPendingPaths().mapNotNull { path ->
        if (!File(path).isFile || path in legacyPendingPaths()) null else pendingBundleIssue(path)?.let { path to it }
    }.toMap()
    fun requirePendingBundle(path: String) {
        pendingBundleIssue(path)?.let { throw CloudSyncException(it, false) }
    }
    internal fun requirePendingSnapshot(path: String, snapshot: File) {
        if (path !in rawPendingPaths()) return
        fun verify(expected: String?, file: File) {
            val digest = com.pyano.audio.recordingProofDigest(expected)
            if (digest == null || digest != com.pyano.audio.recordingProofDigest(fileProof(file.absolutePath))) {
                throw CloudSyncException("The queued recording no longer matches the saved take. Local files were kept; restore the original pair before retrying.", false)
            }
        }
        verify(prefs.getString("pending-file:$path", null), snapshot)
        val midi = com.pyano.audio.RecordingBundleFiles.midi(snapshot)
        val expected = prefs.getString("pending-midi:$path", null)
        if (expected == NO_MIDI) {
            if (midi.exists()) throw CloudSyncException("The queued recording contains unexpected MIDI. Local files were kept.", false)
        } else verify(expected, midi)
    }
    fun pendingPaths(): Set<String> = rawPendingPaths().filterTo(mutableSetOf()) { pendingBundleIssue(it) == null }
    @Synchronized fun queued(path: String) {
        check(prefs.edit().putStringSet("pending", rawPendingPaths() - path).remove("pending-file:$path").remove("pending-profile:$path").remove("pending-id:$path").remove("pending-midi:$path").commit())
    }
    @Synchronized fun delete(path: String) {
        check(prefs.edit().remove("path:$path").remove("file:$path").remove("pending-file:$path").remove("pending-profile:$path").remove("pending-id:$path").remove("pending-midi:$path").putStringSet("pending", rawPendingPaths() - path).commit())
    }
    @Synchronized fun rename(old: String, new: String) {
        if (old == new) return
        val actual = checkNotNull(fileProof(new)) { "Renamed recording is unavailable" }
        val activeProof = prefs.getString("file:$old", null)
        val activeId = prefs.getString("path:$old", null)
        val stagedProof = prefs.getString("pending-file:$old", null)
        val stagedId = prefs.getString("pending-id:$old", null)
        val profile = prefs.getString("pending-profile:$old", null)
        val stagedMidi = prefs.getString("pending-midi:$old", null)
        val wasPending = old in rawPendingPaths()
        if (activeId == null && activeProof == null && stagedId == null && stagedProof == null && !wasPending) return
        val edit = prefs.edit()
        for (path in listOf(old, new)) for (prefix in listOf("path", "file", "pending-file", "pending-id", "pending-profile", "pending-midi")) edit.remove("$prefix:$path")
        if (activeProof == actual && activeId != null) edit.putString("path:$new", activeId).putString("file:$new", activeProof)
        else if (activeId != null) {
            val id = UUID.randomUUID().toString()
            edit.putString("path:$new", id).putString("file:$new", actual).putBoolean("unverified-owner:$id", true)
            if (synced(activeId) || prefs.getBoolean("historical-receipt:$activeId", false)) edit.putBoolean("historical-receipt:$id", true)
        }
        val pending = rawPendingPaths() - old - new
        if (wasPending) {
            stagedProof?.let { edit.putString("pending-file:$new", it) }
            stagedId?.let { edit.putString("pending-id:$new", it) }
            profile?.let { edit.putString("pending-profile:$new", it) }
            stagedMidi?.let { edit.putString("pending-midi:$new", it) }
        }
        check(edit.putStringSet("pending", if (wasPending) pending + new else pending).commit()) {
            "Recording renamed; could not persist cloud status. Check sync before retrying."
        }
    }
    companion object {
        const val TAG_PREFIX = "pyano-recording:"
        private const val NO_MIDI = "absent"
    }
}
