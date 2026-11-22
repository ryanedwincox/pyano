// Concern: renders workstation navigation | Non-concern: tab contents | IO: (selected tab) -> app scaffold
package com.pyano.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pyano.PyanoViewModel

/** Tab definitions for top-level navigation. Order matches persisted index. */
private enum class AppTab(val title: String) {
    Synth("Synth"),
    Metronome("Metronome"),
    Loops("Loops"),
    Recorder("Recorder"),
    Scores("Scores"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PyanoApp(viewModel: PyanoViewModel, onOpenApps: (() -> Unit)? = null) {
    val pendingTake by viewModel.pendingTake.collectAsState()
    val takeBusy by viewModel.takeBusy.collectAsState()
    val takeError by viewModel.takeError.collectAsState()
    val takeNameLocked by viewModel.takeNameLocked.collectAsState()
    if (pendingTake) SaveTakeFlow(
        takeBusy, viewModel.suggestedTakeName, { viewModel.resolveTake(true, it) }, { viewModel.resolveTake(false) },
        takeError, retryNameLocked = takeNameLocked
    )
    val viewerState by viewModel.scoreViewer.state.collectAsState()
    val screenKeyboard by viewModel.screenKeyboardEnabled.collectAsState()
    val keyboardOwner = remember(viewModel) { Any() }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (viewerState.source != null) {
                val recording by viewModel.isAudioRecording.collectAsState()
                val countdown by viewModel.recordingCountdownSeconds.collectAsState()
                val recordingOwner = recordingCountdownOwner(viewModel)
                val syncBusy by viewModel.syncBusy.collectAsState()
                ScoreViewerScreen(viewModel.scoreViewer, recording, !pendingTake && !takeBusy && !syncBusy, countdown) {
                    if (recording) viewModel.stopAudioRecording()
                    else viewModel.startAudioRecording(viewerState.source?.score?.title, recordingOwner)
                }
            }
            else WorkstationTabs(viewModel, onOpenApps)
        }
        if (screenKeyboard) OnScreenKeyboard(
            { note, down -> viewModel.screenNote(keyboardOwner, note, down) },
            { viewModel.releaseScreenNotes(keyboardOwner) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkstationTabs(viewModel: PyanoViewModel, onOpenApps: (() -> Unit)?) {
    // Clamped: a saved index can point past the end (Devices used to be a tab).
    val selectedTab = viewModel.selectedTab.collectAsState().value
        .takeIf { it in AppTab.entries.indices } ?: AppTab.Synth.ordinal
    val outputProblem by viewModel.outputWarning.collectAsState()
    var showDevices by remember { mutableStateOf(false) }
    var showServerSettings by rememberSaveable(selectedTab) { mutableStateOf(false) }
    if (showServerSettings) {
        val syncBusy by viewModel.syncBusy.collectAsState()
        val serverSettingsStatus by viewModel.serverSettingsStatus.collectAsState()
        ServerSettingsDialog(
            endpoint = viewModel.recordingSyncEndpoint,
            cloud = viewModel.cloudSyncEnabled,
            ownerId = viewModel.cloudSyncOwnerId,
            status = serverSettingsStatus,
            busy = syncBusy,
            onConfigure = viewModel::configureRecordingSync,
            onDismiss = { showServerSettings = false },
            loadToken = viewModel::loadRecordingSyncToken,
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Pyano") },
                    actions = {
                        TextButton(onClick = { showDevices = true }) {
                            Text(
                                "Devices",
                                color = if (outputProblem != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Pyano can be the home app; this reaches the regular launcher.
                        if (onOpenApps != null) {
                            TextButton(onClick = onOpenApps) { Text("Apps") }
                        }
                        Image(
                            painter = painterResource(id = com.pyano.R.drawable.ic_pyano_logo),
                            contentDescription = "Pyano logo",
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(40.dp)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    AppTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { viewModel.setSelectedTab(index) },
                            text = { Text(tab.title) },
                        )
                    }
                }
                outputProblem?.let { problem ->
                    OutputWarningBanner(problem, { showDevices = true }, viewModel::dismissOutputWarning)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                AppTab.Synth.ordinal -> SynthTab(viewModel)
                AppTab.Metronome.ordinal -> MetronomeTab(viewModel)
                AppTab.Loops.ordinal -> LoopStationTab(viewModel)
                AppTab.Recorder.ordinal -> RecorderTab(viewModel) { showServerSettings = true }
                AppTab.Scores.ordinal -> ScoreLibraryTab(viewModel.scoreLibrary, viewModel::openScore) { showServerSettings = true }
            }
        }
    }

    if (showDevices) {
        Dialog(
            onDismissRequest = { showDevices = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.88f),
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showDevices = false }) { Text("Close") }
                    }
                    DevicesPanel(viewModel)
                }
            }
        }
    }
}
