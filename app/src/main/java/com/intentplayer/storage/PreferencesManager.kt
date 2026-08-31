package com.intentplayer.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit

object PreferencesManager {

    private const val TAG = "PreferencesManager"
    private const val PREFS_NAME = "intent_player_prefs"
    private const val KEY_FOLDER_URI = "folder_uri"
    private const val KEY_TRACK_INDEX = "track_index"
    private const val KEY_PLAYBACK_POSITION_MS = "playback_position_ms"

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

    const val MIN_AUTO_RESUME_TIMEOUT_MS = 10L * 60L * 1000L
    const val MAX_AUTO_RESUME_TIMEOUT_MS = 24L * 60L * 60L * 1000L
    const val MAX_APP_PLAYBACK_VOLUME = 5.0f

    fun saveFolderUri(context: Context, folderUri: Uri) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_FOLDER_URI, folderUri.toString()) }
        Log.d(TAG, "Saved folderUri: $folderUri")
    }

    fun loadFolderUri(context: Context): Uri? {
        val uriString = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_FOLDER_URI, null) ?: return null
        return try {
            Uri.parse(uriString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse saved URI: $uriString", e)
            null
        }
    }

    fun clearFolderUri(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_FOLDER_URI) }
    }

    fun saveTrackIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_TRACK_INDEX, index) }
    }

    fun loadTrackIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TRACK_INDEX, 0)

    fun savePlaybackPosition(context: Context, positionMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putLong(KEY_PLAYBACK_POSITION_MS, positionMs) }
    }

    fun loadPlaybackPosition(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PLAYBACK_POSITION_MS, 0L)

    fun clearPlaybackState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            remove(KEY_TRACK_INDEX)
            remove(KEY_PLAYBACK_POSITION_MS)
        }
    }

    fun isFirstLaunch(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FIRST_LAUNCH, true)

    fun setFirstLaunchCompleted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_FIRST_LAUNCH, false) }
    }

    fun isSilentNotificationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_SILENT_NOTIFICATION, true)

    fun setSilentNotificationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_SILENT_NOTIFICATION, enabled) }
    }

    fun isAutoBluetoothControlEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_AUTO_BLUETOOTH_CONTROL, true)

    fun setAutoBluetoothControlEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_AUTO_BLUETOOTH_CONTROL, enabled) }
    }

    fun saveErrorLog(context: Context, message: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentLogs = getErrorLogs(context).toMutableList()
        val timestamp = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        currentLogs.add(0, "[$timestamp] $message")
        while (currentLogs.size > 20) currentLogs.removeAt(currentLogs.lastIndex)
        prefs.edit { putString(KEY_ERROR_LOGS, currentLogs.joinToString("\n")) }
    }

    fun getErrorLogs(context: Context): List<String> {
        val logs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ERROR_LOGS, "") ?: ""
        return if (logs.isEmpty()) emptyList() else logs.split("\n")
    }

    fun clearErrorLogs(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { remove(KEY_ERROR_LOGS) }
    }

    fun isBlockAudioFocusSend(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_SEND, true)

    fun setBlockAudioFocusSend(context: Context, block: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_SEND, block) }
    }

    fun isBlockAudioFocusReceive(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_RECEIVE, true)

    fun setBlockAudioFocusReceive(context: Context, block: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_BLOCK_AUDIO_FOCUS_RECEIVE, block) }
    }

    fun getBluetoothReconnectDelayMs(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PREF_BLUETOOTH_RECONNECT_DELAY_MS, 500)

    fun setBluetoothReconnectDelayMs(context: Context, delayMs: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putInt(KEY_PREF_BLUETOOTH_RECONNECT_DELAY_MS, delayMs) }
    }

    fun isBlockSpeakerMutePlaybackEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_BLOCK_SPEAKER_MUTE_PLAYBACK, true)

    fun setBlockSpeakerMutePlaybackEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_BLOCK_SPEAKER_MUTE_PLAYBACK, enabled) }
    }

    fun isAutoResumeTimeoutEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_AUTO_RESUME_TIMEOUT_ENABLED, false)

    fun setAutoResumeTimeoutEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_AUTO_RESUME_TIMEOUT_ENABLED, enabled) }
    }

    fun getAutoResumeTimeoutMs(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_PREF_AUTO_RESUME_TIMEOUT_MS, 10L * 60L * 1000L)
            .coerceIn(MIN_AUTO_RESUME_TIMEOUT_MS, MAX_AUTO_RESUME_TIMEOUT_MS)

    fun setAutoResumeTimeoutMs(context: Context, timeoutMs: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit {
                putLong(
                    KEY_PREF_AUTO_RESUME_TIMEOUT_MS,
                    timeoutMs.coerceIn(MIN_AUTO_RESUME_TIMEOUT_MS, MAX_AUTO_RESUME_TIMEOUT_MS)
                )
            }
    }

    fun isUseCustomMediaPlayback(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_USE_CUSTOM_MEDIA_PLAYBACK, true)

    fun setUseCustomMediaPlayback(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_USE_CUSTOM_MEDIA_PLAYBACK, enabled) }
    }

    fun isEnableAppVolume(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREF_ENABLE_APP_VOLUME, true)

    fun setEnableAppVolume(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_PREF_ENABLE_APP_VOLUME, enabled) }
    }

    fun getAppPlaybackVolume(context: Context): Float {
        val value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(KEY_PREF_APP_PLAYBACK_VOLUME, 1.0f)
        return if (value.isFinite()) {
            value.coerceIn(0.0f, MAX_APP_PLAYBACK_VOLUME)
        } else 1.0f
    }

    fun setAppPlaybackVolume(context: Context, volume: Float) {
        val safeVolume = if (volume.isFinite()) {
            volume.coerceIn(0.0f, MAX_APP_PLAYBACK_VOLUME)
        } else 1.0f
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putFloat(KEY_PREF_APP_PLAYBACK_VOLUME, safeVolume) }
    }
}
