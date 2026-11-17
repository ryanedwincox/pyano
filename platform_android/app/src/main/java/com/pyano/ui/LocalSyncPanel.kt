// Concern: configures manual recording uploads | Non-concern: network transport | IO: (server settings) -> configuration actions
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LocalSyncPanel(endpoint: String, status: String, busy: Boolean, onConfigure: (String, String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var address by remember(endpoint) { mutableStateOf(endpoint) }
    var token by remember { mutableStateOf("") }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Local sync", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { expanded = !expanded }, enabled = !busy) { Text("Server settings") }
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            if (expanded) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, label = { Text("Server address") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("Device token") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth()
                )
                Text("Token stays in memory for this app session.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !busy && address.isNotBlank() && token.isNotBlank(), onClick = {
                    onConfigure(address, token)
                    token = ""
                    expanded = false
                }) { Text("Use server") }
            }
        }
    }
}
