package com.intentplayer.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.intentplayer.model.Track

/**
 * SAF (Storage Access Framework) 経由で選択されたフォルダから
 * 音楽ファイルをスキャンし、Track リストを生成する。
 *
 * メタデータは MediaStore から取得する（MediaMetadataRetriever は使用しない）。
 *
 * 対応拡張子: mp3, m4a, flac, aac, ogg, opus, wav, wma
 */
object FolderScanner {

    private const val TAG = "FolderScanner"

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3",   // MPEG Layer 3
        "m4a",   // MPEG-4 Audio (AAC)
        "flac",  // Free Lossless Audio Codec
        "aac",   // Advanced Audio Coding
        "ogg",   // Ogg Vorbis
        "opus",  // Opus
        "wav",   // Waveform Audio
        "wma"    // Windows Media Audio
    )

    fun scanFolder(context: Context, folderUri: Uri): List<Track> {
        Log.d(TAG, "scanFolder: $folderUri (scheme=${folderUri.scheme})")

        val folder = if (folderUri.scheme == "file") {
            val file = java.io.File(folderUri.path ?: return emptyList())
            DocumentFile.fromFile(file)
        } else {
            DocumentFile.fromTreeUri(context, folderUri)
        }

        if (folder == null) {
            Log.w(TAG, "DocumentFile returned null for: $folderUri")
            return emptyList()
        }

        if (!folder.isDirectory) {
            Log.w(TAG, "URI is not a directory: $folderUri")
            return emptyList()
        }

        if (!folder.canRead()) {
            Log.w(TAG, "Cannot read folder: $folderUri")
            val persistedUris = context.contentResolver.persistedUriPermissions
            Log.d(TAG, "persistedUriPermissions count=${persistedUris.size}")
            persistedUris.forEach { perm ->
                Log.d(TAG, "  persisted: ${perm.uri} read=${perm.isReadPermission}")
            }
            throw SecurityException("フォルダへの読み取り権限がありません: $folderUri")
        }

        val allFiles = folder.listFiles()
        Log.d(TAG, "listFiles() returned ${allFiles.size} items")

        val tracks = allFiles
            .filter { file ->
                val supported = file.isFile && isSupportedAudio(file.name)
                if (!supported && file.isFile) {
                    Log.d(TAG, "  skip: ${file.name}")
                }
                supported
            }
            .mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val uri = file.uri
                val trackName = removeExtension(fileName)

                // MediaStore からメタデータを取得
                val meta = queryMediaStoreMeta(context, uri)

                Log.d(TAG, "  track: $fileName artist=${meta?.artist} album=${meta?.album}")

                Track(
                    uri = uri,
                    name = trackName,
                    fileName = fileName,
                    artist = meta?.artist,
                    album = meta?.album,
                    durationMs = meta?.durationMs ?: 0L
                )
            }
            .sortedBy { it.name.lowercase() }

        Log.d(TAG, "Found ${tracks.size} tracks in folder")
        return tracks
    }

    // ==========================================
    // メタデータ取得（MediaStore のみ）
    // ==========================================

    private data class AudioMeta(
        val artist: String?,
        val album: String?,
        val durationMs: Long
    )

    /**
     * MediaStore でメタデータを取得する。
     * MediaStore に登録されていないファイルでは null を返す。
     * null の場合、トラック名はファイル名から生成済みなので再生に問題はない。
     */
    private fun queryMediaStoreMeta(context: Context, uri: Uri): AudioMeta? {
        return try {
            val mediaUri = resolveMediaUri(context, uri) ?: return null

            val projection = arrayOf(
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION
            )

            context.contentResolver.query(
                mediaUri, projection, null, null, null
            )?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) {
                    val artist = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    ).takeIf { it.isNotBlank() && it != "<unknown>" }
                    val album = cursor.getString(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    ).takeIf { it.isNotBlank() && it != "<unknown>" }
                    val duration = cursor.getLong(
                        cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    )
                    AudioMeta(artist = artist, album = album, durationMs = duration)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryMediaStoreMeta failed for $uri: ${e.message}")
            null
        }
    }

    /**
     * SAF document URI を MediaStore の audio URI に変換する。
     */
    private fun resolveMediaUri(context: Context, safUri: Uri): Uri? {
        return try {
            val docFile = DocumentFile.fromSingleUri(context, safUri) ?: return null
            val displayName = docFile.name ?: return null

            val projection = arrayOf(MediaStore.Audio.Media._ID)
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(displayName)

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveMediaUri failed for $safUri: ${e.message}")
            null
        }
    }

    // ==========================================
    // ユーティリティ
    // ==========================================

    private fun isSupportedAudio(fileName: String?): Boolean {
        if (fileName == null) return false
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    private fun removeExtension(fileName: String): String {
        return fileName.substringBeforeLast('.')
    }
}
