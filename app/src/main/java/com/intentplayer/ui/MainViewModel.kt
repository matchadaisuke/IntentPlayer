package com.intentplayer.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * メイン画面の ViewModel。
 * MediaController を介して PlaybackService と通信し、再生状態を UI に反映する。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "MainViewModel"
    private val context = application.applicationContext

    // MediaController 関連
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var controllerRetryCount = 0

    enum class AppScreen { ONBOARDING, MAIN, SETTINGS, DIAGNOSIS }

    // ----- UI 状態 -----
    
    val currentScreen = MutableStateFlow(AppScreen.MAIN)

    // 設定関連
    val silentNotificationEnabled = MutableStateFlow(true)
    val autoBluetoothControlEnabled = MutableStateFlow(true)
    val blockAudioFocusSend = MutableStateFlow(true)
    val blockAudioFocusReceive = MutableStateFlow(true)
    val bluetoothReconnectDelayMs = MutableStateFlow(500)
    val blockSpeakerMutePlaybackEnabled = MutableStateFlow(true)
    val autoResumeTimeoutEnabled = MutableStateFlow(false)
    val autoResumeTimeoutMs = MutableStateFlow(30000L)
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

    // ==========================================
    // 初期化
    // ==========================================

    init {
        // 設定値のロード
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
        reloadErrorLogs()




        // アプリバージョンの取得
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion.value = packageInfo.versionName ?: "Unknown"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app version", e)
        }

        // フォルダが保存されていれば初回セットアップ済みとみなす
        val savedUri = PreferencesManager.loadFolderUri(context)
        val shouldShowOnboarding = PreferencesManager.isFirstLaunch(context) && savedUri == null
        if (shouldShowOnboarding) {
            currentScreen.value = AppScreen.ONBOARDING
        } else {
            currentScreen.value = AppScreen.MAIN
            // フォルダ選択済みなら初回フラグも念のために落とす
            if (savedUri != null) PreferencesManager.setFirstLaunchCompleted(context)
        }

        if (savedUri != null) {
            folderUri.value = savedUri
            scanFolder(savedUri)
        }

        initMediaController()
        startPositionPolling()
    }

    fun completeOnboarding() {
        PreferencesManager.setFirstLaunchCompleted(context)
        currentScreen.value = AppScreen.MAIN
    }

    fun navigateTo(screen: AppScreen) {
        currentScreen.value = screen
        if (screen == AppScreen.SETTINGS || screen == AppScreen.DIAGNOSIS) {
            reloadErrorLogs()
        }
    }

    fun setSilentNotificationEnabled(enabled: Boolean) {
        PreferencesManager.setSilentNotificationEnabled(context, enabled)
        silentNotificationEnabled.value = enabled
    }

    fun setAutoBluetoothControlEnabled(enabled: Boolean) {
        PreferencesManager.setAutoBluetoothControlEnabled(context, enabled)
        autoBluetoothControlEnabled.value = enabled
    }

    fun setBlockAudioFocusSend(block: Boolean) {
        PreferencesManager.setBlockAudioFocusSend(context, block)
        blockAudioFocusSend.value = block
    }

    fun setBlockAudioFocusReceive(block: Boolean) {
        PreferencesManager.setBlockAudioFocusReceive(context, block)
        blockAudioFocusReceive.value = block
    }

    fun setBluetoothReconnectDelayMs(delayMs: Int) {
        PreferencesManager.setBluetoothReconnectDelayMs(context, delayMs)
        bluetoothReconnectDelayMs.value = delayMs
    }

    fun setBlockSpeakerMutePlaybackEnabled(enabled: Boolean) {
        PreferencesManager.setBlockSpeakerMutePlaybackEnabled(context, enabled)
        blockSpeakerMutePlaybackEnabled.value = enabled
    }

    fun setAutoResumeTimeoutEnabled(enabled: Boolean) {
        PreferencesManager.setAutoResumeTimeoutEnabled(context, enabled)
        autoResumeTimeoutEnabled.value = enabled
    }

    fun setAutoResumeTimeoutMs(timeoutMs: Long) {
        PreferencesManager.setAutoResumeTimeoutMs(context, timeoutMs)
        autoResumeTimeoutMs.value = timeoutMs
    }

    fun setUseCustomMediaPlayback(enabled: Boolean) {
        PreferencesManager.setUseCustomMediaPlayback(context, enabled)
        useCustomMediaPlayback.value = enabled
    }

    fun setEnableAppVolume(enabled: Boolean) {
        PreferencesManager.setEnableAppVolume(context, enabled)
        enableAppVolume.value = enabled
    }

    fun setAppPlaybackVolume(volume: Float) {
        // NaN や Infinite は coerceIn を素通りするため先にガード
        val safeVolume = if (volume.isFinite()) volume.coerceIn(0.0f, 1.0f) else 1.0f
        PreferencesManager.setAppPlaybackVolume(context, safeVolume)
        appPlaybackVolume.value = safeVolume
        try {
            controller?.volume = safeVolume
        } catch (e: IllegalArgumentException) {
            // 万が一 safeVolume が範囲外になっても最大値で安全にフォールバック
            controller?.volume = 1.0f
        }
    }

    fun reloadErrorLogs() {



        errorLogs.value = PreferencesManager.getErrorLogs(context)
    }

    fun clearErrorLogs() {
        PreferencesManager.clearErrorLogs(context)
        errorLogs.value = emptyList()
    }

    // 再試行ロジック付きの MediaController 初期化
    private fun initMediaController() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controllerRetryCount = 0
                setupControllerListener()
                Log.d(TAG, "MediaController connected")
            } catch (e: Exception) {
                Log.w(TAG, "MediaController connection failed (attempt=$controllerRetryCount): ${e.message}")
                // 失敗したら指数バックオフで再試行（最大 MAX_CONTROLLER_RETRIES 回）
                if (controllerRetryCount < MAX_CONTROLLER_RETRIES) {
                    controllerRetryCount++
                    val delayMs = (2000L * controllerRetryCount).coerceAtMost(10_000L)
                    viewModelScope.launch {
                        delay(delayMs)
                        initMediaController()
                    }
                } else {
                    Log.e(TAG, "MediaController: 再試行上限に達しました。次回の playTrack() 後に再接続します。")
                    controllerRetryCount = 0
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
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying.value = playing
                if (!playing) {
                    // 停止時は playbackSpeed を controller から再同期
                    playbackSpeed.value = c.playbackParameters.speed
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
                    }
                    Player.STATE_IDLE, Player.STATE_ENDED -> {
                        // 停止・終了時はトラック情報をクリア
                        currentTrack.value = null
                        currentPositionMs.value = 0L
                        durationMs.value = 0L
                    }
                }
            }
        })
        // 初期状態の反映
        isPlaying.value = c.isPlaying
        durationMs.value = c.duration.takeIf { it > 0 } ?: 0L
        updateCurrentTrackFromMetadata(c.mediaMetadata)
    }

    /**
     * MediaSession のメタデータから currentTrack を更新する。
     * tracks にマッチしない場合はメタデータから仮の Track を生成し、UI ボタンを有効化する。
     */
    private fun updateCurrentTrackFromMetadata(metadata: MediaMetadata) {
        val title = metadata.title?.toString()
        if (title.isNullOrBlank()) return

        // tracks.value からマッチするものを優先して探す
        val found = tracks.value.find { it.name == title }
        if (found != null) {
            currentTrack.value = found
            return
        }

        // マッチしない場合はメタデータから仮 Track を生成
        val fallback = Track(
            uri = Uri.EMPTY,
            name = title,
            fileName = title,
            artist = metadata.artist?.toString(),
            album = metadata.albumTitle?.toString(),
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L
        )
        currentTrack.value = fallback
        Log.d(TAG, "currentTrack set from metadata fallback: title='$title'")
    }

    /**
     * 再生位置ポーリング。
     * 500ms ごとに再生位置を更新し、currentTrack が null の場合は再取得する。
     */
    private fun startPositionPolling() {
        positionPollingJob = viewModelScope.launch {
            while (isActive) {
                val c = controller
                if (c != null) {
                    val playing = c.isPlaying
                    val state = c.playbackState
                    isPlaying.value = playing

                    if (playing || state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                        currentPositionMs.value = c.currentPosition.coerceAtLeast(0L)
                        val dur = c.duration
                        if (dur > 0) durationMs.value = dur

                        // 再生中なのに currentTrack が null の場合は再取得
                        if (currentTrack.value == null && state != Player.STATE_IDLE) {
                            updateCurrentTrackFromMetadata(c.mediaMetadata)
                        }
                    }

                    // コントローラーが切断されていたら再接続
                    if (!c.isConnected) {
                        Log.w(TAG, "MediaController disconnected, reconnecting...")
                        controller = null
                        initMediaController()
                    }
                }
                delay(500)
            }
        }
    }

    // ==========================================
    // バッテリー最適化・通知権限
    // ==========================================

    fun onBatteryOptimizationResult(isOptimized: Boolean) {
        isBatteryOptimized.value = isOptimized
    }

    fun onNotificationPermissionResult(isDenied: Boolean) {
        isNotificationPermissionDenied.value = isDenied
        if (isDenied) {
            uiMessage.value = "通知権限が拒否されています。設定から通知を許可すると、ロック画面・Bluetoothデバイスで再生情報が表示されます。"
        }
    }

    // ==========================================
    // フォルダ選択・スキャン
    // ==========================================

    fun onFolderSelected(uri: Uri) {
        folderUri.value = uri
        PreferencesManager.saveFolderUri(context, uri)
        currentTrack.value = null  // フォルダ変更時はリセット
        scanFolder(uri)
    }

    private fun scanFolder(uri: Uri) {
        viewModelScope.launch {
            isScanning.value = true
            uiMessage.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    FolderScanner.scanFolder(context, uri)
                }
                tracks.value = result
                if (result.isEmpty()) {
                    uiMessage.value = "フォルダに音楽ファイルが見つかりませんでした"
                } else {
                    // スキャン完了後、すでに再生中のトラックの currentTrack を再マッチング
                    val c = controller
                    if (c != null && (c.isPlaying || c.playbackState == Player.STATE_READY)) {
                        updateCurrentTrackFromMetadata(c.mediaMetadata)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                uiMessage.value = "スキャンエラー: ${e.message}"
                tracks.value = emptyList()
            } finally {
                isScanning.value = false
            }
        }
    }

    // ==========================================
    // 再生制御
    // ==========================================

    fun playTrack(index: Int) {
        val currentFolder = folderUri.value ?: return
        val intent = Intent(context, PlaybackService::class.java).apply {
            putExtra(ControlReceiver.EXTRA_COMMAND, ControlReceiver.CMD_PLAY)
            putExtra(ControlReceiver.EXTRA_FOLDER_URI, currentFolder.toString())
            putExtra("index", index)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            // 再生開始後、MediaController が未接続なら再試行
            if (controller == null || !controller!!.isConnected) {
                viewModelScope.launch {
                    delay(500L)
                    if (controller == null || !controller!!.isConnected) {
                        controllerRetryCount = 0
                        initMediaController()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "playTrack: startForegroundService failed: ${e.message}", e)
            uiMessage.value = "再生開始に失敗しました: ${e.message}"
        }
    }

    fun pause() { controller?.pause() }
    fun resume() { controller?.play() }

    fun stop() {
        controller?.stop()
        currentTrack.value = null
        currentPositionMs.value = 0L
        durationMs.value = 0L
    }

    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
        currentPositionMs.value = positionMs
    }

    fun setPlaybackSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))
        playbackSpeed.value = speed.coerceIn(0.5f, 2.0f)
    }

    fun clearUiMessage() {
        uiMessage.value = null
    }

    // ==========================================
    // 自己診断ツール
    // ==========================================
    
    data class DiagnosisResult(
        val name: String,
        val isOk: Boolean,
        val message: String,
        val actionType: ActionType? = null
    ) {
        enum class ActionType { REQUEST_NOTIFICATION, REQUEST_STORAGE, OPEN_BATTERY_SETTINGS, REQUEST_BLUETOOTH, SELECT_FOLDER }
    }

    val diagnosisResults = MutableStateFlow<List<DiagnosisResult>>(emptyList())

    fun runSelfDiagnosis() {
        val results = mutableListOf<DiagnosisResult>()

        // 1. 通知権限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isOk = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            results.add(DiagnosisResult(
                "通知権限 (Android 13+)", isOk,
                if (isOk) "許可されています" else "拒否されています。ロック画面やBTデバイスに情報が表示されません。",
                if (!isOk) DiagnosisResult.ActionType.REQUEST_NOTIFICATION else null
            ))
        }

        // 2. ストレージ権限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            results.add(DiagnosisResult(
                "メディアオーディオ権限", isOk,
                if (isOk) "許可されています" else "拒否されています。ファイルのメタデータ取得に失敗する可能性があります。",
                if (!isOk) DiagnosisResult.ActionType.REQUEST_STORAGE else null
            ))
        } else {
            val isOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            results.add(DiagnosisResult(
                "ストレージ読み込み権限", isOk,
                if (isOk) "許可されています" else "拒否されています。メタデータ取得に失敗する可能性があります。",
                if (!isOk) DiagnosisResult.ActionType.REQUEST_STORAGE else null
            ))
        }

        // 3. バッテリー最適化
        val isBatteryOk = com.intentplayer.storage.BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
        results.add(DiagnosisResult(
            "バッテリー最適化除外", isBatteryOk,
            if (isBatteryOk) "除外されています" else "除外されていません。バックグラウンド再生が停止する可能性があります。",
            if (!isBatteryOk) DiagnosisResult.ActionType.OPEN_BATTERY_SETTINGS else null
        ))

        // 4. Bluetooth権限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isOk = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            results.add(DiagnosisResult(
                "Bluetooth接続権限", isOk,
                if (isOk) "許可されています" else "拒否されています。Bluetooth機器情報の取得に失敗します。",
                if (!isOk) DiagnosisResult.ActionType.REQUEST_BLUETOOTH else null
            ))
        }

        // 5. フォルダURI権限
        val currentUri = folderUri.value
        if (currentUri != null) {
            val persistedUris = context.contentResolver.persistedUriPermissions
            val hasPermission = persistedUris.any { it.uri == currentUri && it.isReadPermission }
            results.add(DiagnosisResult(
                "フォルダアクセス権限 (SAF)", hasPermission,
                if (hasPermission) "有効です" else "無効になっています。再選択が必要です。",
                if (!hasPermission) DiagnosisResult.ActionType.SELECT_FOLDER else null
            ))
        } else {
            results.add(DiagnosisResult(
                "フォルダ選択", false,
                "フォルダが選択されていません。",
                DiagnosisResult.ActionType.SELECT_FOLDER
            ))
        }

        // 6. サービス接続状態
        val c = controller
        val isConnected = c != null && c.isConnected
        results.add(DiagnosisResult(
            "PlaybackService 接続", isConnected,
            if (isConnected) "接続されています" else "切断されています。再生できません。",
            null // 自動で再試行されるためアクションなし
        ))

        diagnosisResults.value = results
    }

    override fun onCleared() {
        super.onCleared()
        positionPollingJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    companion object {
        private const val MAX_CONTROLLER_RETRIES = 5
    }
}
