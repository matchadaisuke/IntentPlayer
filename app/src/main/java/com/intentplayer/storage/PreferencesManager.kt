package com.intentplayer.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit

/**
 * PreferencesManager
 * 再生位置・トラックインデックスの保存などのSharedPreferences管理。 - savePlaybackPosition() / loadPlaybackPosition()
 *   - saveTrackIndex() / loadTrackIndex()
 *
 * 保存する内容：
 * - 最後に選択したフォルダの URI
 * - 最後の再生トラックインデックス（プレイリスト内の何番目か）
 * - 最後の再生位置（ミリ秒）
 */
object PreferencesManager {

    private const val TAG = "PreferencesManager"
    private const val PREFS_NAME = "intent_player_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_TRACK_INDEX = "track_index"
    private const val KEY_PLAYBACK_POSITION_MS = "playback_position_ms"

    // Settings Keys
    private const val KEY_FIRST_LAUNCH = "is_first_launch"
    private const val KEY_PREF_AUDIO_FOCUS_NOTIFICATION = "pref_audio_focus_notification"
    private const val KEY_PREF_AUTO_BLUETOOTH_CONTROL = "pref_auto_bluetooth_control"
    private const val KEY_PREF_SILENT_NOTIFICATION = "pref_silent_notification"
    private const val KEY_ERROR_LOGS = "error_logs"
    private const val KEY_PREF_BLOCK_AUDIO_FOCUS_SEND = "pref_block_audio_focus_send"
    private const val KEY_PREF_BLOCK_AUDIO_FOCUS_RECEIVE = "pref_block_audio_focus_receive"
    private const val KEY_PREF_BLUETOOTH_RECONNECT_DELAY_MS = "pref_bluetooth_reconnect_delay_ms"
    private const val KEY_PREF_BLOCK_SPEAKER_MUTE_PLAYBACK = "pref_block_speaker_mute_playback"
    private const val KEY_PREF_AUTO_RESUME_TIMEOUT_ENABLED = "pref_auto_resume_timeout_enabled"
    private const val KEY_PREF_AUTO_RESUME_TIMEOUT_MS = "pref_auto_resume_timeout_ms"
    private const val KEY_PREF_USE_CUSTOM_MEDIA_PLAYBACK = "pref_use_custom_media_playback"
    private const val KEY_PREF_ENABLE_APP_VOLUME = "pref_enable_app_volume"
    private const val KEY_PREF_APP_PLAYBACK_VOLUME = "pref_app_playback_volume"




    // ==========================================
    // フォルダ URI
    // ==========================================

    fun saveFolderUri(context: Context, folderUri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_FOLDER_URI, folderUri.toString()) }
        Log.d(TAG, "Saved folderUri: $folderUri")
    }

    fun loadFolderUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved URI: $uriString", e)
            null
        }
    }

    fun clearFolderUri(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { remove(KEY_FOLDER_URI) }
        Log.d(TAG, "Cleared folderUri")
    }

    // ==========================================
    // 再生トラックインデックス
    // ==========================================

    /**
     * 現在のトラックインデックス（プレイリスト内の何番目か）を保存する。
     * play コマンドで途中から再生するために使用。
     */
    fun saveTrackIndex(context: Context, index: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putInt(KEY_TRACK_INDEX, index) }
        Log.d(TAG, "Saved trackIndex: $index")
    }

    /**
     * 保存済みのトラックインデックスを読み込む。
     * デフォルト値は 0（先頭トラック）。
     */
    fun loadTrackIndex(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_TRACK_INDEX, 0)
    }

    // ==========================================
    // 再生位置（ミリ秒）
    // ==========================================

    /**
     * 現在の再生位置（ミリ秒）を保存する。
     * play コマンドで途中から再生するために使用。
     */
    fun savePlaybackPosition(context: Context, positionMs: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit { putLong(KEY_PLAYBACK_POSITION_MS, positionMs) }
        Log.d(TAG, "Saved playbackPosition: ${positionMs}ms")
    }

    /**
     * 保存済みの再生位置（ミリ秒）を読み込む。
     * デフォルト値は 0（先頭）。
     */
    fun loadPlaybackPosition(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_PLAYBACK_POSITION_MS, 0L)
    }

    /**
     * 再生位置とトラックインデックスをまとめてリセットする。
     * stop コマンドや新しいフォルダ選択時に使用。
     */
    fun clearPlaybackState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_TRACK_INDEX)
            remove(KEY_PLAYBACK_POSITION_MS)
        }
        Log.d(TAG, "Cleared playback state (trackIndex + position)")
    }

    // ==========================================
    // アプリ設定・初回起動
    // ==========================================

    fun isFirstLaunch(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIRST_LAUNCH, true)
    }

    fun setFirstLaunchCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_FIRST_LAUNCH, false) }
    }

    fun isSilentNotificationEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_SILENT_NOTIFICATION, true)
    }

    fun setSilentNotificationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_SILENT_NOTIFICATION, enabled) }
    }

    fun isAutoBluetoothControlEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_AUTO_BLUETOOTH_CONTROL, true)
    }

    fun setAutoBluetoothControlEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_AUTO_BLUETOOTH_CONTROL, enabled) }
    }

    // ==========================================
    // エラーログ
    // ==========================================

    fun saveErrorLog(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentLogs = getErrorLogs(context).toMutableList()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        currentLogs.add(0, "[$timestamp] $message")
        if (currentLogs.size > 20) {
            currentLogs.removeAt(currentLogs.lastIndex)
        }
        prefs.edit { putString(KEY_ERROR_LOGS, currentLogs.joinToString("\n")) }
        Log.w(TAG, "Error logged: $message")
    }

    fun getErrorLogs(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val logsStr = prefs.getString(KEY_ERROR_LOGS, "") ?: ""
        return if (logsStr.isEmpty()) emptyList() else logsStr.split("\n")
    }

    fun clearErrorLogs(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_ERROR_LOGS) }
        Log.d(TAG, "Cleared error logs")
    }

    /** true = オーディオフォーカスを送信しない（デフォルト） */
    fun isBlockAudioFocusSend(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_SEND, true)
    }

    fun setBlockAudioFocusSend(context: Context, block: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_SEND, block) }
    }

    /** true = オーディオフォーカスを受信しない（デフォルト） */
    fun isBlockAudioFocusReceive(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_RECEIVE, true)
    }

    fun setBlockAudioFocusReceive(context: Context, block: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_RECEIVE, block) }
    }

    fun getBluetoothReconnectDelayMs(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREF_BLUETOOTH_RECONNECT_DELAY_MS, 500)
    }

    fun setBluetoothReconnectDelayMs(context: Context, delayMs: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_PREF_BLUETOOTH_RECONNECT_DELAY_MS, delayMs) }
    }

    fun isBlockSpeakerMutePlaybackEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_BLOCK_SPEAKER_MUTE_PLAYBACK, true)
    }

    fun setBlockSpeakerMutePlaybackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_BLOCK_SPEAKER_MUTE_PLAYBACK, enabled) }
    }

    fun isAutoResumeTimeoutEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_AUTO_RESUME_TIMEOUT_ENABLED, false)
    }

    fun setAutoResumeTimeoutEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_AUTO_RESUME_TIMEOUT_ENABLED, enabled) }
    }

    fun getAutoResumeTimeoutMs(context: Context): Long {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PREF_AUTO_RESUME_TIMEOUT_MS, 30000L)
    }

    fun setAutoResumeTimeoutMs(context: Context, timeoutMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(KEY_PREF_AUTO_RESUME_TIMEOUT_MS, timeoutMs) }
    }

    fun isUseCustomMediaPlayback(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_USE_CUSTOM_MEDIA_PLAYBACK, true)
    }

    fun setUseCustomMediaPlayback(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_USE_CUSTOM_MEDIA_PLAYBACK, enabled) }
    }

    fun isEnableAppVolume(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_ENABLE_APP_VOLUME, true)
    }

    fun setEnableAppVolume(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_ENABLE_APP_VOLUME, enabled) }
    }

    fun getAppPlaybackVolume(context: Context): Float {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PREF_APP_PLAYBACK_VOLUME, 1.0f)
    }

    fun setAppPlaybackVolume(context: Context, volume: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putFloat(KEY_PREF_APP_PLAYBACK_VOLUME, volume) }
    }
}



