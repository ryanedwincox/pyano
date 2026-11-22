// Concern: edits shared server settings in a private modal | Non-concern: transport | IO: (settings) -> configuration actions
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun ServerSettingsDialog(
    endpoint: String,
    cloud: Boolean,
    ownerId: String,
    status: String,
    busy: Boolean,
    onConfigure: (String, String, String, Boolean, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    loadToken: suspend (Boolean) -> String = { "" },
) {
    var address by remember(endpoint) { mutableStateOf(if (cloud) endpoint else "") }
    var token by remember { mutableStateOf("") }
    var owner by remember(ownerId) { mutableStateOf(if (cloud) ownerId else "") }
    var tokenVisible by remember { mutableStateOf(false) }
    var credentialRevision by remember { mutableIntStateOf(0) }
    var loadingToken by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var active by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    val currentLoadToken by rememberUpdatedState(loadToken)
    LaunchedEffect(credentialRevision, active, endpoint, ownerId) {
        token = ""
        tokenVisible = false
        loadingToken = true
        if (active) {
            val storedToken = currentLoadToken(true)
            if (address.trimEnd('/') == endpoint.trimEnd('/') && owner == ownerId) token = storedToken
            loadingToken = false
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                token = ""
                tokenVisible = false
                active = false
            }
            if (event == Lifecycle.Event.ON_START) active = true
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Server settings") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                CloudProvisioningImport(busy = busy) { importedAddress, importedOwner, importedToken, importedCloud, complete ->
                    onConfigure(importedAddress, importedOwner, importedToken, importedCloud) { saved ->
                        if (saved) {
                            address = importedAddress
                            owner = importedOwner
                            credentialRevision++
                        }
                        complete(saved)
                    }
                }
                OutlinedTextField(
                    value = address, onValueChange = { if (it != address) { token = ""; tokenVisible = false }; address = it }, label = { Text("Cloud address") },
                    singleLine = true, enabled = !busy && !loadingToken, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = owner, onValueChange = { if (it != owner) { token = ""; tokenVisible = false }; owner = it }, label = { Text("Owner ID") },
                    singleLine = true, enabled = !busy && !loadingToken, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("Device token") },
                    visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(), singleLine = true,
                    trailingIcon = {
                        IconButton(enabled = !busy && !loadingToken, onClick = { tokenVisible = !tokenVisible }) {
                            Icon(if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (tokenVisible) "Hide device token" else "Show device token")
                        }
                    },
                    keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                    enabled = !busy && !loadingToken, modifier = Modifier.fillMaxWidth()
                )

            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !loadingToken && address.isNotBlank() && token.isNotBlank() && owner.isNotBlank(), onClick = {
                onConfigure(address, owner, token, true) { saved ->
                    tokenVisible = false
                    if (saved) {
                        token = ""
                        onDismiss()
                    }
                }
            }) { Text("Use server") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } },
    )
}
