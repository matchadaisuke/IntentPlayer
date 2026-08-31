package com.intentplayer.ui

import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
                title = "外部機器に再生情報を出さない",
                description = "オンにすると、イヤホンや時計からの再生操作を受け付けず、再生中の曲情報も外部機器に表示しません。アプリ内とMacroDroidからは操作できます。",
                checked = useCustomMediaPlayback,
                onCheckedChange = viewModel::setUseCustomMediaPlayback
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "アプリの音量をさらに大きくする",
                description = "オンにすると、アプリ内の音量を0〜500%で調整できます。100%を超えると音を増幅するため、音源や端末によっては音が歪むことがあります。",
                checked = enableAppVolume,
                onCheckedChange = viewModel::setEnableAppVolume
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "イヤホンを外したら止め、戻したら再開",
                description = "イヤホンやBluetooth機器が外れたら自動で一時停止し、つなぎ直したら続きから再生します。",
                checked = autoBluetoothControlEnabled,
                onCheckedChange = viewModel::setAutoBluetoothControlEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (autoBluetoothControlEnabled) {
                SettingSwitchRow(
                    title = "自動再開できる時間を決める",
                    description = "オンにすると、イヤホンを外してから指定した時間を過ぎた場合は、つなぎ直しても自動再開しません。10分〜24時間で設定できます。",
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
                title = "音量を0にしたら自動で一時停止",
                description = "端末やBluetooth機器の音量を0にすると自動で一時停止し、同じ出力先の音量を上げると自動で再開します。",
                checked = blockSpeakerMutePlaybackEnabled,
                onCheckedChange = viewModel::setBlockSpeakerMutePlaybackEnabled
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "他のアプリの音楽を止めずに再生する",
                description = "オンにすると、IntentPlayerを再生しても他の音楽アプリへ停止を求めません。オフにすると、他のアプリの音が止まる場合があります。",
                checked = blockAudioFocusSend,
                onCheckedChange = viewModel::setBlockAudioFocusSend
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            SettingSwitchRow(
                title = "他のアプリが再生を始めても止まらない",
                description = "オンにすると、別の音楽アプリや動画アプリが音を出し始めてもIntentPlayerはそのまま再生を続けます。",
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
                        Text("イヤホン接続後、再生まで少し待つ", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "イヤホンやBluetooth機器をつなぎ直したあと、再生を始めるまでの待ち時間です。機器の切り替えが不安定なときに少し長くします。",
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
                    Text("MacroDroidなどから操作するための情報", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("■ 受け付ける操作 ( com.intentplayer.CONTROL )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "command: play / force_play / pause / stop / next / previous / seek / speed\n" +
                            "folderUri: 再生するフォルダ\n" +
                            "seekTo: 移動先の再生位置（ミリ秒）\n" +
                            "speed: 再生速度 0.5〜2.0",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text("■ 再生完了のお知らせ ( com.intentplayer.PLAYBACK_EVENT )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "event: track_completed / playlist_completed\ntrackName: 対象の曲名",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Text("■ エラーのお知らせ ( com.intentplayer.ERROR )", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "reason: エラーの種類\nmessage: エラー内容",
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
                Text("最近のエラー (20件まで)", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::clearErrorLogs) { Text("クリア") }
            }
            if (errorLogs.isEmpty()) {
                Text("エラーはありません", modifier = Modifier.padding(top = 8.dp))
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
    val minMs = PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS
    val maxMs = PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS
    val safeMs = timeoutMs.coerceIn(minMs, maxMs)
    val minMinutes = (minMs / 60_000L).toInt()
    val maxMinutes = (maxMs / 60_000L).toInt()
    val totalMinutes = (safeMs / 60_000L).toInt()
    var showDetailDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("自動再開できる時間", style = MaterialTheme.typography.titleMedium)
            Text(formatDuration(safeMs), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "スライダーは10分刻みで大まかに調整できます。秒単位で決めたい場合は詳細設定を使ってください。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = totalMinutes.toFloat(),
            onValueChange = { minutes ->
                val roundedMinutes = ((minutes / 10f).roundToLong() * 10L)
                    .coerceIn(minMinutes.toLong(), maxMinutes.toLong())
                onTimeoutChanged(roundedMinutes * 60_000L)
            },
            valueRange = minMinutes.toFloat()..maxMinutes.toFloat(),
            steps = ((maxMinutes - minMinutes) / 10 - 1).coerceAtLeast(0)
        )
        TextButton(onClick = { showDetailDialog = true }) {
            Text("詳しく設定する")
        }
    }

    if (showDetailDialog) {
        TimeoutDetailDialog(
            timeoutMs = safeMs,
            onDismiss = { showDetailDialog = false },
            onConfirm = { newMs ->
                onTimeoutChanged(newMs.coerceIn(minMs, maxMs))
                showDetailDialog = false
            }
        )
    }
}

@Composable
private fun TimeoutDetailDialog(
    timeoutMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val initialSeconds = (timeoutMs / 1000L).coerceIn(600L, 86_400L)
    var hours by remember { mutableStateOf((initialSeconds / 3600L).toInt()) }
    var minutes by remember { mutableStateOf(((initialSeconds % 3600L) / 60L).toInt()) }
    var seconds by remember { mutableStateOf((initialSeconds % 60L).toInt()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("時間を詳しく設定") },
        text = {
            Column {
                Text(
                    "時間・分・秒をそれぞれ上下に回して選びます。設定できる範囲は10分〜24時間です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberWheel("時間", hours, 0, 24) { hours = it }
                    NumberWheel("分", minutes, 0, 59) { minutes = it }
                    NumberWheel("秒", seconds, 0, 59) { seconds = it }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val totalSeconds = (hours * 3600L + minutes * 60L + seconds)
                    .coerceIn(600L, 86_400L)
                onConfirm(totalSeconds * 1000L)
            }) { Text("決定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

@Composable
private fun NumberWheel(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    onValueChanged: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        AndroidView(
            factory = { context ->
                NumberPicker(context).apply {
                    this.minValue = minValue
                    this.maxValue = maxValue
                    wrapSelectorWheel = true
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setOnValueChangedListener { _, _, newValue -> onValueChanged(newValue) }
                }
            },
            update = { picker ->
                picker.minValue = minValue
                picker.maxValue = maxValue
                if (picker.value != value) picker.value = value.coerceIn(minValue, maxValue)
            },
            modifier = Modifier.width(88.dp).height(150.dp)
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
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
