// SoundFontBrowser: Bottom-sheet browser for discovering, importing, and selecting SoundFont files. NOT concerned with synth engine.
package com.pyano.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pyano.PyanoViewModel
import com.pyano.audio.SF2Info
import com.pyano.audio.SF2MetadataReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundFontBrowser(
    viewModel: PyanoViewModel,
    onDismiss: () -> Unit
) {
    val soundFonts by viewModel.availableSoundFonts.collectAsState()
    val currentSoundFont by viewModel.soundFontName.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val favorites by viewModel.favoriteSoundFonts.collectAsState()
    val favoritePaths = remember(favorites) { favorites.map { it.path }.toSet() }

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
            Text(
                "Select SoundFont",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Text(
                "Place .sf2 files in your Downloads folder",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (soundFonts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No .sf2 files found in Downloads",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(max = 500.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(soundFonts, key = { it.path }) { sf ->
                        SoundFontCard(
                            info = sf,
                            isActive = sf.name == currentSoundFont,
                            isFavorite = sf.path in favoritePaths,
                            onToggleFavorite = { viewModel.toggleFavoriteSoundFont(sf) },
                            onClick = {
                                viewModel.loadSoundFontByPath(sf.path, sf.fileName)
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SoundFontCard(
    info: SF2Info,
    isActive: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = if (isActive) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${info.fileName}  ·  ${SF2MetadataReader.formatSize(info.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (info.isBundled) {
                SuggestionChip(
                    onClick = {},
                    label = { Text("bundled", style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp).padding(start = 8.dp)
                )
            }
        }
    }
}
