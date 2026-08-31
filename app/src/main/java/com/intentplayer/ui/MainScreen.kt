package com.intentplayer.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intentplayer.storage.PreferencesManager

private enum class MainTab { PLAYER, QUEUE, FOLDERS, SETTINGS }

@Composable
fun MainScreen(viewModel: MainViewModel, onSelectFolderClick: () -> Unit, onBatteryOptimizationClick: () -> Unit) {
    val screen by viewModel.currentScreen.collectAsState()
    if (screen == MainViewModel.AppScreen.ONBOARDING) {
        OnboardingScreen(viewModel, onSelectFolderClick, onBatteryOptimizationClick)
        return
    }
    val folderUri by viewModel.folderUri.collectAsState()
    var tab by remember(screen) { mutableStateOf(if (screen == MainViewModel.AppScreen.SETTINGS) MainTab.SETTINGS else if (folderUri == null) MainTab.FOLDERS else MainTab.PLAYER) }
    Scaffold(bottomBar = {
        NavigationBar {
            NavItem(MainTab.PLAYER, tab, Icons.Default.PlayCircle, "再生") { tab = it }
            NavItem(MainTab.QUEUE, tab, Icons.Default.QueueMusic, "キュー") { tab = it }
            NavItem(MainTab.FOLDERS, tab, Icons.Default.Folder, "フォルダ") { tab = it }
            NavItem(MainTab.SETTINGS, tab, Icons.Default.Settings, "設定") { tab = it }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.PLAYER -> PlayerTab(viewModel, onBatteryOptimizationClick)
                MainTab.QUEUE -> QueueTab(viewModel)
                MainTab.FOLDERS -> FolderTab(viewModel, onSelectFolderClick) { tab = MainTab.QUEUE }
                MainTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(tab: MainTab, selected: MainTab, icon: ImageVector, label: String, select: (MainTab) -> Unit) {
    NavigationBarItem(selected == tab, { select(tab) }, { Icon(icon, label) }, label = { Text(label) })
}

@Composable
private fun PlayerTab(viewModel: MainViewModel, onBatteryOptimizationClick: () -> Unit) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val track by viewModel.currentTrack.collectAsState()
    val position by viewModel.currentPositionMs.collectAsState()
    val duration by viewModel.durationMs.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()
    val volumeEnabled by viewModel.enableAppVolume.collectAsState()
    val volume by viewModel.appPlaybackVolume.collectAsState()
    val batteryOptimized by viewModel.isBatteryOptimized.collectAsState()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Text("再生", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            if (batteryOptimized) Card(Modifier.fillMaxWidth().padding(top = 12.dp)) { Column(Modifier.padding(14.dp)) { Text("バッテリー最適化が有効です"); TextButton(onClick = onBatteryOptimizationClick) { Text("設定を開く") } } }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(Modifier.size(190.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.GraphicEq, null, modifier = Modifier.size(88.dp)) } }
                    Spacer(Modifier.height(24.dp))
                    Text(track?.name ?: "再生するファイルがありません", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    SeekSection(position, duration, speed, track != null, viewModel::seekTo)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(viewModel::previous, enabled = track != null, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipPrevious, "前へ", modifier = Modifier.size(34.dp)) }
                        FilledIconButton({ if (isPlaying) viewModel.pause() else viewModel.resume() }, enabled = track != null, modifier = Modifier.size(76.dp)) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "一時停止" else "再生", modifier = Modifier.size(46.dp)) }
                        IconButton(viewModel::next, enabled = track != null, modifier = Modifier.size(56.dp)) { Icon(Icons.Default.SkipNext, "次へ", modifier = Modifier.size(34.dp)) }
                        FilledTonalIconButton(viewModel::stop, enabled = track != null, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Stop, "停止") }
                    }
                    Spacer(Modifier.height(20.dp))
                    SpeedSelector(speed, viewModel::setPlaybackSpeed)
                    if (volumeEnabled) {
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("音量"); Text("${(volume * 100).toInt()}%") }
                        Slider(volume.coerceIn(0f, PreferencesManager.MAX_APP_PLAYBACK_VOLUME), viewModel::setAppPlaybackVolume, valueRange = 0f..PreferencesManager.MAX_APP_PLAYBACK_VOLUME, steps = 39, enabled = track != null)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekSection(position: Long, duration: Long, speed: Float, enabled: Boolean, seek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(0f) }
    val actual = if (dragging) value else if (duration > 0) position.toFloat() / duration else 0f
    val shown = if (duration > 0) (actual.coerceIn(0f, 1f) * duration).toLong() else 0L
    val remaining = ((duration - shown).coerceAtLeast(0L) / (speed.takeIf { it > 0f } ?: 1f)).toLong()
    Slider(actual.coerceIn(0f, 1f), { dragging = true; value = it }, onValueChangeFinished = { dragging = false; if (duration > 0) seek((value * duration).toLong()) }, enabled = enabled)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(clock(shown)); Text(clock(duration)) }
    Text("残り ${clock(remaining)}", modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SpeedSelector(speed: Float, setSpeed: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("再生速度")
        Box {
            OutlinedButton({ expanded = true }) { Text("${speedText(speed)}×") }
            DropdownMenu(expanded, { expanded = false }) { listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s -> DropdownMenuItem({ Text("${speedText(s)}×") }, { setSpeed(s); expanded = false }) } }
        }
    }
}

@Composable
private fun QueueTab(viewModel: MainViewModel) {
    val tracks by viewModel.tracks.collectAsState()
    val current by viewModel.currentTrack.collectAsState()
    val folder by viewModel.folderUri.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("キュー", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("${tracks.size}件・${clock(tracks.sumOf { it.durationMs.coerceAtLeast(0L) })}", style = MaterialTheme.typography.titleMedium)
        Text(folderName(folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (tracks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("キューは空です") }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(tracks) { index, track ->
                val playing = track.uri == current?.uri
                Card(Modifier.fillMaxWidth().clickable { viewModel.playTrack(index) }, colors = if (playing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (playing) Icons.Default.GraphicEq else Icons.Default.AudioFile, null)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) { Text(track.name, maxLines = 2, overflow = TextOverflow.Ellipsis); Text(clock(track.durationMs), style = MaterialTheme.typography.bodySmall) }
                        Icon(Icons.Default.PlayArrow, "このファイルから再生")
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderTab(viewModel: MainViewModel, selectFolder: () -> Unit, openQueue: () -> Unit) {
    val folder by viewModel.folderUri.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("フォルダ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) {
            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(44.dp)); Spacer(Modifier.height(12.dp))
            Text("現在のフォルダ", style = MaterialTheme.typography.labelLarge); Text(folderName(folder), style = MaterialTheme.typography.titleMedium)
            Text("${tracks.size}件・${clock(tracks.sumOf { it.durationMs.coerceAtLeast(0L) })}"); Spacer(Modifier.height(16.dp))
            Button(selectFolder, Modifier.fillMaxWidth()) { Icon(Icons.Default.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text(if (folder == null) "フォルダを選択" else "別のフォルダを選択") }
            if (tracks.isNotEmpty()) OutlinedButton(openQueue, Modifier.fillMaxWidth()) { Text("キューを見る") }
        } }
        Spacer(Modifier.height(16.dp))
        Text("インテントでフォルダが指定された場合はそのフォルダを再生し、指定がない場合は設定した既定のフォルダを使用します。")
    }
}

private fun folderName(uri: Uri?): String = uri?.lastPathSegment?.replace("primary:", "/") ?: "フォルダが選択されていません"
private fun clock(ms: Long): String { val s = (ms.coerceAtLeast(0L) + 999) / 1000; val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60; return if (h > 0) "$h:${m.toString().padStart(2,'0')}:${sec.toString().padStart(2,'0')}" else "${m.toString().padStart(2,'0')}:${sec.toString().padStart(2,'0')}" }
private fun speedText(speed: Float): String = if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString().trimEnd('0').trimEnd('.')
