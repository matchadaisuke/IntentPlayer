package com.intentplayer.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * バッテリー最適化の状態確認と除外設定誘導を行うヘルパークラス。
 *
 * なぜバッテリー最適化が問題になるか:
 *   Android は電池消費を抑えるため、バックグラウンドアプリを強制的に停止することがある。
 *   「バッテリー最適化」が有効だと、音楽再生中に Service が止まってしまうことがある。
 *   これを防ぐには、ユーザーに「最適化しない」を設定してもらう必要がある。
 *
 * 注意:
 *   REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 権限が AndroidManifest.xml に必要。
 *   ただし Google Play Store では、この権限は通常 Music / Audio プレイヤーカテゴリで許可される。
 *   IntentPlayer は音楽プレイヤーなので問題なし。
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimizationHelper"

    /**
     * バッテリー最適化が除外（無効化）されているかチェックする。
     *
     * @return true = 除外済み（最適化されていない = Service が止まりにくい）
     *         false = 最適化中（Service が止まる可能性あり）
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        // Android 6.0 未満は考慮不要（minSdk = 26 なので常に対応）
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val result = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        Log.d(TAG, "isIgnoringBatteryOptimizations: $result")
        return result
    }

    /**
     * バッテリー最適化除外ダイアログを開く Intent を返す。
     *
     * 使い方:
     *   val intent = BatteryOptimizationHelper.createBatteryOptimizationIntent(context)
     *   if (intent != null) startActivity(intent)
     *
     * @return ダイアログ用 Intent（Android 6.0 未満は null）
     *
     * 動作:
     *   ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS を使うと
     *   「IntentPlayer のバッテリー最適化を無効にしますか？」
     *   というダイアログが表示される。
     *   ユーザーが「最適化しない」を選ぶと isIgnoringBatteryOptimizations が true になる。
     */
    fun createBatteryOptimizationIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * バッテリー設定画面を開く Intent を返す（フォールバック用）。
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS が使えない端末向け。
     */
    fun createBatterySettingsIntent(): Intent {
        return Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
