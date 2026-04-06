package com.pyano.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pyano.PyanoViewModel
import com.pyano.audio.SF2Info
import com.pyano.audio.SF2MetadataReader

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SoundFontBrowser(
    viewModel: PyanoViewModel,
    onDismiss: () -> Unit
) {
    val soundFonts by viewModel.availableSoundFonts.collectAsState()
    val currentSoundFont by viewModel.soundFontName.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var deleteTarget by remember { mutableStateOf<SF2Info?>(null) }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            viewModel.importSoundFont(it)
        }
    }

    // Scan on open
    LaunchedEffect(Unit) {
        viewModel.scanSoundFonts()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Select SoundFont",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Loading SoundFont...")
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 400.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(soundFonts, key = { it.path }) { sf ->
                        val isActive = sf.fileName == currentSoundFont
                        SoundFontCard(
                            info = sf,
                            isActive = isActive,
                            onClick = {
                                viewModel.loadSoundFontByPath(sf.path, sf.fileName)
                                onDismiss()
                            },
                            onLongClick = {
                                if (!sf.isBundled) {
                                    deleteTarget = sf
                                }
                            }
                        )
                    }

                    // Import button
                    item {
                        OutlinedCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { importPicker.launch(arrayOf("*/*")) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "+ Import from storage",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    deleteTarget?.let { sf ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove SoundFont?") },
            text = { Text("Remove \"${sf.name}\" from the app? The original file is not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSoundFont(sf)
                    deleteTarget = null
                }) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SoundFontCard(
    info: SF2Info,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isActive) CardDefaults.outlinedCardBorder() else null
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Line 1: Name + badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    info.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (info.isBundled) {
                    Spacer(modifier = Modifier.width(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text("bundled", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }

            // Line 2: Size
            Text(
                SF2MetadataReader.formatSize(info.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
