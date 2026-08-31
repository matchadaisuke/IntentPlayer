from pathlib import Path

path = Path("app/src/main/java/com/intentplayer/service/PlaybackService.kt")
s = path.read_text()


def once(old: str, new: str, label: str) -> None:
    global s
    if old not in s:
        raise SystemExit(f"missing patch target: {label}")
    s = s.replace(old, new, 1)


once(
    "    private var notificationChangedCallback: MediaNotification.Provider.Callback? = null\n",
    "    private var notificationChangedCallback: MediaNotification.Provider.Callback? = null\n"
    "    private var notificationTickerJob: Job? = null\n",
    "notification ticker field",
)

once(
    "        saveCurrentPosition()\n        serviceScope.cancel()\n",
    "        saveCurrentPosition()\n        notificationTickerJob?.cancel()\n"
    "        notificationTickerJob = null\n        serviceScope.cancel()\n",
    "ticker cleanup",
)

once(
    "            ControlReceiver.CMD_SEEK -> {\n"
    "                val pos = intent.getLongExtra(ControlReceiver.EXTRA_SEEK_TO, -1L)\n"
    "                if (pos >= 0) exoPlayer?.seekTo(pos)\n"
    "            }",
    "            ControlReceiver.CMD_SEEK -> {\n"
    "                val pos = intent.getLongExtra(ControlReceiver.EXTRA_SEEK_TO, -1L)\n"
    "                if (pos >= 0) {\n"
    "                    exoPlayer?.seekTo(pos)\n"
    "                    refreshMediaPresentation()\n"
    "                }\n"
    "            }",
    "seek refresh",
)

once(
    "            ControlReceiver.CMD_SPEED -> {\n"
    "                val speed = intent.getFloatExtra(ControlReceiver.EXTRA_SPEED, 1.0f)\n"
    "                exoPlayer?.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))\n"
    "            }",
    "            ControlReceiver.CMD_SPEED -> {\n"
    "                val speed = intent.getFloatExtra(ControlReceiver.EXTRA_SPEED, 1.0f)\n"
    "                exoPlayer?.setPlaybackSpeed(speed.coerceIn(0.5f, 2.0f))\n"
    "                refreshMediaPresentation()\n"
    "            }",
    "speed refresh",
)

once(
    "            override fun onIsPlayingChanged(isPlaying: Boolean) {\n"
    "                if (isPlaying) requestAudioFocus() else abandonAudioFocus()\n"
    "                refreshMediaPresentation()\n"
    "            }",
    "            override fun onIsPlayingChanged(isPlaying: Boolean) {\n"
    "                if (isPlaying) requestAudioFocus() else abandonAudioFocus()\n"
    "                refreshMediaPresentation()\n"
    "                updateNotificationTicker(isPlaying)\n"
    "            }\n\n"
    "            override fun onPositionDiscontinuity(\n"
    "                oldPosition: Player.PositionInfo,\n"
    "                newPosition: Player.PositionInfo,\n"
    "                reason: Int\n"
    "            ) {\n"
    "                refreshMediaPresentation()\n"
    "            }",
    "listener refresh hooks",
)

once(
    "                return if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {\n"
    "                    MediaNotification(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())\n"
    "                } else {\n"
    "                    delegate.createNotification(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)\n"
    "                }",
    "                val created = if (PreferencesManager.isUseCustomMediaPlayback(this@PlaybackService)) {\n"
    "                    MediaNotification(FOREGROUND_NOTIFICATION_ID, buildSilentPlaybackNotification())\n"
    "                } else {\n"
    "                    delegate.createNotification(mediaSession, customLayout, actionFactory, onNotificationChangedCallback)\n"
    "                }\n"
    "                applyRemainingTime(created.notification)\n"
    "                return created",
    "provider create notification",
)

once(
    "            if (refreshed != null) {\n"
    "                try {\n"
    "                    callback.onNotificationChanged(refreshed)\n",
    "            if (refreshed != null) {\n"
    "                try {\n"
    "                    applyRemainingTime(refreshed.notification)\n"
    "                    callback.onNotificationChanged(refreshed)\n",
    "provider refresh notification",
)

once(
    "        val isPlaying = player?.isPlaying == true\n"
    "        val statusText = if (isPlaying) \"再生中\" else \"一時停止中\"\n",
    "        val isPlaying = player?.isPlaying == true\n"
    "        val remainingText = buildRemainingTimeText()\n",
    "custom notification remaining text",
)

once(
    "            .setContentText(statusText)\n",
    "            .setContentText(remainingText)\n",
    "custom notification content text",
)

marker = "    private fun loadArtworkBitmap(uri: Uri?): Bitmap? {"
if marker not in s:
    raise SystemExit("missing patch target: helper insertion")
helpers = '''    private fun updateNotificationTicker(isPlaying: Boolean) {
        notificationTickerJob?.cancel()
        notificationTickerJob = null
        if (!isPlaying) return
        notificationTickerJob = serviceScope.launch {
            while (isActive && exoPlayer?.isPlaying == true) {
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
        } else {
            0L
        }

        val speed = player.playbackParameters.speed.takeIf { it > 0f } ?: 1f
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
        val totalSeconds = ((ms.coerceAtLeast(0L) + 999L) / 1000L)
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

'''
s = s.replace(marker, helpers + marker, 1)

once(
    "        private const val STATE_BROADCAST_INTERVAL_MS = 500L\n",
    "        private const val STATE_BROADCAST_INTERVAL_MS = 500L\n"
    "        private const val NOTIFICATION_REMAINING_UPDATE_INTERVAL_MS = 1_000L\n",
    "remaining update interval",
)

path.write_text(s)
