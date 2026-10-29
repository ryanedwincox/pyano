// DevicesPanel: Shows the USB devices on the bus now and the connection history, to diagnose a dropped audio interface.
// NOT concerned with: selecting outputs (SynthTab) or recording the events (DeviceDiagnostics).
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pyano.DeviceDiagnostics
import com.pyano.DiagEvent
import com.pyano.PyanoViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DevicesPanel(viewModel: PyanoViewModel) {
    val diagnostics = viewModel.diagnostics
    val usbDevices by diagnostics.usbDevices.collectAsState()
    val events by diagnostics.events.collectAsState()
    val problem by viewModel.outputProblem.collectAsState()
    val selectedOutput by viewModel.selectedAudioOutput.collectAsState()
    val timeFormat = remember { SimpleDateFormat("MMM d HH:mm:ss", Locale.US) }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        item {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio output", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text("Playing through: ${selectedOutput?.name ?: "Default"}")
                    if (problem != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(problem!!, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("On the USB bus now", style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { diagnostics.refreshUsb() }) { Text("Refresh") }
                    }
                    if (usbDevices.isEmpty()) Text("Nothing. Is the hub connected?")
                    usbDevices.forEach { device ->
                        val reconnects = diagnostics.recentAttaches(device.name)
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Text(device.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${device.functions} · ${device.power} · ${device.vidPid} · " +
                                    "connection #${device.path.substringAfterLast('/').trimStart('0')} since boot",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (reconnects >= 2) {
                                Text(
                                    "Reconnected $reconnects times in the last " +
                                        "${DeviceDiagnostics.FLAP_WINDOW_MS / 60000} minutes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("History (newest first)", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { diagnostics.clear() }) { Text("Clear") }
            }
        }

        items(events.asReversed()) { event ->
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(
                    timeFormat.format(Date(event.timeMs)),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.width(130.dp),
                )
                Text(
                    event.text,
                    fontSize = 13.sp,
                    color = when (event.kind) {
                        DiagEvent.Kind.Disconnected -> MaterialTheme.colorScheme.error
                        DiagEvent.Kind.Connected -> MaterialTheme.colorScheme.primary
                        DiagEvent.Kind.Power -> MaterialTheme.colorScheme.tertiary
                        DiagEvent.Kind.Info -> Color.Unspecified
                    },
                )
            }
        }
    }
}
