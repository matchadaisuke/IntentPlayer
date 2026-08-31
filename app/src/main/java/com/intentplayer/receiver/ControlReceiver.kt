package com.intentplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.intentplayer.service.PlaybackService
import com.intentplayer.storage.PreferencesManager
import kotlin.math.abs

class ControlReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ControlReceiver"
        const val ACTION_CONTROL = "com.intentplayer.CONTROL"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_FOLDER_URI = "folderUri"
        const val EXTRA_SEEK_TO = "seekTo"
        const val EXTRA_SPEED = "speed"
        const val CMD_PLAY = "play"
        const val CMD_FORCE_PLAY = "force_play"
        const val CMD_PAUSE = "pause"
        const val CMD_STOP = "stop"
        const val CMD_NEXT = "next"
        const val CMD_PREVIOUS = "previous"
        const val CMD_SEEK = "seek"
        const val CMD_SPEED = "speed"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CONTROL) return
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: run {
            sendErrorBroadcast(context, "no_command", "command extra が指定されていません")
            return
        }
        when (command.lowercase()) {
            CMD_PLAY, CMD_FORCE_PLAY -> resolveFolderUri(context, intent)?.let {
                deliverCommandToService(context, command.lowercase(), it)
            }
            CMD_PAUSE, CMD_STOP, CMD_NEXT, CMD_PREVIOUS -> sendCommandOnly(context, command.lowercase())
            CMD_SEEK -> {
                val value = intent.getLongExtra(EXTRA_SEEK_TO, -1L)
                if (value < 0) {
                    sendErrorBroadcast(context, "invalid_seek", "seekTo extra が不正です")
                } else {
                    sendCommandOnly(context, CMD_SEEK) { putExtra(EXTRA_SEEK_TO, value) }
                }
            }
            CMD_SPEED -> {
                val value = intent.getFloatExtra(EXTRA_SPEED, Float.NaN)
                val normalized = PreferencesManager.normalizePlaybackSpeed(value)
                val validRange = value.isFinite() && value in PreferencesManager.MIN_PLAYBACK_SPEED..PreferencesManager.MAX_PLAYBACK_SPEED
                val validStep = value.isFinite() && abs(value - normalized) < 0.001f
                if (!validRange || !validStep) {
                    sendErrorBroadcast(
                        context,
                        "invalid_speed",
                        "speed は0.5〜5.0の範囲で0.25刻みで指定してください"
                    )
                } else {
                    sendCommandOnly(context, CMD_SPEED) { putExtra(EXTRA_SPEED, normalized) }
                }
            }
            else -> sendErrorBroadcast(context, "unknown_command", "不明なコマンド: $command")
        }
    }

    private fun resolveFolderUri(context: Context, intent: Intent): Uri? {
        intent.getStringExtra(EXTRA_FOLDER_URI)?.let { raw ->
            val parsed = runCatching { Uri.parse(raw) }.getOrNull()
            if (parsed != null && parsed.scheme != null) return parsed
            sendErrorBroadcast(context, "invalid_uri", "folderUri extra が不正です")
            return null
        }
        val value = context.getSharedPreferences("intent_player_prefs", Context.MODE_PRIVATE)
            .getString("default_folder_uri", null)
        if (value != null) {
            val parsed = runCatching { Uri.parse(value) }.getOrNull()
            if (parsed != null && parsed.scheme != null) return parsed
        }
        sendErrorBroadcast(
            context,
            "no_folder_uri",
            "既定のフォルダが未設定です。IntentPlayerの設定から既定のフォルダを選択してください。"
        )
        return null
    }

    private fun deliverCommandToService(context: Context, command: String, folderUri: Uri) {
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_FOLDER_URI, folderUri.toString())
        }
        try {
            context.startService(serviceIntent)
            return
        } catch (e: Exception) {
            Log.w(TAG, "startService failed: ${e.message}")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                sendErrorBroadcast(
                    context,
                    "fgs_start_blocked",
                    "バックグラウンド起動制限のため再生を開始できません。IntentPlayerを一度開いてから再度お試しください。"
                )
            }
        } else {
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                sendErrorBroadcast(context, "service_start_failed", "サービス起動に失敗しました: ${e.message}")
            }
        }
    }

    private fun sendCommandOnly(context: Context, command: String, extras: (Intent.() -> Unit)? = null) {
        try {
            context.startService(Intent(context, PlaybackService::class.java).apply {
                putExtra(EXTRA_COMMAND, command)
                extras?.invoke(this)
            })
        } catch (_: Exception) {
            Log.d(TAG, "service inactive; skipped: $command")
        }
    }

    private fun sendErrorBroadcast(context: Context, reason: String, message: String) {
        context.sendBroadcast(Intent(PlaybackService.ACTION_ERROR).apply {
            putExtra(PlaybackService.EXTRA_ERROR_REASON, reason)
            putExtra(PlaybackService.EXTRA_ERROR_MESSAGE, message)
        })
    }
}
