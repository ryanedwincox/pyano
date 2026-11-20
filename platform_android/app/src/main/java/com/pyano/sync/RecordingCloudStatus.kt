// Concern: tracks private recording upload identity and receipts | Non-concern: publishing | IO: (paths and jobs) -> cloud status
package com.pyano.sync

import android.content.Context
import androidx.work.WorkInfo
import java.util.UUID

enum class RecordingCloudState { PENDING, UPLOADING, SYNCED, FAILED }

fun recordingCloudState(states: List<WorkInfo.State>, synced: Boolean): RecordingCloudState = when {
    WorkInfo.State.RUNNING in states -> RecordingCloudState.UPLOADING
    states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } -> RecordingCloudState.PENDING
    synced || WorkInfo.State.SUCCEEDED in states -> RecordingCloudState.SYNCED
    states.any { it == WorkInfo.State.FAILED || it == WorkInfo.State.CANCELLED } -> RecordingCloudState.FAILED
    else -> RecordingCloudState.PENDING
}

class RecordingCloudStatus(context: Context) {
    private val prefs = context.getSharedPreferences("recording_cloud_status", Context.MODE_PRIVATE)
    @Synchronized fun id(path: String): String = prefs.getString("path:$path", null) ?: UUID.randomUUID().toString().also {
        check(prefs.edit().putString("path:$path", it).commit()) { "Could not save upload identity" }
    }
    fun existingId(path: String): String? = prefs.getString("path:$path", null)
    fun synced(id: String) = prefs.getBoolean("synced:$id", false)
    fun complete(id: String) { check(prefs.edit().putBoolean("synced:$id", true).commit()) { "Could not save cloud receipt" } }
    @Synchronized fun pending(path: String) {
        val paths = pendingPaths() + path
        check(prefs.edit().putStringSet("pending", paths).commit()) { "Could not queue automatic upload" }
    }
    fun pendingPaths(): Set<String> = prefs.getStringSet("pending", emptySet()).orEmpty().toSet()
    @Synchronized fun queued(path: String) { check(prefs.edit().putStringSet("pending", pendingPaths() - path).commit()) }
    @Synchronized fun delete(path: String) {
        check(prefs.edit().remove("path:$path").putStringSet("pending", pendingPaths() - path).commit())
    }
    @Synchronized fun rename(old: String, new: String) {
        if (old == new) return
        val edit = prefs.edit().remove("path:$old")
        existingId(old)?.let { edit.putString("path:$new", it) }
        check(edit.putStringSet("pending", pendingPaths().map { if (it == old) new else it }.toSet()).commit()) {
            "Recording renamed; could not persist cloud status. Check sync before retrying."
        }
    }
    companion object { const val TAG_PREFIX = "pyano-recording:" }
}
