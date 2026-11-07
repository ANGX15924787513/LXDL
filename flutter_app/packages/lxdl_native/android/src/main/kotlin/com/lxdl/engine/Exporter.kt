package com.lxdl.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

object Exporter {
    data class Out(val display: String, val uri: String)

    /**
     * 导出顺序：SAF 选中的目录 > 系统下载目录（MediaStore，Q+）> 老式公共目录（<Q）。
     * 任何一步失败都会把**真实原因**带出来，不再静默回退成一句 EACCES。
     */
    fun export(ctx: Context, src: File, subDir: String, name: String, mime: String, treeUri: String): Out {
        val dir = subDir.trim().trim('/').ifEmpty { "lxdl" }
        val errors = mutableListOf<String>()

        if (treeUri.isNotBlank()) {
            runCatching { exportToTree(ctx, src, dir, name, mime, treeUri) }
                .onSuccess { return it }
                .onFailure {
                    android.util.Log.w("lxdl", "export to SAF failed: ${it.message}")
                    errors += "自定义目录：${it.message}"
                }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { exportToMediaStore(ctx, src, dir, name, mime) }
                .onSuccess { return it }
                .onFailure {
                    android.util.Log.w("lxdl", "export to MediaStore failed: ${it.message}")
                    errors += "系统下载目录：${it.message}"
                }
        } else {
            runCatching { exportToLegacy(ctx, src, dir, name) }
                .onSuccess { return it }
                .onFailure {
                    android.util.Log.w("lxdl", "export to legacy dir failed: ${it.message}")
                    errors += "下载目录：${it.message}"
                }
        }

        // 最后兜底：App 自己的外部目录，任何权限都不需要，写完用 FileProvider 共享
        runCatching { exportToAppDir(ctx, src, dir, name) }
            .onSuccess { return it }
            .onFailure { errors += "应用目录：${it.message}" }

        throw IllegalStateException("保存失败（${errors.joinToString("；")}）")
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trim().ifBlank { "audio.mp3" }

    private fun exportToMediaStore(ctx: Context, src: File, dir: String, name: String, mime: String): Out {
        val resolver = ctx.contentResolver
        val rel = Environment.DIRECTORY_DOWNLOADS + "/" + dir
        val collection: Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        var lastError: Throwable? = null

        repeat(2) { attempt ->
            val base = safeName(name)
            val display = if (attempt == 0) base else {
                val dot = base.lastIndexOf('.')
                if (dot > 0) {
                    base.substring(0, dot) + " (" + (System.currentTimeMillis() % 1000) + ")" + base.substring(dot)
                } else {
                    base + "-" + (System.currentTimeMillis() % 1000)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, display)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, rel)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(collection, values)
            } catch (e: Throwable) {
                lastError = e
                null
            }
            if (uri == null) {
                if (lastError == null) lastError = IllegalStateException("insert 返回 null")
                return@repeat
            }
            try {
                val out = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("openOutputStream 返回 null")
                out.use { o -> src.inputStream().use { it.copyTo(o) } }
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
                return Out("$rel/$display", uri.toString())
            } catch (e: Throwable) {
                lastError = e
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        throw lastError ?: IllegalStateException("写入系统下载目录失败")
    }

    private fun exportToAppDir(ctx: Context, src: File, dir: String, name: String): Out {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(ctx.filesDir, "Download")
        val dest = File(File(base, dir), safeName(name))
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".lxdl.files", dest)
        return Out("应用私有目录/Download/$dir/${dest.name}", uri.toString())
    }

    @Suppress("DEPRECATION")
    private fun exportToLegacy(ctx: Context, src: File, dir: String, name: String): Out {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dest = File(File(base, dir), safeName(name))
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        return Out("${Environment.DIRECTORY_DOWNLOADS}/$dir/${dest.name}", "")
    }

    private fun exportToTree(ctx: Context, src: File, dir: String, name: String, mime: String, treeUri: String): Out {
        val resolver = ctx.contentResolver
        val tree = Uri.parse(treeUri)
        var parent = DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )
        dir.split('/').filter { it.isNotBlank() }.forEach { part ->
            parent = findChild(resolver, parent, part) ?: run {
                DocumentsContract.createDocument(
                    resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, part
                ) ?: throw IllegalStateException("无法创建目录 $part")
            }
        }
        val doc = DocumentsContract.createDocument(resolver, parent, mime, safeName(name))
            ?: throw IllegalStateException("无法写入文件（目录可能只读）")
        val out = resolver.openOutputStream(doc) ?: throw IllegalStateException("打不开输出流")
        out.use { o -> src.inputStream().use { it.copyTo(o) } }
        return Out(safeName(name), doc.toString())
    }

    private fun findChild(
        resolver: android.content.ContentResolver,
        parent: Uri,
        name: String
    ): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            parent, DocumentsContract.getDocumentId(parent)
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(parent, c.getString(0))
                }
            }
        }
        return null
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
