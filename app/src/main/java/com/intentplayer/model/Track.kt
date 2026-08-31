package com.intentplayer.model

import android.net.Uri

/**
 * 音楽ファイル1曲を表すデータクラス。
 * MediaSession / 通知へ渡すタイトル・アーティスト・アルバム・アートワークを保持する。
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
