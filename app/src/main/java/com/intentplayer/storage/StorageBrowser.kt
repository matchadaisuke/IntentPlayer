package com.intentplayer.storage

import android.content.Context
import android.os.Environment
import java.io.File

/** すべてのファイルへのアクセス権限を使う簡易ファイルブラウザ。 */
object StorageBrowser {
    data class Root(val name: String, val file: File, val removable: Boolean)
    data class Entry(val file: File, val isDirectory: Boolean, val isAudio: Boolean)

    private val audioExtensions = setOf("mp3", "m4a", "flac", "aac", "ogg", "opus", "wav", "wma")

    fun roots(context: Context): List<Root> {
        val roots = linkedMapOf<String, Root>()

        runCatching {
            val primary = Environment.getExternalStorageDirectory().canonicalFile
            roots[primary.path] = Root("内部ストレージ", primary, false)
        }

        context.getExternalFilesDirs(null).filterNotNull().forEach { appDir ->
            val storageRoot = storageRootFor(appDir) ?: return@forEach
            val canonical = runCatching { storageRoot.canonicalFile }.getOrElse { storageRoot.absoluteFile }
            if (roots.containsKey(canonical.path)) return@forEach
            val removable = runCatching { Environment.isExternalStorageRemovable(appDir) }.getOrDefault(true)
            val label = if (removable) "SDカード" else "ストレージ"
            roots[canonical.path] = Root(label, canonical, removable)
        }
        return roots.values.toList()
    }

    fun list(directory: File): Result<List<Entry>> = runCatching {
        require(directory.isDirectory) { "フォルダではありません" }
        if (!directory.canRead()) throw SecurityException("このフォルダを読み取る権限がありません")
        val children = directory.listFiles() ?: throw SecurityException("このフォルダの内容を取得できません")
        children.asSequence()
            .filter { it.isDirectory || (it.isFile && isAudio(it)) }
            .map { Entry(it, it.isDirectory, it.isFile && isAudio(it)) }
            .sortedWith(compareBy<Entry> { !it.isDirectory }.thenBy { it.file.name.lowercase() })
            .toList()
    }

    fun isAudio(file: File): Boolean = file.extension.lowercase() in audioExtensions

    private fun storageRootFor(appDir: File): File? {
        val path = appDir.absolutePath
        val marker = "/Android/"
        val index = path.indexOf(marker)
        return if (index > 0) File(path.substring(0, index)) else null
    }
}
