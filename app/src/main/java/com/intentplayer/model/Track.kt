package com.intentplayer.model

import android.net.Uri

/**
 * 音楽ファイル1曲を表すデータクラス。
 * artist / album / durationMs を保持。
 * MediaSession/MediaMetadata に渡すために必要。
 *   - FolderScanner で MediaStore から取得する。
 *
 * @param uri        SAF経由で取得したファイルのURI（再生・表示に使用）
 * @param name       曲名（ファイル名から拡張子を除いたもの）
 * @param fileName   ファイル名（拡張子含む）例: "song.mp3"
 * @param artist     アーティスト名（MediaStore から取得。不明なら null）
 * @param album      アルバム名（MediaStore から取得。不明なら null）
 * @param durationMs 曲長（ミリ秒。MediaStore から取得。不明なら 0）
 */
data class Track(
    val uri: Uri,
    val name: String,
    val fileName: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L
)
