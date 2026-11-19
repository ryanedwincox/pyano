// Concern: configures manual recording uploads | Non-concern: network transport | IO: (server settings) -> configuration actions
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LocalSyncPanel(
    endpoint: String,
    cloud: Boolean,
    ownerId: String,
    status: String,
    busy: Boolean,
    onConfigure: (String, String, String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var address by remember(endpoint) { mutableStateOf(endpoint) }
    var token by remember { mutableStateOf("") }
    var cloudMode by remember(cloud) { mutableStateOf(cloud) }
    var owner by remember(ownerId) { mutableStateOf(ownerId) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Recording sync", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = !expanded }, enabled = !busy) { Text("Server settings") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (expanded) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = cloudMode, onClick = { cloudMode = true }, label = { Text("Cloud") })
                    FilterChip(selected = !cloudMode, onClick = { cloudMode = false }, label = { Text("Local prototype") })
                }
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, label = { Text(if (cloudMode) "Cloud address" else "Server address") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                if (cloudMode) OutlinedTextField(
                    value = owner, onValueChange = { owner = it }, label = { Text("Owner ID") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("Device token") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    if (cloudMode) "Cloud tokens are encrypted with Android Keystore; uploads retry in the background."
                    else "Local tokens stay in memory for this app session.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(enabled = !busy && address.isNotBlank() && token.isNotBlank() && (!cloudMode || owner.isNotBlank()), onClick = {
                    onConfigure(address, owner, token, cloudMode)
                    token = ""
                    expanded = false
                }) { Text("Use server") }
            }
        }
    }
}
