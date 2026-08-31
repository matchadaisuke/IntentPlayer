package com.intentplayer.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intentplayer.storage.PreferencesManager
import kotlin.math.roundToLong

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
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            context.getSharedPreferences("intent_player_prefs", Context.MODE_PRIVATE).edit().putString("default_folder_uri", uri.toString()).apply()
            defaultFolder = uri
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("設定") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Category(Icons.Default.PlayCircle, "再生")
            SwitchRow("独自の再生システムを有効にする", "有効にするとAndroid標準の再生システムを使わずに再生します。イヤホンやスマートウォッチなどの外部機器には再生中のファイル情報が表示されず、外部機器からの再生操作も受け付けません。IntentPlayerとインテントからの操作は引き続き使用できます。", custom, viewModel::setUseCustomMediaPlayback)
            SwitchRow("独自の音量調節を有効にする", "有効にするとIntentPlayerの音量を0〜500%の範囲で個別に調節できます。100%を超える設定では音を増幅するため、ファイルや端末によっては音割れや歪みが発生する場合があります。", appVolume, viewModel::setEnableAppVolume)
            SwitchRow("音量0で一時停止する", "有効にすると端末やBluetooth機器の音量が0になったときに自動で一時停止します。同じ出力先の音量を上げると、自動で再生を再開します。", mutePause, viewModel::setBlockSpeakerMutePlaybackEnabled)
            SwitchRow("オーディオフォーカスを送信しない", "有効にするとIntentPlayerの再生開始時に他のアプリへオーディオフォーカスを要求しません。そのため、他の音楽・動画アプリなどの再生を止めずにIntentPlayerを再生できます。", focusSend, viewModel::setBlockAudioFocusSend)
            SwitchRow("オーディオフォーカスを受信しない", "有効にすると他のアプリによるオーディオフォーカスの変化を受けてもIntentPlayerを一時停止しません。他のアプリが音声を再生し始めても、そのまま再生を続けます。", focusReceive, viewModel::setBlockAudioFocusReceive)

            Category(Icons.Default.Bluetooth, "接続")
            SwitchRow("Bluetooth接続に合わせて一時停止・再開する", "有効にすると、再生中にイヤホンやBluetooth機器との接続が切れたときに自動で一時停止し、再接続されたときに続きから自動で再生します。", bluetooth, viewModel::setAutoBluetoothControlEnabled)
            if (bluetooth) {
                SwitchRow("自動再開の有効時間を設定する", "有効にすると、Bluetooth機器との接続が切れてから指定した時間を過ぎた場合、再接続されても自動で再生しません。", timeoutEnabled, viewModel::setAutoResumeTimeoutEnabled)
                if (timeoutEnabled) TimeoutEditor(timeoutMs, viewModel::setAutoResumeTimeoutMs)
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Bluetooth再接続後の待ち時間", style = MaterialTheme.typography.titleMedium); Text("${reconnectMs}ms") }
                    Text("Bluetooth機器が再接続されてから、自動で再生を始めるまでの待ち時間を設定します。再接続直後の再生が不安定な場合は、待ち時間を長くしてください。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(reconnectMs.toFloat(), { viewModel.setBluetoothReconnectDelayMs(((it / 100f).roundToLong() * 100).toInt()) }, valueRange = 0f..5000f, steps = 49)
                }
            }

            Category(Icons.Default.Folder, "ファイルとフォルダ")
            LinkRow(Icons.Default.AdminPanelSettings, "すべてのファイルへのアクセス", if (allFilesAllowed()) "許可されています。端末内のファイルをスキャンできます。" else "未許可です。Androidの設定からIntentPlayerにすべてのファイルへのアクセスを許可してください。", if (allFilesAllowed()) "許可済み" else "設定") { openAllFilesSettings(context) }
            LinkRow(Icons.Default.FolderSpecial, "既定のフォルダ", "インテントで再生フォルダが指定されていない場合に使用します。\n${folderName(defaultFolder)}", "選択") { folderPicker.launch(null) }
            Text("フォルダ画面で選んだフォルダが現在のキューになります。既定のフォルダは、フォルダ指定のないインテント再生に使用します。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))

            Category(Icons.Default.Info, "このアプリについて")
            LinkRow(Icons.Default.IntegrationInstructions, "インテントの使い方", "MacroDroid・Tasker・Automateなどから操作する方法をGitHubで確認します。", "開く") { openUrl(context, GUIDE_URL) }
            LinkRow(Icons.Default.Code, "GitHubリポジトリ", "ソースコード、更新履歴、ドキュメントを確認します。", "開く") { openUrl(context, REPO_URL) }
            LinkRow(Icons.Default.Gavel, "ライセンス情報", "IntentPlayer本体と使用しているオープンソースソフトウェアのライセンスを確認します。", "表示") { licenses = true }
            LinkRow(Icons.Default.Apps, "バージョン", "現在インストールされているバージョンです。", version) {}
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { Text("最近のエラー", style = MaterialTheme.typography.titleMedium); TextButton(viewModel::clearErrorLogs) { Text("クリア") } }
            if (errors.isEmpty()) Text("エラーはありません", style = MaterialTheme.typography.bodySmall) else errors.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp)) }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun Category(icon: ImageVector, title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    HorizontalDivider()
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change) }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, description: String, trailing: String, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 14.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis) }; Spacer(Modifier.width(8.dp)); Text(trailing, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun TimeoutEditor(ms: Long, change: (Long) -> Unit) {
    val min = PreferencesManager.MIN_AUTO_RESUME_TIMEOUT_MS / 60_000L
    val max = PreferencesManager.MAX_AUTO_RESUME_TIMEOUT_MS / 60_000L
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("自動再開の有効時間", style = MaterialTheme.typography.titleMedium); Text(durationText(ms)) }
        Text("自動再開を受け付ける時間を10分〜24時間の範囲で設定します。スライダーでは10分単位で指定できます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider((ms / 60_000L).toFloat(), { value -> change(((value / 10f).roundToLong() * 10L).coerceIn(min, max) * 60_000L) }, valueRange = min.toFloat()..max.toFloat(), steps = ((max - min) / 10 - 1).toInt().coerceAtLeast(0))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseScreen(back: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("ライセンス情報") }, navigationIcon = { IconButton(back) { Icon(Icons.Default.ArrowBack, "戻る") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("IntentPlayer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("MIT License\nCopyright © 2026 matchadaisuke", modifier = Modifier.padding(vertical = 8.dp)); TextButton({ openUrl(context, LICENSE_URL) }) { Text("MIT License全文をGitHubで表示") }
            HorizontalDivider(Modifier.padding(vertical = 16.dp)); Text("使用しているオープンソースソフトウェア", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            LicenseItem("AndroidX / Jetpack Compose", "Apache License 2.0"); LicenseItem("AndroidX Media3 / ExoPlayer", "Apache License 2.0"); LicenseItem("AndroidX Core / Lifecycle / Activity / DocumentFile / Media", "Apache License 2.0"); LicenseItem("Kotlin Coroutines for Android", "Apache License 2.0")
            Text("各ライブラリの著作権表示とライセンス条件は、それぞれの配布元が提供するライセンスに従います。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LicenseItem(name: String, license: String) { ListItem(headlineContent = { Text(name) }, supportingContent = { Text(license) }, leadingContent = { Icon(Icons.Default.Description, null) }) }
private fun loadDefaultFolder(context: Context): Uri? = context.getSharedPreferences("intent_player_prefs", Context.MODE_PRIVATE).getString("default_folder_uri", null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
private fun folderName(uri: Uri?): String = uri?.lastPathSegment?.replace("primary:", "/") ?: "未設定"
private fun durationText(ms: Long): String { val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; return if (h > 0) "${h}時間${if (m > 0) "${m}分" else ""}" else "${m}分" }
private fun allFilesAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
private fun openAllFilesSettings(context: Context) { if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return; try { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) } catch (_: Exception) { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
private fun openUrl(context: Context, url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
