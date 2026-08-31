package com.intentplayer.storage

import android.content.ContentUris
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
 * メタデータは MediaStore から取得する。
 */
object FolderScanner {

    private const val TAG = "FolderScanner"

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "flac", "aac", "ogg", "opus", "wav", "wma"
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
            throw SecurityException("フォルダへの読み取り権限がありません: $folderUri")
        }

        return folder.listFiles()
            .filter { file -> file.isFile && isSupportedAudio(file.name) }
            .mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val uri = file.uri
                val meta = queryMediaStoreMeta(context, uri)
                Track(
                    uri = uri,
                    name = removeExtension(fileName),
                    fileName = fileName,
                    artist = meta?.artist,
                    album = meta?.album,
                    durationMs = meta?.durationMs ?: 0L,
                    artworkUri = meta?.artworkUri
                )
            }
            .sortedBy { it.name.lowercase() }
            .also { Log.d(TAG, "Found ${it.size} tracks in folder") }
    }

    private data class AudioMeta(
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val artworkUri: Uri?
    )

    private fun queryMediaStoreMeta(context: Context, uri: Uri): AudioMeta? {
        return try {
            val mediaUri = resolveMediaUri(context, uri) ?: return null
            val projection = arrayOf(
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )
            context.contentResolver.query(mediaUri, projection, null, null, null)?.use { cursor: Cursor ->
                if (!cursor.moveToFirst()) return@use null
                val artist = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                )?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val album = cursor.getString(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                )?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val duration = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                )
                val albumId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                )
                val artworkUri = if (albumId > 0L) {
                    ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                } else null
                AudioMeta(artist, album, duration, artworkUri)
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryMediaStoreMeta failed for $uri: ${e.message}")
            null
        }
    }

    private fun resolveMediaUri(context: Context, safUri: Uri): Uri? {
        return try {
            val docFile = DocumentFile.fromSingleUri(context, safUri) ?: return null
            val displayName = docFile.name ?: return null
            val projection = arrayOf(MediaStore.Audio.Media._ID)
            val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(displayName)
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveMediaUri failed for $safUri: ${e.message}")
            null
        }
    }

    private fun isSupportedAudio(fileName: String?): Boolean {
        if (fileName == null) return false
        return fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS
    }

    private fun removeExtension(fileName: String): String = fileName.substringBeforeLast('.')
}
