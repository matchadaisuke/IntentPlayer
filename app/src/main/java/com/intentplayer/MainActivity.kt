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
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.intentplayer.service.PlaybackService
import com.intentplayer.storage.AppThemeMode
import com.intentplayer.storage.AppThemePreferences
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
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            Toast.makeText(this, "フォルダの永続アクセス権を保存できませんでした", Toast.LENGTH_LONG).show()
        }
        viewModel.onFolderSelected(uri)
    }

    private val batteryOptimizationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.onBatteryOptimizationResult(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this))
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(!granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleLaunchIntent(intent)
        AppThemePreferences.initialize(this)

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
            requestNotificationPermission()
            requestRuntimePermissions()
            checkBatteryOptimization()
        }

        setContent {
            val themeMode = AppThemePreferences.mode
            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            IntentPlayerTheme(darkTheme = darkTheme) {
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
        ContextCompat.registerReceiver(
            this,
            playbackErrorReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
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
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER_TAB, false) == true) {
            playerTabRequest++
            intent.removeExtra(EXTRA_OPEN_PLAYER_TAB)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            } else {
                viewModel.onNotificationPermissionResult(false)
            }
        } else {
            viewModel.onNotificationPermissionResult(false)
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_CODE_RUNTIME_PERMISSIONS)
    }

    private fun checkBatteryOptimization() {
        viewModel.onBatteryOptimizationResult(!BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this))
    }

    private fun openBatteryOptimizationSettings() {
        try {
            batteryOptimizationLauncher.launch(BatteryOptimizationHelper.createBatteryOptimizationIntent(this))
        } catch (_: Exception) {
            startActivity(BatteryOptimizationHelper.createBatterySettingsIntent())
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER_TAB = "com.intentplayer.extra.OPEN_PLAYER_TAB"
        private const val REQUEST_CODE_RUNTIME_PERMISSIONS = 1001
        private const val EXIT_BACK_INTERVAL_MS = 2_000L
    }
}
