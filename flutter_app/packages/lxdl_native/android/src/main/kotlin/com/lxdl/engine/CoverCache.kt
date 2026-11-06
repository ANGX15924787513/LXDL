package com.lxdl.engine

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面磁盘缓存：URL -> cacheDir/covers/md5(url)。同一个封面只下一次，
 * 标签写入和界面显示共用这份缓存。
 */
internal object CoverCache {
    private const val MAX_FILES = 200
    private val mem = ConcurrentHashMap<String, String>()

    private fun fileOf(ctx: Context, url: String, variant: String): File {
        val dir = File(ctx.cacheDir, "covers").apply { mkdirs() }
        val ext = when {
            url.contains(".png", ignoreCase = true) -> "png"
            url.contains(".webp", ignoreCase = true) -> "webp"
            url.contains(".gif", ignoreCase = true) -> "gif"
            else -> "jpg"
        }
        return File(dir, Crypto.md5("$url#$variant") + "." + ext)
    }

    /** 命中返回本地文件（非空），否则 null。 */
    fun get(ctx: Context, url: String, variant: String = ""): File? {
        val key = "$url#$variant"
        mem[key]?.let { p ->
            val f = File(p)
            if (f.isFile && f.length() > 0) return f
            mem.remove(key)
        }
        val f = fileOf(ctx, url, variant)
        if (f.isFile && f.length() > 0) {
            mem[key] = f.absolutePath
            return f
        }
        return null
    }

    fun put(ctx: Context, url: String, bytes: ByteArray, variant: String = ""): File? {
        if (bytes.isEmpty()) return null
        val key = "$url#$variant"
        val f = fileOf(ctx, url, variant)
        return runCatching {
            f.writeBytes(bytes)
            mem[key] = f.absolutePath
            trim(ctx)
            f
        }.getOrNull()
    }

    /** 简单裁剪：超过上限就按最久未修改的删到 80%。 */
    private fun trim(ctx: Context) {
        val dir = File(ctx.cacheDir, "covers")
        val files = dir.listFiles { f -> f.isFile } ?: return
        if (files.size <= MAX_FILES) return
        files.sortBy { it.lastModified() }
        val target = (MAX_FILES * 0.8).toInt()
        files.take(files.size - target).forEach { f ->
            val stale = mem.filterValues { it == f.absolutePath }.keys
            stale.forEach { mem.remove(it) }
            runCatching { f.delete() }
        }
    }
}
