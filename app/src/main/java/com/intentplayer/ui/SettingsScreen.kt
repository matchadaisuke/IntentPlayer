package com.intentplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val useCustomMediaPlayback by viewModel.useCustomMediaPlayback.collectAsState()
    val enableAppVolume by viewModel.enableAppVolume.collectAsState()
    val autoBluetoothControlEnabled by viewModel.autoBluetoothControlEnabled.collectAsState()
    val blockAudioFocusSend by viewModel.blockAudioFocusSend.collectAsState()
    val blockAudioFocusReceive by viewModel.blockAudioFocusReceive.collectAsState()
    val bluetoothReconnectDelayMs by viewModel.bluetoothReconnectDelayMs.collectAsState()
    val blockSpeakerMutePlaybackEnabled by viewModel.blockSpeakerMutePlaybackEnabled.collectAsState()
    val autoResumeTimeoutEnabled by viewModel.autoResumeTimeoutEnabled.collectAsState()
    val autoResumeTimeoutMs by viewModel.autoResumeTimeoutMs.collectAsState()
    val appVersion by viewModel.appVersion.collectAsState()
    val errorLogs by viewModel.errorLogs.collectAsState()


    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo(MainViewModel.AppScreen.MAIN) }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp)
        ) {
            // Custom Media Playback System Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("独自のメディア再生システムを使う", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "オンにすると、スマートウォッチ等のリモコン操作やOS標準の再生表示を無効にし、アプリ独自でバックグラウンド再生を行います。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useCustomMediaPlayback,
                    onCheckedChange = { viewModel.setUseCustomMediaPlayback(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // App Volume Control Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("アプリ独自の音量調整", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "オンにすると、メイン画面に音量スライダーが表示され、システムの音量とは独立して音量を調節（最大200%まで増幅）できます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enableAppVolume,
                    onCheckedChange = { viewModel.setEnableAppVolume(it) }
                )
            }

            
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Earphone Auto Control Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("イヤホン脱着時の自動制御", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "イヤホンの切断時に再生を自動で停止し、再接続時に続きから再開します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = autoBluetoothControlEnabled,
                    onCheckedChange = { viewModel.setAutoBluetoothControlEnabled(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (autoBluetoothControlEnabled) {
                // Auto Resume Timeout Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("再接続の有効期限を設定", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "オンにすると、切断されてから一定時間以上経過した後の再接続時に自動で再生を再開しなくなります。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoResumeTimeoutEnabled,
                        onCheckedChange = { viewModel.setAutoResumeTimeoutEnabled(it) }
                    )
                }

                if (autoResumeTimeoutEnabled) {
                    // Auto Resume Timeout Slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val seconds = (autoResumeTimeoutMs / 1000).toInt()
                            val displayValue = if (seconds >= 60) {
                                val mins = seconds / 60
                                val secs = seconds % 60
                                if (secs > 0) "${mins}分${secs}秒" else "${mins}分"
                            } else {
                                "${seconds}秒"
                            }
                            Text("有効期限", style = MaterialTheme.typography.titleMedium)
                            Text(displayValue, style = MaterialTheme.typography.titleMedium)
                        }
                        Slider(
                            value = (autoResumeTimeoutMs / 1000).toFloat(),
                            onValueChange = { viewModel.setAutoResumeTimeoutMs(Math.round(it / 10f) * 10000L) },
                            valueRange = 10f..600f,
                            steps = 58
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }


            // Block Speaker Mute Playback Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("スピーカー消音時の再生防止", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "イヤホン未接続で音量がオフの時、誤って再生が開始されるのを防ぎます。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = blockSpeakerMutePlaybackEnabled,
                    onCheckedChange = { viewModel.setBlockSpeakerMutePlaybackEnabled(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))



            // Block Audio Focus Send
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("オーディオフォーカスを送信しない", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "オンにすると、再生開始時に他のアプリの音を止めません。オフにすると他の音楽を一時停止させながら再生を開始します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = blockAudioFocusSend,
                    onCheckedChange = { viewModel.setBlockAudioFocusSend(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Block Audio Focus Receive
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("オーディオフォーカスを受信しない", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "オンにすると、他のアプリ（動画や別の音楽アプリ）が再生を開始しても割り込みを無視して再生を続けます。オフにすると他アプリの割り込み時に一時停止します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = blockAudioFocusReceive,
                    onCheckedChange = { viewModel.setBlockAudioFocusReceive(it) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Bluetooth Reconnect Delay Slider
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bluetooth再接続ディレイ時間", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "切断後、再接続された際の再生開始までの待機時間（ミリ秒）です。お使いの機器の切替ラグに合わせて調整してください。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("${bluetoothReconnectDelayMs}ms", style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = bluetoothReconnectDelayMs.toFloat(),
                    onValueChange = { viewModel.setBluetoothReconnectDelayMs(Math.round(it / 100f) * 100) },
                    valueRange = 0f..5000f,
                    steps = 49
                )

            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Intent Spec Help Section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("インテント仕様 (外部制御用)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text("■ 受信コマンド ( com.intentplayer.CONTROL )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Extras:\n" +
                        "・command (String): play | force_play | pause | stop | next | previous | seek | speed\n" +
                        "・folderUri (String): SAFフォルダURI (play/force_play時。省略時は前回続きから)\n" +
                        "・seekTo (Long): シーク位置ミリ秒 (seek時)\n" +
                        "・speed (Float): 速度 0.5〜2.0 (speed時)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text("■ 送信イベント ( com.intentplayer.PLAYBACK_EVENT )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Extras:\n" +
                        "・event (String): track_completed | playlist_completed\n" +
                        "・trackName (String): 対象のトラック名",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    Text("■ エラー通知 ( com.intentplayer.ERROR )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Extras:\n" +
                        "・reason (String): no_files | permission_denied | playback_failed など\n" +
                        "・message (String): エラーの詳細メッセージ",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // App Version Display
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "バージョン: $appVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Error Logs Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("エラーログ (直近20件)", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { viewModel.clearErrorLogs() }) {
                    Text("クリア")
                }
            }

            if (errorLogs.isEmpty()) {
                Text(
                    "エラーログはありません",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    errorLogs.forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
