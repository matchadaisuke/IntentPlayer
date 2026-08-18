package com.intentplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.intentplayer.service.PlaybackService
import com.intentplayer.storage.PreferencesManager

/**
 * 外部アプリ（MacroDroid 等）からの Broadcast Intent を受信し、
 * PlaybackService にコマンドを転送する BroadcastReceiver。
 *
 * 常時 FGS として稼働する PlaybackService に対して startService() でコマンドを送る。
 * サービスが落ちている場合は startForegroundService() にフォールバックする。
 */
class ControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ControlReceiver"

        const val ACTION_CONTROL   = "com.intentplayer.CONTROL"
        const val EXTRA_COMMAND    = "command"
        const val EXTRA_FOLDER_URI = "folderUri"
        const val EXTRA_SEEK_TO    = "seekTo"
        const val EXTRA_SPEED      = "speed"

        const val CMD_PLAY       = "play"
        const val CMD_FORCE_PLAY = "force_play"
        const val CMD_PAUSE      = "pause"
        const val CMD_STOP       = "stop"
        const val CMD_NEXT       = "next"
        const val CMD_PREVIOUS   = "previous"
        const val CMD_SEEK       = "seek"
        const val CMD_SPEED      = "speed"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        if (intent.action != ACTION_CONTROL) {
            Log.w(TAG, "Unknown action: ${intent.action}")
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND)
        if (command == null) {
            Log.w(TAG, "No 'command' extra")
            sendErrorBroadcast(context, "no_command", "command extra が指定されていません")
            return
        }

        Log.d(TAG, "command: $command")

        when (command.lowercase()) {

            CMD_PLAY -> {
                val folderUri = resolveFolderUri(context, intent) ?: return
                Log.d(TAG, "CMD_PLAY: folderUri=$folderUri")
                // startService() で常時FGSに送信
                deliverCommandToService(context, CMD_PLAY, folderUri)
            }

            CMD_FORCE_PLAY -> {
                val folderUri = resolveFolderUri(context, intent) ?: return
                Log.d(TAG, "CMD_FORCE_PLAY: folderUri=$folderUri")
                deliverCommandToService(context, CMD_FORCE_PLAY, folderUri)
            }

            CMD_PAUSE    -> sendCommandOnly(context, CMD_PAUSE)
            CMD_STOP     -> sendCommandOnly(context, CMD_STOP)
            CMD_NEXT     -> sendCommandOnly(context, CMD_NEXT)
            CMD_PREVIOUS -> sendCommandOnly(context, CMD_PREVIOUS)

            CMD_SEEK -> {
                val seekToMs = intent.getLongExtra(EXTRA_SEEK_TO, -1L)
                if (seekToMs < 0) {
                    sendErrorBroadcast(context, "invalid_seek", "seekTo extra が不正です")
                    return
                }
                sendCommandOnly(context, CMD_SEEK) { putExtra(EXTRA_SEEK_TO, seekToMs) }
            }

            CMD_SPEED -> {
                val speed = intent.getFloatExtra(EXTRA_SPEED, -1f)
                if (speed !in 0.5f..2.0f) {
                    sendErrorBroadcast(context, "invalid_speed", "speed は 0.5〜2.0 で指定してください")
                    return
                }
                sendCommandOnly(context, CMD_SPEED) { putExtra(EXTRA_SPEED, speed) }
            }

            else -> {
                Log.w(TAG, "Unknown command: $command")
                sendErrorBroadcast(context, "unknown_command", "不明なコマンド: $command")
            }
        }
    }

    /**
     * play / force_play コマンドをサービスに届ける。
     *
     * 優先順位:
     *   1. startService() — FGS 稼働中ならバックグラウンドからでも到達可能
     *   2. startForegroundService() — サービスが落ちている場合のフォールバック
     *   3. どちらも失敗 — エラーブロードキャストでユーザーに手動起動を促す
     */
    private fun deliverCommandToService(context: Context, command: String, folderUri: Uri) {
        val serviceIntent = Intent(context, PlaybackService::class.java).apply {
            putExtra(EXTRA_COMMAND, command)
            putExtra(EXTRA_FOLDER_URI, folderUri.toString())
        }

        // Step 1: startService()（既存 FGS へのコマンド送信 - バックグラウンド制限対象外）
        try {
            context.startService(serviceIntent)
            Log.d(TAG, "deliverCommandToService: startService() 成功")
            return
        } catch (e: Exception) {
            Log.w(TAG, "startService() 失敗: ${e.message} → startForegroundService() を試みます")
        }

        // Step 2: startForegroundService()（サービス未起動時・アプリがフォアグラウンドなら成功）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "deliverCommandToService: startForegroundService() 成功")
                return
            } catch (e: Exception) {
                // Android 12+: バックグラウンドからの FGS 起動制限
                // → アプリが完全にバックグラウンドかつサービスも落ちている状態
                Log.e(TAG, "startForegroundService() も失敗 (Android 12+ BG制限?): ${e.message}", e)
                sendErrorBroadcast(
                    context,
                    "fgs_start_blocked",
                    "バックグラウンド起動制限のため再生を開始できません。" +
                    "IntentPlayer を一度開いて再生を開始してください。"
                )
            }
        } else {
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "startService() 失敗 (API < O): ${e.message}", e)
                sendErrorBroadcast(context, "service_start_failed", "サービス起動に失敗しました: ${e.message}")
            }
        }
    }

    /**
     * pause / stop / next / previous / seek / speed コマンドを送る。
     * サービスが稼働していない場合は無視する（再生中でないため操作不要）。
     */
    private fun sendCommandOnly(
        context: Context,
        command: String,
        extras: (Intent.() -> Unit)? = null
    ) {
        try {
            val intent = Intent(context, PlaybackService::class.java).apply {
                putExtra(EXTRA_COMMAND, command)
                extras?.invoke(this)
            }
            context.startService(intent)
            Log.d(TAG, "sendCommandOnly: $command 送信完了")
        } catch (e: Exception) {
            // サービス未起動時: pause/stop/next 等は再生中でないと意味がないため無視
            Log.d(TAG, "sendCommandOnly スキップ (サービス未起動): cmd=$command, ${e.message}")
        }
    }

    /**
     * Intent から folderUri を解決する。
     *   1. インテントの EXTRA_FOLDER_URI
     *   2. SharedPreferences の保存済み URI
     */
    private fun resolveFolderUri(context: Context, intent: Intent): Uri? {
        val folderUriStr = intent.getStringExtra(EXTRA_FOLDER_URI)
        if (folderUriStr != null) {
            return try {
                Uri.parse(folderUriStr)
            } catch (e: Exception) {
                Log.e(TAG, "invalid folderUri: $folderUriStr", e)
                sendErrorBroadcast(context, "invalid_uri", "folderUri の形式が不正です: $folderUriStr")
                null
            }
        }

        val saved = PreferencesManager.loadFolderUri(context)
        if (saved == null) {
            Log.w(TAG, "folderUri 未指定かつ保存済み URI なし")
            sendErrorBroadcast(
                context,
                "no_folder_uri",
                "フォルダが未設定です。IntentPlayer を開いてフォルダを選択してください。"
            )
        } else {
            Log.d(TAG, "保存済み URI を使用: $saved")
        }
        return saved
    }

    private fun sendErrorBroadcast(context: Context, reason: String, message: String) {
        Log.d(TAG, "sendErrorBroadcast: reason=$reason")
        context.sendBroadcast(Intent(PlaybackService.ACTION_ERROR).apply {
            putExtra(PlaybackService.EXTRA_ERROR_REASON, reason)
            putExtra(PlaybackService.EXTRA_ERROR_MESSAGE, message)
        })
    }
}
