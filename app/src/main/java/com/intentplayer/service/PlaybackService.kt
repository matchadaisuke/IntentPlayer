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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10

/**
 * 音楽再生の中核サービス。
 *
 * - 起動直後から待機通知を表示して Intent を受信できる状態を維持
 * - 独自メディア再生モードでは Bluetooth / OS の media button を消費して無視
 * - 独自通知は曲切り替え・再生状態変化ごとに明示更新
 * - 100% 超の音量は LoudnessEnhancer で最大 200% 相当まで増幅
 * - メディア音量 0 で自動一時停止し、自動停止だった場合のみ音量復帰で再開
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
    private var consecutiveErrors = 0

    // Bluetooth / 有線 自動 Resume
    private var pausedByDisconnect = false
    private var lastDisconnectTimeMs = 0L
    private var reconnectJob: Job? = null

    // 音量0による自動一時停止。手動pauseとは区別する。
    private var pausedByMute = false

    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // ==========================================
    // ライフサイクル
    // ==========================================

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        ensureNotificationChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildIdleNotification())

        initExoPlayer()
        initMediaSession()
        setupNotificationProvider()
        startPositionSaveLoop()
        startMuteMonitorLoop()
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (PreferencesManager.isUseCustomMediaPlayback(this) &&
            controllerInfo.packageName != packageName
        ) {
            Log.d(
                TAG,
                "onGetSession: blocked external controller ${controllerInfo.packageName} because custom playback is ON"
            )
            return null
        }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: keep service alive for intent control")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        saveCurrentPosition()
        serviceScope.cancel()
        unregisterReceivers()
        abandonAudioFocus()
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
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
    // Intent commands
    // ==========================================

    private fun handleCommand(intent: Intent, command: String) {
        when (command) {
            ControlReceiver.CMD_PLAY, CMD_FORCE_PLAY -> {
                val folderUriStr = intent.getStringExtra(ControlReceiver.EXTRA_FOLDER_URI)
                val folderUri = if (folderUriStr != null) {
                    try {
                        Uri.parse(folderUriStr)
                    } catch (_: Exception) {
                        null
                    }
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
                    pausedByMute = false
                    if (command == CMD_FORCE_PLAY) {
                        playFromUriFromStart(this, folderUri)
                    } else {
                        val player = exoPlayer
                        if (player != null &&
                            currentFolderUri == folderUri &&
                            index == -1 &&
                            player.playbackState != Player.STATE_IDLE &&
                            player.playbackState != Player.STATE_ENDED
                        ) {
                            player.play()
                        } else {
                            if (index >= 0) playFromUriAt(this, folderUri, index)
                            else playFromUri(this, folderUri)
                        }
                    }
                }
            }

            ControlReceiver.CMD_PAUSE -> {
                pausedByDisconnect = false
                pausedByMute = false
                exoPlayer?.pause()
            }

            ControlReceiver.CMD_STOP -> {
                pausedByDisconnect = false
                pausedByMute = false
                stopPlayback()
            }

            ControlReceiver.CMD_NEXT -> {
                exoPlayer?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
            }

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

            CMD_APP_VOLUME -> {
                val requested = intent.getFloatExtra(EXTRA_APP_VOLUME, 1.0f)
                applyVolume(requested)
            }
        }
    }

    // ==========================================
    // Player / MediaSession
    // ==========================================

    private fun initExoPlayer() {
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
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
                consecutiveErrors = 0
                val idx = player.currentMediaItemIndex
                PreferencesManager.saveTrackIndex(this@PlaybackService, idx)
                PreferencesManager.savePlaybackPosition(this@PlaybackService, 0L)

                // 独自通知は Media3 の自動更新だけに頼らず曲遷移で確実に更新する。
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    updateSilentNotification()
                }

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    playlist.getOrNull(idx - 1)?.let { prev ->
                        sendEventBroadcast(EVENT_TRACK_COMPLETED, prev.name)
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    sendEventBroadcast(EVENT_PLAYLIST_COMPLETED, "")
                    consecutiveErrors = 0
                    stopPlayback()
                    return
                }
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    updateSilentNotification()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) requestAudioFocus() else abandonAudioFocus()
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    updateSilentNotification()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val msg = "再生エラー: code=${error.errorCode} msg=${error.message}"
                Log.e(TAG, msg, error)
                sendErrorBroadcast(this@PlaybackService, ERROR_PLAYBACK_FAILED, msg)
                PreferencesManager.saveErrorLog(this@PlaybackService, msg)

                consecutiveErrors++
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    consecutiveErrors = 0
                    stopPlayback()
                    return
                }

                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                } else {
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
                pausedByMute = false
                super.play()
            }

            override fun pause() {
                pausedByDisconnect = false
                pausedByMute = false
                super.pause()
            }

            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady && shouldBlockPlayback()) {
                    handlePlaybackBlocked()
                    return
                }
                if (playWhenReady) {
                    pausedByDisconnect = false
                    pausedByMute = false
                }
                super.setPlayWhenReady(playWhenReady)
            }

            override fun stop() {
                pausedByDisconnect = false
                pausedByMute = false
                super.stop()
                stopPlayback()
            }
        }
    }

    private fun initMediaSession() {
        val player = playerWrapper ?: return
        val sessionActivityPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPi)
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                        Log.d(
                            TAG,
                            "Ignored media button from ${controllerInfo.packageName} because custom playback is ON"
                        )
                        // true = Media3 / Player へ伝播させない。Bluetooth の再生/一時停止もここで止める。
                        return true
                    }
                    return false
                }
            })
            .build()
    }

    // ==========================================
    // 通知
    // ==========================================

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
                    return MediaNotification(
                        FOREGROUND_NOTIFICATION_ID,
                        buildSilentPlaybackNotification()
                    )
                }
                return defaultProvider.createNotification(
                    mediaSession,
                    customLayout,
                    actionFactory,
                    onNotificationChangedCallback
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

    private fun buildSilentPlaybackNotification(): Notification {
        val player = exoPlayer
        val title = player?.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Unknown"
        val idx = player?.currentMediaItemIndex ?: 0
        val total = playlist.size
        val indexText = if (total > 0) "[${idx + 1}/$total]" else ""
        val isPlaying = player?.isPlaying == true
        val statusText = if (isPlaying) "再生中" else "一時停止中"

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
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
                this,
                command.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$indexText $title".trim())
            .setContentText(statusText)
            .setContentIntent(tapIntent)
            .addAction(0, "前へ", buildActionIntent(ControlReceiver.CMD_PREVIOUS))
            .addAction(
                0,
                if (isPlaying) "一時停止" else "再生",
                buildActionIntent(
                    if (isPlaying) ControlReceiver.CMD_PAUSE else ControlReceiver.CMD_PLAY
                )
            )
            .addAction(0, "次へ", buildActionIntent(ControlReceiver.CMD_NEXT))
            .addAction(0, "停止", buildActionIntent(ControlReceiver.CMD_STOP))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateSilentNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update playback notification: ${e.message}")
        }
    }

    private fun buildIdleNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "音楽の再生状態を表示します"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID_IDLE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID_IDLE,
                    getString(R.string.notification_channel_idle_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "待機状態の通知を表示します"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    // ==========================================
    // Audio focus
    // ==========================================

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (PreferencesManager.isBlockAudioFocusReceive(this)) {
            Log.d(TAG, "Audio focus change ignored: $focusChange")
            return@OnAudioFocusChangeListener
        }

        val player = exoPlayer ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val baseVolume = if (PreferencesManager.isEnableAppVolume(this)) {
                    PreferencesManager.getAppPlaybackVolume(this)
                } else {
                    1.0f
                }
                applyVolume(baseVolume * 0.2f)
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                val baseVolume = if (PreferencesManager.isEnableAppVolume(this)) {
                    PreferencesManager.getAppPlaybackVolume(this)
                } else {
                    1.0f
                }
                applyVolume(baseVolume)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (PreferencesManager.isBlockAudioFocusSend(this)) return true

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

        return audioManager.requestAudioFocus(audioFocusRequest!!) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            try {
                audioManager.abandonAudioFocusRequest(it)
            } catch (_: Exception) {
            }
        }
    }

    // ==========================================
    // Bluetooth / 有線 / 音量0 自動制御
    // ==========================================

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (state == BluetoothProfile.STATE_DISCONNECTED &&
                        prev == BluetoothProfile.STATE_CONNECTED
                    ) {
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

    private fun isWiredDevice(device: AudioDeviceInfo): Boolean {
        return device.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY
        )
    }

    private fun isExternalAudioDeviceConnected(): Boolean {
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

    private fun isMusicStreamMuted(): Boolean {
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } else {
            false
        }
        return volume == 0 || muted
    }

    private fun shouldBlockPlayback(): Boolean {
        return PreferencesManager.isBlockSpeakerMutePlaybackEnabled(this) && isMusicStreamMuted()
    }

    private fun handlePlaybackBlocked() {
        Log.w(TAG, "Playback blocked because media volume is muted")
        sendErrorBroadcast(
            this,
            "blocked_media_mute",
            "メディア音量が0のため再生を一時停止しました"
        )
    }

    private fun startMuteMonitorLoop() {
        serviceScope.launch {
            var wasMuted = isMusicStreamMuted()
            while (isActive) {
                delay(MUTE_MONITOR_INTERVAL_MS)

                if (!PreferencesManager.isBlockSpeakerMutePlaybackEnabled(this@PlaybackService)) {
                    pausedByMute = false
                    wasMuted = isMusicStreamMuted()
                    continue
                }

                val muted = isMusicStreamMuted()
                val player = exoPlayer

                if (muted && !wasMuted && player?.isPlaying == true) {
                    pausedByMute = true
                    player.pause()
                    Log.d(TAG, "Media volume reached zero: auto-paused")
                } else if (!muted && wasMuted && pausedByMute && player != null) {
                    if (player.mediaItemCount > 0 &&
                        player.playbackState != Player.STATE_IDLE &&
                        player.playbackState != Player.STATE_ENDED
                    ) {
                        pausedByMute = false
                        player.play()
                        Log.d(TAG, "Media volume restored: auto-resumed")
                    } else {
                        pausedByMute = false
                    }
                }
                wasMuted = muted
            }
        }
    }

    private fun onAudioDeviceDisconnected() {
        if (!PreferencesManager.isAutoBluetoothControlEnabled(this)) return
        val player = exoPlayer ?: return

        saveCurrentPosition()
        lastDisconnectTimeMs = System.currentTimeMillis()
        pausedByDisconnect = player.isPlaying
        if (pausedByDisconnect) {
            player.pause()
        }
        Log.d(TAG, "Audio device disconnected: auto-paused=$pausedByDisconnect")
    }

    private fun onAudioDeviceReconnected() {
        if (!PreferencesManager.isAutoBluetoothControlEnabled(this)) return
        val player = exoPlayer ?: return
        if (!pausedByDisconnect) return

        if (PreferencesManager.isAutoResumeTimeoutEnabled(this)) {
            val timeoutMs = PreferencesManager.getAutoResumeTimeoutMs(this)
            val elapsed = System.currentTimeMillis() - lastDisconnectTimeMs
            if (elapsed > timeoutMs) {
                pausedByDisconnect = false
                Log.d(TAG, "Audio device reconnect timeout expired: $elapsed > $timeoutMs")
                return
            }
        }

        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delayMs = PreferencesManager.getBluetoothReconnectDelayMs(this@PlaybackService)
            delay(delayMs.toLong())
            if (!isExternalAudioDeviceConnected()) {
                Log.w(TAG, "Reconnect event received but no external audio route is active")
                pausedByDisconnect = false
                return@launch
            }

            pausedByDisconnect = false
            if (shouldBlockPlayback()) {
                // 接続先の音量が0なら、音量復帰ループに引き継ぐ。
                pausedByMute = true
                Log.d(TAG, "Reconnected while muted: waiting for volume restore")
            } else {
                player.play()
                Log.d(TAG, "Audio device reconnected: resumed after ${delayMs}ms")
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
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
    }

    private fun unregisterReceivers() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {
        }
    }

    // ==========================================
    // Position / playlist
    // ==========================================

    private fun startPositionSaveLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                exoPlayer?.takeIf { it.isPlaying }?.let { saveCurrentPosition() }
            }
        }
    }

    private fun saveCurrentPosition() {
        val player = exoPlayer ?: return
        if (player.currentPosition > 0) {
            PreferencesManager.saveTrackIndex(this, player.currentMediaItemIndex)
            PreferencesManager.savePlaybackPosition(this, player.currentPosition)
        }
    }

    private fun playFromUri(context: Context, folderUri: Uri) {
        playFromUriAt(
            context,
            folderUri,
            PreferencesManager.loadTrackIndex(context),
            PreferencesManager.loadPlaybackPosition(context)
        )
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
                val tracks = withContext(Dispatchers.IO) {
                    FolderScanner.scanFolder(context, folderUri)
                }
                if (tracks.isEmpty()) {
                    sendErrorBroadcast(context, "no_files", "音楽ファイルが見つかりません")
                } else {
                    playlist = tracks
                    consecutiveErrors = 0
                    loadPlaylist(
                        tracks,
                        startIndex.coerceIn(0, tracks.size - 1),
                        startPositionMs
                    )
                }
            } catch (e: SecurityException) {
                val msg = "フォルダへのアクセス権がありません。アプリを開いてフォルダを再選択してください。"
                Log.e(TAG, msg, e)
                sendErrorBroadcast(context, "permission_error", msg)
                PreferencesManager.saveErrorLog(context, "スキャンエラー(権限): ${e.message}")
            } catch (e: Exception) {
                val msg = "フォルダの読み込みに失敗しました: ${e.message}"
                Log.e(TAG, msg, e)
                sendErrorBroadcast(context, "scan_error", msg)
                PreferencesManager.saveErrorLog(context, "スキャンエラー: ${e.message}")
            }
        }
    }

    private fun loadPlaylist(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        val player = exoPlayer ?: return
        val enableVolume = PreferencesManager.isEnableAppVolume(this)
        applyVolume(
            if (enableVolume) PreferencesManager.getAppPlaybackVolume(this) else 1.0f
        )
        pausedByDisconnect = false
        pausedByMute = false

        player.setMediaItems(
            tracks.map { track ->
                MediaItem.Builder()
                    .setUri(track.uri)
                    .setMediaId(track.uri.toString())
                    .setMediaMetadata(buildMediaMetadata(track))
                    .build()
            },
            startIndex,
            startPositionMs
        )
        player.prepare()
        if (shouldBlockPlayback()) {
            player.playWhenReady = false
            pausedByMute = true
            handlePlaybackBlocked()
        } else {
            player.playWhenReady = true
        }
    }

    // ==========================================
    // App volume / LoudnessEnhancer
    // ==========================================

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        try {
            loudnessEnhancer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release previous LoudnessEnhancer: ${e.message}")
        }
        loudnessEnhancer = null

        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return

        try {
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).also {
                it.enabled = true
            }
            val volume = if (PreferencesManager.isEnableAppVolume(this)) {
                PreferencesManager.getAppPlaybackVolume(this)
            } else {
                1.0f
            }
            applyVolume(volume)
        } catch (e: Exception) {
            // 端末がエフェクトを提供しない場合もクラッシュせず通常音量で再生する。
            Log.w(TAG, "LoudnessEnhancer unavailable for session=$audioSessionId: ${e.message}")
            loudnessEnhancer = null
        }
    }

    private fun applyVolume(volume: Float) {
        val player = exoPlayer ?: return
        val clamped = if (volume.isFinite()) {
            volume.coerceIn(0.0f, PreferencesManager.MAX_APP_PLAYBACK_VOLUME)
        } else {
            1.0f
        }

        // ExoPlayer 自体は 0..1。100%超はエフェクト側へ分離して範囲外例外を防ぐ。
        try {
            player.volume = clamped.coerceAtMost(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set base player volume", e)
            try {
                player.volume = 1.0f
            } catch (_: Exception) {
            }
        }

        val enhancer = loudnessEnhancer ?: return
        try {
            if (clamped > 1.0f) {
                val gainMb = (20.0 * log10(clamped.toDouble()) * 100.0)
                    .toInt()
                    .coerceAtLeast(0)
                enhancer.setTargetGain(gainMb)
                if (!enhancer.enabled) enhancer.enabled = true
            } else {
                enhancer.setTargetGain(0)
            }
        } catch (e: Exception) {
            // 増幅失敗は再生自体を落とさず、100%を上限として継続する。
            Log.w(TAG, "Failed to apply LoudnessEnhancer gain: ${e.message}")
            try {
                enhancer.setTargetGain(0)
            } catch (_: Exception) {
            }
        }
    }

    private fun buildMediaMetadata(track: Track): MediaMetadata {
        return MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist ?: "Unknown Artist")
            .setAlbumTitle(track.album ?: "Unknown Album")
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    private fun stopPlayback() {
        pausedByDisconnect = false
        pausedByMute = false
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        PreferencesManager.clearPlaybackState(this)
        stopSelf()
    }

    // ==========================================
    // Broadcasts
    // ==========================================

    private fun sendEventBroadcast(event: String, trackName: String) {
        sendBroadcast(Intent(ACTION_PLAYBACK_EVENT).apply {
            putExtra(EXTRA_EVENT, event)
            putExtra(EXTRA_TRACK_NAME, trackName)
            putExtra(EXTRA_FOLDER_URI_KEY, currentFolderUri?.toString() ?: "")
        })
    }

    private fun sendErrorBroadcast(context: Context, reason: String, message: String) {
        context.sendBroadcast(Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_REASON, reason)
            putExtra(EXTRA_ERROR_MESSAGE, message)
        })
    }

    companion object {
        const val ACTION_PLAYBACK_EVENT = "com.intentplayer.PLAYBACK_EVENT"
        const val ACTION_ERROR = "com.intentplayer.ERROR"
        const val EXTRA_EVENT = "event"
        const val EXTRA_TRACK_NAME = "trackName"
        const val EXTRA_FOLDER_URI_KEY = "folderUri"
        const val EVENT_TRACK_COMPLETED = "track_completed"
        const val EVENT_PLAYLIST_COMPLETED = "playlist_completed"
        const val EXTRA_ERROR_REASON = "reason"
        const val EXTRA_ERROR_MESSAGE = "message"
        const val ERROR_PLAYBACK_FAILED = "playback_failed"
        const val CMD_FORCE_PLAY = "force_play"

        const val NOTIFICATION_CHANNEL_ID = "intentplayer_playback"
        const val NOTIFICATION_CHANNEL_ID_IDLE = "intentplayer_idle"
        const val FOREGROUND_NOTIFICATION_ID = 1001

        private const val CMD_APP_VOLUME = "app_volume"
        private const val EXTRA_APP_VOLUME = "volume"
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val MUTE_MONITOR_INTERVAL_MS = 500L
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }
}
