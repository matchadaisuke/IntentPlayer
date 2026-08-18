package com.intentplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.intentplayer.storage.BatteryOptimizationHelper
import com.intentplayer.ui.MainScreen
import com.intentplayer.ui.MainViewModel
import com.intentplayer.ui.theme.IntentPlayerTheme

/**
 * MainActivity
 *
 * - 通知権限チェック:
 *   POST_NOTIFICATIONS が拒否された場合は MainViewModel.onNotificationPermissionResult() で
 *   UI にバナーを表示してユーザーに案内する。
 *
 * - onNewIntent での処理:
 *   launchMode="singleTop" のとき、通知タップや Bluetooth 接続で
 *   onNewIntent が呼ばれる場合があるため ViewModel に処理を委譲する。
 */
class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val viewModel: MainViewModel by viewModels()

    // フォルダ選択ランチャー
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) {
            Log.d(TAG, "Folder picker cancelled")
            return@registerForActivityResult
        }
        Log.d(TAG, "Folder selected: $uri")

        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, flags)
            Log.d(TAG, "Persistable URI permission granted")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to take persistable permission", e)
        }

        viewModel.onFolderSelected(uri)
    }

    // バッテリー最適化除外ダイアログのランチャー
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        val isOptimized = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        Log.d(TAG, "batteryOptimizationLauncher result: isBatteryOptimized=$isOptimized")
        viewModel.onBatteryOptimizationResult(isOptimized)
    }

    // 通知権限リクエストランチャー (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS result: granted=$granted")
        viewModel.onNotificationPermissionResult(!granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!com.intentplayer.storage.PreferencesManager.isFirstLaunch(this)) {
            // 通知権限を先にリクエスト（ロック画面・BT通知の前提条件）
            requestNotificationPermission()

            // READ_MEDIA_AUDIO 権限をリクエスト
            requestStoragePermission()

            // バッテリー最適化状態を確認して ViewModel に通知
            checkBatteryOptimization()
        }

        setContent {
            IntentPlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        viewModel = viewModel,
                        onSelectFolderClick = {
                            folderPickerLauncher.launch(null)
                        },
                        onBatteryOptimizationClick = {
                            openBatteryOptimizationSettings()
                        }
                    )
                }
            }
        }
    }

    /**
     * launchMode="singleTop" のとき呼ばれる。
     * 通知タップ・Bluetooth 接続・ショートカットからの起動を処理する。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: action=${intent.action}")
        // 現時点では特別な処理不要（MediaSession が通知のタップを処理する）
        // 将来的に DeepLink 対応が必要な場合はここで処理する
    }

    /**
     * 通知権限リクエスト。
     *
     * Android 13 (API 33) 以降、POST_NOTIFICATIONS が必要。
     * これがないと:
     *   - ロック画面に再生情報が出ない
     *   - Bluetooth デバイスに曲名が送出されない
     *   - 通知欄の再生コントロールが表示されない
     *
     * すでに許可済みの場合はリクエストしない。
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            when {
                ContextCompat.checkSelfPermission(this, permission) ==
                        PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "POST_NOTIFICATIONS: already granted")
                    viewModel.onNotificationPermissionResult(false)
                }
                else -> {
                    Log.d(TAG, "POST_NOTIFICATIONS: requesting")
                    notificationPermissionLauncher.launch(permission)
                }
            }
        } else {
            // Android 12 以下は権限不要
            viewModel.onNotificationPermissionResult(false)
        }
    }

    /**
     * READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE 権限リクエスト。
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.READ_MEDIA_AUDIO
            if (ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_MEDIA_AUDIO: requesting")
                requestPermissions(arrayOf(permission), REQUEST_CODE_STORAGE)
            }
        } else {
            // Android 12 以下は READ_EXTERNAL_STORAGE が必要 (MediaStore メタデータ取得用)
            val permission = Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "READ_EXTERNAL_STORAGE: requesting")
                requestPermissions(arrayOf(permission), REQUEST_CODE_STORAGE)
            }
        }
    }

    private fun checkBatteryOptimization() {
        val isOptimized = !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)
        Log.d(TAG, "checkBatteryOptimization: isBatteryOptimized=$isOptimized")
        viewModel.onBatteryOptimizationResult(isOptimized)
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = BatteryOptimizationHelper.createBatteryOptimizationIntent(this)
            batteryOptimizationLauncher.launch(intent)
            Log.d(TAG, "Battery optimization dialog opened")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open battery optimization dialog, falling back", e)
            try {
                val settingsIntent = BatteryOptimizationHelper.createBatterySettingsIntent()
                startActivity(settingsIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open battery settings", e2)
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_STORAGE = 1001
    }
}
