// Concern: presents dismissible audio output warning | Non-concern: routing recovery | IO: (warning, actions) -> banner
package com.pyano.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun OutputWarningBanner(problem: String, onDetails: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            TextButton(onClick = onDetails, modifier = Modifier.weight(1f)) {
                Text(problem, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onClick = onDismiss) { Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}
