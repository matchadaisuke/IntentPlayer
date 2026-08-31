package com.intentplayer.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.intentplayer.model.Track
import com.intentplayer.receiver.ControlReceiver
import com.intentplayer.service.PlaybackService
import com.intentplayer.storage.FolderScanner
import com.intentplayer.storage.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val context = application.applicationContext

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var controllerRetryCount = 0

    enum class AppScreen { ONBOARDING, MAIN, SETTINGS }

    val currentScreen = MutableStateFlow(AppScreen.MAIN)

    val silentNotificationEnabled = MutableStateFlow(true)
    val autoBluetoothControlEnabled = MutableStateFlow(true)
    val blockAudioFocusSend = MutableStateFlow(true)
    val blockAudioFocusReceive = MutableStateFlow(true)
    val bluetoothReconnectDelayMs = MutableStateFlow(500)
    val blockSpeakerMutePlaybackEnabled = MutableStateFlow(true)
    val autoResumeTimeoutEnabled = MutableStateFlow(false)
    val autoResumeTimeoutMs = MutableStateFlow(10L * 60L * 1000L)
    val useCustomMediaPlayback = MutableStateFlow(true)
    val enableAppVolume = MutableStateFlow(true)
    val appPlaybackVolume = MutableStateFlow(1.0f)
    val appVersion = MutableStateFlow("Unknown")
    val errorLogs = MutableStateFlow<List<String>>(emptyList())

    val folderUri = MutableStateFlow<Uri?>(null)
    val tracks = MutableStateFlow<List<Track>>(emptyList())
    val isPlaying = MutableStateFlow(false)
    val currentTrack = MutableStateFlow<Track?>(null)
    val currentPositionMs = MutableStateFlow(0L)
    val durationMs = MutableStateFlow(0L)
    val playbackSpeed = MutableStateFlow(1.0f)
    val uiMessage = MutableStateFlow<String?>(null)
    val isScanning = MutableStateFlow(false)
    val isBatteryOptimized = MutableStateFlow(false)
    val isNotificationPermissionDenied = MutableStateFlow(false)

    private var positionPollingJob: Job? = null

    private val playbackStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (intent.action != PlaybackService.ACTION_PLAYBACK_STATE) return
            val hasMedia = intent.getBooleanExtra(PlaybackService.EXTRA_STATE_HAS_MEDIA, false)
            val index = intent.getIntExtra(PlaybackService.EXTRA_STATE_INDEX, -1)
            val incomingDuration = intent.getLongExtra(PlaybackService.EXTRA_STATE_DURATION_MS, 0L)
            isPlaying.value = intent.getBooleanExtra(PlaybackService.EXTRA_STATE_IS_PLAYING, false)
            currentPositionMs.value = intent.getLongExtra(PlaybackService.EXTRA_STATE_POSITION_MS, 0L).coerceAtLeast(0L)
            if (incomingDuration > 0L || !hasMedia) durationMs.value = incomingDuration.coerceAtLeast(0L)
            playbackSpeed.value = PreferencesManager.normalizePlaybackSpeed(
                intent.getFloatExtra(PlaybackService.EXTRA_STATE_SPEED, playbackSpeed.value)
            )
            currentTrack.value = if (hasMedia) tracks.value.getOrNull(index) ?: currentTrack.value else null
        }
    }

    init {
        reloadSettingsFromStorage()
        reloadErrorLogs()

        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion.value = packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app version", e)
        }

        val savedUri = PreferencesManager.loadFolderUri(context)
        currentScreen.value = if (PreferencesManager.isFirstLaunch(context)) AppScreen.ONBOARDING else AppScreen.MAIN

        if (savedUri != null) {
            folderUri.value = savedUri
            scanFolder(savedUri)
        }

        val stateFilter = IntentFilter(PlaybackService.ACTION_PLAYBACK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(playbackStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(playbackStateReceiver, stateFilter)
        }

        if (!useCustomMediaPlayback.value) initMediaController()
        startPositionPolling()
    }

    fun reloadSettingsFromStorage() {
        silentNotificationEnabled.value = PreferencesManager.isSilentNotificationEnabled(context)
        autoBluetoothControlEnabled.value = PreferencesManager.isAutoBluetoothControlEnabled(context)
        blockAudioFocusSend.value = PreferencesManager.isBlockAudioFocusSend(context)
        blockAudioFocusReceive.value = PreferencesManager.isBlockAudioFocusReceive(context)
        bluetoothReconnectDelayMs.value = PreferencesManager.getBluetoothReconnectDelayMs(context)
        blockSpeakerMutePlaybackEnabled.value = PreferencesManager.isBlockSpeakerMutePlaybackEnabled(context)
        autoResumeTimeoutEnabled.value = PreferencesManager.isAutoResumeTimeoutEnabled(context)
        autoResumeTimeoutMs.value = PreferencesManager.getAutoResumeTimeoutMs(context)
        useCustomMediaPlayback.value = PreferencesManager.isUseCustomMediaPlayback(context)
        enableAppVolume.value = PreferencesManager.isEnableAppVolume(context)
        appPlaybackVolume.value = PreferencesManager.getAppPlaybackVolume(context)
        playbackSpeed.value = PreferencesManager.getPlaybackSpeed(context)
    }

    fun completeOnboarding() {
        PreferencesManager.setFirstLaunchCompleted(context)
        currentScreen.value = AppScreen.MAIN
    }

    fun navigateTo(screen: AppScreen) {
        currentScreen.value = screen
        if (screen == AppScreen.SETTINGS) reloadErrorLogs()
    }

    fun setSilentNotificationEnabled(enabled: Boolean) {
        PreferencesManager.setSilentNotificationEnabled(context, enabled)
        silentNotificationEnabled.value = PreferencesManager.isSilentNotificationEnabled(context)
    }

    fun setAutoBluetoothControlEnabled(enabled: Boolean) {
        PreferencesManager.setAutoBluetoothControlEnabled(context, enabled)
        autoBluetoothControlEnabled.value = PreferencesManager.isAutoBluetoothControlEnabled(context)
    }

    fun setBlockAudioFocusSend(block: Boolean) {
        PreferencesManager.setBlockAudioFocusSend(context, block)
        blockAudioFocusSend.value = PreferencesManager.isBlockAudioFocusSend(context)
    }

    fun setBlockAudioFocusReceive(block: Boolean) {
        PreferencesManager.setBlockAudioFocusReceive(context, block)
        blockAudioFocusReceive.value = PreferencesManager.isBlockAudioFocusReceive(context)
    }

    fun setBluetoothReconnectDelayMs(delayMs: Int) {
        PreferencesManager.setBluetoothReconnectDelayMs(context, delayMs)
        bluetoothReconnectDelayMs.value = PreferencesManager.getBluetoothReconnectDelayMs(context)
    }

    fun setBlockSpeakerMutePlaybackEnabled(enabled: Boolean) {
        PreferencesManager.setBlockSpeakerMutePlaybackEnabled(context, enabled)
        blockSpeakerMutePlaybackEnabled.value = PreferencesManager.isBlockSpeakerMutePlaybackEnabled(context)
    }

    fun setAutoResumeTimeoutEnabled(enabled: Boolean) {
        PreferencesManager.setAutoResumeTimeoutEnabled(context, enabled)
        autoResumeTimeoutEnabled.value = PreferencesManager.isAutoResumeTimeoutEnabled(context)
    }

    fun setAutoResumeTimeoutMs(timeoutMs: Long) {
        PreferencesManager.setAutoResumeTimeoutMs(context, timeoutMs)
        autoResumeTimeoutMs.value = PreferencesManager.getAutoResumeTimeoutMs(context)
    }

    fun setUseCustomMediaPlayback(enabled: Boolean) {
        PreferencesManager.setUseCustomMediaPlayback(context, enabled)
        useCustomMediaPlayback.value = PreferencesManager.isUseCustomMediaPlayback(context)
        sendServiceCommand(PlaybackService.CMD_CUSTOM_MODE) {
            putExtra(PlaybackService.EXTRA_CUSTOM_MODE, enabled)
        }
        if (enabled) {
            releaseMediaController()
        } else {
            viewModelScope.launch {
                delay(300L)
                initMediaController()
            }
        }
    }

    fun setEnableAppVolume(enabled: Boolean) {
        PreferencesManager.setEnableAppVolume(context, enabled)
        enableAppVolume.value = PreferencesManager.isEnableAppVolume(context)
        sendAppVolumeToService(if (enableAppVolume.value) appPlaybackVolume.value else 1.0f)
    }

    fun setAppPlaybackVolume(volume: Float) {
        PreferencesManager.setAppPlaybackVolume(context, volume)
        appPlaybackVolume.value = PreferencesManager.getAppPlaybackVolume(context)
        sendAppVolumeToService(appPlaybackVolume.value)
    }

    private fun sendAppVolumeToService(volume: Float) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            putExtra(ControlReceiver.EXTRA_COMMAND, "app_volume")
            putExtra("volume", volume)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            Log.d(TAG, "PlaybackService is not active; volume will apply on next playback")
        }
    }

    fun reloadErrorLogs() {
        errorLogs.value = PreferencesManager.getErrorLogs(context)
    }

    fun clearErrorLogs() {
        PreferencesManager.clearErrorLogs(context)
        errorLogs.value = emptyList()
    }

    private fun initMediaController() {
        if (useCustomMediaPlayback.value) return
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controllerRetryCount = 0
                setupControllerListener()
                controller?.setPlaybackSpeed(playbackSpeed.value)
            } catch (e: Exception) {
                if (controllerRetryCount < MAX_CONTROLLER_RETRIES) {
                    controllerRetryCount++
                    viewModelScope.launch {
                        delay((2000L * controllerRetryCount).coerceAtMost(10_000L))
                        initMediaController()
                    }
                } else {
                    controllerRetryCount = 0
                    uiMessage.value = "Android標準の再生システムへの接続に失敗しました"
                }
            }
        }, MoreExecutors.directExecutor())
    }

    private fun setupControllerListener() {
        val c = controller ?: return
        c.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                updateCurrentTrackFromMetadata(metadata)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaMetadata?.let(::updateCurrentTrackFromMetadata)
                if (c.duration > 0L) durationMs.value = c.duration
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
                playbackSpeed.value = PreferencesManager.normalizePlaybackSpeed(c.playbackParameters.speed)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> if (c.duration > 0L) durationMs.value = c.duration
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        currentTrack.value = null
                        currentPositionMs.value = 0L
                        durationMs.value = 0L
                    }
                }
            }
        })
        isPlaying.value = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
        if (c.duration > 0L) durationMs.value = c.duration
        playbackSpeed.value = PreferencesManager.normalizePlaybackSpeed(c.playbackParameters.speed)
        updateCurrentTrackFromMetadata(c.mediaMetadata)
    }

    private fun updateCurrentTrackFromMetadata(metadata: MediaMetadata) {
        val title = metadata.title?.toString()
        if (title.isNullOrBlank()) return
        val found = tracks.value.find { it.name == title }
        currentTrack.value = found ?: Track(
            uri = Uri.EMPTY,
            name = title,
            fileName = title,
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L,
            artworkUri = metadata.artworkUri
        )
    }

    private fun startPositionPolling() {
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    isPlaying.value = c.playWhenReady && c.playbackState != Player.STATE_IDLE && c.playbackState != Player.STATE_ENDED
                    playbackSpeed.value = PreferencesManager.normalizePlaybackSpeed(c.playbackParameters.speed)
                    if (c.isPlaying || c.playbackState == Player.STATE_READY || c.playbackState == Player.STATE_BUFFERING) {
                        currentPositionMs.value = c.currentPosition.coerceAtLeast(0L)
                        if (c.duration > 0) durationMs.value = c.duration
                        if (currentTrack.value == null && c.playbackState != Player.STATE_IDLE) {
                            updateCurrentTrackFromMetadata(c.mediaMetadata)
                        }
                    }
                    if (!c.isConnected) {
                        controller = null
                        if (!useCustomMediaPlayback.value) initMediaController()
                    }
                }
                delay(500)
            }
        }
    }

    fun onBatteryOptimizationResult(isOptimized: Boolean) {
        isBatteryOptimized.value = isOptimized
    }

    fun onNotificationPermissionResult(isDenied: Boolean) {
        isNotificationPermissionDenied.value = isDenied
        if (isDenied) uiMessage.value = "通知権限が拒否されています。設定から通知を許可してください。"
    }

    fun onFolderSelected(uri: Uri) {
        folderUri.value = uri
        PreferencesManager.saveFolderUri(context, uri)
        currentTrack.value = null
        scanFolder(uri)
    }

    private fun scanFolder(uri: Uri) {
        viewModelScope.launch {
            isScanning.value = true
            uiMessage.value = null
            try {
                val result = withContext(Dispatchers.IO) { FolderScanner.scanFolder(context, uri) }
                tracks.value = result
                if (result.isEmpty()) {
                    currentTrack.value = null
                    uiMessage.value = "フォルダに音楽ファイルが見つかりませんでした"
                } else {
                    controller?.let { c ->
                        if (c.mediaItemCount > 0 && c.playbackState != Player.STATE_IDLE) updateCurrentTrackFromMetadata(c.mediaMetadata)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                uiMessage.value = "スキャンエラー: ${e.message}"
                tracks.value = emptyList()
                currentTrack.value = null
            } finally {
                isScanning.value = false
            }
        }
    }

    fun playTrack(index: Int) {
        val currentFolder = folderUri.value ?: return
        if (index !in tracks.value.indices) return
        val intent = Intent(context, PlaybackService::class.java).apply {
            putExtra(ControlReceiver.EXTRA_COMMAND, ControlReceiver.CMD_PLAY)
            putExtra(ControlReceiver.EXTRA_FOLDER_URI, currentFolder.toString())
            putExtra("index", index)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
            if (!useCustomMediaPlayback.value && (controller == null || controller?.isConnected != true)) {
                viewModelScope.launch {
                    delay(500L)
                    if (controller == null || controller?.isConnected != true) {
                        controllerRetryCount = 0
                        initMediaController()
                    }
                }
            }
        } catch (e: Exception) {
            uiMessage.value = "再生開始に失敗しました: ${e.message}"
        }
    }

    fun pause() {
        if (useCustomMediaPlayback.value) sendServiceCommand(ControlReceiver.CMD_PAUSE) else controller?.pause()
    }

    fun resume() {
        if (useCustomMediaPlayback.value) sendServiceCommand(ControlReceiver.CMD_PLAY) else controller?.play()
    }

    fun stop() {
        if (useCustomMediaPlayback.value) sendServiceCommand(ControlReceiver.CMD_STOP) else controller?.stop()
        currentTrack.value = null
        currentPositionMs.value = 0L
        durationMs.value = 0L
    }

    fun next() {
        if (useCustomMediaPlayback.value) sendServiceCommand(ControlReceiver.CMD_NEXT) else controller?.seekToNextMediaItem()
    }

    fun previous() {
        if (useCustomMediaPlayback.value) sendServiceCommand(ControlReceiver.CMD_PREVIOUS) else controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        val safe = positionMs.coerceAtLeast(0L)
        if (useCustomMediaPlayback.value) {
            sendServiceCommand(ControlReceiver.CMD_SEEK) { putExtra(ControlReceiver.EXTRA_SEEK_TO, safe) }
        } else {
            controller?.seekTo(safe)
        }
        currentPositionMs.value = safe
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = PreferencesManager.normalizePlaybackSpeed(speed)
        PreferencesManager.setPlaybackSpeed(context, safe)
        if (useCustomMediaPlayback.value) {
            sendServiceCommand(ControlReceiver.CMD_SPEED) { putExtra(ControlReceiver.EXTRA_SPEED, safe) }
        } else {
            controller?.setPlaybackSpeed(safe)
        }
        playbackSpeed.value = safe
    }

    private fun sendServiceCommand(command: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(context, PlaybackService::class.java).apply {
            putExtra(ControlReceiver.EXTRA_COMMAND, command)
            extras()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && command == ControlReceiver.CMD_PLAY) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Service command failed: $command: ${e.message}")
            uiMessage.value = "操作に失敗しました: ${e.message}"
        }
    }

    private fun releaseMediaController() {
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controllerRetryCount = 0
    }

    fun clearUiMessage() {
        uiMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        positionPollingJob?.cancel()
        try { context.unregisterReceiver(playbackStateReceiver) } catch (_: Exception) {}
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    companion object {
        private const val MAX_CONTROLLER_RETRIES = 5
    }
}
