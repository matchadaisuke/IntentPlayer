package com.intentplayer.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
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

/**
 * MainScreen
 * メイン画面。
 * 通知権限が拒否されているとロック画面・BT通知が出ないため、
 * ユーザーに設定画面への案内を表示する。
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onSelectFolderClick: () -> Unit,
    onBatteryOptimizationClick: () -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsState()

    when (currentScreen) {
        MainViewModel.AppScreen.ONBOARDING -> {
            OnboardingScreen(
                viewModel = viewModel,
                onSelectFolderClick = onSelectFolderClick,
                onBatteryOptimizationClick = onBatteryOptimizationClick
            )
        }
        MainViewModel.AppScreen.SETTINGS -> {
            BackHandler { viewModel.navigateTo(MainViewModel.AppScreen.MAIN) }
            SettingsScreen(viewModel = viewModel)
        }
        MainViewModel.AppScreen.DIAGNOSIS -> {
            // 旧自己診断画面はメイン導線から外した。既存状態から遷移した場合はメインへ戻す。
            LaunchedEffect(Unit) {
                viewModel.navigateTo(MainViewModel.AppScreen.MAIN)
            }
        }
        MainViewModel.AppScreen.MAIN -> {
            MainScreenContent(
                viewModel = viewModel,
                onSelectFolderClick = onSelectFolderClick,
                onBatteryOptimizationClick = onBatteryOptimizationClick
            )
        }
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

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
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
                    Icon(
                        imageVector = Icons.Default.Audiotrack,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "IntentPlayer",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
                IconButton(onClick = { viewModel.navigateTo(MainViewModel.AppScreen.SETTINGS) }) {
                    Icon(Icons.Default.Settings, contentDescription = "設定")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isNotificationPermissionDenied) {
                NotificationPermissionBanner()
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isBatteryOptimized) {
                BatteryOptimizationBanner(
                    onClickOptimize = onBatteryOptimizationClick
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FolderSection(
                folderUri = folderUri,
                onSelectFolderClick = onSelectFolderClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            NowPlayingSection(
                currentTrack = currentTrack,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                playbackSpeed = playbackSpeed,
                enableAppVolume = enableAppVolume,
                appPlaybackVolume = appPlaybackVolume,
                onVolumeChange = { volume -> viewModel.setAppPlaybackVolume(volume) },
                onPauseResumeClick = {
                    if (isPlaying) viewModel.pause() else viewModel.resume()
                },
                onStopClick = { viewModel.stop() },
                onNextClick = { viewModel.next() },
                onPreviousClick = { viewModel.previous() },
                onSeekTo = { positionMs -> viewModel.seekTo(positionMs) },
                onSpeedSelected = { speed -> viewModel.setPlaybackSpeed(speed) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            uiMessage?.let { message ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = message,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(
                            onClick = { viewModel.clearUiMessage() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "閉じる",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            TrackListSection(
                tracks = tracks,
                currentTrack = currentTrack,
                isScanning = isScanning,
                onTrackClick = { index -> viewModel.playTrack(index) }
            )
        }
    }
}

@Composable
private fun NotificationPermissionBanner() {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "通知権限が拒否されています",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "通知権限がないとロック画面・Bluetoothデバイスへの再生情報表示ができません。" +
                        "設定 → アプリ → IntentPlayer → 通知 から許可してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun BatteryOptimizationBanner(
    onClickOptimize: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "バッテリー最適化が有効です",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "バックグラウンド再生中に音楽が止まる場合があります。" +
                        "「最適化しない」に設定すると安定して再生できます。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onClickOptimize
            ) {
                Text("バッテリー最適化を無効にする")
            }
        }
    }
}

@Composable
private fun FolderSection(
    folderUri: Uri?,
    onSelectFolderClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "フォルダ",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (folderUri != null) {
                val displayPath = folderUri.lastPathSegment ?: folderUri.toString()
                Text(
                    text = displayPath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onSelectFolderClick) {
                    Text("フォルダを変更")
                }
            } else {
                Text(
                    text = "フォルダが選択されていません",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSelectFolderClick) {
                    Text("フォルダを選択")
                }
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
            containerColor = if (currentTrack != null)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {

            Text(
                text = "再生中",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = currentTrack?.name ?: "（なし）",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            SeekBarSection(
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                enabled = currentTrack != null && durationMs > 0,
                onSeekTo = onSeekTo
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onPreviousClick,
                    enabled = currentTrack != null
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "前へ")
                }

                Button(
                    onClick = onPauseResumeClick,
                    enabled = currentTrack != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "一時停止" else "再生"
                    )
                }

                OutlinedButton(
                    onClick = onNextClick,
                    enabled = currentTrack != null
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "次へ")
                }

                OutlinedButton(
                    onClick = onStopClick,
                    enabled = currentTrack != null
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            SpeedSelector(
                currentSpeed = playbackSpeed,
                onSpeedSelected = onSpeedSelected
            )

            if (enableAppVolume) {
                Spacer(modifier = Modifier.height(12.dp))
                VolumeSliderSection(
                    volume = appPlaybackVolume,
                    enabled = currentTrack != null,
                    onVolumeChange = onVolumeChange
                )
            }
        }
    }
}

@Composable
private fun VolumeSliderSection(
    volume: Float,
    enabled: Boolean,
    onVolumeChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "アプリ独自の音量",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
            val percentage = (volume * 100).toInt()
            Text(
                text = "$percentage%",
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
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
            if (interaction is DragInteraction.Cancel ||
                interaction is DragInteraction.Stop
            ) {
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

    val sliderValue = if (isDragging) {
        dragValue
    } else {
        if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f
    }

    Column {
        Slider(
            value = sliderValue.coerceIn(0f, 1f),
            onValueChange = { value ->
                isDragging = true
                dragValue = value
            },
            onValueChangeFinished = {
                isDragging = false
                if (durationMs > 0) {
                    val seekMs = (dragValue * durationMs).toLong()
                    onSeekTo(seekMs)
                }
            },
            enabled = enabled,
            interactionSource = interactionSource,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatTime(currentPositionMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatTime(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SpeedSelector(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
    var expanded by remember { mutableStateOf(false) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "再生速度:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))

        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text("${formatSpeed(currentSpeed)}x")
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                speedOptions.forEach { speed ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${formatSpeed(speed)}x",
                                color = if (speed == currentSpeed)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                        },
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "曲一覧", style = MaterialTheme.typography.titleSmall)
            if (tracks.isNotEmpty()) {
                Text(
                    text = "${tracks.size}曲",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        when {
            isScanning -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("スキャン中...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            tracks.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "フォルダを選択してください",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn {
                    itemsIndexed(tracks) { index, track ->
                        TrackItem(
                            track = track,
                            isPlaying = track.uri == currentTrack?.uri,
                            onClick = { onTrackClick(index) }
                        )
                        if (index < tracks.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackItem(
    track: Track,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun formatSpeed(speed: Float): String {
    return if (speed == speed.toLong().toFloat()) {
        speed.toLong().toString()
    } else {
        speed.toString().trimEnd('0').trimEnd('.')
            .let { if (it.contains('.')) it else "$it.0" }
    }
}
