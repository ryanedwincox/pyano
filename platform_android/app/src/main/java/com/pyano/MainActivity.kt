// MainActivity: Compose entry point — wires permissions, the foreground service, and the root UI to the ViewModel.
// NOT concerned with: audio engine internals, MIDI parsing, or DSP.
package com.pyano

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
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
        // Shown over the lock screen: a key press wakes the tablet straight into Pyano
        // (KioskController) while every other app stays behind the PIN.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        viewModel.initialize()
        requestStoragePermission()
        requestNotificationPermissionIfNeeded()
        // Start the foreground service so audio survives screen-off / backgrounding.
        PyanoAudioService.start(this)

        setContent {
            PyanoTheme {
                PyanoApp(viewModel = viewModel, onOpenApps = otherLauncher()?.let { { openApps(it) } })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.uiVisible = true
    }

    override fun onStop() {
        viewModel.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        // Only stop the service when the user actually closes the app, not on
        // configuration changes (rotation, etc).
        if (isFinishing) {
            PyanoAudioService.stop(this)
        }
        super.onDestroy()
    }

    /** The regular launcher (e.g. One UI Home), for when Pyano is the home app. */
    private fun otherLauncher(): Intent? {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = packageManager.queryIntentActivities(home, 0).firstOrNull {
            // Settings' FallbackHome only covers the time before the first unlock.
            it.activityInfo.packageName != packageName && it.activityInfo.packageName != "com.android.settings"
        } ?: return null
        return Intent(home)
            .setClassName(launcher.activityInfo.packageName, launcher.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Opens the regular launcher, asking for the PIN first if Pyano is shown over the lock screen. */
    private fun openApps(launcher: Intent) {
        if (getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            Toast.makeText(this, "Pyano is pinned: swipe up and hold to unpin first", Toast.LENGTH_LONG).show()
            return
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = startActivity(launcher)
            })
        } else {
            startActivity(launcher)
        }
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
