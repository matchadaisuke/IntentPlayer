package com.intentplayer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.intentplayer.service.PlaybackService
import com.intentplayer.storage.PreferencesManager

/**
 * BootReceiver
 *
 * 端末起動時に呼び出され、前回再生していたフォルダURIが保存されていれば
 * バックグラウンドサービスを自動起動して再生を再開する。
 *
 * LOCKED_BOOT_COMPLETED (Direct Boot):
 *   ストレージ復号前に発火するため、SharedPreferences (暗号化ストレージ) はまだ読めない。
 *   ここでは通知チャンネルの事前作成のみ実施する。
 *
 * BOOT_COMPLETED:
 *   ユーザーが初回ロック解除後に発火するため、SharedPreferences が読める。
 *   folderUri を復元し、サービスを起動して再生を再開する。
 *
 * MY_PACKAGE_REPLACED:
 *   アプリ更新時の再起動。更新前に再生していた場合は再開する。
 *
 * 備考 (Android 12+ バックグラウンド起動制限):
 *   BOOT_COMPLETED は「FGS バックグラウンド起動許可」の例外ケースであり、
 *   startForegroundService() は呼んで良いが、5秒制約は厳格に適用されるため
 *   PlaybackService.onStartCommand() の先頭でプレースホルダー通知を即時表示している。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // BOOT_COMPLETED: SharedPreferences が読める段階
                // → folderUri を確認してサービスを起動
                Log.d(TAG, "BOOT_COMPLETED: starting playback if needed")
                startPlaybackServiceIfNeeded(context)
            }

            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // LOCKED_BOOT_COMPLETED: SharedPreferences はまだ読めない
                // → サービス起動は行わない（BOOT_COMPLETED で行う）
                // → ここでは通知チャンネルの事前作成のみ実施する
                //   (Android 8.0+ で通知チャンネルは早期に作成しておくと
                //    BOOT_COMPLETED後のFGS通知が確実に表示される)
                Log.d(TAG, "LOCKED_BOOT_COMPLETED: pre-creating notification channel only")
                preCreateNotificationChannel(context)
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // アプリ更新後: サービスを待機状態で再起動するのみ。
                // 再生の自動再開はしない（更新後に勝手に再生が始まらないようにするため）。
                Log.d(TAG, "MY_PACKAGE_REPLACED: restarting service in standby only")
                startServiceStandbyIfNeeded(context)
            }

            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    /**
     * 保存済み folderUri がある場合のみ PlaybackService を起動して再生を再開する。
     *
     * folderUri がない場合は起動しない:
     *   再生するものがない状態で startForegroundService を呼ぶと
     *   Media3 が startForeground() を呼ばず 5秒タイムアウトが発生する。
     *   PlaybackService.onStartCommand() の startForegroundImmediately() で
     *   プレースホルダー通知を即時表示するため、実際にはタイムアウトしないが、
     *   不必要なサービス起動は避ける。
     */
    private fun startPlaybackServiceIfNeeded(context: Context) {
        val savedFolderUri = PreferencesManager.loadFolderUri(context)

        if (savedFolderUri == null) {
            Log.d(TAG, "No saved folderUri. Skipping service start.")
            return
        }

        Log.d(TAG, "Saved folderUri=$savedFolderUri — starting PlaybackService with play command")

        try {
            // CMD_PLAY を Intent に含めることで
            // onStartCommand → playFromUri() が即座に呼ばれる
            val serviceIntent = Intent(context, PlaybackService::class.java).apply {
                putExtra(ControlReceiver.EXTRA_COMMAND, ControlReceiver.CMD_PLAY)
                putExtra(ControlReceiver.EXTRA_FOLDER_URI, savedFolderUri.toString())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "startForegroundService called")
            } else {
                context.startService(serviceIntent)
                Log.d(TAG, "startService called (API < 26)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PlaybackService: ${e.message}", e)
        }
    }

    /**
     * アプリ更新後にサービスを待機状態で再起動する。
     * CMD_PLAY を送らないため、自動再生はしない。
     * インテント制御の受信待機状態のためにサービスだけ起動しておく。
     */
    private fun startServiceStandbyIfNeeded(context: Context) {
        val savedFolderUri = PreferencesManager.loadFolderUri(context)

        if (savedFolderUri == null) {
            Log.d(TAG, "No saved folderUri. Skipping service start.")
            return
        }

        Log.d(TAG, "Saved folderUri=$savedFolderUri — starting PlaybackService in standby (no auto-play)")

        try {
            // コマンドなし = 待機状態でサービスを起動するのみ
            val serviceIntent = Intent(context, PlaybackService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                Log.d(TAG, "startForegroundService (standby) called")
            } else {
                context.startService(serviceIntent)
                Log.d(TAG, "startService (standby, API < 26) called")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start PlaybackService in standby: ${e.message}", e)
        }
    }

    /**
     * LOCKED_BOOT_COMPLETED 時の通知チャンネル事前作成。
     *
     * BOOT_COMPLETED 後に FGS 通知を即時表示するためには、
     * 通知チャンネルが事前に作成されている必要がある。
     * LOCKED_BOOT_COMPLETED で作成しておくことで確実に表示できる。
     */
    private fun preCreateNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            if (nm.getNotificationChannel(PlaybackService.NOTIFICATION_CHANNEL_ID) == null) {
                val channel = android.app.NotificationChannel(
                    PlaybackService.NOTIFICATION_CHANNEL_ID,
                    "音楽再生",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "音楽の再生状態を表示します"
                    setShowBadge(false)
                    // VISIBILITY_PUBLIC = 1 (android.app.Notification.VISIBILITY_PUBLIC)
                    // ロック画面に再生情報を全て表示するために必須
                    lockscreenVisibility = 1
                }
                nm.createNotificationChannel(channel)
                Log.d(TAG, "NotificationChannel pre-created in LOCKED_BOOT_COMPLETED")
            }
        }
    }
}
