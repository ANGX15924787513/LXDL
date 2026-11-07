package com.lxdl.engine

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
    var theme: String = "#3A7AF0",
    /** 脚本引擎：webview（默认）/ quickjs */
    var engine: String = "webview",
    /** 已随包内置的音源版本（空 = 还没导入过） */
    var builtinSourceVersion: String = "",
    var lyrics: Boolean = true,
    var lrcSidecar: Boolean = false,
    var concurrency: Int = 1,
    var wifiOnly: Boolean = false,
    var outTree: String = "",
    var history: MutableList<String> = mutableListOf()
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mirror", mirror)
        .put("proxy", proxy)
        .put("order", JSONArray(order))
        .put("quality", quality)
        .put("naming", naming)
        .put("outDir", outDir)
        .put("theme", theme)
        .put("engine", engine)
        .put("builtinSourceVersion", builtinSourceVersion)
        .put("lyrics", lyrics)
        .put("lrcSidecar", lrcSidecar)
        .put("concurrency", concurrency)
        .put("wifiOnly", wifiOnly)
        .put("outTree", outTree)
        .put("history", JSONArray(history))

    companion object {
        fun from(o: JSONObject?): Cfg {
            if (o == null) return Cfg()
            fun list(key: String): MutableList<String> {
                val arr = o.optJSONArray(key) ?: JSONArray()
                val out = mutableListOf<String>()
                for (i in 0 until arr.length()) out.add(arr.optString(i))
                return out
            }
            return Cfg(
                mirror = o.optString("mirror", ""),
                proxy = o.optString("proxy", ""),
                order = list("order"),
                quality = o.optString("quality", "320k"),
                naming = o.optString("naming", "{title} - {artist}"),
                outDir = o.optString("outDir", "lxdl"),
                theme = o.optString("theme", "#3A7AF0"),
                engine = o.optString("engine", "webview").let {
                    when (it) { "quickjs", "rquickjs" -> it; else -> "webview" }
                },
                builtinSourceVersion = o.optString("builtinSourceVersion", ""),
                lyrics = o.optBoolean("lyrics", true),
                lrcSidecar = o.optBoolean("lrcSidecar", false),
                concurrency = o.optInt("concurrency", 1).coerceIn(1, 8),
                wifiOnly = o.optBoolean("wifiOnly", true),
                outTree = o.optString("outTree", ""),
                history = list("history")
            )
        }
    }
}

data class SourceEntry(val name: String, val file: String, val version: String, val author: String)

data class Item(
    val key: Int,
    val platform: String,
    val songId: String,
    var title: String,
    var artist: String,
    var album: String,
    var path: String = "",
    var uri: String = "",
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
        .put("uri", uri)
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

    fun rememberHistory(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        cfg.history.remove(k)
        cfg.history.add(0, k)
        while (cfg.history.size > 20) cfg.history.removeAt(cfg.history.size - 1)
        saveCfg()
    }

    // ---------------- 音源 ----------------

    // listSources 每次都要把脚本读一遍抽 @name/@version，缓存一下（导入/删除/排序时失效）
    @Volatile private var sourcesCache: List<SourceEntry>? = null

    fun invalidateSources() {
        sourcesCache = null
    }

    // 随包音源：进程内只读一次
    private val builtinSource: String? by lazy {
        runCatching {
            ctx.assets.open("lx_source.js").bufferedReader().use { it.readText() }
        }.getOrNull()
    }
    private val builtinVersion: String by lazy {
        Regex("@version\\s+(.+)").find(builtinSource ?: "")
            ?.groupValues?.get(1)?.trim() ?: ""
    }

    /**
     * 随包内置一个音源：首次运行（或内置版本变了）时把 assets/lx_source.js 写进 sources 目录。
     * 用户删掉后，下次 App 升级带了新版本会再出现。
     */
    private fun ensureBuiltinSource() {
        val text = builtinSource ?: return
        if (text.isBlank()) return
        if (builtinVersion.isNotEmpty() && builtinVersion == cfg.builtinSourceVersion) return

        synchronized(lock) {
            if (builtinVersion.isNotEmpty() && builtinVersion == cfg.builtinSourceVersion) return
            runCatching {
                val name = Regex("@name\\s+(.+)").find(text)
                    ?.groupValues?.get(1)?.trim() ?: "内置音源"
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48)
                File(sourcesDir, safe + ".js").writeText(text)
                if (!cfg.order.contains(name)) cfg.order.add(name)
                cfg.builtinSourceVersion = builtinVersion
                saveCfg()
                invalidateSources()
            }
        }
    }

    fun listSources(): List<SourceEntry> {
        ensureBuiltinSource()
        sourcesCache?.let { return it }
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
        val result: List<SourceEntry> = list.toList()
        sourcesCache = result
        return result
    }

    // 本次运行里学到的「音源 -> 支持平台」，用来跳过不可能命中的脚本
    private val sourcePlatforms = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    fun notePlatforms(file: String, platforms: List<String>) {
        sourcePlatforms[file] = platforms
    }

    fun knownPlatforms(file: String): List<String>? = sourcePlatforms[file]

    fun importSource(input: String, http: Http): SourceEntry {
        val text = if (input.trim().startsWith("http")) http.text(input.trim(), timeoutMs = 60_000) else input
        require(text.contains("EVENT_NAMES") || text.contains("inited")) { "不是洛雪音源脚本" }
        val name = Regex("@name\\s+(.+)").find(text)?.groupValues?.get(1)?.trim()
            ?: "source-" + System.currentTimeMillis()
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(48)
        File(sourcesDir, "$safe.js").writeText(text)
        if (!cfg.order.contains(name)) cfg.order.add(name)
        saveCfg()
        invalidateSources()
        return SourceEntry(
            name, "$safe.js",
            Regex("@version\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: "",
            Regex("@author\\s+(.+)").find(text)?.groupValues?.get(1)?.trim() ?: ""
        )
    }

    fun deleteSource(name: String) {
        sourcesDir.listFiles()?.firstOrNull { f ->
            f.name.endsWith(".js") && (f.nameWithoutExtension == name || f.readText().contains("@name $name"))
        }?.delete()
        cfg.order.remove(name)
        saveCfg()
        invalidateSources()
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
        invalidateSources()
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
                uri = o.optString("uri"),
                status = o.optString("status"),
                progress = o.optDouble("progress", 0.0),
                done = o.optLong("done"),
                total = o.optLong("total"),
                error = o.optString("error").ifEmpty { null }
            )
            if (it.status == "downloading" || it.status == "paused") {
                it.status = "failed"
                it.error = "上次退出时未完成"
            } else if (it.status == "done" && !exists(it)) {
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

    fun item(key: Int): Item? = synchronized(lock) { items.firstOrNull { it.key == key } }

    fun progress(key: Int, done: Long, total: Long?) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let {
            it.done = done
            if (total != null && total > 0) {
                it.total = total
                it.progress = (done.toDouble() / total).coerceIn(0.0, 1.0)
            }
        }
    }

    fun setStatus(key: Int, status: String) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.status = status
        persist()
    }

    fun finish(key: Int, path: String, uri: String) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let {
            it.path = path
            it.uri = uri
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

    fun updateMeta(key: Int, title: String, artist: String, album: String) = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let {
            it.title = title
            it.artist = artist
            it.album = album
        }
        persist()
    }

    fun removeItem(key: Int) = synchronized(lock) {
        items.removeAll { it.key == key }
        persist()
    }

    private fun exists(item: Item): Boolean {
        if (item.uri.isNotBlank()) {
            return runCatching {
                ctx.contentResolver.openInputStream(android.net.Uri.parse(item.uri))?.close()
                true
            }.getOrDefault(false)
        }
        val path = item.path
        if (path.isBlank()) return false
        if (path.startsWith("/")) return File(path).isFile
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

    /** 已完成的文件是否还在（绝对路径 / MediaStore / SAF 树）。 */
    fun exists(path: String, uri: String): Boolean =
        exists(Item(0, "", "", "", "", "", path = path, uri = uri))

    fun listDownloads(): List<Item> = synchronized(lock) {
        items.forEach {
            if (it.status == "done" && !exists(it)) it.status = "deleted"
        }
        items.toList()
    }
}
