// Concern: coordinates workstation state | Non-concern: DSP implementation | IO: (user and device events) -> app state
package com.pyano

import android.app.Application
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.pyano.audio.AudioRecorder
import com.pyano.audio.AudioStats
import com.pyano.audio.FluidSynthEngine
import com.pyano.audio.LoopEngine
import com.pyano.audio.RecordingInfo
import com.pyano.audio.MidiEventType
import com.pyano.audio.TimestampedMidiEvent
import com.pyano.audio.SF2Info
import com.pyano.audio.SF2MetadataReader
import com.pyano.audio.Sf2Subset
import com.pyano.audio.SfPreset
import com.pyano.midi.MidiDeviceManager
import com.pyano.midi.MidiEventHandler
import com.pyano.midi.MidiRecordingListener
import com.pyano.sync.RecordingUploadScheduler
import com.pyano.sync.CloudProfileUnavailable
import com.pyano.sync.saveAndScheduleTake
import com.pyano.sync.RecordingCloudStatus
import com.pyano.sync.RecordingCloudState
import com.pyano.sync.recordingCloudState
import com.pyano.sync.recordingUploadError
import com.pyano.sync.RecordingUploadWarning
import com.pyano.scores.ScoreLibraryController
import com.pyano.scores.ScoreProfile
import com.pyano.scores.scoreCacheRoot
import com.pyano.sync.CloudCredentialsStore
import com.pyano.sync.CloudUploadWorker
import com.pyano.sync.CloudUploadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

data class AudioOutput(val id: Int, val name: String, val type: Int) {
    /** Use type+name across reconnects; Android reassigns device IDs, while the synthetic Default entry has a fixed key. */
    val preferredKey: String
        get() = if (id == 0) "default" else "$type|$name"
}

class PyanoViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "Pyano"
        // Separate tag so glitch diagnostics can be isolated: adb logcat -s PyanoAudio
        private const val AUDIO_TAG = "PyanoAudio"
        private const val AUDIO_STATS_INTERVAL_TICKS = 20      // 1 s at the 20 Hz poll rate
        private const val AUDIO_STATS_HEARTBEAT_TICKS = 200    // 10 s
        private const val AUDIO_STATS_LOAD_WARN = 0.7f
        private const val DEFAULT_SF = "FluidR3_GM.sf2"
        private const val PREFS_NAME = "pyano_settings"
        // Per-channel reverb send forced after each program change so reverb behaves consistently across instruments (see applyChannelSendDefaults).
        private const val REVERB_SEND_DEFAULT = 96
        private const val CC_REVERB_SEND = 91
        private const val CC_CHORUS_SEND = 93
        private const val METRONOME_CHANNEL = 9
        private val DIAGNOSED_OUTPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI,
        )
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, 0)
    val engine = FluidSynthEngine()
    private val midiManager = MidiDeviceManager(application)
    private var midiEventHandler: MidiEventHandler? = null
    private val kiosk = KioskController(application)
    val diagnostics = DeviceDiagnostics(application)

    /** MainActivity instances started; the poll loop slows down while none is shown. */
    private val visibleScreens = java.util.concurrent.atomic.AtomicInteger(0)
    private val uiVisible get() = visibleScreens.get() > 0
    private var initialized = false

    fun screenStarted() {
        visibleScreens.incrementAndGet()
    }

    fun screenStopped() {
        visibleScreens.decrementAndGet()
    }
    private var midiDevice: MidiDevice? = null
    private var midiConnectionGeneration = 0
    private val screenNotes = com.pyano.keyboard.PianoNoteOwners { note, down ->
        midiEventHandler?.onSend(byteArrayOf((if (down) 0x90 else 0x80).toByte(), note.toByte(), (if (down) 100 else 0).toByte()), 0, 3, 0)
    }
    private val _screenKeyboardEnabled = MutableStateFlow(prefs.getBoolean("screenKeyboard", false))
    val screenKeyboardEnabled = _screenKeyboardEnabled.asStateFlow()
    private var audioDeviceCallback: AudioDeviceCallback? = null

    // UI State
    private val _soundFontName = MutableStateFlow(DEFAULT_SF)
    val soundFontName = _soundFontName.asStateFlow()

    private val _availableSoundFonts = MutableStateFlow<List<SF2Info>>(emptyList())
    val availableSoundFonts = _availableSoundFonts.asStateFlow()

    private val _favoriteSoundFonts = MutableStateFlow<List<SF2Info>>(emptyList())
    val favoriteSoundFonts = _favoriteSoundFonts.asStateFlow()

    private val _midiDevices = MutableStateFlow<List<MidiDeviceInfo>>(emptyList())
    val midiDevices = _midiDevices.asStateFlow()

    private val _selectedMidiDevice = MutableStateFlow<MidiDeviceInfo?>(null)
    val selectedMidiDevice = _selectedMidiDevice.asStateFlow()

    private val _midiStatus = MutableStateFlow("No MIDI device")
    val midiStatus = _midiStatus.asStateFlow()

    // Dynamic presets from loaded soundfont
    private val _presets = MutableStateFlow<List<SfPreset>>(emptyList())
    val presets = _presets.asStateFlow()

    private val _selectedPresetIndex = MutableStateFlow(0)
    val selectedPresetIndex = _selectedPresetIndex.asStateFlow()

    // Audio output devices
    private val _audioOutputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    val audioOutputs = _audioOutputs.asStateFlow()

    private val _selectedAudioOutput = MutableStateFlow<AudioOutput?>(null)
    val selectedAudioOutput = _selectedAudioOutput.asStateFlow()

    /** Why the chosen output isn't available, or null when it is (or Default is chosen). */
    private val _outputProblem = MutableStateFlow<String?>(null)
    val outputProblem = _outputProblem.asStateFlow()
    private val outputWarningState = com.pyano.audio.OutputWarningState()
    private val _outputWarning = MutableStateFlow<String?>(null)
    val outputWarning = _outputWarning.asStateFlow()

    // Activity monitors
    private val _midiInputLevel = MutableStateFlow(false) // true = activity
    val midiInputLevel = _midiInputLevel.asStateFlow()

    private val _outputLevel = MutableStateFlow(0f)
    val outputLevel = _outputLevel.asStateFlow()

    private val _gain = MutableStateFlow(prefs.getFloat("gain", 3.0f))
    val gain = _gain.asStateFlow()

    private val _transposeSemitones = MutableStateFlow(
        prefs.getInt("transposeSemitones", 0).coerceIn(MidiEventHandler.MIN_TRANSPOSE, MidiEventHandler.MAX_TRANSPOSE)
    )
    val transposeSemitones = _transposeSemitones.asStateFlow()

    // Buffer frames, 0 = automatic; the old bufferSize key meant callback frames and must not be reused.
    private val _bufferSize = MutableStateFlow(prefs.getInt("bufferFrames", 0))
    val bufferSize = _bufferSize.asStateFlow()

    private val _audioStats = MutableStateFlow<AudioStats?>(null)
    val audioStats = _audioStats.asStateFlow()

    // Reverb
    private val _reverbOn = MutableStateFlow(prefs.getBoolean("reverbOn", false))
    val reverbOn = _reverbOn.asStateFlow()
    private val _reverbRoom = MutableStateFlow(prefs.getFloat("reverbRoom", 0.2f))
    val reverbRoom = _reverbRoom.asStateFlow()
    private val _reverbDamp = MutableStateFlow(prefs.getFloat("reverbDamp", 0.0f))
    val reverbDamp = _reverbDamp.asStateFlow()
    private val _reverbWidth = MutableStateFlow(prefs.getFloat("reverbWidth", 0.5f))
    val reverbWidth = _reverbWidth.asStateFlow()
    private val _reverbLevel = MutableStateFlow(prefs.getFloat("reverbLevel", 0.9f))
    val reverbLevel = _reverbLevel.asStateFlow()

    // Metronome
    private val _metronomeBpm = MutableStateFlow(prefs.getInt("metronomeBpm", 120))
    val metronomeBpm = _metronomeBpm.asStateFlow()

    private val _metronomeTimeSig = MutableStateFlow(prefs.getInt("metronomeTimeSig", 4))
    val metronomeTimeSig = _metronomeTimeSig.asStateFlow()

    private val _metronomeRunning = MutableStateFlow(false)
    val metronomeRunning = _metronomeRunning.asStateFlow()

    private val _metronomeBeat = MutableStateFlow(0)
    val metronomeBeat = _metronomeBeat.asStateFlow()

    private val _metronomeClickType = MutableStateFlow(prefs.getInt("metronomeClickType", 0))
    val metronomeClickType = _metronomeClickType.asStateFlow()

    private val _metronomeVolume = MutableStateFlow(prefs.getFloat("metronomeVolume", 1.0f))
    val metronomeVolume = _metronomeVolume.asStateFlow()

    // GM percussion note pairs: downbeat / subbeat. Index into this list is persisted as metronomeClickType so the dropdown stays stable across updates.
    data class MetronomeSound(val name: String, val downbeatNote: Int, val subbeatNote: Int)
    val metronomeSounds = listOf(
        MetronomeSound("Metronome", 34, 33),   // Metronome Bell / Click
        MetronomeSound("Wood Block", 76, 77),  // Hi / Low Wood Block
        MetronomeSound("Claves", 75, 75),
        MetronomeSound("Side Stick", 37, 37),
        MetronomeSound("Cowbell", 56, 56),
        MetronomeSound("Closed Hi-Hat", 42, 42),
        MetronomeSound("Sticks", 31, 31),
    )

    // --- Loop Station ---
    private val loopEngine = LoopEngine(engine)

    private val _loopRecording = MutableStateFlow(false)
    val loopRecording = _loopRecording.asStateFlow()

    private val _loopPlaying = MutableStateFlow(false)
    val loopPlaying = _loopPlaying.asStateFlow()

    private val _loopBpm = MutableStateFlow(prefs.getInt("loopBpm", 120))
    val loopBpm = _loopBpm.asStateFlow()

    private val _loopLengthBars = MutableStateFlow(prefs.getInt("loopLengthBars", 4))
    val loopLengthBars = _loopLengthBars.asStateFlow()

    private val _loopLayerCounts = MutableStateFlow(List(LoopEngine.MAX_LAYERS) { 0 })
    val loopLayerCounts = _loopLayerCounts.asStateFlow()

    private val _loopLayerMuted = MutableStateFlow(List(LoopEngine.MAX_LAYERS) { false })
    val loopLayerMuted = _loopLayerMuted.asStateFlow()

    private val _loopPosition = MutableStateFlow(0f)
    val loopPosition = _loopPosition.asStateFlow()

    private val _loopCountIn = MutableStateFlow(prefs.getBoolean("loopCountIn", false))
    val loopCountIn = _loopCountIn.asStateFlow()

    private val _loopMetronome = MutableStateFlow(prefs.getBoolean("loopMetronome", false))
    val loopMetronome = _loopMetronome.asStateFlow()
    private var loopStartedMetronome = false

    private val _loopSyncBpm = MutableStateFlow(prefs.getBoolean("loopSyncBpm", true))
    val loopSyncBpm = _loopSyncBpm.asStateFlow()

    private val _loopRecordingLayerIndex = MutableStateFlow(-1)
    val loopRecordingLayerIndex = _loopRecordingLayerIndex.asStateFlow()

    private val _loopHasAnyEvents = MutableStateFlow(false)
    val loopHasAnyEvents = _loopHasAnyEvents.asStateFlow()

    private val _loopLayerNames = MutableStateFlow(List(LoopEngine.MAX_LAYERS) { "Layer ${it + 1}" })
    val loopLayerNames = _loopLayerNames.asStateFlow()

    private val cloudCredentials = CloudCredentialsStore(application)
    private val _syncStatus = MutableStateFlow("Configure recording sync to upload selected takes")
    val syncStatus = _syncStatus.asStateFlow()
    private val _uploadError = MutableStateFlow<RecordingUploadWarning?>(null)
    val uploadError = _uploadError.asStateFlow()
    private val _takeRecoveryAvailable = MutableStateFlow(false)
    val takeRecoveryAvailable = _takeRecoveryAvailable.asStateFlow()
    private val _serverSettingsStatus = MutableStateFlow("")
    val serverSettingsStatus = _serverSettingsStatus.asStateFlow()
    private val _syncBusy = MutableStateFlow(false)
    val syncBusy = _syncBusy.asStateFlow()
    private val selectedSync = cloudCredentials.selection()
    private var syncEndpoint = selectedSync?.takeIf { it.cloud }?.endpoint.orEmpty()
    private var syncOwnerId = selectedSync?.takeIf { it.cloud }?.ownerId.orEmpty()
    private var syncProfileId = cloudCredentials.currentProfileId().takeIf { selectedSync?.cloud == true }
    private var finishingRecording = false
    private val _pendingTake = MutableStateFlow(false)
    val pendingTake = _pendingTake.asStateFlow()
    private val _takeBusy = MutableStateFlow(false)
    val takeBusy = _takeBusy.asStateFlow()
    private val _takeError = MutableStateFlow<String?>(null)
    val takeError = _takeError.asStateFlow()
    private val _takeNameLocked = MutableStateFlow(false)
    val takeNameLocked = _takeNameLocked.asStateFlow()
    private val cloudStatus = RecordingCloudStatus(application)
    private val uploadScheduler = RecordingUploadScheduler(application, cloudStatus)
    private val _recordingCloudStates = MutableStateFlow<Map<String, RecordingCloudState>>(emptyMap())
    val recordingCloudStates = _recordingCloudStates.asStateFlow()
    private var cloudJobs: List<WorkInfo> = emptyList()
    private val preparingUploads = mutableSetOf<String>()
    val recordingSyncEndpoint get() = syncEndpoint
    val cloudSyncEnabled get() = true
    val cloudSyncOwnerId get() = syncOwnerId

    val scoreViewer = com.pyano.viewer.ScoreViewerController(
        viewModelScope, com.pyano.viewer.ReadingStateStore(java.io.File(application.noBackupFilesDir, "score-reading"))
    )
    fun openScore(score: com.pyano.scores.Score) { scoreLibrary.viewerSource(score)?.let(scoreViewer::open) }
    val scoreLibrary = ScoreLibraryController(scoreCacheRoot(application.noBackupFilesDir), viewModelScope, context = application)
    private var scoreSelectionGeneration = 0

    private fun updateScoreProfile() {
        val generation = ++scoreSelectionGeneration
        val profileId = syncProfileId
        scoreViewer.clearProfile()
        scoreLibrary.select(null)
        if (profileId == null) return
        viewModelScope.launch {
            val credentials = withContext(Dispatchers.IO) { cloudCredentials.load(profileId) }
            if (generation == scoreSelectionGeneration) scoreLibrary.select(credentials?.let { ScoreProfile(profileId, it) })
        }
    }

    suspend fun loadRecordingSyncToken(cloud: Boolean): String {
        if (!cloud) return ""
        val profileId = syncProfileId ?: return ""
        val credentials = withContext(Dispatchers.IO) { cloudCredentials.load(profileId) }
        return if (profileId == syncProfileId) credentials?.token.orEmpty() else ""
    }

    fun configureRecordingSync(endpoint: String, ownerId: String, token: String, cloud: Boolean, onComplete: (Boolean) -> Unit) {
        if (_syncBusy.value) { onComplete(false); return }
        try {
            require(cloud) { "Cloud settings are required" }
            com.pyano.sync.validateCloudSettings(endpoint, ownerId, token)
        } catch (error: IllegalArgumentException) {
            _serverSettingsStatus.value = error.message ?: "Check the Cloud address, owner ID, and device token."
            onComplete(false)
            return
        }
        _syncBusy.value = true
        viewModelScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    require(cloud) { "Cloud settings are required" }
                    cloudCredentials.save(endpoint, ownerId, token.trim())
                }
                syncProfileId = profile
                syncEndpoint = endpoint.trim().trimEnd('/')
                syncOwnerId = ownerId.trim()
                _serverSettingsStatus.value = "Cloud configured"
                updateScoreProfile()
                resumeAutomaticUploads()
                onComplete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (error: com.pyano.sync.CloudSyncException) {
                _serverSettingsStatus.value = com.pyano.sync.boundedUploadError(
                    error.message ?: "Could not verify Cloud device credentials. Check Server settings and retry.")
                onComplete(false)
            } catch (_: Exception) {
                _serverSettingsStatus.value = "Could not save settings. Check the provisioning values and try again."
                onComplete(false)
            } finally {
                _syncBusy.value = false
            }
        }
    }

    fun uploadRecording(recording: RecordingInfo) {
        if (_syncBusy.value || _isAudioRecording.value) return
        enqueueCloudUpload(recording)
    }

    private fun enqueueCloudUpload(recording: RecordingInfo, profileOverride: String? = null) {
        val profileId = profileOverride ?: syncProfileId
        if (profileId == null) {
            localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                "Configure Cloud in Server settings before uploading this recording.")
            updateRecordingCloudStates()
            return
        }
        if (!preparingUploads.add(recording.path)) return
        viewModelScope.launch {
          try {
            uploadScheduler.enqueue(recording, profileId)
            _syncStatus.value = "Queued ${recording.name} for private cloud upload"
          } catch (e: CancellationException) { throw e }
          catch (error: com.pyano.sync.RecordingOwnerUnverified) {
            localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(), checkNotNull(error.message))
          }
          catch (error: com.pyano.sync.CloudSyncException) {
            localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                com.pyano.sync.boundedUploadError(error.message ?: "Recording upload was rejected. Review its Cloud settings."))
          }
          catch (_: CloudProfileUnavailable) {
            localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                "Reconfigure Cloud with a valid owner and device token, then retry the upload.")
          }
          catch (_: Exception) {
            localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                "Cloud upload could not be queued. Tap the recording’s cloud icon to retry.")
          }
          finally { preparingUploads.remove(recording.path); updateRecordingCloudStates() }
        }
    }

    private var cloudStateGeneration = 0
    private var cloudStateUpdate: kotlinx.coroutines.Job? = null

    private fun updateRecordingCloudStates() {
        val generation = ++cloudStateGeneration
        val recordings = _savedRecordings.value
        val cloudJobs = this.cloudJobs
        val titleJobs = this.titleJobs
        val localWarning = localTitleWarning
        cloudStateUpdate?.cancel()
        cloudStateUpdate = viewModelScope.launch {
            val (statesByPath, warning) = withContext(Dispatchers.IO) {
                val statesByPath = recordings.associate { recording ->
                    val id = cloudStatus.existingId(recording.path)
                    val states = cloudJobs.filter { RecordingCloudStatus.TAG_PREFIX + id in it.tags }.map { it.state }
                    recording.path to recordingCloudState(states, id != null && cloudStatus.synced(id), cloudStatus.hasHistoricalReceipt(recording.path))
                }
                val warning = localWarning?.takeUnless { cloudStatus.warningDismissed(it.jobId) } ?: titleJobs.firstOrNull {
                    it.outputData.getString(CloudUploadWorker.KEY_ERROR) != null && !cloudStatus.warningDismissed(it.id.toString()) &&
                        titleJobs.none { job -> job.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) &&
                            job.tags.intersect(it.tags).any { tag -> tag.startsWith(RecordingCloudStatus.TAG_PREFIX) } } &&
                        recordings.any { recording ->
                            val id = cloudStatus.existingId(recording.path)
                            RecordingCloudStatus.TAG_PREFIX + id in it.tags && id != null &&
                                com.pyano.sync.RecordingTitleSync(getApplication<Application>()).desired(id) != null
                        }
                }?.let { RecordingUploadWarning(it.id.toString(), it.outputData.getString(CloudUploadWorker.KEY_ERROR) ?: "Cloud title update failed") }
                    ?: recordings.firstNotNullOfOrNull { recording ->
                    val recordingId = cloudStatus.existingId(recording.path)
                    val tag = RecordingCloudStatus.TAG_PREFIX + recordingId
                    val titleEnqueueError = cloudJobs.firstOrNull {
                        tag in it.tags && it.state == WorkInfo.State.SUCCEEDED && !cloudStatus.warningDismissed(it.id.toString()) &&
                            it.outputData.getString(CloudUploadWorker.KEY_TITLE_ERROR) != null && recordingId != null &&
                            com.pyano.sync.RecordingTitleSync(getApplication<Application>()).desired(recordingId) != null &&
                            titleJobs.none { title -> tag in title.tags && title.state in
                                setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) }
                    }
                    if (titleEnqueueError != null) return@firstNotNullOfOrNull RecordingUploadWarning(titleEnqueueError.id.toString(),
                        checkNotNull(titleEnqueueError.outputData.getString(CloudUploadWorker.KEY_TITLE_ERROR)))
                    val job = cloudJobs.firstOrNull {
                        tag in it.tags && it.state in setOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED) && !cloudStatus.warningDismissed(it.id.toString())
                    } ?: return@firstNotNullOfOrNull null
                    recordingUploadError(statesByPath[recording.path], job.outputData.getString(CloudUploadWorker.KEY_ERROR))
                        ?.let { RecordingUploadWarning(job.id.toString(), it) }
                }
                statesByPath to warning
            }
            if (generation == cloudStateGeneration) {
                _recordingCloudStates.value = statesByPath
                _uploadError.value = warning
            }
        }
    }

    fun clearUploadWarning(warning: RecordingUploadWarning) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cloudStatus.dismissWarning(warning.jobId) }
            updateRecordingCloudStates()
        }
    }

    private fun resumeAutomaticUploads() {
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                val titles = com.pyano.sync.RecordingTitleSync(getApplication<Application>())
                audioRecorder.getSavedRecordings().forEach { cloudStatus.migrateLegacyIdentity(it.path, titles) }
                val unrecovered = titles.recoverRenames { old, new -> cloudStatus.rename(old, new) }
                if (unrecovered.isNotEmpty()) {
                    localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                        "Some recording renames need recovery. Check their local WAV and MIDI files before retrying.")
                }
                titles.pending().forEach {
                    try { com.pyano.sync.queueRecordingTitle(getApplication(), it) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                            "Cloud name update could not be queued. Retry the recording rename when Cloud is available.")
                    }
                }
                if (cloudStatus.legacyPendingPaths().isNotEmpty()) {
                    localTitleWarning = RecordingUploadWarning("legacy-pending-upload-review",
                        "Older pending recordings remain local because their saved file identity or Cloud owner cannot be verified. " +
                            "Keep or export these recordings locally. Unverified recordings are not uploaded automatically.")
                }
                val paths = if (audioRecorder.automaticUploadBlocked) emptySet() else cloudStatus.pendingPaths()
                val blockedRenames = titles.unresolvedRenamePaths()
                val recordings = audioRecorder.getSavedRecordings()
                cloudStatus.pendingBundleWarnings().entries.firstOrNull { (path, _) ->
                    path !in blockedRenames && recordings.any { it.path == path }
                }?.let { (path, message) ->
                    localTitleWarning = RecordingUploadWarning("pending-bundle:${cloudStatus.existingId(path) ?: path}", message)
                }
                recordings.filter { it.path in paths && it.path !in blockedRenames }.map { recording ->
                    val bound = cloudStatus.existingId(recording.path)?.let { titles.binding(it)?.profileId }
                    recording to com.pyano.sync.automaticRecordingUploadProfile(cloudStatus.pendingProfile(recording.path), bound, syncProfileId)
                }
            }
            refreshRecordings()
            pending.forEach { (recording, profile) -> enqueueCloudUpload(recording, profile) }
        }
    }

    private var localTitleWarning: RecordingUploadWarning? = null
    private var titleJobs: List<WorkInfo> = emptyList()

    private fun observeCloudUploads() {
        viewModelScope.launch {
            WorkManager.getInstance(getApplication()).getWorkInfosByTagFlow("pyano-cloud-title").collect { jobs ->
                titleJobs = jobs
                updateRecordingCloudStates()
            }
        }
        viewModelScope.launch {
            WorkManager.getInstance(getApplication()).getWorkInfosByTagFlow("pyano-cloud-upload").collect { jobs ->
                cloudJobs = jobs
                updateRecordingCloudStates()
                for (completed in jobs.filter { it.state == WorkInfo.State.SUCCEEDED }) {
                    completed.outputData.getString(CloudUploadWorker.KEY_SNAPSHOT_PATH)?.let { path ->
                        withContext(Dispatchers.IO) {
                            CloudUploadQueue(File(getApplication<Application>().filesDir, "cloud-upload-queue")).remove(File(path))
                        }
                    }
                }
                val job = jobs.maxByOrNull { cloudJobPriority(it.state) } ?: return@collect
                _syncStatus.value = when (job.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Cloud upload queued; waiting for network"
                    WorkInfo.State.RUNNING -> "Uploading recording to cloud…"
                    WorkInfo.State.SUCCEEDED -> "Cloud upload complete; awaiting admin publication"
                    WorkInfo.State.FAILED -> ""
                    WorkInfo.State.CANCELLED -> ""
                }
            }
        }
    }

    private fun cloudJobPriority(state: WorkInfo.State) = when (state) {
        WorkInfo.State.RUNNING -> 5
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> 4
        WorkInfo.State.FAILED -> 3
        WorkInfo.State.CANCELLED -> 2
        WorkInfo.State.SUCCEEDED -> 1
    }

    // --- Audio Recorder ---
    val audioRecorder = AudioRecorder(engine, application)

    private val _isAudioRecording = MutableStateFlow(false)
    val isAudioRecording = _isAudioRecording.asStateFlow()
    private val recordingCountdown = com.pyano.audio.RecordingCountdown(viewModelScope)
    val recordingCountdownSeconds = recordingCountdown.seconds
    fun cancelRecordingCountdown(owner: Any? = null) = recordingCountdown.cancel(owner)

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec = _recordingDurationSec.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<RecordingInfo>>(emptyList())
    val savedRecordings = _savedRecordings.asStateFlow()

    init {
        updateScoreProfile()
        observeCloudUploads()
        resumeAutomaticUploads()
    }


    // Mastering
    private val _masteringRecording = MutableStateFlow<RecordingInfo?>(null)
    val masteringRecording = _masteringRecording.asStateFlow()

    // Saved soundfont path
    private val savedSfPath = prefs.getString("sfPath", null)
    private val savedSfFileName = prefs.getString("sfFileName", null)
    private val savedPresetIndex = prefs.getInt("presetIndex", 0)
    private val savedMidiDeviceName = prefs.getString("midiDeviceName", null)

    private fun save(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()
    private fun save(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun save(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    private fun save(key: String, value: String) = prefs.edit().putString(key, value).apply()

    // Tab navigation
    private val _selectedTab = MutableStateFlow(prefs.getInt("selectedTab", 0))
    val selectedTab = _selectedTab.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading = _isLoading.asStateFlow()

    /** Called by every MainActivity instance; only the first call sets things up. */
    fun initialize() {
        if (initialized) return
        initialized = true
        diagnostics.start()
        viewModelScope.launch { diagnostics.usbGeneration.collect { updateOutputProblem() } }

        if (!engine.create(nativeOutputSampleRate(), _bufferSize.value)) {
            Log.e(TAG, "Failed to create engine")
            return
        }

        if (!engine.startAudio()) {
            Log.e(TAG, "Failed to start audio")
            return
        }

        // Copy the bundled GM kit idempotently and load bank 128 preset 0 before piano fonts to stabilize channel 9.
        val gmPath = engine.copySoundFontFromAssets(getApplication(), DEFAULT_SF)
        if (gmPath != null) {
            val kitPath = metronomeKitPath(java.io.File(gmPath))
            if (kitPath == null || engine.loadMetronomeDrumKit(kitPath) < 0) {
                Log.w(TAG, "Metronome kit unavailable; loading the full GM SoundFont for it")
                engine.loadMetronomeDrumKit(gmPath)
            }
        } else {
            Log.e(TAG, "Failed to stage metronome drum soundfont")
        }

        // Push saved metronome sound + volume to engine
        applyMetronomeSound()
        engine.setMetronomeVolume(_metronomeVolume.value)

        // Apply saved settings
        engine.setGain(_gain.value)
        applyReverb()
        setReverbOn(_reverbOn.value)

        // Load saved soundfont or fallback to bundled
        val context = getApplication<Application>()
        var loaded = false
        if (savedSfPath != null && java.io.File(savedSfPath).exists()) {
            val sfId = engine.loadSoundFont(savedSfPath)
            if (sfId >= 0) {
                val info = SF2MetadataReader.readFromFile(java.io.File(savedSfPath))
                _soundFontName.value = info?.name ?: savedSfFileName ?: DEFAULT_SF
                refreshPresets(sfId)
                // Restore saved preset
                if (savedPresetIndex < _presets.value.size) {
                    selectPreset(savedPresetIndex)
                } else {
                    engine.programSelect(0, sfId, 0, 0)
                    applyChannelSendDefaults(0)
                }
                loaded = true
                Log.i(TAG, "Restored saved soundfont: $savedSfPath")
            }
        }
        if (!loaded) {
            val bundledPath = engine.copySoundFontFromAssets(context, DEFAULT_SF)
            if (bundledPath != null) {
                val sfId = engine.loadSoundFont(bundledPath)
                if (sfId >= 0) {
                    engine.programSelect(0, sfId, 0, 0)
                    applyChannelSendDefaults(0)
                    refreshPresets(sfId)
                    val info = SF2MetadataReader.readFromFile(java.io.File(bundledPath))
                    _soundFontName.value = info?.name ?: DEFAULT_SF
                    Log.i(TAG, "Default soundfont loaded")
                }
            }
        }

        _isLoading.value = false

        // Scan on startup too: an already-granted permission does not trigger MainActivity to refresh the list.
        scanSoundFonts()

        // Set up MIDI device monitoring
        midiEventHandler = MidiEventHandler(engine).also {
            it.notePlayedListener = kiosk::onNotePlayed
            it.setTransposeSemitones(_transposeSemitones.value)
        }
        if (_screenKeyboardEnabled.value) _midiStatus.value = "On-screen keyboard"
        refreshMidiDevices()
        midiManager.registerCallback(
            onDeviceAdded = { info ->
                diagnostics.record(DiagEvent.Kind.Connected, "MIDI input added: ${midiManager.getDeviceName(info)}")
                refreshMidiDevices()
                // Bind a matching remembered device (or any device if none remembered) only when no connection is active.
                if (_selectedMidiDevice.value == null && !_screenKeyboardEnabled.value) {
                    val name = midiManager.getDeviceName(info)
                    if (savedMidiDeviceName == null || savedMidiDeviceName == name) {
                        connectMidiDevice(info)
                    }
                }
            },
            onDeviceRemoved = { info ->
                diagnostics.record(DiagEvent.Kind.Disconnected, "MIDI input removed: ${midiManager.getDeviceName(info)}")
                if (_selectedMidiDevice.value?.id == info.id) {
                    disconnectMidi()
                }
                refreshMidiDevices()
            }
        )

        // Auto-connect: prefer the saved device by name, else first available.
        val devices = midiManager.getDevices()
        val preferredMidi = savedMidiDeviceName?.let { saved ->
            devices.firstOrNull { midiManager.getDeviceName(it) == saved }
        } ?: devices.firstOrNull()
        if (preferredMidi != null && !_screenKeyboardEnabled.value) {
            connectMidiDevice(preferredMidi)
        }

        // Enumerate audio outputs and restore saved selection by stable key.
        refreshAudioOutputs()
        tryRebindPreferredAudio()
        registerAudioDeviceCallback()
        maxSystemMediaVolume("startup")

        // Initialize loop engine with saved/current settings
        loopEngine.setBpm(_loopBpm.value)
        loopEngine.setLoopLengthBars(_loopLengthBars.value)
        loopEngine.setTimeSigBeats(_metronomeTimeSig.value)
        loadLoops()

        // Start activity monitor polling
        viewModelScope.launch {
            var tick = 0
            var loggedXruns = 0
            while (true) {
                // Poll at 20 Hz for visible/time-critical work, including loop auto-stop; otherwise poll idle diagnostics at 1 Hz.
                val fast = uiVisible || _metronomeRunning.value || loopEngine.isPlaying ||
                    loopEngine.isRecording || _isAudioRecording.value
                if (fast) {
                    delay(50)
                    tick++
                } else {
                    delay(1000)
                    tick = (tick / AUDIO_STATS_INTERVAL_TICKS + 1) * AUDIO_STATS_INTERVAL_TICKS
                }
                if (tick % AUDIO_STATS_INTERVAL_TICKS == 0) {
                    loggedXruns = logAudioStats(loggedXruns, tick % AUDIO_STATS_HEARTBEAT_TICKS == 0)
                    pauseAudioIfIdle()
                }
                // MIDI input activity: true if event in last 200ms
                val handler = midiEventHandler
                _midiInputLevel.value = handler != null &&
                    (System.currentTimeMillis() - handler.lastEventTimeMs) < 200

                // Audio output level
                _outputLevel.value = engine.getPeakLevel()

                // Metronome beat (poll native engine for current beat index)
                if (_metronomeRunning.value) {
                    _metronomeBeat.value = engine.getMetronomeBeat()
                }

                // Recording duration
                if (_isAudioRecording.value) {
                    _recordingDurationSec.value = (audioRecorder.recordingDurationMs / 1000).toInt()
                }

                // Loop station: position + layer counts + auto-stop
                if (loopEngine.isPlaying || loopEngine.isRecording) {
                    val duration = loopEngine.loopDurationNs
                    if (loopEngine.isPlaying) {
                        _loopPosition.value = if (duration > 0) {
                            (loopEngine.currentPositionNs.toFloat() / duration).coerceIn(0f, 1f)
                        } else 0f
                    } else if (loopEngine.isRecording) {
                        // Recording-only mode: compute position from recording elapsed time
                        _loopPosition.value = if (duration > 0) {
                            ((loopEngine.getRecordingPositionNs() % duration).toFloat() / duration).coerceIn(0f, 1f)
                        } else 0f
                    }
                    _loopPlaying.value = loopEngine.isPlaying
                    _loopRecording.value = loopEngine.isRecording

                    // Auto-stop recording at loop boundary
                    if (loopEngine.checkAutoStopRecording()) {
                        midiEventHandler?.recordingListener = null
                        _loopRecording.value = false
                        _loopRecordingLayerIndex.value = -1
                        refreshLoopLayerCounts()
                        saveLoops()
                        if (loopStartedMetronome && _metronomeRunning.value) {
                            engine.stopMetronome()
                            _metronomeRunning.value = false
                            _metronomeBeat.value = 0
                            loopStartedMetronome = false
                        }
                    }
                }
            }
        }
    }

    /** Log new xruns, callbacks over [AUDIO_STATS_LOAD_WARN], or [heartbeat]; return the reported xrun count. */
    private fun logAudioStats(loggedXruns: Int, heartbeat: Boolean): Int {
        val stats = engine.getAudioStats() ?: return loggedXruns
        _audioStats.value = stats
        val newXruns = stats.xruns - loggedXruns
        val load = stats.callbackLoad
        if (newXruns > 0 || load > AUDIO_STATS_LOAD_WARN || heartbeat) {
            val msg = "xruns=${stats.xruns} (+$newXruns) buffer=${stats.bufferFrames}f " +
                "(%.1f ms) burst=${stats.burstFrames}f callback=${stats.callbackFrames}f ".format(stats.bufferMs) +
                "maxCallback=${stats.maxCallbackMicros}us load=%.0f%% cpu=${stats.maxCallbackCpu} ".format(load * 100) +
                "slowCallbacks=${stats.slowCallbacks} " +
                "peakVoices=${stats.peakVoices} rate=${stats.sampleRate} " +
                "droppedEvents=${stats.droppedEvents} lockedOut=${stats.lockedOutCallbacks} " +
                "pagesRestored=${stats.heapPagesRestored} latencyResets=${stats.latencyResets} " +
                "pinned=${stats.pinResult} paused=${stats.pausedForIdle} " +
                "lastResume=${stats.lastResumeCallMicros}us/${stats.lastResumeFirstCallbackMicros}us"
            if (newXruns > 0 || load > AUDIO_STATS_LOAD_WARN) Log.w(AUDIO_TAG, msg) else Log.i(AUDIO_TAG, msg)
        }
        return stats.xruns
    }

    /** Pause after the note wake timeout to avoid silent playback counting toward safe-volume limits; sound sources resume output. */
    private fun pauseAudioIfIdle() {
        val stats = _audioStats.value ?: return
        if (stats.pausedForIdle || stats.peakVoices > 0) return
        if (SystemClock.elapsedRealtime() - kiosk.lastNoteMs < KioskController.AWAKE_AFTER_LAST_NOTE_MS) return
        if (_metronomeRunning.value || loopEngine.isPlaying || loopEngine.isRecording || audioRecorder.isRecording) return
        engine.pauseForIdle()
    }

    /** Cache a versioned GM click subset keyed by notes to avoid keeping ~150 MB resident; selection/extractor changes regenerate it. */
    private fun metronomeKitPath(gm: java.io.File): String? {
        val keys = metronomeSounds.flatMap { listOf(it.downbeatNote, it.subbeatNote) }.toSortedSet()
        val kit = java.io.File(gm.parentFile, "metronome-kit-v1-${keys.joinToString("-")}.sf2")
        if (kit.exists() && kit.lastModified() >= gm.lastModified()) return kit.absolutePath
        return try {
            if (Sf2Subset.extract(gm, kit, bank = 128, program = 0, keys = keys, name = "Pyano Metronome Kit")) {
                Log.i(TAG, "Metronome kit written: ${kit.name} (${kit.length()} bytes)")
                kit.absolutePath
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Metronome kit extraction failed", e)
            null
        }
    }

    /** The default output's native rate, so the stream opens without resampling. */
    private fun nativeOutputSampleRate(): Int {
        val audioManager = getApplication<Application>().getSystemService(AudioManager::class.java)
        return audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }

    fun refreshAudioOutputs() {
        val context = getApplication<Application>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val outputs = mutableListOf(AudioOutput(0, "Default", 0))
        for (device in devices) {
            // The phone-call uplink and earpiece show up as outputs too; playing there is silent.
            if (device.type == AudioDeviceInfo.TYPE_TELEPHONY ||
                device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) continue
            val typeName = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                else -> "Audio Device"
            }
            val name = if (device.productName.isNotEmpty()) {
                "$typeName (${device.productName})"
            } else {
                typeName
            }
            outputs.add(AudioOutput(device.id, name, device.type))
        }
        _audioOutputs.value = outputs
        Log.i(TAG, "Found ${outputs.size} audio outputs")
        updateOutputProblem()
    }

    /** Distinguish a missing USB bus device (cable/power/hub) from Android failing to open an enumerated output. */
    private fun updateOutputProblem() {
        val key = prefs.getString("audioOutputKey", null)
        if (key == null || key == "default" || _audioOutputs.value.any { it.preferredKey == key }) {
            _outputProblem.value = null
            _outputWarning.value = outputWarningState.update(key, null)
            return
        }
        val name = key.substringAfter('|')
        val type = key.substringBefore('|').toIntOrNull()
        val isUsb = type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
        // Names are "<type> (<product>)"; the product is what the USB bus reports.
        val product = name.substringAfter('(', "").substringBeforeLast(')', "")
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        _outputProblem.value = buildString {
            when {
                !isUsb || product.isEmpty() -> append("$name is not connected.")
                diagnostics.usbDevices.value.any { it.name == product } ->
                    append("$name is on USB but Android hasn't opened it for audio. Replug just that device.")
                else -> {
                    append("$name is not on the USB bus")
                    diagnostics.lastDetachMs(product)?.let { append(" (it left at ${time.format(java.util.Date(it))})") }
                    append(". Check its power and cable, then the hub.")
                }
            }
            if (isUsb && product.isNotEmpty()) {
                val attaches = diagnostics.recentAttaches(product)
                if (attaches >= 3) append(" It reconnected $attaches times in 10 minutes: the connection is unstable.")
            }
            append(" Playing through Default meanwhile.")
        }
        _outputWarning.value = outputWarningState.update(key, _outputProblem.value)
    }

    fun dismissOutputWarning() {
        outputWarningState.dismiss()
        _outputWarning.value = null
    }

    fun setAudioOutput(output: AudioOutput) {
        _selectedAudioOutput.value = output
        save("audioOutputKey", output.preferredKey)
        engine.setAudioDevice(output.id)
        Log.i(TAG, "Set audio output: ${output.name} (id=${output.id}, key=${output.preferredKey})")

        // Use unity media volume for external outputs; leave the built-in speaker alone to avoid unexpected loudness.
        if (output.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            maxSystemMediaVolume("output=${output.name}")
        }
        updateOutputProblem()
    }

    /** Restore output by stable type+name, falling back to Default when the remembered device is absent. */
    private fun tryRebindPreferredAudio() {
        // Read each time: the startup value goes stale once the user picks another output.
        val key = prefs.getString("audioOutputKey", null) ?: _selectedAudioOutput.value?.preferredKey ?: return
        val match = _audioOutputs.value.firstOrNull { it.preferredKey == key }
        if (match != null && match.preferredKey != _selectedAudioOutput.value?.preferredKey) {
            setAudioOutput(match)
        }
    }

    private fun registerAudioDeviceCallback() {
        val audioManager = getApplication<Application>()
            .getSystemService(AudioManager::class.java)
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                recordAudioDevices(addedDevices, added = true)
                refreshAudioOutputs()
                tryRebindPreferredAudio()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                recordAudioDevices(removedDevices, added = false)
                refreshAudioOutputs()
                // If the active output went away, fall back to the Default entry.
                val current = _selectedAudioOutput.value ?: return
                if (_audioOutputs.value.none { it.preferredKey == current.preferredKey }) {
                    _audioOutputs.value.firstOrNull()?.let { fallback ->
                        _selectedAudioOutput.value = fallback
                        engine.setAudioDevice(fallback.id)
                        Log.i(TAG, "Active audio output removed, falling back to ${fallback.name}")
                    }
                }
            }
        }
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private var audioDevicesPrimed = false

    private fun recordAudioDevices(devices: Array<out AudioDeviceInfo>?, added: Boolean) {
        // The callback's first call lists every existing device; the startup line covers those.
        if (!audioDevicesPrimed) {
            audioDevicesPrimed = true
            return
        }
        devices?.filter { it.isSink && it.type in DIAGNOSED_OUTPUT_TYPES }?.forEach {
            diagnostics.record(
                if (added) DiagEvent.Kind.Connected else DiagEvent.Kind.Disconnected,
                "Audio output ${if (added) "added" else "removed"}: ${it.productName} (type ${it.type})",
            )
        }
    }

    fun midiDeviceName(info: MidiDeviceInfo): String = midiManager.getDeviceName(info)

    private fun maxSystemMediaVolume(reason: String) {
        try {
            val audioManager = getApplication<Application>()
                .getSystemService(AudioManager::class.java)
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            val actual = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.i(TAG, "Maxed media volume to $maxVol (actual=$actual) [$reason]")
            if (actual < maxVol) {
                Log.w(TAG, "Volume did not reach max — likely DnD/zen blocking setStreamVolume")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set media volume [$reason]", e)
        }
    }

    private fun refreshPresets(sfId: Int) {
        val presetList = engine.getPresets(sfId)
        _presets.value = presetList
        _selectedPresetIndex.value = 0
        Log.i(TAG, "Found ${presetList.size} presets in soundfont")
    }

    fun refreshMidiDevices() {
        _midiDevices.value = midiManager.getDevices()
    }

    fun selectScreenKeyboard() {
        disconnectMidi()
        _screenKeyboardEnabled.value = true
        _midiStatus.value = "On-screen keyboard"
        save("screenKeyboard", true)
    }

    fun screenNote(owner: Any, note: Int, down: Boolean) {
        if (!_screenKeyboardEnabled.value || note !in 0..127) return
        screenNotes.note(owner, note, down)
    }

    fun releaseScreenNotes(owner: Any) {
        screenNotes.release(owner)
    }

    fun connectMidiDevice(info: MidiDeviceInfo) {
        disconnectMidi()
        _screenKeyboardEnabled.value = false
        save("screenKeyboard", false)
        val generation = midiConnectionGeneration
        val numOutputPorts = info.outputPortCount
        val numInputPorts = info.inputPortCount
        Log.i(TAG, "MIDI device: ${midiManager.getDeviceName(info)}, outputPorts=$numOutputPorts, inputPorts=$numInputPorts")

        midiManager.openDevice(info) { device ->
            if (generation != midiConnectionGeneration) {
                device?.close()
                return@openDevice
            }
            if (device != null) {
                midiDevice = device
                _selectedMidiDevice.value = info
                val name = midiManager.getDeviceName(info)
                _midiStatus.value = name
                save("midiDeviceName", name)

                // Connect to all output ports (keyboard data comes from output ports)
                var connected = false
                for (i in 0 until numOutputPorts) {
                    val outputPort = device.openOutputPort(i)
                    if (outputPort != null) {
                        outputPort.connect(midiEventHandler)
                        Log.i(TAG, "Connected to MIDI output port $i")
                        connected = true
                    } else {
                        Log.e(TAG, "Failed to open MIDI output port $i")
                    }
                }
                if (!connected) {
                    Log.e(TAG, "No MIDI output ports could be opened")
                    _midiStatus.value = "MIDI: no output ports"
                }
            } else {
                Log.e(TAG, "Failed to open MIDI device")
                _midiStatus.value = "Failed to open device"
            }
        }
    }

    fun disconnectMidi() {
        midiConnectionGeneration++
        screenNotes.releaseAll()
        midiDevice?.close()
        midiEventHandler?.releaseAllNotes()
        midiDevice = null
        _selectedMidiDevice.value = null
        _midiStatus.value = "No MIDI device"
    }

    fun selectPreset(index: Int) {
        val presetList = _presets.value
        if (index < 0 || index >= presetList.size) return
        _selectedPresetIndex.value = index
        save("presetIndex", index)
        val preset = presetList[index]
        val sfId = engine.soundFontId
        if (sfId >= 0) {
            engine.programSelect(0, sfId, preset.bank, preset.program)
            applyChannelSendDefaults(0)
            Log.i(TAG, "Selected preset: ${preset.name} (bank=${preset.bank}, prog=${preset.program})")
        }
    }

    /** Presets reset CC91/93: restore reverb send after program changes and keep chorus send at zero. */
    private fun applyChannelSendDefaults(channel: Int) {
        engine.cc(channel, CC_REVERB_SEND, if (_reverbOn.value) REVERB_SEND_DEFAULT else 0)
        engine.cc(channel, CC_CHORUS_SEND, 0)
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
        save("selectedTab", index)
        // Refresh recordings list when switching to Recorder tab (index 3)
        if (index == 3) refreshRecordings()
    }

    fun setGain(value: Float) {
        _gain.value = value
        save("gain", value)
        engine.setGain(value)
    }

    fun setTransposeSemitones(semitones: Int) {
        val clamped = semitones.coerceIn(MidiEventHandler.MIN_TRANSPOSE, MidiEventHandler.MAX_TRANSPOSE)
        midiEventHandler?.setTransposeSemitones(clamped)
        _transposeSemitones.value = clamped
        save("transposeSemitones", clamped)
    }

    fun setBufferSize(value: Int) {
        _bufferSize.value = value
        save("bufferFrames", value)
        engine.setBufferSize(value)
    }

    /** Disable reverb processing and CC91 when off; restore melodic sends when on, keeping metronome dry. */
    fun setReverbOn(on: Boolean) {
        _reverbOn.value = on
        save("reverbOn", on)
        engine.setReverbOn(on)
        for (channel in 0 until 16) {
            val send = if (on && channel != METRONOME_CHANNEL) REVERB_SEND_DEFAULT else 0
            engine.cc(channel, CC_REVERB_SEND, send)
        }
    }

    fun setReverbRoom(value: Float) {
        _reverbRoom.value = value
        save("reverbRoom", value)
        applyReverb()
    }

    fun setReverbDamp(value: Float) {
        _reverbDamp.value = value
        save("reverbDamp", value)
        applyReverb()
    }

    fun setReverbWidth(value: Float) {
        _reverbWidth.value = value
        save("reverbWidth", value)
        applyReverb()
    }

    fun setReverbLevel(value: Float) {
        _reverbLevel.value = value
        save("reverbLevel", value)
        applyReverb()
    }

    private fun applyReverb() {
        engine.setReverb(_reverbRoom.value, _reverbDamp.value, _reverbWidth.value, _reverbLevel.value)
    }

    // --- Metronome ---

    fun setMetronomeBpm(bpm: Int) {
        val clamped = bpm.coerceIn(LoopEngine.BPM_MIN, LoopEngine.BPM_MAX)
        _metronomeBpm.value = clamped
        save("metronomeBpm", clamped)
        engine.setMetronomeBpm(clamped)
        // Sync to loop engine if BPM sync is enabled
        if (_loopSyncBpm.value && _loopBpm.value != clamped) {
            _loopBpm.value = clamped
            save("loopBpm", clamped)
            loopEngine.setBpm(clamped)
        }
    }

    fun setMetronomeTimeSig(beats: Int) {
        val clamped = beats.coerceIn(1, 12)
        _metronomeTimeSig.value = clamped
        save("metronomeTimeSig", clamped)
        engine.setMetronomeTimeSig(clamped)
        loopEngine.setTimeSigBeats(clamped)
    }

    private val tapTimestamps = mutableListOf<Long>()

    /** Record a tap tempo event. Averages the last 4 tap intervals to compute BPM. Discards taps with >2s gaps (user paused). */
    fun tapTempo() {
        val now = System.currentTimeMillis()
        tapTimestamps.add(now)
        if (tapTimestamps.size > 4) tapTimestamps.removeAt(0)
        if (tapTimestamps.size >= 2) {
            val intervals = tapTimestamps.zipWithNext { a, b -> b - a }
            if (intervals.all { it in 150..2000 }) {
                val avgMs = intervals.average()
                val tapBpm = (60000.0 / avgMs).toInt().coerceIn(LoopEngine.BPM_MIN, LoopEngine.BPM_MAX)
                setMetronomeBpm(tapBpm)
            }
        }
    }

    fun toggleMetronome() {
        val newState = !_metronomeRunning.value
        _metronomeRunning.value = newState
        if (newState) {
            // Push current settings to engine before starting
            engine.setMetronomeBpm(_metronomeBpm.value)
            engine.setMetronomeTimeSig(_metronomeTimeSig.value)
            applyMetronomeSound()
            engine.setMetronomeVolume(_metronomeVolume.value)
            engine.startMetronome()
        } else {
            engine.stopMetronome()
            _metronomeBeat.value = 0
        }
    }

    private fun applyMetronomeSound() {
        val idx = _metronomeClickType.value.coerceIn(0, metronomeSounds.lastIndex)
        val sound = metronomeSounds[idx]
        engine.setMetronomeClickNotes(sound.downbeatNote, sound.subbeatNote)
    }

    fun setMetronomeClickType(type: Int) {
        val clamped = type.coerceIn(0, metronomeSounds.lastIndex)
        _metronomeClickType.value = clamped
        save("metronomeClickType", clamped)
        applyMetronomeSound()
    }

    fun setMetronomeVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 2f)
        _metronomeVolume.value = clamped
        save("metronomeVolume", clamped)
        engine.setMetronomeVolume(clamped)
    }

    // --- Loop Station ---

    fun startLoopRecording() {
        startLoopRecordingLayer(-1)
    }

    fun startLoopRecordingLayer(layerIndex: Int) {
        if (loopEngine.isRecording) return

        // Always start metronome for count-in
        val metronomeWasRunning = _metronomeRunning.value
        if (!metronomeWasRunning) {
            engine.setMetronomeBpm(_loopBpm.value)
            engine.setMetronomeTimeSig(loopEngine.timeSigBeats)
            applyMetronomeSound()
            engine.setMetronomeVolume(_metronomeVolume.value)
            engine.startMetronome()
            _metronomeRunning.value = true
            loopStartedMetronome = true
        }

        // Count-in: wait one bar, then start recording + playback
        val countInDurationMs = (loopEngine.timeSigBeats.toLong() * 60_000L) / _loopBpm.value
        viewModelScope.launch {
            delay(countInDurationMs)
            // Start playback of existing layers so user hears them while recording
            if (!loopEngine.isPlaying && loopEngine.hasAnyEvents()) {
                loopEngine.startPlayback(viewModelScope)
                _loopPlaying.value = true
            }
            // Stop metronome after count-in ONLY if loop metronome toggle is off
            if (!_loopMetronome.value && loopStartedMetronome && _metronomeRunning.value) {
                engine.stopMetronome()
                _metronomeRunning.value = false
                _metronomeBeat.value = 0
                loopStartedMetronome = false
            }
            doStartRecording(layerIndex)
        }
    }

    private fun doStartRecording(layerIndex: Int = -1) {
        val idx = if (layerIndex >= 0) {
            loopEngine.startRecordingOnLayer(layerIndex)
        } else {
            loopEngine.startRecording()
        }
        if (idx >= 0) {
            // Wire listener AFTER isRecording is true so no early events are dropped
            midiEventHandler?.recordingListener = object : MidiRecordingListener {
                override fun onMidiEvent(channel: Int, type: MidiEventType, note: Int, velocity: Int) {
                    loopEngine.recordEvent(channel, type, note, velocity)
                }
            }
            _loopRecording.value = true
            _loopRecordingLayerIndex.value = idx
            refreshLoopLayerCounts()
        }
    }

    fun stopLoopRecording() {
        loopEngine.stopRecording()
        midiEventHandler?.recordingListener = null
        _loopRecording.value = false
        _loopRecordingLayerIndex.value = -1
        refreshLoopLayerCounts()
        saveLoops()
        // Stop metronome if we started it for loop recording
        if (loopStartedMetronome && _metronomeRunning.value) {
            engine.stopMetronome()
            _metronomeRunning.value = false
            _metronomeBeat.value = 0
            loopStartedMetronome = false
        }
    }

    fun toggleLoopPlayback() {
        if (loopEngine.isPlaying) {
            loopEngine.stopPlayback()
            _loopPlaying.value = false
            _loopPosition.value = 0f
        } else {
            loopEngine.startPlayback(viewModelScope)
            _loopPlaying.value = true
        }
    }

    fun toggleLoopLayerMuted(index: Int) {
        loopEngine.toggleLayerMuted(index)
        _loopLayerMuted.value = List(LoopEngine.MAX_LAYERS) { loopEngine.isLayerMuted(it) }
    }

    fun clearLoopLayer(index: Int) {
        loopEngine.clearLayer(index)
        refreshLoopLayerCounts()
        saveLoops()
    }

    fun clearAllLoops() {
        val wasPlaying = loopEngine.isPlaying
        val wasRecording = loopEngine.isRecording
        if (wasRecording) stopLoopRecording()
        if (wasPlaying) {
            loopEngine.stopPlayback()
            _loopPlaying.value = false
            _loopPosition.value = 0f
        }
        loopEngine.clearAll()
        refreshLoopLayerCounts()
        saveLoops()
    }

    fun setLoopBpm(bpm: Int) {
        val clamped = bpm.coerceIn(LoopEngine.BPM_MIN, LoopEngine.BPM_MAX)
        _loopBpm.value = clamped
        save("loopBpm", clamped)
        loopEngine.setBpm(clamped)
        // Sync to metronome if enabled
        if (_loopSyncBpm.value) {
            setMetronomeBpm(clamped)
        }
    }

    fun setLoopLengthBars(bars: Int) {
        _loopLengthBars.value = bars
        save("loopLengthBars", bars)
        loopEngine.setLoopLengthBars(bars)
    }

    fun toggleLoopCountIn() {
        val newVal = !_loopCountIn.value
        _loopCountIn.value = newVal
        save("loopCountIn", newVal)
    }

    fun toggleLoopMetronome() {
        val newVal = !_loopMetronome.value
        _loopMetronome.value = newVal
        save("loopMetronome", newVal)
    }

    fun toggleLoopSyncBpm() {
        val newVal = !_loopSyncBpm.value
        _loopSyncBpm.value = newVal
        save("loopSyncBpm", newVal)
        if (newVal) {
            // Sync loop BPM from metronome
            setLoopBpm(_metronomeBpm.value)
        }
    }

    private fun refreshLoopLayerCounts() {
        val counts = List(LoopEngine.MAX_LAYERS) { loopEngine.getLayerEventCount(it) }
        _loopLayerCounts.value = counts
        _loopHasAnyEvents.value = counts.any { it > 0 }
        _loopRecordingLayerIndex.value = loopEngine.recordingLayerIndex
        _loopLayerMuted.value = List(LoopEngine.MAX_LAYERS) { loopEngine.isLayerMuted(it) }
        _loopLayerNames.value = List(LoopEngine.MAX_LAYERS) { loopEngine.getLayerName(it) }
    }

    fun renameLoopLayer(index: Int, name: String) {
        loopEngine.setLayerName(index, name)
        _loopLayerNames.value = List(LoopEngine.MAX_LAYERS) { loopEngine.getLayerName(it) }
        saveLoops()
    }

    fun saveLoops() {
        val context = getApplication<Application>()
        val dir = java.io.File(context.filesDir, "loops")
        dir.mkdirs()
        val file = java.io.File(dir, "loops.json")
        try {
            val root = org.json.JSONObject()
            root.put("bpm", loopEngine.bpm)
            root.put("timeSigBeats", loopEngine.timeSigBeats)
            root.put("loopLengthBars", loopEngine.loopLengthBars)
            val layersArr = org.json.JSONArray()
            for (i in 0 until LoopEngine.MAX_LAYERS) {
                val layerObj = org.json.JSONObject()
                layerObj.put("name", loopEngine.getLayerName(i))
                layerObj.put("muted", loopEngine.isLayerMuted(i))
                val events = loopEngine.getLayerEvents(i)
                val eventsArr = org.json.JSONArray()
                for (e in events) {
                    val ev = org.json.JSONObject()
                    ev.put("o", e.offsetNs)
                    ev.put("c", e.channel)
                    ev.put("t", e.type.ordinal)
                    ev.put("n", e.note)
                    ev.put("v", e.velocity)
                    eventsArr.put(ev)
                }
                layerObj.put("events", eventsArr)
                layersArr.put(layerObj)
            }
            root.put("layers", layersArr)
            file.writeText(root.toString())
            Log.i(TAG, "Loops saved (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save loops", e)
        }
    }

    private fun loadLoops() {
        val context = getApplication<Application>()
        val file = java.io.File(context.filesDir, "loops/loops.json")
        if (!file.exists()) return
        try {
            val root = org.json.JSONObject(file.readText())
            val bpm = root.optInt("bpm", 120)
            val timeSig = root.optInt("timeSigBeats", 4)
            val bars = root.optInt("loopLengthBars", 4)
            loopEngine.setBpm(bpm)
            loopEngine.setTimeSigBeats(timeSig)
            loopEngine.setLoopLengthBars(bars)
            _loopBpm.value = bpm
            _loopLengthBars.value = bars
            val layersArr = root.optJSONArray("layers") ?: return
            for (i in 0 until minOf(layersArr.length(), LoopEngine.MAX_LAYERS)) {
                val layerObj = layersArr.getJSONObject(i)
                loopEngine.setLayerName(i, layerObj.optString("name", "Layer ${i + 1}"))
                if (layerObj.optBoolean("muted", false)) loopEngine.toggleLayerMuted(i)
                val eventsArr = layerObj.optJSONArray("events") ?: continue
                val events = mutableListOf<TimestampedMidiEvent>()
                for (j in 0 until eventsArr.length()) {
                    val ev = eventsArr.getJSONObject(j)
                    events.add(TimestampedMidiEvent(
                        offsetNs = ev.getLong("o"),
                        channel = ev.getInt("c"),
                        type = MidiEventType.entries[ev.getInt("t")],
                        note = ev.getInt("n"),
                        velocity = ev.getInt("v"),
                    ))
                }
                loopEngine.setLayerEvents(i, events)
            }
            refreshLoopLayerCounts()
            Log.i(TAG, "Loops loaded from ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load loops", e)
        }
    }

    // --- Audio Recorder ---

    var suggestedTakeName: String = ""
        private set

    fun startAudioRecording(scoreTitle: String? = null, owner: Any? = null) {
        if (!canStartAudioRecording()) return
        recordingCountdown.start(owner) {
            if (canStartAudioRecording()) {
                _takeNameLocked.value = false
                _takeError.value = null
                suggestedTakeName = com.pyano.audio.RecordingNames.suggestion(scoreTitle)
                audioRecorder.startRecording(viewModelScope)
                _isAudioRecording.value = true
                _recordingDurationSec.value = 0
            }
        }
    }

    private fun canStartAudioRecording() =
        !_isAudioRecording.value && !_syncBusy.value && !finishingRecording && !_pendingTake.value && !_takeBusy.value

    fun stopAudioRecording() {
        cancelRecordingCountdown()
        if (!audioRecorder.isRecording || finishingRecording) return
        finishingRecording = true
        _takeError.value = null
        viewModelScope.launch {
            try {
                _pendingTake.value = audioRecorder.finishTake()
                if (!_pendingTake.value) localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                    "No audio was captured. Check the audio input and record again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pendingTake.value = audioRecorder.hasPendingTake
                _takeError.value = "Could not finish this take. ${e.message.orEmpty().take(240)} Retry Save or Discard."
            } finally {
                finishingRecording = false
                _isAudioRecording.value = false
                _recordingDurationSec.value = 0
                refreshRecordings()
            }
        }
    }

    fun resolveTake(save: Boolean, name: String? = null) {
        if (!_pendingTake.value || _takeBusy.value) return
        _takeBusy.value = true
        _takeError.value = null
        viewModelScope.launch {
            try {
                if (save) {
                    val result = saveAndScheduleTake(
                        { beforePublish -> audioRecorder.saveTake(name, beforePublish) },
                        cloudStatus, uploadScheduler, syncProfileId, onSaved = ::openMastering
                    )
                    _pendingTake.value = false
                    _syncStatus.value = result.message
                    result.warning?.let { localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(), it) }
                    refreshRecordings()
                } else {
                    audioRecorder.discardTake()
                    _pendingTake.value = false
                    _syncStatus.value = "Take discarded"
                }
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) {
                _takeError.value = "Could not ${if (save) "save" else "discard"} this take. ${error.message.orEmpty().take(240)} Retry Save or Discard."
            }
            finally {
                try { _takeNameLocked.value = withContext(Dispatchers.IO) { audioRecorder.pendingTakeNameLocked } }
                finally { _takeBusy.value = false }
            }
        }
    }

    private var recordingRefreshGeneration = 0

    fun recoverSavedTakes() {
        if (_syncBusy.value || _takeBusy.value || _pendingTake.value || _isAudioRecording.value) return
        _syncBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { audioRecorder.recoverSavedTakes(true, cloudStatus::retargetPending) }
                if (localTitleWarning?.jobId?.startsWith("take-publication-recovery:") == true) localTitleWarning = null
                resumeAutomaticUploads()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                localTitleWarning = RecordingUploadWarning("take-publication-recovery:${audioRecorder.publicationRecoveryKey ?: "unknown"}",
                    "The interrupted take could not be recovered. Its files were kept; retry Recover after checking available storage.")
            } finally {
                _syncBusy.value = false
                refreshRecordings()
            }
        }
    }

    fun refreshRecordings() {
        val generation = ++recordingRefreshGeneration
        viewModelScope.launch {
            val (recordings, recoveryWarning, recoveryKey) = withContext(Dispatchers.IO) {
                val records = audioRecorder.getSavedRecordings()
                Triple(records, audioRecorder.publicationRecoveryWarning, audioRecorder.publicationRecoveryKey)
            }
            if (generation != recordingRefreshGeneration) return@launch
            _savedRecordings.value = recordings
            _takeRecoveryAvailable.value = recoveryWarning != null
            recoveryWarning?.let { localTitleWarning = RecordingUploadWarning("take-publication-recovery:${recoveryKey ?: "unknown"}", it) }
            updateRecordingCloudStates()
        }
    }

    fun renameRecording(path: String, newName: String): Boolean {
        if (_syncBusy.value || _takeBusy.value || !preparingUploads.add(path)) {
            localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                "The recording name was not changed. Wait for the current recording operation to finish, then retry the rename.")
            updateRecordingCloudStates()
            return false
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    require(newName.isNotBlank() && newName.length <= 200) { "Names must contain 1–200 characters" }
                    check(!audioRecorder.publicationReserves(path)) { "Use Recover before renaming a recording involved in interrupted take publication" }
                    val titles = com.pyano.sync.RecordingTitleSync(getApplication<Application>())
                    cloudStatus.migrateLegacyIdentity(path, titles)
                    if (path !in cloudStatus.legacyPendingPaths()) cloudStatus.requirePendingBundle(path)
                    val id = cloudStatus.id(path)
                    val credentials = com.pyano.sync.CloudCredentialsStore(getApplication())
                    val pendingProfile = cloudStatus.pendingProfile(path)
                    val boundProfile = titles.binding(id)?.profileId
                    val unverifiedOwner = titles.unknownOwner(id) || cloudStatus.unverifiedOwnerOrigin(id)
                    val hasCloudHistory = unverifiedOwner || if (pendingProfile != null || boundProfile != null) false else {
                        cloudStatus.synced(id) || WorkManager.getInstance(getApplication())
                            .getWorkInfosByTag(RecordingCloudStatus.TAG_PREFIX + id).get().any { "pyano-cloud-upload" in it.tags } ||
                            com.pyano.sync.CloudUploadQueue(File(getApplication<Application>().filesDir, "cloud-upload-queue"))
                                .hasLegacySnapshot(File(path), credentials.knownProfileIds())
                    }
                    val profile = if (unverifiedOwner) null else com.pyano.sync.recordingRenameProfile(pendingProfile, boundProfile) { first, second ->
                        credentials.load(first)?.let { a -> credentials.load(second)?.let { b ->
                            com.pyano.sync.sameCloudOwner(a, b) } } == true
                    }
                    if (profile == null && hasCloudHistory) titles.markUnknownOwner(id)
                    val newBinding = if (profile != null && titles.binding(id) == null) {
                        val wav = File(path)
                        val remote = com.pyano.sync.CloudSyncClient(getApplication<Application>().cacheDir).stableId(
                            wav, com.pyano.audio.RecordingBundleFiles.midi(wav).takeIf { it.isFile })
                        com.pyano.sync.RecordingTitleBinding(profile, remote, wav.nameWithoutExtension)
                    } else null
                    titles.renameLocally(id, File(path), newName.trim(), newBinding) { old, new -> cloudStatus.rename(old, new) }
                    if (profile == null && hasCloudHistory) {
                        error("Saved locally. Cloud owner could not be verified for this older recording.")
                    }
                    if (profile != null) {
                        com.pyano.sync.queueRecordingTitle(getApplication(), id)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                    "Recording name update: ${error.message ?: "Cloud sync could not be queued. Retry the rename."}")
            }
            finally { preparingUploads.remove(path); refreshRecordings() }
        }
        return true
    }

    fun deleteRecording(path: String) {
        if (_syncBusy.value || _takeBusy.value || !preparingUploads.add(path)) return
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    if (path in com.pyano.sync.RecordingTitleSync(getApplication<Application>()).unresolvedRenamePaths()) false
                    else { audioRecorder.deleteRecording(path); cloudStatus.delete(path); true }
                }
                if (!deleted) localTitleWarning = RecordingUploadWarning("rename-recovery:$path",
                    "This recording's rename needs recovery before deletion. Its WAV and MIDI files are kept.")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                localTitleWarning = RecordingUploadWarning(java.util.UUID.randomUUID().toString(),
                    "Delete failed: ${error.message ?: "The recording could not be removed."} Check the recording files before retrying.")
            } finally { preparingUploads.remove(path); refreshRecordings() }
        }
    }

    fun openMastering(recording: RecordingInfo) {
        _masteringRecording.value = recording
    }

    fun closeMastering() {
        _masteringRecording.value = null
    }

    fun scanSoundFonts() {
        val fonts = mutableListOf<SF2Info>()

        // Bundled soundfont from internal storage
        val context = getApplication<Application>()
        val bundledFile = java.io.File(context.filesDir, "soundfonts/$DEFAULT_SF")
        if (bundledFile.exists()) {
            SF2MetadataReader.readFromFile(bundledFile, isBundled = true)?.let { fonts.add(it) }
        }

        // Scan Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        Log.i(TAG, "Scanning Downloads: ${downloadsDir.absolutePath}, exists=${downloadsDir.exists()}, canRead=${downloadsDir.canRead()}")
        if (downloadsDir.exists() && downloadsDir.canRead()) {
            downloadsDir.listFiles()?.filter {
                it.extension.equals("sf2", ignoreCase = true)
            }?.forEach { file ->
                SF2MetadataReader.readFromFile(file)?.let { fonts.add(it) }
            }
        }

        // Sort: bundled first, then alphabetical by name
        fonts.sortWith(compareByDescending<SF2Info> { it.isBundled }.thenBy { it.name.lowercase() })

        _availableSoundFonts.value = fonts
        refreshFavorites()
        Log.i(TAG, "Scanned ${fonts.size} soundfonts")
    }

    fun loadSoundFontByPath(path: String, fileName: String) {
        _isLoading.value = true
        Thread {
            val sfId = engine.loadSoundFont(path)
            if (sfId >= 0) {
                engine.programSelect(0, sfId, 0, 0)
                applyChannelSendDefaults(0)
                val info = SF2MetadataReader.readFromFile(java.io.File(path))
                _soundFontName.value = info?.name ?: fileName.removeSuffix(".sf2")
                refreshPresets(sfId)
                save("sfPath", path)
                save("sfFileName", fileName)
                save("presetIndex", 0)
            }
            _isLoading.value = false
        }.start()
    }

    fun importSoundFont(uri: Uri) {
        val context = getApplication<Application>()
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.sf2"
        val inputStream = context.contentResolver.openInputStream(uri) ?: return

        _isLoading.value = true
        Thread {
            val path = engine.copySoundFontFromUri(context, inputStream, fileName)
            if (path != null) {
                val sfId = engine.loadSoundFont(path)
                if (sfId >= 0) {
                    engine.programSelect(0, sfId, 0, 0)
                    applyChannelSendDefaults(0)
                    val info = SF2MetadataReader.readFromFile(java.io.File(path))
                    _soundFontName.value = info?.name ?: fileName.removeSuffix(".sf2")
                    refreshPresets(sfId)
                }
            }
            _isLoading.value = false
            scanSoundFonts()
        }.start()
    }

    fun deleteSoundFont(info: SF2Info) {
        if (info.isBundled) return
        val file = java.io.File(info.path)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Deleted soundfont: ${info.path}")
        }
        scanSoundFonts()
    }

    fun toggleFavoriteSoundFont(sf: SF2Info) {
        val paths = getFavoritePaths().toMutableList()
        if (sf.path in paths) {
            paths.remove(sf.path)
        } else {
            if (paths.size >= 4) return
            paths.add(sf.path)
        }
        save("favoriteSfPaths", paths.joinToString("\n"))
        refreshFavorites()
    }

    fun isFavoriteSoundFont(path: String): Boolean = path in getFavoritePaths()

    private fun getFavoritePaths(): List<String> {
        val raw = prefs.getString("favoriteSfPaths", null) ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }
    }

    private fun refreshFavorites() {
        val paths = getFavoritePaths()
        val available = _availableSoundFonts.value.associateBy { it.path }
        _favoriteSoundFonts.value = paths.mapNotNull { available[it] }
    }

    override fun onCleared() {
        cancelRecordingCountdown()
        disconnectMidi()
        scoreViewer.close()
        super.onCleared()
        if (audioRecorder.isRecording) {
            audioRecorder.cancelRecording()
            engine.stopRecording()
        }
        engine.midiCapture.cancel()
        loopEngine.stopPlayback()
        midiEventHandler?.recordingListener = null
        engine.stopMetronome()
        kiosk.release()
        diagnostics.stop()
        midiManager.destroy()
        audioDeviceCallback?.let { cb ->
            getApplication<Application>()
                .getSystemService(AudioManager::class.java)
                .unregisterAudioDeviceCallback(cb)
        }
        audioDeviceCallback = null
        engine.stopAudio()
        engine.destroy()
    }
}
