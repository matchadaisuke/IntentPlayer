package com.intentplayer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.intentplayer.MainActivity
import com.intentplayer.R
import com.intentplayer.model.Track
import com.intentplayer.receiver.ControlReceiver
import com.intentplayer.storage.FolderScanner
import com.intentplayer.storage.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音楽再生の中核サービス。
 *
 * - onCreate() で即座に FGS 化し、バックグラウンドからのインテント受信を可能にする
 * - 再生エラー時は次トラックへ自動スキップ（連続エラー上限あり）
 * - フォルダスキャン時の例外をブロードキャストで通知
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val TAG = "PlaybackService"

    private var exoPlayer: ExoPlayer? = null
    private var playerWrapper: Player? = null
    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)


    private var playlist: List<Track> = emptyList()
    private var currentFolderUri: Uri? = null
    private var scanJob: Job? = null

    // 連続エラーカウンタ
    private var consecutiveErrors = 0

    // Bluetooth / 有線 自動 Resume
    private var pausedByDisconnect = false
    private var lastDisconnectTimeMs = 0L
    private var reconnectJob: Job? = null

    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    // ==========================================
    // ライフサイクル
    // ==========================================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // 即座に FGS 化し、バックグラウンドからのインテント到達を保証する
        ensureNotificationChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildIdleNotification())

        initExoPlayer()
        initMediaSession()
        setupNotificationProvider()  // Media3 が再生通知に切り替える
        startPositionSaveLoop()
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}")
        val result = super.onStartCommand(intent, flags, startId)

        val command = intent?.getStringExtra(ControlReceiver.EXTRA_COMMAND)
        if (command != null) {
            handleCommand(intent, command)
        }
        return result
    }

    private fun handleCommand(intent: Intent, command: String) {
        when (command) {
            ControlReceiver.CMD_PLAY, CMD_FORCE_PLAY -> {
                val folderUriStr = intent.getStringExtra(ControlReceiver.EXTRA_FOLDER_URI)
                val folderUri = if (folderUriStr != null) {
                    try { Uri.parse(folderUriStr) } catch (e: Exception) { null }
                } else {
                    PreferencesManager.loadFolderUri(this)
                }
                val index = intent.getIntExtra("index", -1)

                if (folderUri != null) {
                    if (shouldBlockPlayback()) {
                        handlePlaybackBlocked()
                        return
                    }
                    pausedByDisconnect = false
                    if (command == CMD_FORCE_PLAY) {
                        playFromUriFromStart(this, folderUri)
                    } else {
                        val player = exoPlayer
                        if (player != null && currentFolderUri == folderUri && index == -1 &&
                            player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
                        ) {
                            player.play()
                            Log.d(TAG, "CMD_PLAY: already loaded, just resuming")
                        } else {
                            if (index >= 0) playFromUriAt(this, folderUri, index)
                            else playFromUri(this, folderUri)
                        }
                    }
                }
            }

            ControlReceiver.CMD_PAUSE  -> {
                pausedByDisconnect = false
                exoPlayer?.pause()
            }
            ControlReceiver.CMD_STOP   -> {
                pausedByDisconnect = false
                stopPlayback()
            }

            ControlReceiver.CMD_NEXT   -> exoPlayer?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
            ControlReceiver.CMD_PREVIOUS -> {
                val p = exoPlayer ?: return
                if (p.currentPosition > 3000L) p.seekTo(0L)
                else if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
            }
            ControlReceiver.CMD_SEEK -> {
                val pos = intent.getLongExtra(ControlReceiver.EXTRA_SEEK_TO, -1L)
                if (pos >= 0) exoPlayer?.seekTo(pos)
            }
            ControlReceiver.CMD_SPEED -> {
                val speed = intent.getFloatExtra(ControlReceiver.EXTRA_SPEED, 1.0f)
                exoPlayer?.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val useCustom = PreferencesManager.isUseCustomMediaPlayback(this)
        if (useCustom) {
            if (controllerInfo.packageName != packageName) {
                Log.d(TAG, "onGetSession: Blocked connection from ${controllerInfo.packageName} (useCustomMediaPlayback is ON)")
                return null
            }
        }
        return mediaSession
    }

    private fun buildSilentPlaybackNotification(): Notification {
        val player = exoPlayer
        val title = player?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"
        val idx = player?.currentMediaItemIndex ?: 0
        val total = playlist.size
        val indexText = if (total > 0) "[${idx + 1}/$total]" else ""
        // isPlaying は playWhenReady && playbackState==READY の両方が必要
        val isPlaying = player?.isPlaying == true
        val statusText = if (isPlaying) "再生中" else "一時停止中"

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun buildActionIntent(command: String): PendingIntent {
            val intent = Intent(this, ControlReceiver::class.java).apply {
                action = ControlReceiver.ACTION_CONTROL
                putExtra(ControlReceiver.EXTRA_COMMAND, command)
            }
            return PendingIntent.getBroadcast(
                this, command.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$indexText $title".trim())
            .setContentText(statusText)
            .setContentIntent(tapIntent)
            .addAction(0, "前へ", buildActionIntent(ControlReceiver.CMD_PREVIOUS))
            .addAction(0, if (isPlaying) "一時停止" else "再生", buildActionIntent(if (isPlaying) ControlReceiver.CMD_PAUSE else ControlReceiver.CMD_PLAY))
            .addAction(0, "次へ", buildActionIntent(ControlReceiver.CMD_NEXT))
            .addAction(0, "停止", buildActionIntent(ControlReceiver.CMD_STOP))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * サイレント通知モード時に、再生状態の変化を通知に即時反映する。
     * Media3 の createNotification は状態変化を自動検知しないため、手動で更新する。
     */
    private fun updateSilentNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())
    }

    /**
     * タスク削除時もサービスを停止せず、インテント受信のため常駐を維持する。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: サービスは継続稼働します（インテント受信のため）")
        // 意図的に stopSelf() を呼ばない
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        saveCurrentPosition()
        serviceScope.cancel()
        unregisterReceivers()
        abandonAudioFocus()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        playerWrapper = null
        exoPlayer = null
        super.onDestroy()
    }


    // ==========================================
    // 初期化
    // ==========================================

    private fun initExoPlayer() {
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(), false
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(object : Player.Listener {

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                setupLoudnessEnhancer(audioSessionId)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem == null) return
                // トラック遷移成功 → エラーカウンタをリセット
                consecutiveErrors = 0
                val idx = player.currentMediaItemIndex
                PreferencesManager.saveTrackIndex(this@PlaybackService, idx)
                PreferencesManager.savePlaybackPosition(this@PlaybackService, 0L)

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    playlist.getOrNull(idx - 1)?.let { prev ->
                        sendEventBroadcast(EVENT_TRACK_COMPLETED, prev.name)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    sendEventBroadcast(EVENT_PLAYLIST_COMPLETED, "")
                    Log.d(TAG, "Playlist ended: stopping playback and removing notification")
                    stopPlayback()
                }
                // 独自メディア再生システムを使う場合、状態変化を通知に反映する
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    updateSilentNotification()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    requestAudioFocus()
                } else {
                    abandonAudioFocus()
                }
                // 独自メディア再生システムを使う場合、再生/一時停止の切り替えを通知に即時反映する
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    updateSilentNotification()
                }
            }

            /**
             * 再生エラー時の自動スキップ。連続エラーが上限を超えたら停止。
             */
            override fun onPlayerError(error: PlaybackException) {
                val msg = "再生エラー: code=${error.errorCode} msg=${error.message}"
                Log.e(TAG, msg, error)
                sendErrorBroadcast(this@PlaybackService, ERROR_PLAYBACK_FAILED, msg)
                PreferencesManager.saveErrorLog(this@PlaybackService, msg)

                consecutiveErrors++
                Log.w(TAG, "consecutiveErrors=$consecutiveErrors / max=$MAX_CONSECUTIVE_ERRORS")

                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    Log.e(TAG, "連続エラー上限到達。再生を停止します。")
                    consecutiveErrors = 0
                    stopPlayback()
                    return
                }

                if (player.hasNextMediaItem()) {
                    Log.d(TAG, "次トラックへ自動スキップ (attempt=$consecutiveErrors)")
                    player.seekToNextMediaItem()
                } else {
                    Log.d(TAG, "次トラックなし。再生を停止します。")
                    consecutiveErrors = 0
                    stopPlayback()
                }
            }
        })
        exoPlayer = player

        playerWrapper = object : ForwardingPlayer(player) {
            override fun play() {
                if (shouldBlockPlayback()) {
                    handlePlaybackBlocked()
                    return
                }
                pausedByDisconnect = false
                super.play()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady && shouldBlockPlayback()) {
                    handlePlaybackBlocked()
                    return
                }
                if (playWhenReady) {
                    pausedByDisconnect = false
                }
                super.setPlayWhenReady(playWhenReady)
            }

            override fun stop() {
                pausedByDisconnect = false
                super.stop()
                stopPlayback()
            }
        }

    }


    private fun initMediaSession() {
        val player = playerWrapper ?: return
        val sessionActivityPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPi)
            .build()
    }


    private fun setupNotificationProvider() {
        val defaultProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        defaultProvider.setSmallIcon(R.drawable.ic_notification)

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: com.google.common.collect.ImmutableList<androidx.media3.session.CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    val notification = buildSilentPlaybackNotification()
                    return MediaNotification(FOREGROUND_NOTIFICATION_ID, notification)
                }
                return defaultProvider.createNotification(
                    mediaSession, customLayout, actionFactory, onNotificationChangedCallback
                )
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: android.os.Bundle
            ): Boolean {
                return defaultProvider.handleCustomCommand(session, action, extras)
            }
        })
    }

    // ==========================================
    // 通知 (Idle 状態用プレースホルダー)
    // ==========================================

    /**
     * アイドル時（再生前・停止後）の通知。
     * Media3 が再生を開始すると自動的に正規の再生通知（ID=1001）に上書きされる。
     * アプリが非表示でもサービスが FGS として生存し続けるために必要。
     */
    private fun buildIdleNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID_IDLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("待機中")
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "音楽の再生状態を表示します"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
                Log.d(TAG, "NotificationChannel created")
            }
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID_IDLE) == null) {
                val chIdle = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID_IDLE,
                    getString(R.string.notification_channel_idle_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "待機状態の通知を表示します"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(chIdle)
                Log.d(TAG, "NotificationChannel idle created")
            }
        }
    }

    // ==========================================
    // Bluetooth / 有線 自動 Resume
    // ==========================================

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val prev  = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (state == BluetoothProfile.STATE_DISCONNECTED &&
                        prev  == BluetoothProfile.STATE_CONNECTED) {
                        onAudioDeviceDisconnected()
                    } else if (state == BluetoothProfile.STATE_CONNECTED) {
                        onAudioDeviceReconnected()
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (addedDevices.any { isWiredDevice(it) }) onAudioDeviceReconnected()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (removedDevices.any { isWiredDevice(it) }) onAudioDeviceDisconnected()
        }
    }

    private fun isWiredDevice(d: AudioDeviceInfo) = d.type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,   AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val blockReceive = PreferencesManager.isBlockAudioFocusReceive(this)
        if (blockReceive) {
            Log.d(TAG, "Audio focus change received: $focusChange - IGNORED (block receive is ON)")
            return@OnAudioFocusChangeListener
        }

        val player = exoPlayer ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(TAG, "AUDIOFOCUS_LOSS: pausing player")
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT: pausing player")
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: ducking volume")
                val enableVolume = PreferencesManager.isEnableAppVolume(this)
                val baseVolume = if (enableVolume) PreferencesManager.getAppPlaybackVolume(this) else 1.0f
                applyVolume(baseVolume * 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(TAG, "AUDIOFOCUS_GAIN: restoring volume")
                val enableVolume = PreferencesManager.isEnableAppVolume(this)
                applyVolume(if (enableVolume) PreferencesManager.getAppPlaybackVolume(this) else 1.0f)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val blockSend = PreferencesManager.isBlockAudioFocusSend(this)
        if (blockSend) {
            Log.d(TAG, "requestAudioFocus skipped: blockAudioFocusSend is ON")
            return true
        }

        if (audioFocusRequest == null) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }

        val result = audioManager.requestAudioFocus(audioFocusRequest!!)
        val success = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.d(TAG, "requestAudioFocus: result=$result (success=$success)")
        return success
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            val result = audioManager.abandonAudioFocusRequest(it)
            Log.d(TAG, "abandonAudioFocusRequest: result=$result")
        }
    }

    private fun isExternalAudioDeviceConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val externalDeviceTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL
        )
        return devices.any { it.type in externalDeviceTypes }
    }

    private fun shouldBlockPlayback(): Boolean {
        if (!PreferencesManager.isBlockSpeakerMutePlaybackEnabled(this)) {
            return false
        }
        if (!isExternalAudioDeviceConnected()) {
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val isMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                false
            }
            if (volume == 0 || isMuted) {
                return true
            }
        }
        return false
    }

    private fun handlePlaybackBlocked() {
        Log.w(TAG, "Playback blocked: speaker only & muted")
        sendErrorBroadcast(this, "blocked_speaker_mute", "スピーカー接続かつ音量がオフのため再生を制限しました")
        PreferencesManager.saveErrorLog(this, "再生制限: スピーカーのみで音量オフ")
        serviceScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                this@PlaybackService,
                "スピーカー接続かつ音量オフのため再生を制限しました",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun onAudioDeviceDisconnected() {
        if (!PreferencesManager.isAutoBluetoothControlEnabled(this)) return
        saveCurrentPosition() // 切断時の現在位置を即座に保存
        lastDisconnectTimeMs = System.currentTimeMillis()
        pausedByDisconnect = true
        Log.d(TAG, "Audio device disconnected: position recorded")
    }

    private fun onAudioDeviceReconnected() {
        if (!PreferencesManager.isAutoBluetoothControlEnabled(this)) return
        val player = exoPlayer ?: return
        if (!pausedByDisconnect) return
        
        if (PreferencesManager.isAutoResumeTimeoutEnabled(this)) {
            val timeoutMs = PreferencesManager.getAutoResumeTimeoutMs(this)
            if (System.currentTimeMillis() - lastDisconnectTimeMs > timeoutMs) {
                pausedByDisconnect = false
                Log.d(TAG, "Audio device reconnected: timeout expired (elapsed=${System.currentTimeMillis() - lastDisconnectTimeMs}ms > limit=${timeoutMs}ms)")
                return
            }
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delayMs = PreferencesManager.getBluetoothReconnectDelayMs(this@PlaybackService)
            delay(delayMs.toLong()) // OSの音声ルーティング切り替え猶予時間
            if (isExternalAudioDeviceConnected()) {
                pausedByDisconnect = false
                player.play()
                Log.d(TAG, "Audio device reconnected: verified and resumed with delay=${delayMs}ms")
            } else {
                Log.w(TAG, "Audio device reconnected: but no external device active. Keep paused to prevent speaker leak.")
                pausedByDisconnect = false
            }
        }
    }


    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        getSystemService(AudioManager::class.java)
            .registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        try {
            getSystemService(AudioManager::class.java)
                .unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {}
    }

    // ==========================================
    // ポジション保存
    // ==========================================

    private fun startPositionSaveLoop() {
        serviceScope.launch {
            while (true) {
                delay(POSITION_SAVE_INTERVAL_MS)
                exoPlayer?.takeIf { it.isPlaying }?.let { saveCurrentPosition() }
            }
        }
    }

    private fun saveCurrentPosition() {
        val p = exoPlayer ?: return
        if (p.currentPosition > 0) {
            PreferencesManager.saveTrackIndex(this, p.currentMediaItemIndex)
            PreferencesManager.savePlaybackPosition(this, p.currentPosition)
        }
    }

    // ==========================================
    // 再生 API (内部)
    // ==========================================

    private fun playFromUri(context: Context, folderUri: Uri) {
        val idx = PreferencesManager.loadTrackIndex(context)
        val pos = PreferencesManager.loadPlaybackPosition(context)
        playFromUriAt(context, folderUri, idx, pos)
    }

    private fun playFromUriFromStart(context: Context, folderUri: Uri) {
        PreferencesManager.clearPlaybackState(context)
        playFromUriAt(context, folderUri, 0, 0L)
    }

    private fun playFromUriAt(
        context: Context,
        folderUri: Uri,
        startIndex: Int,
        startPositionMs: Long = 0L
    ) {
        currentFolderUri = folderUri
        PreferencesManager.saveFolderUri(context, folderUri)
        serviceScope.launch {
            scanJob?.cancel()
            scanJob = coroutineContext[Job]
            try {
                // scanFolder() の例外を捕捉し、ユーザーに伝える
                val tracks = withContext(Dispatchers.IO) {
                    FolderScanner.scanFolder(context, folderUri)
                }
                if (tracks.isEmpty()) {
                    sendErrorBroadcast(context, "no_files", "音楽ファイルが見つかりません")
                } else {
                    playlist = tracks
                    consecutiveErrors = 0
                    loadPlaylist(tracks, startIndex.coerceIn(0, tracks.size - 1), startPositionMs)
                }
            } catch (e: SecurityException) {
                val msg = "フォルダへのアクセス権がありません。アプリを開いてフォルダを再選択してください。"
                Log.e(TAG, "scanFolder SecurityException: ${e.message}", e)
                sendErrorBroadcast(context, "permission_error", msg)
                PreferencesManager.saveErrorLog(context, "スキャンエラー(権限): ${e.message}")
            } catch (e: Exception) {
                val msg = "フォルダの読み込みに失敗しました: ${e.message}"
                Log.e(TAG, "scanFolder failed: ${e.message}", e)
                sendErrorBroadcast(context, "scan_error", msg)
                PreferencesManager.saveErrorLog(context, "スキャンエラー: ${e.message}")
            }
        }
    }

    private fun loadPlaylist(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        val player = exoPlayer ?: return
        val enableVolume = PreferencesManager.isEnableAppVolume(this)
        applyVolume(if (enableVolume) PreferencesManager.getAppPlaybackVolume(this) else 1.0f)
        pausedByDisconnect = false
        player.setMediaItems(
            tracks.map { t ->
                MediaItem.Builder()
                    .setUri(t.uri)
                    .setMediaId(t.uri.toString())
                    .setMediaMetadata(buildMediaMetadata(t))
                    .build()
            },
            startIndex,
            startPositionMs
        )
        player.prepare()
        if (shouldBlockPlayback()) {
            player.playWhenReady = false
            handlePlaybackBlocked()
        } else {
            player.playWhenReady = true
        }
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release previous LoudnessEnhancer", e)
        }
        loudnessEnhancer = null
        if (audioSessionId == androidx.media3.common.C.AUDIO_SESSION_ID_UNSET) return
        try {
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).also {
                it.enabled = true
            }
            val enableVolume = PreferencesManager.isEnableAppVolume(this)
            applyVolume(if (enableVolume) PreferencesManager.getAppPlaybackVolume(this) else 1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LoudnessEnhancer sessionId=$audioSessionId", e)
            loudnessEnhancer = null
        }
    }

    private fun applyVolume(volume: Float) {
        val player = exoPlayer ?: return
        val clamped = if (volume.isFinite()) volume.coerceIn(0.0f, 2.0f) else 1.0f
        // ExoPlayer には必ず 0..1 を渡す
        try {
            player.volume = clamped.coerceAtMost(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set player volume", e)
        }
        // 1.0f 超分は LoudnessEnhancer でデジタル増幅
        val enhancer = loudnessEnhancer ?: return
        try {
            if (clamped > 1.0f) {
                val gainMb = (20.0 * Math.log10(clamped.toDouble()) * 100.0).toInt()
                enhancer.setTargetGain(gainMb)
                if (!enhancer.enabled) enhancer.enabled = true
            } else {
                enhancer.setTargetGain(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply LoudnessEnhancer gain", e)
        }
    }

    private fun buildMediaMetadata(track: Track) = MediaMetadata.Builder()
        .setTitle(track.name)
        .setArtist(track.artist ?: "Unknown Artist")
        .setAlbumTitle(track.album ?: "Unknown Album")
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()

    /**
     * 再生を停止する。
     * 通知を削除してサービスを停止する。
     */
    private fun stopPlayback() {
        pausedByDisconnect = false
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        PreferencesManager.clearPlaybackState(this)
        stopSelf()
    }


    // ==========================================
    // ブロードキャスト
    // ==========================================

    private fun sendEventBroadcast(event: String, trackName: String) {
        sendBroadcast(Intent(ACTION_PLAYBACK_EVENT).apply {
            putExtra(EXTRA_EVENT, event)
            putExtra(EXTRA_TRACK_NAME, trackName)
            putExtra(EXTRA_FOLDER_URI_KEY, currentFolderUri?.toString() ?: "")
        })
    }

    private fun sendErrorBroadcast(context: Context, reason: String, message: String) {
        Log.w(TAG, "sendErrorBroadcast: reason=$reason msg=$message")
        context.sendBroadcast(Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_REASON, reason)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
    }

    // ==========================================
    // 定数
    // ==========================================

    companion object {
        const val ACTION_PLAYBACK_EVENT  = "com.intentplayer.PLAYBACK_EVENT"
        const val ACTION_ERROR           = "com.intentplayer.ERROR"
        const val EXTRA_EVENT            = "event"
        const val EXTRA_TRACK_NAME       = "trackName"
        const val EXTRA_FOLDER_URI_KEY   = "folderUri"
        const val EVENT_TRACK_COMPLETED  = "track_completed"
        const val EVENT_PLAYLIST_COMPLETED = "playlist_completed"
        const val EXTRA_ERROR_REASON     = "reason"
        const val EXTRA_ERROR_MESSAGE    = "message"
        const val ERROR_PLAYBACK_FAILED  = "playback_failed"
        const val CMD_FORCE_PLAY         = "force_play"
        const val NOTIFICATION_CHANNEL_ID = "intentplayer_playback"
        const val NOTIFICATION_CHANNEL_ID_IDLE = "intentplayer_idle"

        /**
         * Media3 の DefaultMediaNotificationProvider が使う通知 ID と同じ値。
         * onCreate() でのプレースホルダー通知後、Media3 が同 ID で上書きするため競合しない。
         */
        const val FOREGROUND_NOTIFICATION_ID = 1001

        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val AUTO_RESUME_TIMEOUT_MS     = 30_000L
        private const val MAX_CONSECUTIVE_ERRORS     = 3
    }
}
