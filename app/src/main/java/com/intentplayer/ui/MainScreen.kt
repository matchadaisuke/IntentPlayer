package com.intentplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.intentplayer.model.Track
import com.intentplayer.storage.PreferencesManager

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSelectFolderClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsState()
    when (currentScreen) {
        MainViewModel.AppScreen.ONBOARDING -> OnboardingScreen(
            viewModel = viewModel,
            onSelectFolderClick = onSelectFolderClick,
            onBatteryOptimizationClick = onBatteryOptimizationClick
        )
        MainViewModel.AppScreen.SETTINGS -> {
            BackHandler { viewModel.navigateTo(MainViewModel.AppScreen.MAIN) }
            SettingsScreen(viewModel)
        }
        MainViewModel.AppScreen.MAIN -> MainScreenContent(
            viewModel,
            onSelectFolderClick,
            onBatteryOptimizationClick
        )
    }
}

@Composable
fun MainScreenContent(
    viewModel: MainViewModel,
    onSelectFolderClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit
) {
    val folderUri by viewModel.folderUri.collectAsState()
    val tracks by viewModel.tracks.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentTrack by viewModel.currentTrack.collectAsState()
    val uiMessage by viewModel.uiMessage.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val currentPositionMs by viewModel.currentPositionMs.collectAsState()
    val durationMs by viewModel.durationMs.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val enableAppVolume by viewModel.enableAppVolume.collectAsState()
    val appPlaybackVolume by viewModel.appPlaybackVolume.collectAsState()
    val isBatteryOptimized by viewModel.isBatteryOptimized.collectAsState()
    val isNotificationPermissionDenied by viewModel.isNotificationPermissionDenied.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Audiotrack, null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("IntentPlayer", style = MaterialTheme.typography.headlineMedium)
                }
                IconButton(onClick = { viewModel.navigateTo(MainViewModel.AppScreen.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "設定")
                }
            }

            Spacer(Modifier.height(16.dp))
            if (isNotificationPermissionDenied) {
                NotificationPermissionBanner()
                Spacer(Modifier.height(8.dp))
            }
            if (isBatteryOptimized) {
                BatteryOptimizationBanner(onBatteryOptimizationClick)
                Spacer(Modifier.height(8.dp))
            }

            FolderSection(folderUri, onSelectFolderClick)
            Spacer(Modifier.height(16.dp))

            NowPlayingSection(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                enableAppVolume = enableAppVolume,
                appPlaybackVolume = appPlaybackVolume,
                onVolumeChange = viewModel::setAppPlaybackVolume,
                onPauseResumeClick = { if (isPlaying) viewModel.pause() else viewModel.resume() },
                onStopClick = viewModel::stop,
                onNextClick = viewModel::next,
                onPreviousClick = viewModel::previous,
                onSeekTo = viewModel::seekTo,
                onSpeedSelected = viewModel::setPlaybackSpeed
            )

            Spacer(Modifier.height(16.dp))
            uiMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        IconButton(onClick = viewModel::clearUiMessage) {
                            Icon(Icons.Default.Close, contentDescription = "閉じる")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            TrackListSection(tracks, currentTrack, isScanning, viewModel::playTrack)
        }
    }
}

@Composable
private fun NotificationPermissionBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Notifications, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("通知権限が拒否されています", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "通知権限がないとロック画面・Bluetoothデバイスへの再生情報表示ができません。設定から許可してください。",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun BatteryOptimizationBanner(onClickOptimize: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("バッテリー最適化が有効です", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text("バックグラウンド再生が停止する場合があります。", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClickOptimize) { Text("バッテリー最適化を無効にする") }
        }
    }
}

@Composable
private fun FolderSection(folderUri: Uri?, onSelectFolderClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("フォルダ", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            if (folderUri != null) {
                Text(
                    folderUri.lastPathSegment ?: folderUri.toString(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onSelectFolderClick) { Text("フォルダを変更") }
            } else {
                Text("フォルダが選択されていません")
                Spacer(Modifier.height(8.dp))
                Button(onSelectFolderClick) { Text("フォルダを選択") }
            }
        }
    }
}

@Composable
private fun NowPlayingSection(
    currentTrack: Track?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    enableAppVolume: Boolean,
    appPlaybackVolume: Float,
    onVolumeChange: (Float) -> Unit,
    onPauseResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedSelected: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (currentTrack != null) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("再生中", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(currentTrack?.name ?: "（なし）", maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))

            SeekBarSection(currentPositionMs, durationMs, currentTrack != null && durationMs > 0, onSeekTo)
            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onPreviousClick, enabled = currentTrack != null) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "前へ")
                }
                Button(
                    onClick = onPauseResumeClick,
                    enabled = currentTrack != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "一時停止" else "再生"
                    )
                }
                OutlinedButton(onNextClick, enabled = currentTrack != null) {
                    Icon(Icons.Default.SkipNext, contentDescription = "次へ")
                }
                OutlinedButton(onStopClick, enabled = currentTrack != null) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            }

            Spacer(Modifier.height(8.dp))
            SpeedSelector(playbackSpeed, onSpeedSelected)
            if (enableAppVolume) {
                Spacer(Modifier.height(12.dp))
                VolumeSliderSection(appPlaybackVolume, currentTrack != null, onVolumeChange)
            }
        }
    }
}

@Composable
private fun VolumeSliderSection(volume: Float, enabled: Boolean, onVolumeChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("アプリ独自の音量", style = MaterialTheme.typography.bodySmall)
            Text("${(volume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = volume.coerceIn(0f, PreferencesManager.MAX_APP_PLAYBACK_VOLUME),
            onValueChange = onVolumeChange,
            valueRange = 0f..PreferencesManager.MAX_APP_PLAYBACK_VOLUME,
            steps = 39,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SeekBarSection(
    currentPositionMs: Long,
    durationMs: Long,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Cancel || interaction is DragInteraction.Stop) {
                isDragging = false
            }
        }
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            isDragging = false
            dragValue = 0f
        }
    }

    val sliderValue = if (isDragging) dragValue
    else if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f

    Column {
        Slider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = {
                isDragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                isDragging = false
                if (durationMs > 0) onSeekTo((dragValue * durationMs).toLong())
            },
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(currentPositionMs), style = MaterialTheme.typography.bodySmall)
            Text(formatTime(durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SpeedSelector(currentSpeed: Float, onSpeedSelected: (Float) -> Unit) {
    val options = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    var expanded by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("再生速度:", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text("${formatSpeed(currentSpeed)}x") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text("${formatSpeed(speed)}x") },
                        onClick = {
                            onSpeedSelected(speed)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackListSection(
    tracks: List<Track>,
    currentTrack: Track?,
    isScanning: Boolean,
    onTrackClick: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("曲一覧", style = MaterialTheme.typography.titleSmall)
            if (tracks.isNotEmpty()) Text("${tracks.size}曲", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        when {
            isScanning -> Box(
                Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            tracks.isEmpty() -> Box(
                Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center
            ) { Text("フォルダを選択してください") }
            else -> LazyColumn {
                itemsIndexed(tracks) { index, track ->
                    TrackItem(track, track.uri == currentTrack?.uri) { onTrackClick(index) }
                    if (index < tracks.lastIndex) HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TrackItem(track: Track, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toLong().toFloat()) speed.toLong().toString()
    else speed.toString().trimEnd('0').trimEnd('.')
