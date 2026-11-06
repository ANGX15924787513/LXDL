package com.lxdl.app

import android.content.Context
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class Cfg(
    var mirror: String = "",
    var proxy: String = "",
    var order: MutableList<String> = mutableListOf(),
    var quality: String = "320k",
    var naming: String = "{title} - {artist}",
    var outDir: String = "lxdl",
    var lyrics: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mirror", mirror)
        .put("proxy", proxy)
        .put("order", JSONArray(order))
        .put("quality", quality)
        .put("naming", naming)
        .put("outDir", outDir)
        .put("lyrics", lyrics)

    companion object {
        fun from(o: JSONObject?): Cfg {
            if (o == null) return Cfg()
            val arr = o.optJSONArray("order") ?: JSONArray()
            val order = mutableListOf<String>()
            for (i in 0 until arr.length()) order.add(arr.optString(i))
            return Cfg(
                mirror = o.optString("mirror", ""),
                proxy = o.optString("proxy", ""),
                order = order,
                quality = o.optString("quality", "320k"),
                naming = o.optString("naming", "{title} - {artist}"),
                outDir = o.optString("outDir", "lxdl"),
                lyrics = o.optBoolean("lyrics", true)
            )
        }
    }
}

data class SourceEntry(val name: String, val file: String, val version: String, val author: String)

data class Item(
    val key: Int,
    val platform: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    var path: String = "",
    var status: String = "downloading",
    var progress: Double = 0.0,
    var done: Long = 0,
    var total: Long = 0,
    var error: String? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("platform", platform)
        .put("id", songId)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("path", path)
        .put("status", status)
        .put("progress", progress)
        .put("done", done)
        .put("total", total)
        .put("error", error ?: "")
}

class Repo(val ctx: Context) {
    val root: File = File(ctx.filesDir, "lxdl").apply { mkdirs() }
    val sourcesDir: File = File(root, "sources").apply { mkdirs() }
    val cacheDir: File = File(root, "cache").apply { mkdirs() }
    private val cfgFile = File(root, "config.json")
    private val dlFile = File(root, "downloads.json")
    private val lock = Any()
    private val nextKey = AtomicInteger(1)
    private val items = mutableListOf<Item>()
    var cfg: Cfg = Cfg()
        private set

    init {
        cfg = runCatching { Cfg.from(JSONObject(cfgFile.readText())) }.getOrDefault(Cfg())
        loadDownloads()
    }

    fun saveCfg() {
        cfgFile.writeText(cfg.toJson().toString())
    }

    // ---------------- 音源 ----------------

    fun listSources(): List<SourceEntry> {
        val files = sourcesDir.listFiles { f -> f.isFile && f.name.endsWith(".js") } ?: return emptyList()
        val list = files.map { f ->
            val text = runCatching { f.readText() }.getOrDefault("")
            val name = Regex("@name\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: f.nameWithoutExtension
            val version = Regex("@version\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: ""
            val author = Regex("@author\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: ""
            SourceEntry(name, f.name, version, author)
        }.toMutableList()
        val order = cfg.order
        list.sortBy { e ->
            val i = order.indexOf(e.name)
            if (i < 0) Int.MAX_VALUE else i
        }
        return list
    }

    fun importSource(input: String, http: Http): SourceEntry {
        val text = if (input.trim().startsWith("http")) http.text(input.trim(), timeoutMs = 60_000) else input
        require(text.contains("EVENT_NAMES") || text.contains("inited")) { "不是洛雪音源脚本" }
        val name = Regex("@name\\s+(.+)").find(text)?.groupValues?.get(1)?.trim()
            ?: "source-" + System.currentTimeMillis()
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48)
        val file = File(sourcesDir, "$safe.js")
        file.writeText(text)
        if (!cfg.order.contains(name)) cfg.order.add(name)
        saveCfg()
        return SourceEntry(name, file.name,
            Regex("@version\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "",
            Regex("@author\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "")
    }

    fun deleteSource(name: String) {
        sourcesDir.listFiles()?.firstOrNull { f ->
            f.name.endsWith(".js") && (f.nameWithoutExtension == name || f.readText().contains("@name $name"))
        }?.delete()
        cfg.order.remove(name)
        saveCfg()
    }

    fun moveSource(name: String, delta: Int) {
        val list = listSources().map { it.name }.toMutableList()
        val i = list.indexOf(name)
        if (i < 0) return
        val j = i + delta
        if (j < 0 || j >= list.size) return
        val tmp = list[i]
        list[i] = list[j]
        list[j] = tmp
        cfg.order = list.toMutableList()
        saveCfg()
    }

    // ---------------- 下载列表 ----------------

    private fun loadDownloads() {
        val arr = runCatching { JSONArray(dlFile.readText()) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val it = Item(
                key = o.optInt("key"),
                platform = o.optString("platform"),
                songId = o.optString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                path = o.optString("path"),
                status = o.optString("status"),
                progress = o.optDouble("progress", 0.0),
                done = o.optLong("done"),
                total = o.optLong("total"),
                error = o.optString("error").ifEmpty { null }
            )
            if (it.status == "downloading") {
                it.status = "failed"
                it.error = "上次退出时未完成"
            } else if (it.status == "done" && !exists(it.path)) {
                it.status = "deleted"
            }
            items.add(it)
            nextKey.updateAndGet { v -> maxOf(v, it.key + 1) }
        }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { arr.put(it.toJson()) }
        dlFile.writeText(arr.toString())
    }

    fun addItem(t: Track): Item = synchronized(lock) {
        val it = Item(nextKey.getAndIncrement(), t.platform, t.id, t.title, t.artist, t.album)
        items.add(it)
        persist()
        it
    }

    fun progress(key: Int, done: Long, total: Long?) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let {
            it.done = done
            if (total != null && total > 0) {
                it.total = total
                it.progress = (done.toDouble() / total).coerceIn(0.0, 1.0)
            }
        }
    }

    fun finish(key: Int, path: String) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let {
            it.path = path
            it.status = "done"
            it.progress = 1.0
            it.done = maxOf(it.done, it.total)
            it.error = null
        }
        persist()
    }

    fun fail(key: Int, error: String) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let {
            it.status = "failed"
            it.error = error
        }
        persist()
    }

    /** 完成的文件是否还在：绝对路径查文件，MediaStore 相对路径查内容库。 */
    private fun exists(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/")) return File(path).isFile
        // MediaStore.Downloads 只有 API 29+ 有
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false
        val name = path.substringAfterLast('/')
        return runCatching {
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(name), null
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    fun listDownloads(): List<Item> = synchronized(lock) {
        items.forEach {
            if (it.status == "done" && !exists(it.path)) it.status = "deleted"
        }
        items.toList()
    }
}
