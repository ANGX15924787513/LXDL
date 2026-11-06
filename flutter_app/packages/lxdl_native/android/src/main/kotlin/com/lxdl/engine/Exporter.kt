package com.lxdl.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

object Exporter {
    data class Out(val display: String, val uri: String)

    /**
     * 导出到：SAF 选中的目录（outTree）> MediaStore Download（Q+）> 公共 Download 目录。
     */
    fun export(ctx: Context, src: File, subDir: String, name: String, mime: String, treeUri: String): Out {
        val dir = subDir.trim().trim('/').ifEmpty { "lxdl" }
        if (treeUri.isNotBlank()) {
            runCatching { exportToTree(ctx, src, dir, name, mime, treeUri) }.getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { exportToMediaStore(ctx, src, dir, name, mime) }.getOrNull()?.let { return it }
        }
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dest = File(File(base, dir), name)
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        return Out("${Environment.DIRECTORY_DOWNLOADS}/$dir/$name", "")
    }

    private fun exportToMediaStore(ctx: Context, src: File, dir: String, name: String, mime: String): Out {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + dir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("无法写入下载目录")
        resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            ?: throw IllegalStateException("无法写入下载目录")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Out("${Environment.DIRECTORY_DOWNLOADS}/$dir/$name", uri.toString())
    }

    private fun exportToTree(ctx: Context, src: File, dir: String, name: String, mime: String, treeUri: String): Out {
        val resolver = ctx.contentResolver
        val tree = Uri.parse(treeUri)
        var parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        dir.split('/').filter { it.isNotBlank() }.forEach { part ->
            val existing = DocumentsContract.findDocumentPath(resolver, parent, part)?.uri
            parent = existing ?: run {
                val created = DocumentsContract.createDocument(
                    resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part
                ) ?: throw IllegalStateException("无法创建目录 $part")
                created
            }
        }
        val doc = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: throw IllegalStateException("无法写入文件")
        resolver.openOutputStream(doc)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        return Out(name, doc.toString())
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        "lrc" -> "text/plain"
        else -> "audio/mpeg"
    }
}
