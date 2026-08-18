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
    val context = LocalContext.current
    val folderUri by viewModel.folderUri.collectAsState()

    // 各権限の現在の付与状態をリアルタイムで確認するためのキー
    var permissionCheckKey by remember { mutableStateOf(0) }

    // アプリがON_RESUME（設定画面やSAFから戻ってきたとき）に自動で状態を再確認する
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionCheckKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val notificationGranted by remember(permissionCheckKey) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        }
    }

    val storageGranted by remember(permissionCheckKey) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    val bluetoothGranted by remember(permissionCheckKey) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true
        }
    }

    val batteryExcluded by remember(permissionCheckKey) {
        derivedStateOf {
            BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCheckKey++
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCheckKey++
    }

    val bluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCheckKey++
    }

    // 戻るジェスチャーで前のステップに戻る
    BackHandler(enabled = step > 0) {
        step--
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "IntentPlayer の初期設定",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = "ステップ ${(step + 1).coerceAtMost(5)} / 5",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        when (step) {
            0 -> {
                OnboardingStep(
                    title = "1. 通知権限",
                    description = "バックグラウンド再生時の通知や操作パネルの表示に必要です。",
                    isGranted = notificationGranted
                ) {
                    if (notificationGranted) {
                        Button(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("次へ")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    step++
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("通知を許可する")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("許可せずに次へ (スキップ)")
                        }
                    }
                }
            }
            1 -> {
                OnboardingStep(
                    title = "2. ストレージ権限",
                    description = "端末内の音楽ファイルのメタデータ取得に必要です。",
                    isGranted = storageGranted
                ) {
                    if (storageGranted) {
                        Button(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("次へ")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    storageLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                                } else {
                                    storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("ストレージ権限を許可する")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("許可せずに次へ (スキップ)")
                        }
                    }
                }
            }
            2 -> {
                OnboardingStep(
                    title = "3. Bluetooth権限",
                    description = "イヤホン接続・切断時の自動制御に必要です。",
                    isGranted = bluetoothGranted
                ) {
                    if (bluetoothGranted) {
                        Button(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("次へ")
                        }
                    } else {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                                } else {
                                    step++
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Bluetooth権限を許可する")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("許可せずに次へ (スキップ)")
                        }
                    }
                }
            }
            3 -> {
                val hasFolder = folderUri != null
                val displayPath = folderUri?.lastPathSegment ?: ""
                OnboardingStep(
                    title = "4. フォルダ選択",
                    description = if (hasFolder) "選択したフォルダ: $displayPath" else "再生する音楽ファイルが入っているフォルダを選択してください。",
                    isGranted = hasFolder
                ) {
                    if (hasFolder) {
                        Button(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("次へ")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onSelectFolderClick() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("フォルダを変更")
                        }
                    } else {
                        Button(
                            onClick = { onSelectFolderClick() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("フォルダを選択")
                        }
                    }
                }
            }
            4 -> {
                OnboardingStep(
                    title = "5. バッテリー最適化の除外",
                    description = "バックグラウンド再生が停止しないよう、設定から除外することをお勧めします。",
                    isGranted = batteryExcluded
                ) {
                    if (batteryExcluded) {
                        Button(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("次へ")
                        }
                    } else {
                        Button(
                            onClick = { onBatteryOptimizationClick() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("設定を開く")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { step++ },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("設定せずに次へ (スキップ)")
                        }
                    }
                }
            }
            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(64.dp)
                            .padding(bottom = 16.dp)
                    )
                    Text(
                        text = "設定が完了しました！",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(bottom = 32.dp)
                    )
                    Button(
                        onClick = { viewModel.completeOnboarding() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("はじめる")
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingStep(
    title: String,
    description: String,
    isGranted: Boolean,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )
            content()
        }
    }
}
