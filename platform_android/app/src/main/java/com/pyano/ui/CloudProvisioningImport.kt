// Concern: confirms private credential imports | Non-concern: upload execution | IO: (QR or pasted JSON) -> confirmed settings
package com.pyano.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pyano.sync.MAX_PROVISIONING_BYTES
import com.pyano.sync.ProvisioningSession

@Composable
fun CloudProvisioningImport(
    busy: Boolean,
    onConfigure: (String, String, String, Boolean, (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val session = remember { ProvisioningSession() }
    var revision by remember { mutableIntStateOf(0) }
    var ticket by remember { mutableStateOf<Int?>(null) }
    var paste by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf("") }
    var scanOutstanding by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf("") }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val active = ticket
        ticket = null
        if (active == null && scanOutstanding) message = "Scan interrupted. Scan again or paste JSON."
        scanOutstanding = false
        try {
            if (active != null && session.scanActive(active)) {
                if (!session.scanned(active, result.contents)) message = "Scan canceled. You can paste JSON instead."
            }
        } catch (_: Exception) {
            session.cancel()
            message = "Invalid provisioning QR. Paste the version 1 JSON from Cloud instead."
        }
        revision++
    }
    fun launchScanner(active: Int) {
        if (!session.scanActive(active)) return
        try {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(ProvisioningCaptureActivity::class.java).setBeepEnabled(false)
                .setBarcodeImageEnabled(false).setOrientationLocked(false).setPrompt("Scan private Cloud provisioning QR"))
        } catch (_: Exception) {
            session.cancel()
            ticket = null
            scanOutstanding = false
            message = "Scanner unavailable. Paste the Cloud provisioning JSON instead."
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val active = ticket
        if (active == null && scanOutstanding) {
            scanOutstanding = false
            message = "Scan interrupted. Scan again or paste JSON."
        }
        if (active != null && session.scanActive(active)) {
            if (granted) launchScanner(active)
            else {
                session.cancel()
                ticket = null
                scanOutstanding = false
                message = "Camera permission denied. Paste the Cloud provisioning JSON instead."
            }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && scanOutstanding && ticket == null) {
                scanOutstanding = false
                message = "Scan interrupted. Scan again or paste JSON."
            }
            if (event == Lifecycle.Event.ON_STOP) {
                raw = ""
                paste = false
                session.clearPending()
                revision++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); session.cancel() }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            raw = ""; paste = false; message = ""
            val active = session.beginScan()
            ticket = active
            scanOutstanding = true
            revision++
            if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                session.cancel(); ticket = null; scanOutstanding = false
                message = "No camera available. Paste the Cloud provisioning JSON instead."
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                launchScanner(active)
            } else {
                try { cameraPermission.launch(Manifest.permission.CAMERA) } catch (_: Exception) {
                    session.cancel(); ticket = null; scanOutstanding = false
                    message = "Camera unavailable. Paste the Cloud provisioning JSON instead."
                }
            }
        }) { Text("Scan QR") }
        OutlinedButton(enabled = !busy, onClick = {
            session.cancel(); ticket = null; scanOutstanding = false; raw = ""; paste = true; message = ""; revision++
        }) { Text("Paste JSON") }
    }
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (paste) AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { raw = ""; paste = false; session.cancel() },
        title = { Text("Paste private provisioning JSON") },
        text = {
            OutlinedTextField(value = raw, onValueChange = {
                if (it.length <= MAX_PROVISIONING_BYTES) raw = it
                else { raw = ""; message = "Provisioning JSON is too large." }
            }, label = { Text("Provisioning JSON") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = raw.isNotBlank() && !busy, onClick = {
            val input = raw
            raw = ""; paste = false
            try { session.pasted(input) } catch (_: Exception) {
                message = "Invalid provisioning JSON. Use the version 1 JSON from Cloud device provisioning."
            }
            revision++
        }) { Text("Review import") } },
        dismissButton = { TextButton(onClick = { raw = ""; paste = false; session.cancel() }) { Text("Cancel") } }
    )
    val pending = remember(revision) { session.pending }
    if (pending != null) AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = { if (!busy) { session.cancel(); revision++ } },
        title = { Text("Confirm Cloud settings") },
        text = { Text("Cloud: ${pending.endpoint}\nOwner: ${pending.ownerId}") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            onConfigure(pending.endpoint, pending.ownerId, pending.token, true) { saved ->
                session.cancel(); revision++
                message = if (saved) "Cloud settings imported." else "Could not save Cloud settings. Try importing again."
            }
        }) { Text("Save Cloud settings") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { session.cancel(); revision++ }) { Text("Cancel") } }
    )
}
