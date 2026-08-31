package com.intentplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.intentplayer.storage.BatteryOptimizationHelper

@Composable
fun OnboardingScreen(
    viewModel: MainViewModel,
    onSelectFolderClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var checkKey by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val folderUri by viewModel.folderUri.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) checkKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationGranted by remember(checkKey) {
        derivedStateOf {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
    }
    val storageGranted by remember(checkKey) {
        derivedStateOf {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else Manifest.permission.READ_EXTERNAL_STORAGE
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    val bluetoothGranted by remember(checkKey) {
        derivedStateOf {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
    }
    val batteryExcluded by remember(checkKey) {
        derivedStateOf { BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkKey++ }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkKey++ }
    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { checkKey++ }

    BackHandler(enabled = step > 0) { step-- }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("IntentPlayer のセットアップ", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (step < 5) "ステップ ${step + 1} / 5" else "セットアップ完了",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        when (step) {
            0 -> SetupStep(
                title = "1. 通知権限",
                description = "再生通知・ロック画面表示に必要です。",
                ok = notificationGranted,
                actionLabel = if (notificationGranted) "次へ" else "通知を許可する",
                onAction = {
                    if (notificationGranted) step++
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )

            1 -> SetupStep(
                title = "2. ストレージ権限",
                description = "音楽ファイルのメタデータ取得に必要です。",
                ok = storageGranted,
                actionLabel = if (storageGranted) "次へ" else "ストレージ権限を許可する",
                onAction = {
                    if (storageGranted) step++
                    else storageLauncher.launch(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Manifest.permission.READ_MEDIA_AUDIO
                        } else Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                }
            )

            2 -> SetupStep(
                title = "3. Bluetooth権限",
                description = "イヤホン接続・切断時の自動制御に必要です。",
                ok = bluetoothGranted,
                actionLabel = if (bluetoothGranted) "次へ" else "Bluetooth権限を許可する",
                onAction = {
                    if (bluetoothGranted) step++
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }
            )

            3 -> SetupStep(
                title = "4. 音楽フォルダ",
                description = folderUri?.let { "選択済み: ${it.lastPathSegment ?: it}" }
                    ?: "再生する音楽フォルダを選択してください。",
                ok = folderUri != null,
                actionLabel = if (folderUri != null) "次へ" else "フォルダを選択",
                onAction = {
                    if (folderUri != null) step++ else onSelectFolderClick()
                },
                secondaryLabel = if (folderUri != null) "フォルダを変更" else null,
                onSecondary = onSelectFolderClick
            )

            4 -> SetupStep(
                title = "5. バッテリー最適化",
                description = "バックグラウンドでIntent待機・再生を安定させるため、最適化対象から除外してください。",
                ok = batteryExcluded,
                actionLabel = if (batteryExcluded) "次へ" else "設定を開く",
                onAction = {
                    if (batteryExcluded) step++ else onBatteryOptimizationClick()
                }
            )

            else -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("必要な権限・設定が完了しました", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = viewModel::completeOnboarding,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("はじめる") }
            }
        }
    }
}

@Composable
private fun SetupStep(
    title: String,
    description: String,
    ok: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(12.dp))
            Text(description, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(actionLabel) }
            if (secondaryLabel != null && onSecondary != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}
