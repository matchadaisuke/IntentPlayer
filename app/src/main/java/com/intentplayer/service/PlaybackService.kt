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

    private var loudnessEnhancer: android.media.audiofx.LoudnessEnhancer? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    override fun onCreate() {
        super.onCreate()
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
        val result = super.onStartCommand(intent, flags, startId)
        val command = intent?.getStringExtra(ControlReceiver.EXTRA_COMMAND)
        if (command != null) handleCommand(intent, command)
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        if (PreferencesManager.isUseCustomMediaPlayback(this) &&
            controllerInfo.packageName != packageName
        ) {
            Log.d(TAG, "Blocked external controller ${controllerInfo.packageName}")
            return null
        }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved: keep service alive for intent control")
    }

    override fun onDestroy() {
        saveCurrentPosition()
        serviceScope.cancel()
        unregisterReceivers()
        abandonAudioFocus()
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
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

    private fun handleCommand(intent: Intent, command: String) {
        when (command) {
            ControlReceiver.CMD_PLAY, CMD_FORCE_PLAY -> {
                val folderUriStr = intent.getStringExtra(ControlReceiver.EXTRA_FOLDER_URI)
                val folderUri = if (folderUriStr != null) {
                    try { Uri.parse(folderUriStr) } catch (_: Exception) { null }
                } else {
                    PreferencesManager.loadFolderUri(this)
                }
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
                clearAutomaticPauseReasons()
                exoPlayer?.pause()
            }
            ControlReceiver.CMD_STOP -> {
                clearAutomaticPauseReasons()
                stopPlayback()
            }
            ControlReceiver.CMD_NEXT ->
                exoPlayer?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()

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
                applyVolume(intent.getFloatExtra(EXTRA_APP_VOLUME, 1.0f))
            }
        }
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
                refreshMediaPresentation()

                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    playlist.getOrNull(idx - 1)?.let { prev ->
                        sendEventBroadcast(EVENT_TRACK_COMPLETED, prev.name)
                    }
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

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) requestAudioFocus() else abandonAudioFocus()
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
                if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                else {
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
                        Log.d(TAG, "Ignored external media button from ${controllerInfo.packageName}")
                        return true
                    }
                    return false
                }
            })
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
                return if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {
                    MediaNotification(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())
                } else {
                    defaultProvider.createNotification(
                        mediaSession,
                        customLayout,
                        actionFactory,
                        onNotificationChangedCallback
                    )
                }
            }

            override fun handleCustomCommand(
                session: MediaSession,
                action: String,
                extras: android.os.Bundle
            ): Boolean = defaultProvider.handleCustomCommand(session, action, extras)
        })
    }

    private fun refreshMediaPresentation() {
        if (PreferencesManager.isUseCustomMediaPlayback(this)) {
            updateSilentNotification()
        }
        // MediaSession metadata is sourced directly from the current MediaItem's MediaMetadata.
        // onMediaItemTransition/onMediaMetadataChanged cover manual next/previous, auto advance,
        // indexed seeks and any metadata replacement.
    }

    private fun buildSilentPlaybackNotification(): Notification {
        val player = exoPlayer
        val metadata = player?.currentMediaItem?.mediaMetadata
        val title = metadata?.title?.toString().orEmpty().ifBlank { "Unknown" }
        val artist = metadata?.artist?.toString().orEmpty()
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
            return PendingIntent.getBroadcast(
                this,
                command.hashCode(),
                Intent(this, ControlReceiver::class.java).apply {
                    action = ControlReceiver.ACTION_CONTROL
                    putExtra(ControlReceiver.EXTRA_COMMAND, command)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$indexText $title".trim())
            .setContentText(if (artist.isBlank()) statusText else "$artist ・ $statusText")
            .setContentIntent(tapIntent)
            .addAction(0, "前へ", buildActionIntent(ControlReceiver.CMD_PREVIOUS))
            .addAction(
                0,
                if (isPlaying) "一時停止" else "再生",
                buildActionIntent(if (isPlaying) ControlReceiver.CMD_PAUSE else ControlReceiver.CMD_PLAY)
            )
            .addAction(0, "次へ", buildActionIntent(ControlReceiver.CMD_NEXT))
            .addAction(0, "停止", buildActionIntent(ControlReceiver.CMD_STOP))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)

        loadArtworkBitmap(metadata?.artworkUri)?.let(builder::setLargeIcon)
        return builder.build()
    }

    private fun loadArtworkBitmap(uri: Uri?): Bitmap? {
        if (uri == null) return null
        return try {
            contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
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

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (PreferencesManager.isBlockAudioFocusReceive(this)) return@OnAudioFocusChangeListener
        val player = exoPlayer ?: return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player.pause()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val base = if (PreferencesManager.isEnableAppVolume(this)) {
                    PreferencesManager.getAppPlaybackVolume(this)
                } else 1.0f
                applyVolume(base * 0.2f)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val base = if (PreferencesManager.isEnableAppVolume(this)) {
                    PreferencesManager.getAppPlaybackVolume(this)
                } else 1.0f
                applyVolume(base)
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
        return audioManager.requestAudioFocus(audioFocusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let {
            try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) {}
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            val prev = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    if (state == BluetoothProfile.STATE_DISCONNECTED && prev == BluetoothProfile.STATE_CONNECTED) {
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
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in externalTypes }
    }

    private fun isExternalAudioDeviceConnected(): Boolean = externalOutputDevices().isNotEmpty()

    private fun currentOutputRouteKey(): String {
        val external = externalOutputDevices()
        if (external.isEmpty()) return "speaker"
        return external
            .map { "${it.type}:${it.address}" }
            .sorted()
            .joinToString("|")
    }

    private fun isMusicStreamMuted(): Boolean {
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val muted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        } else false
        return volume == 0 || muted
    }

    private fun shouldBlockPlayback(): Boolean =
        PreferencesManager.isBlockSpeakerMutePlaybackEnabled(this) && isMusicStreamMuted()

    private fun handlePlaybackBlocked() {
        sendErrorBroadcast(this, "blocked_media_mute", "メディア音量が0のため再生を一時停止しました")
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
                    Log.d(TAG, "Volume zero: paused route=$mutedRouteKey")
                }

                if (!muted && pausedByMute && player != null) {
                    val currentRoute = currentOutputRouteKey()
                    if (currentRoute == mutedRouteKey &&
                        player.mediaItemCount > 0 &&
                        player.playbackState != Player.STATE_IDLE &&
                        player.playbackState != Player.STATE_ENDED
                    ) {
                        pausedByMute = false
                        mutedRouteKey = null
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
            } else {
                player.play()
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
                val tracks = withContext(Dispatchers.IO) { FolderScanner.scanFolder(context, folderUri) }
                if (tracks.isEmpty()) {
                    sendErrorBroadcast(context, "no_files", "音楽ファイルが見つかりません")
                } else {
                    playlist = tracks
                    consecutiveErrors = 0
                    loadPlaylist(tracks, startIndex.coerceIn(0, tracks.size - 1), startPositionMs)
                }
            } catch (e: SecurityException) {
                val msg = "フォルダへのアクセス権がありません。アプリを開いてフォルダを再選択してください。"
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
        applyVolume(
            if (PreferencesManager.isEnableAppVolume(this)) {
                PreferencesManager.getAppPlaybackVolume(this)
            } else 1.0f
        )
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
            startPositionMs
        )
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
            loudnessEnhancer = android.media.audiofx.LoudnessEnhancer(audioSessionId).also {
                it.enabled = true
            }
            applyVolume(
                if (PreferencesManager.isEnableAppVolume(this)) {
                    PreferencesManager.getAppPlaybackVolume(this)
                } else 1.0f
            )
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

    private fun buildMediaMetadata(track: Track): MediaMetadata {
        return MediaMetadata.Builder()
            .setTitle(track.name)
            .setArtist(track.artist ?: "Unknown Artist")
            .setAlbumTitle(track.album ?: "Unknown Album")
            .setArtworkUri(track.artworkUri)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
    }

    private fun stopPlayback() {
        clearAutomaticPauseReasons()
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        PreferencesManager.clearPlaybackState(this)
        stopSelf()
    }

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
        private const val MAX_LOUDNESS_GAIN_MB = 600
    }
}
