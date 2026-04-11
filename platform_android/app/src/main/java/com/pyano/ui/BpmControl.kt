// BpmControl: Reusable BPM display with +/- buttons and slider. NOT concerned with tempo source or sync logic.
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pyano.audio.LoopEngine

@Composable
fun BpmControl(
    bpm: Int,
    onBpmChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // BPM number with +/- buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            FilledTonalButton(
                onClick = { onBpmChange(bpm - 1) },
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = enabled
            ) {
                Text("\u2212", style = MaterialTheme.typography.titleLarge)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = "$bpm",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "BPM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = { onBpmChange(bpm + 1) },
                modifier = Modifier.size(48.dp),
                contentPadding = PaddingValues(0.dp),
                enabled = enabled
            ) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Slider(
            value = bpm.toFloat(),
            onValueChange = { onBpmChange(it.toInt()) },
            valueRange = LoopEngine.BPM_MIN.toFloat()..LoopEngine.BPM_MAX.toFloat(),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        )
    }
}
