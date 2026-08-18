# IntentPlayer ProGuard Rules
# Phase 12: Media3 / MediaSession / BroadcastReceiver 保護

# デバッグ情報を保持（クラッシュ解析に必要）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==========================================
# Media3 / ExoPlayer
# ==========================================
# ExoPlayer は内部でリフレクションを使用するため保護が必要
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ==========================================
# MediaSession / MediaBrowser (androidx.media)
# ==========================================
-keep class androidx.media.** { *; }
-dontwarn androidx.media.**

# ==========================================
# IntentPlayer コンポーネント
# ==========================================
# Service / BroadcastReceiver は AndroidManifest から参照されるため保護
-keep class com.intentplayer.service.PlaybackService { *; }
-keep class com.intentplayer.receiver.ControlReceiver { *; }
-keep class com.intentplayer.receiver.BootReceiver { *; }
-keep class com.intentplayer.model.Track { *; }

# ==========================================
# Kotlin Coroutines
# ==========================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ==========================================
# Compose
# ==========================================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
