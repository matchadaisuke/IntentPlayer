package com.intentplayer.storage

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.intentplayer.model.Track

/** SAF URI と file URI の両方から、選択フォルダ直下の音声ファイルを読み込む。 */
object FolderScanner {
    private const val TAG = "FolderScanner"

    private val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "flac", "aac", "ogg", "opus", "wav", "wma"
    )

    fun scanFolder(context: Context, folderUri: Uri): List<Track> {
        Log.d(TAG, "scanFolder: $folderUri (scheme=${folderUri.scheme})")

        if (folderUri.scheme == "file") {
            return scanFileFolder(context, folderUri)
        }

        val folder = DocumentFile.fromTreeUri(context, folderUri)
        if (folder == null || !folder.isDirectory) {
            Log.w(TAG, "URI is not a directory: $folderUri")
            return emptyList()
        }
        if (!folder.canRead()) throw SecurityException("フォルダへの読み取り権限がありません: $folderUri")

        return folder.listFiles()
            .filter { it.isFile && isSupportedAudio(it.name) }
            .mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val meta = queryMediaStoreMeta(context, file.uri) ?: readRetrieverMeta(context, file.uri)
                Track(
                    uri = file.uri,
                    name = removeExtension(fileName),
                    fileName = fileName,
                    artist = meta?.artist,
                    album = meta?.album,
                    durationMs = meta?.durationMs ?: 0L,
                    artworkUri = meta?.artworkUri
                )
            }
            .sortedBy { it.name.lowercase() }
            .also { Log.d(TAG, "Found ${it.size} tracks in SAF folder") }
    }

    private fun scanFileFolder(context: Context, folderUri: Uri): List<Track> {
        val directory = java.io.File(folderUri.path ?: return emptyList())
        if (!directory.isDirectory) return emptyList()
        if (!directory.canRead()) throw SecurityException("フォルダへの読み取り権限がありません: ${directory.absolutePath}")
        val files = directory.listFiles() ?: throw SecurityException("フォルダの内容を取得できません: ${directory.absolutePath}")

        return files.asSequence()
            .filter { it.isFile && isSupportedAudio(it.name) }
            .map { file ->
                val uri = Uri.fromFile(file)
                val meta = readRetrieverMeta(context, uri)
                Track(
                    uri = uri,
                    name = removeExtension(file.name),
                    fileName = file.name,
                    artist = meta?.artist,
                    album = meta?.album,
                    durationMs = meta?.durationMs ?: 0L,
                    artworkUri = meta?.artworkUri
                )
            }
            .sortedBy { it.name.lowercase() }
            .toList()
            .also { Log.d(TAG, "Found ${it.size} tracks in file folder") }
    }

    private data class AudioMeta(
        val artist: String?,
        val album: String?,
        val durationMs: Long,
        val artworkUri: Uri?
    )

    private fun readRetrieverMeta(context: Context, uri: Uri): AudioMeta? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "file") {
                retriever.setDataSource(uri.path)
            } else {
                retriever.setDataSource(context, uri)
            }
            AudioMeta(
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                artworkUri = null
            )
        } catch (e: Exception) {
            Log.d(TAG, "MediaMetadataRetriever failed for $uri: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

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
                val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                    ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                val duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
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
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf(displayName),
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
