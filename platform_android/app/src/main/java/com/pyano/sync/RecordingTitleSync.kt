// Concern: persists profile-bound title changes | Non-concern: recording audio transfer | IO: (local identity and title) -> durable intent
package com.pyano.sync

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import com.pyano.audio.RecordingBundleFiles

data class RecordingTitleBinding(val profileId: String, val remoteId: String, val uploadTitle: String)

internal fun recordingRenameProfile(pending: String?, bound: String?, sameOwner: (String, String) -> Boolean): String? {
    check(pending == null || bound == null || pending == bound || sameOwner(pending, bound)) {
        "This recording has conflicting Cloud owner metadata; its local name was not changed"
    }
    return pending ?: bound
}

internal fun requireRecordingUploadOwner(boundProfile: String?, profile: String, sameOwner: (String, String) -> Boolean) {
    if (boundProfile != null && boundProfile != profile && !sameOwner(boundProfile, profile)) {
        throw RecordingOwnerUnverified("This recording belongs to another Cloud owner. Select its original Cloud account before retrying.")
    }
}

internal fun validateRecordingUploadBinding(binding: RecordingTitleBinding, profile: String, remoteId: String,
    sameOwner: (String, String) -> Boolean) {
    requireRecordingUploadOwner(binding.profileId, profile, sameOwner)
    if (binding.remoteId != remoteId) throw CloudSyncException(
        "Recording files no longer match its original Cloud upload. The local files were kept; restore the original WAV and MIDI before retrying.", false)
}

internal fun automaticRecordingUploadProfile(pending: String?, bound: String?, selected: String?): String? =
    pending ?: bound ?: selected

internal interface TitleStorage {
    fun read(key: String): String?
    fun write(values: Map<String, String?>)
}

class RecordingTitleSync internal constructor(private val storage: TitleStorage,
    private val ownerScope: (String) -> String? = { null }) {
    constructor(context: Context) : this(object : TitleStorage {
        private val prefs = context.getSharedPreferences("recording_title_sync", Context.MODE_PRIVATE)
        override fun read(key: String) = prefs.getString(key, null)
        override fun write(values: Map<String, String?>) {
            val edit = prefs.edit()
            values.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
            check(edit.commit()) { "Could not persist Cloud title change" }
        }
    }, { profile -> CloudCredentialsStore(context).recordingOwnerScope(profile) })

    fun unknownOwner(id: String): Boolean = synchronized(LOCK) { storage.read("$id.unknownOwner") == "true" }
    fun markUnknownOwner(id: String) = synchronized(LOCK) { storage.write(mapOf("$id.unknownOwner" to "true")) }

    fun binding(id: String): RecordingTitleBinding? = synchronized(LOCK) {
        val profile = storage.read("$id.profile") ?: return@synchronized null
        RecordingTitleBinding(profile, storage.read("$id.remote") ?: return@synchronized null, storage.read("$id.upload").orEmpty()).also {
            val values = groupRegistration(id, it)
            if (values.any { (key, value) -> storage.read(key) != value }) storage.write(values)
        }
    }

    fun bind(id: String, profile: String, remote: String, uploadTitle: String): RecordingTitleBinding = synchronized(LOCK) {
        if (unknownOwner(id)) throw RecordingOwnerUnverified()
        val previous = binding(id)
        if (previous != null) return@synchronized previous
        val created = RecordingTitleBinding(profile, remote, uploadTitle)
        storage.write(mapOf("$id.profile" to profile, "$id.remote" to remote, "$id.upload" to uploadTitle) + groupRegistration(id, created))
        created
    }

    fun desired(id: String): String? = synchronized(LOCK) {
        if (id in transactions() || unknownOwner(id)) null else {
            val group = remoteQueueKey(id)
            if (group == null) storage.read("$id.desired") else storage.read("group:$group.desired")
        }
    }
    fun renamed(id: String, title: String) = synchronized(LOCK) {
        require(title.isNotBlank() && title.length <= 200) { "Recording names must contain 1–200 characters" }
        storage.write(desiredValues(id, title, binding(id)))
    }
    fun renameLocally(id: String, source: File, title: String, newBinding: RecordingTitleBinding? = null,
        moved: (String, String) -> Unit): String = synchronized(LOCK) {
        require(com.pyano.audio.RecordingNames.valid(title)) { "Enter a valid recording name" }
        recoverRenames(moved)
        check(id !in transactions()) { "This recording needs local rename recovery before another rename" }
        try {
            RecordingBundleFiles.rename(source, title) { destination ->
                storage.write(mapOf("$id.from" to source.absolutePath, "$id.to" to destination.absolutePath,
                    "$id.hadMidi" to RecordingBundleFiles.midi(source).exists().toString(),
                    "$id.wavIdentity" to com.pyano.audio.recordingFileProof(source),
                    "$id.midiIdentity" to RecordingBundleFiles.midi(source).takeIf { it.isFile }?.let {
                        com.pyano.audio.recordingFileProof(it) },
                    "$id.newProfile" to newBinding?.profileId, "$id.newRemote" to newBinding?.remoteId,
                    "$id.newUpload" to newBinding?.uploadTitle, "transactions" to (transactions() + id).joinToString(",")))
            }
        } catch (error: Exception) {
            val destination = storage.read("$id.to")?.let(::File)
            fun matches(file: File, key: String) = runCatching {
                com.pyano.audio.recordingFileProof(file) == storage.read(key)
            }.getOrDefault(false)
            val originalMidi = if (storage.read("$id.hadMidi") == "true")
                matches(RecordingBundleFiles.midi(source), "$id.midiIdentity") else !RecordingBundleFiles.midi(source).exists()
            if (destination != null && matches(source, "$id.wavIdentity") && originalMidi &&
                !matches(destination, "$id.wavIdentity") && !matches(RecordingBundleFiles.midi(destination), "$id.midiIdentity")) {
                storage.write(mapOf("$id.from" to null, "$id.to" to null, "$id.hadMidi" to null,
                    "$id.wavIdentity" to null, "$id.midiIdentity" to null,
                    "$id.newProfile" to null, "$id.newRemote" to null, "$id.newUpload" to null,
                    "transactions" to (transactions() - id).joinToString(",")))
            }
            throw error
        }
        finishRename(id, moved)
    }
    fun recoverRenames(moved: (String, String) -> Unit): List<String> = synchronized(LOCK) {
        transactions().filter { id ->
            try { finishRename(id, moved); false } catch (_: Exception) { true }
        }
    }
    private fun transactions(): Set<String> = storage.read("transactions").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
    private fun finishRename(id: String, moved: (String, String) -> Unit): String {
        val source = File(checkNotNull(storage.read("$id.from")))
        val destination = File(checkNotNull(storage.read("$id.to")))
        RecordingBundleFiles.recoverRename(source, destination, storage.read("$id.hadMidi") == "true",
            checkNotNull(storage.read("$id.wavIdentity")), storage.read("$id.midiIdentity"))
        moved(source.absolutePath, destination.absolutePath)
        val values = mutableMapOf<String, String?>("$id.from" to null, "$id.to" to null, "$id.hadMidi" to null,
                    "$id.wavIdentity" to null, "$id.midiIdentity" to null,
            "transactions" to (transactions() - id).joinToString(","))
        val binding = binding(id) ?: storage.read("$id.newProfile")?.let { profile ->
            RecordingTitleBinding(profile, checkNotNull(storage.read("$id.newRemote")), storage.read("$id.newUpload").orEmpty())
        }
        if (binding != null) {
            values["$id.profile"] = binding.profileId
            values["$id.remote"] = binding.remoteId
            values["$id.upload"] = binding.uploadTitle
            values.putAll(desiredValues(id, destination.nameWithoutExtension, binding))
        }
        values["$id.newProfile"] = null
        values["$id.newRemote"] = null
        values["$id.newUpload"] = null
        storage.write(values)
        return destination.absolutePath
    }
    fun acknowledge(id: String, title: String) = synchronized(LOCK) {
        if (desired(id) == title) {
            val group = remoteQueueKey(id)
            val members = if (group == null) setOf(id) else memberIds(group)
            val values = members.associate { "$it.desired" to null }.toMutableMap<String, String?>()
            values["pending"] = (pending() - members).joinToString(",")
            if (group != null) values["group:$group.desired"] = null
            storage.write(values)
        }
    }
    fun remoteQueueKey(id: String): String? = synchronized(LOCK) {
        binding(id) ?: return@synchronized null
        storage.read("$id.group")
    }
    fun unresolvedRenamePaths(): Set<String> = synchronized(LOCK) {
        transactions().flatMap { listOfNotNull(storage.read("$it.from"), storage.read("$it.to")) }.toSet()
    }
    private fun scopeKey(binding: RecordingTitleBinding): String? {
        val owner = ownerScope(binding.profileId) ?: return null
        return java.security.MessageDigest.getInstance("SHA-256").digest("$owner\u0000${binding.remoteId}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun memberIds(group: String) = storage.read("group:$group.members").orEmpty().split(",").filter { it.isNotBlank() }.toSet()
    private fun groupRegistration(id: String, binding: RecordingTitleBinding): Map<String, String?> {
        val previous = storage.read("$id.group")
        val group = scopeKey(binding) ?: previous ?: return emptyMap()
        val members = memberIds(group) + (previous?.takeIf { it != group }?.let(::memberIds) ?: emptySet()) + id
        val values = mutableMapOf<String, String?>("group:$group.members" to members.joinToString(","))
        members.forEach { values["$it.group"] = group }
        var latestOrder = storage.read("group:$group.order")?.toLongOrNull()
        var latestTitle = storage.read("group:$group.desired")
        if (previous != null && previous != group) {
            val oldOrder = storage.read("group:$previous.order")?.toLongOrNull()
            val oldTitle = storage.read("group:$previous.desired")
            if (oldOrder != null && (latestOrder == null || oldOrder > latestOrder || oldOrder == latestOrder && oldTitle == null)) {
                latestOrder = oldOrder
                latestTitle = oldTitle
            }
            values["group:$previous.members"] = null
            values["group:$previous.order"] = null
            values["group:$previous.desired"] = null
        }
        val local = storage.read("$id.desired")
        val localOrder = storage.read("$id.order")?.toLongOrNull() ?: 0
        if (local != null && (latestOrder == null || localOrder > latestOrder)) {
            latestOrder = localOrder
            latestTitle = local
        }
        if (latestOrder != null) {
            values["group:$group.order"] = latestOrder.toString()
            values["group:$group.desired"] = latestTitle
            if (latestOrder > (storage.read("edit-order")?.toLongOrNull() ?: 0)) values["edit-order"] = latestOrder.toString()
            if (latestTitle == null) {
                val superseded = members.filter { (storage.read("$it.order")?.toLongOrNull() ?: 0) <= latestOrder }.toSet()
                superseded.forEach { values["$it.desired"] = null }
                values["pending"] = (pending() - superseded).joinToString(",")
            }
        }
        return values
    }
    private fun desiredValues(id: String, title: String, binding: RecordingTitleBinding?): Map<String, String?> {
        val order = (storage.read("edit-order")?.toLongOrNull() ?: 0) + 1
        val values = mutableMapOf<String, String?>("edit-order" to order.toString(), "$id.order" to order.toString(),
            "$id.desired" to title, "pending" to (pending() + id).joinToString(","))
        val group = storage.read("$id.group") ?: binding?.let(::scopeKey)
        if (group != null) {
            values["$id.group"] = group
            values["group:$group.members"] = (memberIds(group) + id).joinToString(",")
            values["group:$group.desired"] = title
            values["group:$group.order"] = order.toString()
        }
        return values
    }
    fun pending(): Set<String> = synchronized(LOCK) { storage.read("pending").orEmpty().split(",").filter { it.isNotBlank() }.toSet() }
    companion object { private val LOCK = Any() }
}

private val titleSendLocks = ConcurrentHashMap<String, Mutex>()

suspend fun syncRecordingTitle(store: RecordingTitleSync, id: String, send: suspend (RecordingTitleBinding, String) -> Boolean) {
    val binding = store.binding(id) ?: return
    val group = checkNotNull(store.remoteQueueKey(id)) { "Cloud title owner metadata is unavailable. Reconfigure the original Cloud account." }
    titleSendLocks.computeIfAbsent(group) { Mutex() }.withLock {
        while (true) {
            val title = store.desired(id) ?: return@withLock
            if (!send(binding, title)) return@withLock
            store.acknowledge(id, title)
        }
    }
}

suspend fun queueRecordingTitle(context: Context, id: String) = withContext(Dispatchers.IO) {
    val group = checkNotNull(RecordingTitleSync(context).remoteQueueKey(id)) { "Cloud title owner metadata is unavailable" }
    val request = OneTimeWorkRequestBuilder<RecordingTitleWorker>()
        .setInputData(Data.Builder().putString(CloudUploadWorker.KEY_RECORDING_ID, id).build())
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .addTag("pyano-cloud-title").addTag(RecordingCloudStatus.TAG_PREFIX + id).build()
    WorkManager.getInstance(context).enqueueUniqueWork("pyano-title-$group", ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get()
}
