package com.intentplayer.model

import android.net.Uri

/**
 * 再生対象の音声ファイルを表すデータクラス。
 * MediaSession / 通知へ渡すタイトル・アルバム・アートワークなどを保持する。
 */
data class Track(
    val uri: Uri,
    val name: String,
    val fileName: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUri: Uri? = null
)
