// MainActivity: Compose entry point — wires permissions, the foreground service, and the root UI to the ViewModel.
// NOT concerned with: audio engine internals, MIDI parsing, or DSP.
package com.pyano

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.pyano.ui.PyanoApp
import com.pyano.ui.theme.PyanoTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PyanoViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanSoundFonts()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: service runs either way, only the visible notification is affected */ }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
            viewModel.scanSoundFonts()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize()
        requestStoragePermission()
        requestNotificationPermissionIfNeeded()
        // Start the foreground service so audio survives screen-off / backgrounding.
        PyanoAudioService.start(this)

        setContent {
            PyanoTheme {
                PyanoApp(viewModel = viewModel)
            }
        }
    }

    override fun onDestroy() {
        // Only stop the service when the user actually closes the app, not on
        // configuration changes (rotation, etc).
        if (isFinishing) {
            PyanoAudioService.stop(this)
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(perm)
            }
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            }
        } else {
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(permission)
            }
        }
    }
}
