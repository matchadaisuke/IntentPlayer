package com.intentplayer.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intentplayer.storage.PreferencesManager
import com.intentplayer.storage.StorageBrowser
import java.io.File

private enum class MainTab { PLAYER, QUEUE, FOLDERS, SETTINGS }

@Composable
fun MainScreen(viewModel: MainViewModel, onSelectFolderClick: () -> Unit, onBatteryOptimizationClick: () -> Unit) {
    val screen by viewModel.currentScreen.collectAsState()
    if (screen == MainViewModel.AppScreen.ONBOARDING) {
        OnboardingScreen(viewModel, onSelectFolderClick, onBatteryOptimizationClick)
        return
    }

    val folderUri by viewModel.folderUri.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    var tab by remember(screen) {
        mutableStateOf(
            if (screen == MainViewModel.AppScreen.SETTINGS) MainTab.SETTINGS
            else if (folderUri == null) MainTab.FOLDERS
            else if (currentTrack == null) MainTab.QUEUE
            else MainTab.PLAYER
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
                NavItem(MainTab.PLAYER, tab, Icons.Default.PlayCircle, "再生") { tab = it }
                NavItem(MainTab.QUEUE, tab, Icons.Default.QueueMusic, "キュー") { tab = it }
                NavItem(MainTab.FOLDERS, tab, Icons.Default.Folder, "フォルダ") { tab = it }
                NavItem(MainTab.SETTINGS, tab, Icons.Default.Settings, "設定") { tab = it }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
        ) {
            when (tab) {
                MainTab.PLAYER -> PlayerTab(viewModel, onBatteryOptimizationClick)
                MainTab.QUEUE -> QueueTab(viewModel)
                MainTab.FOLDERS -> FolderTab(
                    viewModel = viewModel,
                    openSaf = onSelectFolderClick,
                    openQueue = { tab = MainTab.QUEUE }
                )
                MainTab.SETTINGS -> SettingsScreen(viewModel)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(tab: MainTab, selected: MainTab, icon: ImageVector, label: String, select: (MainTab) -> Unit) {
    NavigationBarItem(
        selected = selected == tab,
        onClick = { select(tab) },
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) }
    )
}

@Composable
private fun PlayerTab(viewModel: MainViewModel, onBatteryOptimizationClick: () -> Unit) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val track by viewModel.currentTrack.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val position by viewModel.currentPositionMs.collectAsState()
    val duration by viewModel.durationMs.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()
    val volumeEnabled by viewModel.enableAppVolume.collectAsState()
    val volume by viewModel.appPlaybackVolume.collectAsState()
    val batteryOptimized by viewModel.isBatteryOptimized.collectAsState()

    val selectedTrack = track
    val currentIndex = tracks.indexOfFirst { candidate ->
        candidate.uri == selectedTrack?.uri || candidate.name == selectedTrack?.name
    }
    val followingDuration = if (currentIndex >= 0 && currentIndex + 1 < tracks.size) {
        tracks.subList(currentIndex + 1, tracks.size).sumOf { it.durationMs.coerceAtLeast(0L) }
    } else 0L

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text("再生", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())
            if (batteryOptimized) {
                Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("バッテリー最適化が有効です")
                        TextButton(onClick = onBatteryOptimizationClick) { Text("設定を開く") }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        Modifier.size(190.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(88.dp))
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        track?.name ?: "再生するファイルがありません",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(18.dp))
                    SeekSection(
                        position = position,
                        duration = duration,
                        followingDuration = followingDuration,
                        speed = speed,
                        enabled = track != null,
                        seek = viewModel::seekTo
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(viewModel::previous, enabled = track != null, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipPrevious, "前へ", modifier = Modifier.size(34.dp))
                        }
                        FilledIconButton(
                            { if (isPlaying) viewModel.pause() else viewModel.resume() },
                            enabled = track != null,
                            modifier = Modifier.size(76.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "一時停止" else "再生",
                                modifier = Modifier.size(46.dp)
                            )
                        }
                        IconButton(viewModel::next, enabled = track != null, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.SkipNext, "次へ", modifier = Modifier.size(34.dp))
                        }
                        FilledTonalIconButton(viewModel::stop, enabled = track != null, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Stop, "停止")
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    SpeedSelector(speed, viewModel::setPlaybackSpeed)
                    if (volumeEnabled) {
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("音量")
                            Text("${(volume * 100).toInt()}%")
                        }
                        Slider(
                            value = volume.coerceIn(0f, PreferencesManager.MAX_APP_PLAYBACK_VOLUME),
                            onValueChange = viewModel::setAppPlaybackVolume,
                            valueRange = 0f..PreferencesManager.MAX_APP_PLAYBACK_VOLUME,
                            steps = 39,
                            enabled = track != null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekSection(
    position: Long,
    duration: Long,
    followingDuration: Long,
    speed: Float,
    enabled: Boolean,
    seek: (Long) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(0f) }
    val actual = if (dragging) value else if (duration > 0) position.toFloat() / duration else 0f
    val shown = if (duration > 0) (actual.coerceIn(0f, 1f) * duration).toLong() else 0L
    val safeSpeed = speed.takeIf { it > 0f } ?: 1f
    val currentRemainingMedia = (duration - shown).coerceAtLeast(0L)
    val fileRemaining = (currentRemainingMedia / safeSpeed).toLong()
    val queueRemaining = ((currentRemainingMedia + followingDuration.coerceAtLeast(0L)) / safeSpeed).toLong()

    Slider(
        value = actual.coerceIn(0f, 1f),
        onValueChange = { dragging = true; value = it },
        onValueChangeFinished = {
            dragging = false
            if (duration > 0) seek((value * duration).toLong())
        },
        enabled = enabled
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(clock(shown))
        Text(clock(duration))
    }
    Text(
        text = "残り: ${clock(fileRemaining)}・${clock(queueRemaining)}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SpeedSelector(speed: Float, setSpeed: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("再生速度")
        Box {
            OutlinedButton({ expanded = true }) { Text("${speedText(speed)}×") }
            DropdownMenu(expanded, { expanded = false }) {
                listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                    DropdownMenuItem({ Text("${speedText(s)}×") }, { setSpeed(s); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun QueueTab(viewModel: MainViewModel) {
    val tracks by viewModel.tracks.collectAsState()
    val current by viewModel.currentTrack.collectAsState()
    val folder by viewModel.folderUri.collectAsState()

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("キュー", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("${tracks.size}件・${clock(tracks.sumOf { it.durationMs.coerceAtLeast(0L) })}", style = MaterialTheme.typography.titleMedium)
        Text(folderName(folder), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("キューは空です") }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(tracks) { index, item ->
                    val playing = item.uri == current?.uri
                    Card(
                        Modifier.fillMaxWidth().clickable { viewModel.playTrack(index) },
                        colors = if (playing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (playing) Icons.Default.GraphicEq else Icons.Default.AudioFile, contentDescription = null)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(clock(item.durationMs), style = MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Default.PlayArrow, "このファイルから再生")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderTab(viewModel: MainViewModel, openSaf: () -> Unit, openQueue: () -> Unit) {
    val context = LocalContext.current
    val currentQueueFolder by viewModel.folderUri.collectAsState()
    val roots = remember { StorageBrowser.roots(context) }
    var root by remember { mutableStateOf<StorageBrowser.Root?>(null) }
    var directory by remember { mutableStateOf<File?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val permissionGranted = remember(refreshKey) { allFilesAllowed() }
    val entriesResult = remember(directory, refreshKey) { directory?.let(StorageBrowser::list) }

    fun navigateUp() {
        val current = directory ?: return
        val activeRoot = root?.file
        if (activeRoot != null && sameFile(current, activeRoot)) {
            directory = null
            root = null
        } else {
            directory = current.parentFile ?: activeRoot
        }
    }

    BackHandler(enabled = directory != null) {
        navigateUp()
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (directory != null) {
                IconButton(onClick = ::navigateUp) { Icon(Icons.Default.ArrowBack, "戻る") }
            }
            Column(Modifier.weight(1f)) {
                Text("フォルダ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    directory?.absolutePath ?: "ストレージを選択",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.AdminPanelSettings, null)
                        Spacer(Modifier.width(10.dp))
                        Text("すべてのファイルへのアクセスが必要です", fontWeight = FontWeight.SemiBold)
                    }
                    Text("許可すると内部ストレージやSDカードをファイルマネージャーのように閲覧できます。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { openAllFilesSettings(context) }) { Text("権限を許可") }
                        TextButton(onClick = openSaf) { Text("SAFで開く") }
                    }
                }
            }
        }

        if (directory == null) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(roots.size) { index ->
                    val item = roots[index]
                    Card(
                        Modifier.fillMaxWidth().clickable {
                            root = item
                            directory = item.file
                            refreshKey++
                        }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (item.removable) Icons.Default.SdStorage else Icons.Default.Storage, null, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(item.file.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = openSaf, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text("SAFでフォルダを開く")
                    }
                }
            }
        } else {
            val browseError = entriesResult?.exceptionOrNull()
            if (browseError != null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Default.Lock, null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("この場所を直接読み取れません")
                        Text(browseError.message ?: "アクセス権限を確認してください", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = openSaf) { Text("SAFで開く") }
                    }
                }
            } else {
                val entries = entriesResult?.getOrNull().orEmpty()
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = entry.isDirectory) {
                                    if (entry.isDirectory) directory = entry.file
                                }
                                .padding(horizontal = 12.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (entry.isDirectory) Icons.Default.Folder else Icons.Default.AudioFile, null, modifier = Modifier.size(30.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (entry.isDirectory) "フォルダ" else "音声ファイル",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (entry.isDirectory) Icon(Icons.Default.ChevronRight, null)
                        }
                        HorizontalDivider()
                    }
                    if (entries.isEmpty()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                Text("表示できるフォルダ・音声ファイルはありません")
                            }
                        }
                    }
                }
            }

            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    if (currentQueueFolder != null) {
                        Text(
                            "現在: ${folderName(currentQueueFolder)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            val selected = directory ?: return@Button
                            viewModel.onFolderSelected(Uri.fromFile(selected))
                            openQueue()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = browseError == null
                    ) {
                        Icon(Icons.Default.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text("選択")
                    }
                }
            }
        }
    }
}

private fun sameFile(a: File, b: File): Boolean = runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(a.absolutePath == b.absolutePath)
private fun folderName(uri: Uri?): String = when (uri?.scheme) {
    "file" -> uri.path ?: "未設定"
    else -> uri?.lastPathSegment?.replace("primary:", "/") ?: "未設定"
}
private fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L) + 999) / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
    else "${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}"
}
private fun speedText(speed: Float): String = if (speed == speed.toLong().toFloat()) speed.toLong().toString() else speed.toString().trimEnd('0').trimEnd('.')
private fun allFilesAllowed(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
private fun openAllFilesSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
    } catch (_: Exception) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }
}
