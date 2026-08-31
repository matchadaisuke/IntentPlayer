package com.intentplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.intentplayer.storage.PreferencesManager
import kotlin.math.roundToInt

private const val REPO_URL = "https://github.com/matchadaisuke/IntentPlayer"
private const val GUIDE_URL = "https://github.com/matchadaisuke/IntentPlayer/blob/main/docs/INTENT_GUIDE.md"
private const val LICENSE_URL = "https://github.com/matchadaisuke/IntentPlayer/blob/main/LICENSE"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    var licenses by remember { mutableStateOf(false) }
    if (licenses) { LicenseScreen { licenses = false }; return }

    val custom by viewModel.useCustomMediaPlayback.collectAsState()
    val appVolume by viewModel.enableAppVolume.collectAsState()
    val mutePause by viewModel.blockSpeakerMutePlaybackEnabled.collectAsState()
    val focusSend by viewModel.blockAudioFocusSend.collectAsState()
    val focusReceive by viewModel.blockAudioFocusReceive.collectAsState()
    val bluetooth by viewModel.autoBluetoothControlEnabled.collectAsState()
    val timeoutEnabled by viewModel.autoResumeTimeoutEnabled.collectAsState()
    val timeoutMs by viewModel.autoResumeTimeoutMs.collectAsState()
    val reconnectMs by viewModel.bluetoothReconnectDelayMs.collectAsState()
    val version by viewModel.appVersion.collectAsState()
    val errors by viewModel.errorLogs.collectAsState()

    var defaultFolder by remember { mutableStateOf(loadDefaultFolder(context)) }
    var timeoutDialog by remember { mutableStateOf(false) }
    var reconnectDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val persisted = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                true
            }.getOrDefault(false)
            if (!persisted) {
                Toast.makeText(context, "このフォルダの永続アクセス権を保存できませんでした", Toast.LENGTH_LONG).show()
            }
            context.getSharedPreferences("intent_player_prefs", Context.MODE_PRIVATE)
                .edit().putString("default_folder_uri", uri.toString()).apply()
            defaultFolder = uri
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use {
                    it.write(PreferencesManager.exportSettings(context))
                } ?: error("保存先を開けませんでした")
            }.onSuccess {
                Toast.makeText(context, "設定をエクスポートしました", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "エクスポートに失敗しました: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    val text = reader.readText()
                    require(text.length <= 256 * 1024) { "バックアップファイルが大きすぎます" }
                    text
                } ?: error("バックアップを開けませんでした")
                PreferencesManager.importSettings(context, json)
                applyImportedSettings(viewModel, context)
                defaultFolder = loadDefaultFolder(context)
            }.onSuccess {
                Toast.makeText(context, "設定をインポートしました", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "インポートに失敗しました: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (timeoutDialog) {
        DurationPickerDialog(
            title = "自動再開の有効時間",
            initialMs = timeoutMs,
            minMs = PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS,
            maxMs = PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS,
            onDismiss = { timeoutDialog = false },
            onConfirm = {
                viewModel.setAutoResumeTimeoutMs(it)
                timeoutDialog = false
            }
        )
    }

    if (reconnectDialog) {
        MillisecondPickerDialog(
            title = "Bluetooth再接続後の待ち時間",
            initialMs = reconnectMs,
            maxMs = 5000,
            onDismiss = { reconnectDialog = false },
            onConfirm = {
                viewModel.setBluetoothReconnectDelayMs(it)
                reconnectDialog = false
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("設定") }) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Category(Icons.Default.PlayCircle, "再生")
            SwitchRow(
                "独自の再生システムを有効にする",
                "有効にするとAndroid標準の再生システムを使わずに再生します。イヤホンやスマートウォッチなどの外部機器には再生中のファイル情報が表示されず、外部機器からの再生操作も受け付けません。IntentPlayerとインテントからの操作は引き続き使用できます。",
                custom,
                viewModel::setUseCustomMediaPlayback
            )
            SwitchRow(
                "独自の音量調節を有効にする",
                "有効にするとIntentPlayerの音量を0〜500%の範囲で個別に調節できます。100%を超える設定では音を増幅するため、ファイルや端末によっては音割れや歪みが発生する場合があります。",
                appVolume,
                viewModel::setEnableAppVolume
            )
            SwitchRow(
                "音量0で一時停止する",
                "有効にすると端末やBluetooth機器の音量が0になったときに自動で一時停止します。同じ出力先の音量を上げると、自動で再生を再開します。",
                mutePause,
                viewModel::setBlockSpeakerMutePlaybackEnabled
            )
            SwitchRow(
                "オーディオフォーカスを送信しない",
                "有効にするとIntentPlayerの再生開始時に他のアプリへオーディオフォーカスを要求しません。そのため、他の音楽・動画アプリなどの再生を止めずにIntentPlayerを再生できます。",
                focusSend,
                viewModel::setBlockAudioFocusSend
            )
            SwitchRow(
                "オーディオフォーカスを受信しない",
                "有効にすると他のアプリによるオーディオフォーカスの変化を受けてもIntentPlayerを一時停止しません。他のアプリが音声を再生し始めても、そのまま再生を続けます。",
                focusReceive,
                viewModel::setBlockAudioFocusReceive
            )

            Category(Icons.Default.Bluetooth, "接続")
            SwitchRow(
                "Bluetooth接続に合わせて一時停止・再開する",
                "有効にすると、再生中にイヤホンやBluetooth機器との接続が切れたときに自動で一時停止し、再接続されたときに続きから自動で再生します。",
                bluetooth,
                viewModel::setAutoBluetoothControlEnabled
            )
            if (bluetooth) {
                SwitchRow(
                    "自動再開の有効時間を設定する",
                    "有効にすると、Bluetooth機器との接続が切れてから指定した時間を過ぎた場合、再接続されても自動で再生しません。",
                    timeoutEnabled,
                    viewModel::setAutoResumeTimeoutEnabled
                )
                if (timeoutEnabled) {
                    ValueSliderRow(
                        title = "自動再開の有効時間",
                        description = "自動再開を受け付ける時間を1分〜24時間の範囲で設定します。スライダーは1分単位です。右の現在値を押すと、時・分・秒をロール式で詳しく設定できます。",
                        valueText = durationTextDetailed(timeoutMs),
                        value = (timeoutMs / 60_000f).coerceIn(1f, 1440f),
                        range = 1f..1440f,
                        onValueClick = { timeoutDialog = true },
                        onValueChange = { viewModel.setAutoResumeTimeoutMs(it.roundToInt().toLong() * 60_000L) }
                    )
                }

                ValueSliderRow(
                    title = "Bluetooth再接続後の待ち時間",
                    description = "Bluetooth機器が再接続されてから、自動で再生を始めるまでの待ち時間を設定します。再接続直後の再生が不安定な場合は、待ち時間を長くしてください。右の現在値から細かく指定できます。",
                    valueText = "${reconnectMs}ms",
                    value = reconnectMs.toFloat(),
                    range = 0f..5000f,
                    onValueClick = { reconnectDialog = true },
                    onValueChange = { viewModel.setBluetoothReconnectDelayMs((it / 100f).roundToInt() * 100) }
                )
            }

            Category(Icons.Default.Folder, "ファイルとフォルダ")
            LinkRow(
                Icons.Default.AdminPanelSettings,
                "すべてのファイルへのアクセス",
                if (allFilesAllowed()) "許可されています。端末内のファイルをスキャンできます。" else "未許可です。必要な場合はここを押してAndroidの設定を開いてください。自動では設定画面を開きません。SAFでもフォルダを選べます。",
                if (allFilesAllowed()) "許可済み" else "設定"
            ) { openAllFilesSettings(context) }
            LinkRow(
                Icons.Default.FolderSpecial,
                "既定のフォルダ",
                "インテントで再生フォルダが指定されていない場合に使用します。\n${folderName(defaultFolder)}",
                "選択"
            ) { folderPicker.launch(null) }
            Text(
                "フォルダ画面で選んだフォルダが現在のキューになります。既定のフォルダは、フォルダ指定のないインテント再生に使用します。SAFの既定フォルダは端末側の永続アクセス権が必要です。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )

            Category(Icons.Default.Backup, "バックアップ")
            LinkRow(
                Icons.Default.UploadFile,
                "設定をエクスポート",
                "現在の設定をJSONファイルとして保存します。保存先はSAFで選択できます。再生位置や最近のエラーは含みません。",
                "保存"
            ) { exportLauncher.launch("IntentPlayer-settings.json") }
            LinkRow(
                Icons.Default.Download,
                "設定をインポート",
                "エクスポートしたIntentPlayerの設定を検証して読み込みます。既定フォルダは、この端末でアクセス権が残っている場合だけ復元します。",
                "読込"
            ) { importLauncher.launch(arrayOf("application/json", "text/plain")) }

            Category(Icons.Default.Info, "このアプリについて")
            LinkRow(Icons.Default.IntegrationInstructions, "インテントの使い方", "MacroDroid・Tasker・Automateなどから操作する方法をGitHubで確認します。", "開く") { openUrl(context, GUIDE_URL) }
            LinkRow(Icons.Default.Code, "GitHubリポジトリ", "ソースコード、更新履歴、ドキュメントを確認します。", "開く") { openUrl(context, REPO_URL) }
            LinkRow(Icons.Default.Gavel, "ライセンス情報", "IntentPlayer本体と使用しているオープンソースソフトウェアのライセンスを確認します。", "表示") { licenses = true }
            LinkRow(Icons.Default.Apps, "バージョン", "現在インストールされているバージョンです。", version) {}
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("最近のエラー", style = MaterialTheme.typography.titleMedium)
                TextButton(viewModel::clearErrorLogs) { Text("クリア") }
            }
            if (errors.isEmpty()) {
                Text("エラーはありません", style = MaterialTheme.typography.bodySmall)
            } else {
                errors.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Category(icon: ImageVector, title: String) {
    Row(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
    HorizontalDivider()
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, change)
    }
}

@Composable
private fun ValueSliderRow(
    title: String,
    description: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueClick: () -> Unit,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onValueClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text(valueText)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.Edit, contentDescription = "詳しく設定", modifier = Modifier.size(16.dp))
            }
        }
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, description: String, trailing: String, click: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(trailing, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DurationPickerDialog(
    title: String,
    initialMs: Long,
    minMs: Long,
    maxMs: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    var selectedMs by remember(initialMs) { mutableLongStateOf(initialMs.coerceIn(minMs, maxMs)) }
    val pickerTextColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AlertDialog(
        modifier = Modifier.widthIn(max = 390.dp),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("時　　分　　秒", style = MaterialTheme.typography.labelMedium)
                AndroidView(
                    factory = { ctx ->
                        val hour = NumberPicker(ctx).apply {
                            minValue = 0
                            maxValue = 24
                            wrapSelectorWheel = false
                            applyNumberPickerTextColor(this, pickerTextColor)
                        }
                        val minute = NumberPicker(ctx).apply {
                            minValue = 0
                            maxValue = 59
                            wrapSelectorWheel = true
                            applyNumberPickerTextColor(this, pickerTextColor)
                        }
                        val second = NumberPicker(ctx).apply {
                            minValue = 0
                            maxValue = 59
                            wrapSelectorWheel = true
                            applyNumberPickerTextColor(this, pickerTextColor)
                        }
                        fun update() {
                            if (hour.value == 24) {
                                minute.value = 0
                                second.value = 0
                            }
                            selectedMs = ((hour.value * 3600L + minute.value * 60L + second.value) * 1000L)
                                .coerceIn(minMs, maxMs)
                        }
                        val total = initialMs.coerceIn(minMs, maxMs) / 1000L
                        hour.value = (total / 3600L).toInt()
                        minute.value = ((total % 3600L) / 60L).toInt()
                        second.value = (total % 60L).toInt()
                        hour.setOnValueChangedListener { _, _, _ -> update() }
                        minute.setOnValueChangedListener { _, _, _ -> update() }
                        second.setOnValueChangedListener { _, _, _ -> update() }
                        LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER
                            addView(hour)
                            addView(minute)
                            addView(second)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(durationTextDetailed(selectedMs), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selectedMs.coerceIn(minMs, maxMs)) }) { Text("設定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@Composable
private fun MillisecondPickerDialog(
    title: String,
    initialMs: Int,
    maxMs: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selected by remember(initialMs) { mutableIntStateOf(initialMs.coerceIn(0, maxMs)) }
    val pickerTextColor = MaterialTheme.colorScheme.onSurface.toArgb()
    AlertDialog(
        modifier = Modifier.widthIn(max = 360.dp),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("秒　　 ミリ秒", style = MaterialTheme.typography.labelMedium)
                AndroidView(
                    factory = { ctx ->
                        val seconds = NumberPicker(ctx).apply {
                            minValue = 0
                            maxValue = maxMs / 1000
                            wrapSelectorWheel = false
                            applyNumberPickerTextColor(this, pickerTextColor)
                        }
                        val millis = NumberPicker(ctx).apply {
                            minValue = 0
                            maxValue = 99
                            displayedValues = Array(100) { (it * 10).toString().padStart(3, '0') }
                            wrapSelectorWheel = true
                            applyNumberPickerTextColor(this, pickerTextColor)
                        }
                        seconds.value = initialMs.coerceIn(0, maxMs) / 1000
                        millis.value = (initialMs.coerceIn(0, maxMs) % 1000) / 10
                        fun update() {
                            if (seconds.value == maxMs / 1000 && maxMs % 1000 == 0) millis.value = 0
                            selected = (seconds.value * 1000 + millis.value * 10).coerceIn(0, maxMs)
                        }
                        seconds.setOnValueChangedListener { _, _, _ -> update() }
                        millis.setOnValueChangedListener { _, _, _ -> update() }
                        LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER
                            addView(seconds)
                            addView(millis)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${selected}ms")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected.coerceIn(0, maxMs)) }) { Text("設定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseScreen(back: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("ライセンス情報") },
                navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "戻る") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text("IntentPlayer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("MIT License\nCopyright © 2026 matchadaisuke", modifier = Modifier.padding(vertical = 8.dp))
            TextButton({ openUrl(context, LICENSE_URL) }) { Text("MIT License全文をGitHubで表示") }
            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text("使用しているオープンソースソフトウェア", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LicenseItem("AndroidX / Jetpack Compose", "Apache License 2.0")
            LicenseItem("AndroidX Media3 / ExoPlayer", "Apache License 2.0")
            LicenseItem("AndroidX Core / Lifecycle / Activity / DocumentFile / Media", "Apache License 2.0")
            LicenseItem("Kotlin Coroutines for Android", "Apache License 2.0")
            Text("各ライブラリの著作権表示とライセンス条件は、それぞれの配布元が提供するライセンスに従います。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LicenseItem(name: String, license: String) {
    ListItem(headlineContent = { Text(name) }, supportingContent = { Text(license) }, leadingContent = { Icon(Icons.Default.Description, null) })
}

private fun applyImportedSettings(viewModel: MainViewModel, context: Context) {
    viewModel.setAutoBluetoothControlEnabled(PreferencesManager.isAutoBluetoothControlEnabled(context))
    viewModel.setBlockAudioFocusSend(PreferencesManager.isBlockAudioFocusSend(context))
    viewModel.setBlockAudioFocusReceive(PreferencesManager.isBlockAudioFocusReceive(context))
    viewModel.setBluetoothReconnectDelayMs(PreferencesManager.getBluetoothReconnectDelayMs(context))
    viewModel.setBlockSpeakerMutePlaybackEnabled(PreferencesManager.isBlockSpeakerMutePlaybackEnabled(context))
    viewModel.setAutoResumeTimeoutEnabled(PreferencesManager.isAutoResumeTimeoutEnabled(context))
    viewModel.setAutoResumeTimeoutMs(PreferencesManager.getAutoResumeTimeoutMs(context))
    viewModel.setUseCustomMediaPlayback(PreferencesManager.isUseCustomMediaPlayback(context))
    viewModel.setEnableAppVolume(PreferencesManager.isEnableAppVolume(context))
    viewModel.setAppPlaybackVolume(PreferencesManager.getAppPlaybackVolume(context))
    viewModel.setPlaybackSpeed(PreferencesManager.getPlaybackSpeed(context))
}

private fun loadDefaultFolder(context: Context): Uri? =
    context.getSharedPreferences("intent_player_prefs", Context.MODE_PRIVATE)
        .getString("default_folder_uri", null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }

private fun folderName(uri: Uri?): String = uri?.lastPathSegment?.replace("primary:", "/") ?: "未設定"

private fun durationTextDetailed(ms: Long): String {
    val total = ms.coerceAtLeast(0L) / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return when {
        h > 0 -> "${h}時間${m}分${s}秒"
        m > 0 -> "${m}分${s}秒"
        else -> "${s}秒"
    }
}

private fun allFilesAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
    } catch (_: Exception) {
        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            .onFailure { Toast.makeText(context, "設定画面を開けませんでした。SAFを利用してください。", Toast.LENGTH_LONG).show() }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, "リンクを開けませんでした", Toast.LENGTH_LONG).show() }
}
