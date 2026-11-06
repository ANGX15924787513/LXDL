package com.lxdl.native

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Exporter {
    /** 把文件导出到 Download/<subDir>/<name>，返回给用户看的相对路径。 */
    fun export(ctx: Context, src: File, subDir: String, name: String, mime: String): String {
        val dir = subDir.trim().trim('/').ifEmpty { "lxdl" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + dir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入下载目录")
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("无法写入下载目录")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "${Environment.DIRECTORY_DOWNLOADS}/$dir/$name"
        }
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dest = File(File(base, dir), name)
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        return dest.absolutePath
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "m4a", "mp4" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }
}
