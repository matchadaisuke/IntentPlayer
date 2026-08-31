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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
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
    private var pausedByDisconnect = false
    private var lastDisconnectTimeMs = 0L
    private var reconnectJob: Job? = null
    private var pausedByMute = false
    private var mutedRouteKey: String? = null
    private var requestedPlaybackSpeed = 1.0f
    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    private var defaultNotificationProvider: DefaultMediaNotificationProvider? = null
    private var lastNotificationLayout: ImmutableList<CommandButton>? = null
    private var lastNotificationActionFactory: MediaNotification.ActionFactory? = null
    private var notificationChangedCallback: MediaNotification.Provider.Callback? = null
    private var notificationTickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        requestedPlaybackSpeed = PreferencesManager.getPlaybackSpeed(this)
        ensureNotificationChannel()
        startForeground(FOREGROUND_NOTIFICATION_ID, buildIdleNotification())
        initExoPlayer()
        if (!PreferencesManager.isUseCustomMediaPlayback(this)) {
            initMediaSession()
            setupNotificationProvider()
        }
        startPositionSaveLoop()
        startStateBroadcastLoop()
        startMuteMonitorLoop()
        registerReceivers()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        intent?.getStringExtra(ControlReceiver.EXTRA_COMMAND)?.let { handleCommand(intent, it) }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: keep service alive for intent control")
    }

    override fun onDestroy() {
        saveCurrentPosition()
        notificationTickerJob?.cancel()
        notificationTickerJob = null
        reconnectJob?.cancel()
        scanJob?.cancel()
        serviceScope.cancel()
        unregisterReceivers()
        abandonAudioFocus()
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        try { mediaSession?.release() } catch (_: Exception) {}
        mediaSession = null
        playerWrapper = null
        try { exoPlayer?.release() } catch (_: Exception) {}
        exoPlayer = null
        super.onDestroy()
    }

    private fun handleCommand(intent: Intent, command: String) {
        when (command) {
            ControlReceiver.CMD_PLAY, CMD_FORCE_PLAY -> {
                val folderUriStr = intent.getStringExtra(ControlReceiver.EXTRA_FOLDER_URI)
                val folderUri = if (folderUriStr != null) {
                    try { Uri.parse(folderUriStr) } catch (_: Exception) { null }
                } else PreferencesManager.loadFolderUri(this)
                val index = intent.getIntExtra("index", -1)
                if (folderUri != null) {
                    if (shouldBlockPlayback()) {
                        handlePlaybackBlocked()
                        return
                    }
                    clearAutomaticPauseReasons()
                    if (command == CMD_FORCE_PLAY) {
                        playFromUriFromStart(this, folderUri)
                    } else {
                        val player = exoPlayer
                        val reusableQueue = player != null &&
                            currentFolderUri == folderUri &&
                            player.mediaItemCount > 0 &&
                            player.playbackState != Player.STATE_IDLE &&
                            player.playbackState != Player.STATE_ENDED

                        if (reusableQueue && player != null) {
                            if (index >= 0) {
                                val target = index.coerceIn(0, player.mediaItemCount - 1)
                                player.seekTo(target, 0L)
                            }
                            player.setPlaybackSpeed(requestedPlaybackSpeed)
                            player.play()
                        } else {
                            if (index >= 0) playFromUriAt(this, folderUri, index) else playFromUri(this, folderUri)
                        }
                    }
                } else {
                    sendErrorBroadcast(this, "no_folder_uri", "再生するフォルダが設定されていません")
                }
            }
            ControlReceiver.CMD_PAUSE -> {
                clearAutomaticPauseReasons()
                exoPlayer?.pause()
            }
            ControlReceiver.CMD_STOP -> {
                clearAutomaticPauseReasons()
                stopPlayback()
            }
            ControlReceiver.CMD_NEXT -> {
                val player = exoPlayer ?: return
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.setPlaybackSpeed(requestedPlaybackSpeed)
                }
            }
            ControlReceiver.CMD_PREVIOUS -> {
                val player = exoPlayer ?: return
                if (player.currentPosition > 3000L) player.seekTo(0L)
                else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                player.setPlaybackSpeed(requestedPlaybackSpeed)
            }
            ControlReceiver.CMD_SEEK -> {
                val pos = intent.getLongExtra(ControlReceiver.EXTRA_SEEK_TO, -1L)
                if (pos >= 0) {
                    exoPlayer?.seekTo(pos)
                    refreshMediaPresentation()
                }
            }
            ControlReceiver.CMD_SPEED -> {
                requestedPlaybackSpeed = PreferencesManager.normalizePlaybackSpeed(
                    intent.getFloatExtra(ControlReceiver.EXTRA_SPEED, PreferencesManager.getPlaybackSpeed(this))
                )
                PreferencesManager.setPlaybackSpeed(this, requestedPlaybackSpeed)
                exoPlayer?.setPlaybackSpeed(requestedPlaybackSpeed)
                refreshMediaPresentation()
            }
            CMD_APP_VOLUME -> applyVolume(intent.getFloatExtra(EXTRA_APP_VOLUME, 1.0f))
            CMD_CUSTOM_MODE -> applyCustomMediaPlaybackMode(
                intent.getBooleanExtra(EXTRA_CUSTOM_MODE, PreferencesManager.isUseCustomMediaPlayback(this))
            )
        }
    }

    private fun applyCustomMediaPlaybackMode(enabled: Boolean) {
        if (enabled) {
            try { mediaSession?.release() } catch (e: Exception) {
                Log.w(TAG, "Failed to release MediaSession for private mode: ${e.message}")
            }
            mediaSession = null
            defaultNotificationProvider = null
            lastNotificationLayout = null
            lastNotificationActionFactory = null
            notificationChangedCallback = null
            updateSilentNotification()
        } else if (mediaSession == null) {
            initMediaSession()
            setupNotificationProvider()
            refreshMediaPresentation()
        }
        sendPlaybackStateBroadcast()
    }

    private fun clearAutomaticPauseReasons() {
        pausedByDisconnect = false
        pausedByMute = false
        mutedRouteKey = null
    }

    private fun initExoPlayer() {
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                false
            )
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.setPlaybackSpeed(requestedPlaybackSpeed)
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
                player.setPlaybackSpeed(requestedPlaybackSpeed)
                refreshMediaPresentation()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    playlist.getOrNull(idx - 1)?.let { sendEventBroadcast(EVENT_TRACK_COMPLETED, it.name) }
                }
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                refreshMediaPresentation()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    sendEventBroadcast(EVENT_PLAYLIST_COMPLETED, "")
                    consecutiveErrors = 0
                    stopPlayback()
                    return
                }
                refreshMediaPresentation()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                refreshMediaPresentation()
                updateNotificationTicker(playWhenReady)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) requestAudioFocus()
                else if (!player.playWhenReady) abandonAudioFocus()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                refreshMediaPresentation()
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
                    player.setPlaybackSpeed(requestedPlaybackSpeed)
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
                clearAutomaticPauseReasons()
                super.play()
            }
            override fun pause() {
                clearAutomaticPauseReasons()
                super.pause()
            }
            override fun setPlayWhenReady(playWhenReady: Boolean) {
                if (playWhenReady && shouldBlockPlayback()) {
                    handlePlaybackBlocked()
                    return
                }
                if (playWhenReady) clearAutomaticPauseReasons()
                super.setPlayWhenReady(playWhenReady)
            }
            override fun stop() {
                clearAutomaticPauseReasons()
                super.stop()
                stopPlayback()
            }
        }
    }

    private fun playerActivityIntent(): Intent = Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        putExtra(MainActivity.EXTRA_OPEN_PLAYER_TAB, true)
    }

    private fun initMediaSession() {
        if (PreferencesManager.isUseCustomMediaPlayback(this) || mediaSession != null) return
        val player = playerWrapper ?: return
        val sessionActivityPi = PendingIntent.getActivity(
            this,
            0,
            playerActivityIntent(),
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
                        Log.d(TAG, "Ignored external media button from ${controllerInfo.packageName}")
                        return true
                    }
                    return false
                }
            })
            .build()
    }

    private fun setupNotificationProvider() {
        val delegate = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(NOTIFICATION_CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        delegate.setSmallIcon(R.drawable.ic_notification)
        defaultNotificationProvider = delegate

        setMediaNotificationProvider(object : MediaNotification.Provider {
            override fun createNotification(
                mediaSession: MediaSession,
                customLayout: ImmutableList<CommandButton>,
                actionFactory: MediaNotification.ActionFactory,
                onNotificationChangedCallback: MediaNotification.Provider.Callback
            ): MediaNotification {
                lastNotificationLayout = customLayout
                lastNotificationActionFactory = actionFactory
                notificationChangedCallback = onNotificationChangedCallback
                val created = if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    MediaNotification(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())
                } else {
                    delegate.createNotification(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)
                }
                applyRemainingTime(created.notification)
                return created
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: android.os.Bundle
            ): Boolean = delegate.handleCustomCommand(session, action, extras)
        })
    }

    private fun refreshMediaPresentation() {
        val session = mediaSession
        val callback = notificationChangedCallback
        if (session != null && callback != null) {
            val refreshed = if (PreferencesManager.isUseCustomMediaPlayback(this)) {
                MediaNotification(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())
            } else {
                val delegate = defaultNotificationProvider
                val layout = lastNotificationLayout
                val actionFactory = lastNotificationActionFactory
                if (delegate != null && layout != null && actionFactory != null) {
                    delegate.createNotification(session, layout, actionFactory, callback)
                } else null
            }
            if (refreshed != null) {
                try {
                    applyRemainingTime(refreshed.notification)
                    callback.onNotificationChanged(refreshed)
                } catch (e: Exception) {
                    Log.w(TAG, "Media3 notification callback refresh failed: ${e.message}")
                }
            }
        }
        if (PreferencesManager.isUseCustomMediaPlayback(this)) updateSilentNotification()
        sendPlaybackStateBroadcast()
    }

    private fun isPlaybackRequested(player: Player?): Boolean =
        player != null &&
            player.playWhenReady &&
            player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

    private fun buildSilentPlaybackNotification(): Notification {
        val player = exoPlayer
        val metadata = player?.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString().orEmpty().ifBlank { "Unknown" }
        val idx = player?.currentMediaItemIndex ?: 0
        val total = playlist.size
        val indexText = if (total > 0) "[${idx + 1}/$total]" else ""
        val playbackRequested = isPlaybackRequested(player)
        val remainingText = buildRemainingTimeText()
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            playerActivityIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun buildActionIntent(command: String): PendingIntent = PendingIntent.getBroadcast(
            this,
            command.hashCode(),
            Intent(this, ControlReceiver::class.java).apply {
                action = ControlReceiver.ACTION_CONTROL
                putExtra(ControlReceiver.EXTRA_COMMAND, command)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$indexText $title".trim())
            .setContentText(remainingText)
            .setContentIntent(tapIntent)
            .addAction(0, "前へ", buildActionIntent(ControlReceiver.CMD_PREVIOUS))
            .addAction(0, if (playbackRequested) "一時停止" else "再生", buildActionIntent(if (playbackRequested) ControlReceiver.CMD_PAUSE else ControlReceiver.CMD_PLAY))
            .addAction(0, "次へ", buildActionIntent(ControlReceiver.CMD_NEXT))
            .addAction(0, "停止", buildActionIntent(ControlReceiver.CMD_STOP))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
        loadArtworkBitmap(metadata?.artworkUri)?.let(builder::setLargeIcon)
        return builder.build()
    }

    private fun updateNotificationTicker(playbackRequested: Boolean) {
        notificationTickerJob?.cancel()
        notificationTickerJob = null
        if (!playbackRequested) return
        notificationTickerJob = serviceScope.launch {
            while (isActive && isPlaybackRequested(exoPlayer)) {
                delay(NOTIFICATION_REMAINING_UPDATE_INTERVAL_MS)
                refreshMediaPresentation()
            }
        }
    }

    private fun calculateRemainingTimesMs(): Pair<Long, Long> {
        val player = exoPlayer ?: return 0L to 0L
        if (player.mediaItemCount <= 0) return 0L to 0L

        val index = player.currentMediaItemIndex.coerceAtLeast(0)
        val currentDurationMs = player.duration.takeIf { it > 0L }
            ?: playlist.getOrNull(index)?.durationMs?.takeIf { it > 0L }
            ?: 0L
        val currentPositionMs = player.currentPosition.coerceAtLeast(0L)
        val currentRemainingMediaMs = (currentDurationMs - currentPositionMs).coerceAtLeast(0L)
        val followingMediaMs = if (index + 1 < playlist.size) {
            playlist.subList(index + 1, playlist.size).sumOf { it.durationMs.coerceAtLeast(0L) }
        } else 0L

        val speed = PreferencesManager.normalizePlaybackSpeed(requestedPlaybackSpeed)
        val fileRemainingMs = (currentRemainingMediaMs / speed).toLong().coerceAtLeast(0L)
        val folderRemainingMs = ((currentRemainingMediaMs + followingMediaMs) / speed)
            .toLong()
            .coerceAtLeast(0L)
        return fileRemainingMs to folderRemainingMs
    }

    private fun buildRemainingTimeText(): String {
        val (fileRemainingMs, folderRemainingMs) = calculateRemainingTimesMs()
        return "残り: ${formatRemainingClock(fileRemainingMs)}・${formatRemainingClock(folderRemainingMs)}"
    }

    private fun formatRemainingClock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        } else {
            "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        }
    }

    private fun applyRemainingTime(notification: Notification) {
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, buildRemainingTimeText())
    }

    private fun loadArtworkBitmap(uri: Uri?): Bitmap? {
        if (uri == null) return null
        return try {
            contentResolver.openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input) }
        } catch (e: Exception) {
            Log.d(TAG, "Artwork load failed for $uri: ${e.message}")
            null
        }
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
            playerActivityIntent(),
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
            nm.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音楽の再生状態を表示します"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID_IDLE) == null) {
            nm.createNotificationChannel(NotificationChannel(
                NOTIFICATION_CHANNEL_ID_IDLE,
                getString(R.string.notification_channel_idle_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "待機状態の通知を表示します"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (PreferencesManager.isBlockAudioFocusReceive(this)) return@OnAudioFocusChangeListener
        val player = exoPlayer ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val base = if (PreferencesManager.isEnableAppVolume(this)) PreferencesManager.getAppPlaybackVolume(this) else 1.0f
                applyVolume(base * 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val base = if (PreferencesManager.isEnableAppVolume(this)) PreferencesManager.getAppPlaybackVolume(this) else 1.0f
                applyVolume(base)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (PreferencesManager.isBlockAudioFocusSend(this)) return true
        if (audioFocusRequest == null) {
            audioFocusRequest = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }
        return audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (state == BluetoothProfile.STATE_DISCONNECTED && prev == BluetoothProfile.STATE_CONNECTED) onAudioDeviceDisconnected()
                    else if (state == BluetoothProfile.STATE_CONNECTED) onAudioDeviceReconnected()
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

    private fun isWiredDevice(device: AudioDeviceInfo): Boolean = device.type in setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY
    )

    private fun externalOutputDevices(): List<AudioDeviceInfo> {
        val externalTypes = setOf(
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
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.type in externalTypes }
    }

    private fun isExternalAudioDeviceConnected(): Boolean = externalOutputDevices().isNotEmpty()

    private fun currentOutputRouteKey(): String {
        val external = externalOutputDevices()
        if (external.isEmpty()) return "speaker"
        return external.map { "${it.type}:${runCatching { it.address }.getOrDefault("")}" }.sorted().joinToString("|")
    }

    private fun isMusicStreamMuted(): Boolean {
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) audioManager.isStreamMute(AudioManager.STREAM_MUSIC) else false
        return volume == 0 || muted
    }

    private fun shouldBlockPlayback(): Boolean =
        PreferencesManager.isBlockSpeakerMutePlaybackEnabled(this) && isMusicStreamMuted()

    private fun handlePlaybackBlocked() {
        sendErrorBroadcast(this, "blocked_media_mute", "メディア音量が0のため再生できません。音量を上げてください。")
    }

    private fun startMuteMonitorLoop() {
        serviceScope.launch {
            var wasMuted = isMusicStreamMuted()
            while (isActive) {
                delay(MUTE_MONITOR_INTERVAL_MS)
                if (!PreferencesManager.isBlockSpeakerMutePlaybackEnabled(this@PlaybackService)) {
                    pausedByMute = false
                    mutedRouteKey = null
                    wasMuted = isMusicStreamMuted()
                    continue
                }
                val muted = isMusicStreamMuted()
                val player = exoPlayer
                if (muted && !wasMuted && player?.isPlaying == true) {
                    pausedByMute = true
                    mutedRouteKey = currentOutputRouteKey()
                    player.pause()
                    sendErrorBroadcast(this@PlaybackService, "paused_media_mute", "メディア音量が0になったため再生を一時停止しました。")
                    Log.d(TAG, "Volume zero: paused route=$mutedRouteKey")
                }
                if (!muted && pausedByMute && player != null) {
                    val currentRoute = currentOutputRouteKey()
                    if (currentRoute == mutedRouteKey && player.mediaItemCount > 0 &&
                        player.playbackState != Player.STATE_IDLE && player.playbackState != Player.STATE_ENDED
                    ) {
                        pausedByMute = false
                        mutedRouteKey = null
                        player.setPlaybackSpeed(requestedPlaybackSpeed)
                        player.play()
                        Log.d(TAG, "Volume restored on same route: resumed route=$currentRoute")
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
        if (pausedByDisconnect) player.pause()
    }

    private fun onAudioDeviceReconnected() {
        if (!PreferencesManager.isAutoBluetoothControlEnabled(this)) return
        val player = exoPlayer ?: return
        if (!pausedByDisconnect) return
        if (PreferencesManager.isAutoResumeTimeoutEnabled(this)) {
            val timeout = PreferencesManager.getAutoResumeTimeoutMs(this)
            if (System.currentTimeMillis() - lastDisconnectTimeMs > timeout) {
                pausedByDisconnect = false
                return
            }
        }
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            val delayMs = PreferencesManager.getBluetoothReconnectDelayMs(this@PlaybackService)
            delay(delayMs.toLong())
            if (!isExternalAudioDeviceConnected()) {
                pausedByDisconnect = false
                return@launch
            }
            pausedByDisconnect = false
            if (shouldBlockPlayback()) {
                pausedByMute = true
                mutedRouteKey = currentOutputRouteKey()
                handlePlaybackBlocked()
            } else {
                player.setPlaybackSpeed(requestedPlaybackSpeed)
                player.play()
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth receiver unavailable: ${e.message}")
            PreferencesManager.saveErrorLog(this, "Bluetooth権限不足: ${e.message}")
        }
        try {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Audio device callback unavailable: ${e.message}")
        }
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Exception) {}
    }

    private fun startPositionSaveLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                exoPlayer?.takeIf { it.isPlaying }?.let { saveCurrentPosition() }
            }
        }
    }

    private fun startStateBroadcastLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(STATE_BROADCAST_INTERVAL_MS)
                if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    sendPlaybackStateBroadcast()
                }
            }
        }
    }

    private fun sendPlaybackStateBroadcast() {
        val player = exoPlayer ?: return
        val index = player.currentMediaItemIndex
        sendBroadcast(Intent(ACTION_PLAYBACK_STATE).setPackage(packageName).apply {
            putExtra(EXTRA_STATE_INDEX, index)
            putExtra(EXTRA_STATE_IS_PLAYING, isPlaybackRequested(player))
            putExtra(EXTRA_STATE_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            putExtra(
                EXTRA_STATE_DURATION_MS,
                player.duration.takeIf { it > 0 }
                    ?: playlist.getOrNull(index)?.durationMs?.takeIf { it > 0L }
                    ?: 0L
            )
            putExtra(EXTRA_STATE_SPEED, requestedPlaybackSpeed)
            putExtra(EXTRA_STATE_HAS_MEDIA, player.mediaItemCount > 0)
        })
    }

    private fun saveCurrentPosition() {
        val player = exoPlayer ?: return
        if (player.currentPosition > 0) {
            PreferencesManager.saveTrackIndex(this, player.currentMediaItemIndex)
            PreferencesManager.savePlaybackPosition(this, player.currentPosition)
        }
    }

    private fun playFromUri(context: Context, folderUri: Uri) {
        playFromUriAt(context, folderUri, PreferencesManager.loadTrackIndex(context), PreferencesManager.loadPlaybackPosition(context))
    }

    private fun playFromUriFromStart(context: Context, folderUri: Uri) {
        PreferencesManager.clearPlaybackState(context)
        playFromUriAt(context, folderUri, 0, 0L)
    }

    private fun playFromUriAt(context: Context, folderUri: Uri, startIndex: Int, startPositionMs: Long = 0L) {
        currentFolderUri = folderUri
        PreferencesManager.saveFolderUri(context, folderUri)
        serviceScope.launch {
            scanJob?.cancel()
            scanJob = coroutineContext[Job]
            try {
                val tracks = withContext(Dispatchers.IO) { FolderScanner.scanFolder(context, folderUri) }
                if (tracks.isEmpty()) {
                    sendErrorBroadcast(context, "no_files", "音楽ファイルが見つかりません")
                } else {
                    playlist = tracks
                    consecutiveErrors = 0
                    loadPlaylist(tracks, startIndex.coerceIn(0, tracks.size - 1), startPositionMs)
                }
            } catch (e: SecurityException) {
                val msg = "フォルダへのアクセス権がありません。アプリを開いてフォルダを再選択するか、SAFで選択してください。"
                sendErrorBroadcast(context, "permission_error", msg)
                PreferencesManager.saveErrorLog(context, "スキャンエラー(権限): ${e.message}")
            } catch (e: Exception) {
                val msg = "フォルダの読み込みに失敗しました: ${e.message}"
                sendErrorBroadcast(context, "scan_error", msg)
                PreferencesManager.saveErrorLog(context, "スキャンエラー: ${e.message}")
            }
        }
    }

    private fun loadPlaylist(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        val player = exoPlayer ?: return
        applyVolume(if (PreferencesManager.isEnableAppVolume(this)) PreferencesManager.getAppPlaybackVolume(this) else 1.0f)
        clearAutomaticPauseReasons()
        player.setMediaItems(
            tracks.map { track ->
                MediaItem.Builder()
                    .setUri(track.uri)
                    .setMediaId(track.uri.toString())
                    .setMediaMetadata(buildMediaMetadata(track))
                    .build()
            },
            startIndex,
            startPositionMs.coerceAtLeast(0L)
        )
        player.setPlaybackSpeed(requestedPlaybackSpeed)
        player.prepare()
        if (shouldBlockPlayback()) {
            player.playWhenReady = false
            pausedByMute = true
            mutedRouteKey = currentOutputRouteKey()
            handlePlaybackBlocked()
        } else {
            player.playWhenReady = true
        }
    }

    private fun setupLoudnessEnhancer(audioSessionId: Int) {
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
        try {
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).also { it.enabled = true }
            applyVolume(if (PreferencesManager.isEnableAppVolume(this)) PreferencesManager.getAppPlaybackVolume(this) else 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "LoudnessEnhancer unavailable: ${e.message}")
            loudnessEnhancer = null
        }
    }

    private fun applyVolume(volume: Float) {
        val player = exoPlayer ?: return
        val clamped = if (volume.isFinite()) {
            volume.coerceIn(0.0f, PreferencesManager.MAX_APP_PLAYBACK_VOLUME)
        } else 1.0f

        try {
            player.volume = clamped.coerceAtMost(1.0f)
        } catch (e: Exception) {
            Log.e(TAG, "Base volume failed", e)
            try { player.volume = 1.0f } catch (_: Exception) {}
        }

        val enhancer = loudnessEnhancer ?: return
        try {
            val gainMb = if (clamped > 1.0f) {
                (20.0 * log10(clamped.toDouble()) * 100.0)
                    .toInt()
                    .coerceIn(0, MAX_LOUDNESS_GAIN_MB)
            } else 0
            enhancer.setTargetGain(gainMb)
            if (!enhancer.enabled) enhancer.enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "Loudness gain failed: ${e.message}")
            try { enhancer.setTargetGain(0) } catch (_: Exception) {}
        }
    }

    private fun buildMediaMetadata(track: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(track.name)
        .setAlbumTitle(track.album ?: "Unknown Album")
        .setArtworkUri(track.artworkUri)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .build()

    private fun stopPlayback() {
        clearAutomaticPauseReasons()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        playlist = emptyList()
        currentFolderUri = null
        PreferencesManager.clearPlaybackState(this)
        sendPlaybackStateBroadcast()
        stopSelf()
    }

    private fun sendEventBroadcast(event: String, trackName: String) {
        sendBroadcast(Intent(ACTION_PLAYBACK_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_EVENT, event)
            putExtra(EXTRA_TRACK_NAME, trackName)
            putExtra(EXTRA_FOLDER_URI_KEY, currentFolderUri?.toString() ?: "")
        })
    }

    private fun sendErrorBroadcast(context: Context, reason: String, message: String) {
        context.sendBroadcast(Intent(ACTION_ERROR).apply {
            setPackage(context.packageName)
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
        const val ACTION_PLAYBACK_STATE = "com.intentplayer.PLAYBACK_STATE_INTERNAL"
        const val EXTRA_STATE_INDEX = "stateIndex"
        const val EXTRA_STATE_IS_PLAYING = "stateIsPlaying"
        const val EXTRA_STATE_POSITION_MS = "statePositionMs"
        const val EXTRA_STATE_DURATION_MS = "stateDurationMs"
        const val EXTRA_STATE_SPEED = "stateSpeed"
        const val EXTRA_STATE_HAS_MEDIA = "stateHasMedia"
        const val CMD_CUSTOM_MODE = "custom_mode"
        const val EXTRA_CUSTOM_MODE = "customMode"
        private const val CMD_APP_VOLUME = "app_volume"
        private const val EXTRA_APP_VOLUME = "volume"
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val STATE_BROADCAST_INTERVAL_MS = 500L
        private const val NOTIFICATION_REMAINING_UPDATE_INTERVAL_MS = 1_000L
        private const val MUTE_MONITOR_INTERVAL_MS = 500L
        private const val MAX_CONSECUTIVE_ERRORS = 3
        private const val MAX_LOUDNESS_GAIN_MB = 1_400
    }
}
