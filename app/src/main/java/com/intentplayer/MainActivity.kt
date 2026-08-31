package com.intentplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.intentplayer.service.PlaybackService
import com.intentplayer.storage.BatteryOptimizationHelper
import com.intentplayer.storage.PreferencesManager
import com.intentplayer.ui.MainScreen
import com.intentplayer.ui.MainViewModel
import com.intentplayer.ui.theme.IntentPlayerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var lastExitBackAtMs = 0L
    private var playerTabRequest by mutableIntStateOf(0)

    private val playbackErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != PlaybackService.ACTION_ERROR) return
            val message = intent.getStringExtra(PlaybackService.EXTRA_ERROR_MESSAGE)
                ?.takeIf { it.isNotBlank() }
                ?: return
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) {}
        viewModel.onFolderSelected(uri)
    }
    private val batteryOptimizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onBatteryOptimizationResult(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this))
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> viewModel.onNotificationPermissionResult(!granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val now = SystemClock.elapsedRealtime()
                if (now - lastExitBackAtMs <= EXIT_BACK_INTERVAL_MS) {
                    finish()
                } else {
                    lastExitBackAtMs = now
                    Toast.makeText(this@MainActivity, "もう一度戻ると終了します", Toast.LENGTH_SHORT).show()
                }
            }
        })

        if (!PreferencesManager.isFirstLaunch(this)) {
            requestNotificationPermission(); requestStoragePermission(); requestAllFilesAccess(); checkBatteryOptimization()
        }
        setContent {
            IntentPlayerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        viewModel = viewModel,
                        onSelectFolderClick = { folderPickerLauncher.launch(null) },
                        onBatteryOptimizationClick = ::openBatteryOptimizationSettings,
                        playerTabRequest = playerTabRequest
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PlaybackService.ACTION_ERROR)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playbackErrorReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playbackErrorReceiver, filter)
        }
    }

    override fun onStop() {
        try { unregisterReceiver(playbackErrorReceiver) } catch (_: Exception) {}
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent != null && intent.action != Intent.ACTION_MAIN) {
            playerTabRequest++
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) notificationPermissionLauncher.launch(permission) else viewModel.onNotificationPermissionResult(false)
        } else viewModel.onNotificationPermissionResult(false)
    }
    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(permission), REQUEST_CODE_STORAGE)
    }
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
    private fun checkBatteryOptimization() { viewModel.onBatteryOptimizationResult(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)) }
    private fun openBatteryOptimizationSettings() {
        try { batteryOptimizationLauncher.launch(BatteryOptimizationHelper.createBatteryOptimizationIntent(this)) } catch (_: Exception) { startActivity(BatteryOptimizationHelper.createBatterySettingsIntent()) }
    }
    companion object {
        private const val REQUEST_CODE_STORAGE = 1001
        private const val EXIT_BACK_INTERVAL_MS = 2_000L
    }
}
