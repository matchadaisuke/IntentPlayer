package com.intentplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.intentplayer.storage.PreferencesManager
import kotlin.math.roundToLong

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
            SettingSwitchRow(
                title = "独自のメディア再生システムを使う",
                description = "オンにすると、Bluetoothイヤホン・スマートウォッチ等からのメディアボタン操作を受け付けず、インテントとアプリ内操作を中心に再生します。",
                checked = useCustomMediaPlayback,
                onCheckedChange = viewModel::setUseCustomMediaPlayback
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "アプリ独自の音量調整",
                description = "オンにすると、メイン画面からシステム音量とは別に0〜200%で調整できます。100%を超える範囲はLoudnessEnhancerで増幅します。",
                checked = enableAppVolume,
                onCheckedChange = viewModel::setEnableAppVolume
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "イヤホン脱着時の自動制御",
                description = "イヤホンの切断時に再生を自動で停止し、再接続時に続きから再開します。",
                checked = autoBluetoothControlEnabled,
                onCheckedChange = viewModel::setAutoBluetoothControlEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (autoBluetoothControlEnabled) {
                SettingSwitchRow(
                    title = "再接続の有効期限を設定",
                    description = "オンにすると、切断から指定時間を超えた再接続では自動再開しません。1秒〜24時間まで設定できます。",
                    checked = autoResumeTimeoutEnabled,
                    onCheckedChange = viewModel::setAutoResumeTimeoutEnabled
                )
                if (autoResumeTimeoutEnabled) {
                    AutoResumeTimeoutEditor(
                        timeoutMs = autoResumeTimeoutMs,
                        onTimeoutChanged = viewModel::setAutoResumeTimeoutMs
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            SettingSwitchRow(
                title = "消音時は自動一時停止",
                description = "スピーカーまたはBluetooth再生中にメディア音量が0になったら一時停止し、その自動停止後に同じ出力経路で音量を上げると再開します。",
                checked = blockSpeakerMutePlaybackEnabled,
                onCheckedChange = viewModel::setBlockSpeakerMutePlaybackEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "オーディオフォーカスを送信しない",
                description = "オンにすると、再生開始時に他のアプリの音を止めません。オフにすると他の音楽を一時停止させながら再生を開始します。",
                checked = blockAudioFocusSend,
                onCheckedChange = viewModel::setBlockAudioFocusSend
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "オーディオフォーカスを受信しない",
                description = "オンにすると、他のアプリが再生を開始しても割り込みを無視して再生を続けます。",
                checked = blockAudioFocusReceive,
                onCheckedChange = viewModel::setBlockAudioFocusReceive
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Bluetooth再接続ディレイ時間", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "切断後、再接続された際の再生開始までの待機時間（ミリ秒）です。",
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

            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("インテント仕様 (外部制御用)", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("■ 受信コマンド ( com.intentplayer.CONTROL )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Extras:\n・command (String): play | force_play | pause | stop | next | previous | seek | speed\n" +
                            "・folderUri (String): SAFフォルダURI (play/force_play時。省略時は前回続きから)\n" +
                            "・seekTo (Long): シーク位置ミリ秒 (seek時)\n" +
                            "・speed (Float): 速度 0.5〜2.0 (speed時)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text("■ 送信イベント ( com.intentplayer.PLAYBACK_EVENT )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Extras:\n・event (String): track_completed | playlist_completed\n・trackName (String): 対象のトラック名",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text("■ エラー通知 ( com.intentplayer.ERROR )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Extras:\n・reason (String): no_files | permission_denied | playback_failed など\n・message (String): エラーの詳細メッセージ",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "バージョン: $appVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("エラーログ (直近20件)", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::clearErrorLogs) { Text("クリア") }
            }
            if (errorLogs.isEmpty()) {
                Text("エラーログはありません", modifier = Modifier.padding(top = 8.dp))
            } else {
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

@Composable
private fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AutoResumeTimeoutEditor(
    timeoutMs: Long,
    onTimeoutChanged: (Long) -> Unit
) {
    val seconds = (timeoutMs / 1000L).coerceIn(
        PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS / 1000L,
        PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS / 1000L
    )
    var secondsText by remember(seconds) { mutableStateOf(seconds.toString()) }
    val minMinutes = PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS / 60_000f
    val maxMinutes = PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS / 60_000f
    val sliderMinutes = timeoutMs / 60_000f

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("有効期限", style = MaterialTheme.typography.titleMedium)
            Text(formatDuration(timeoutMs), style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = sliderMinutes.coerceIn(minMinutes, maxMinutes),
            onValueChange = { minutes ->
                onTimeoutChanged(
                    (minutes * 60_000f).roundToLong().coerceIn(
                        PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS,
                        PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS
                    )
                )
            },
            valueRange = minMinutes..maxMinutes
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = secondsText,
            onValueChange = { input ->
                val digits = input.filter { it.isDigit() }.take(5)
                secondsText = digits
                digits.toLongOrNull()?.let { parsed ->
                    onTimeoutChanged(
                        (parsed * 1000L).coerceIn(
                            PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS,
                            PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS
                        )
                    )
                }
            },
            label = { Text("秒数で指定") },
            supportingText = { Text("1〜86400秒（24時間）。スライダーと数値入力は常に同期します。") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> if (seconds > 0L) "${hours}時間${minutes}分${seconds}秒" else "${hours}時間${minutes}分"
        minutes > 0L -> if (seconds > 0L) "${minutes}分${seconds}秒" else "${minutes}分"
        else -> "${seconds}秒"
    }
}
