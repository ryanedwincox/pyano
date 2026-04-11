package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.log10
import kotlin.math.roundToInt

/** Convert raw peak amplitude (0..∞) to dB, clamped to [-60, 0]. */
fun peakToDb(peak: Float): Float =
    if (peak > 0.0001f) (20 * log10(peak)).coerceIn(-60f, 0f) else -60f

/** Convert raw peak amplitude to 0..1 fraction suitable for LinearProgressIndicator. */
fun peakToFraction(peak: Float): Float =
    ((peakToDb(peak) + 60f) / 60f).coerceIn(0f, 1f)

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: String = "%.1f",
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format(format, value),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
    }
}

@Composable
fun IntSlider(
    label: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$value",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    enabled: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        if (enabled != null && onToggle != null) {
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun BufferSizeSlider(
    bufferSize: Int,
    onBufferSizeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val steps = listOf(32, 64, 128, 256, 512, 1024)
    val index = steps.indexOf(bufferSize).coerceAtLeast(0)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Buffer",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(72.dp)
        )
        Slider(
            value = index.toFloat(),
            onValueChange = { onBufferSizeChange(steps[it.roundToInt().coerceIn(0, steps.lastIndex)]) },
            valueRange = 0f..(steps.size - 1).toFloat(),
            steps = steps.size - 2,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$bufferSize",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
    }
}
