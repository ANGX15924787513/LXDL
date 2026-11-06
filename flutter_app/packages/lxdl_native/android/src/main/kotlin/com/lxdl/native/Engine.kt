package com.lxdl.native

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class SourceInfo(
    val key: String,
    val name: String,
    val actions: List<String>,
    val qualities: List<String>
) {
    fun has(action: String) = actions.contains(action)
}

private data class Candidate(val url: String, val quality: String, val source: String, val official: Boolean)

class Engine(
    private val ctx: Context,
    private val repo: Repo,
    private val http: Http,
    private val web: WebView,
    private val providers: Providers
) {
    var onLog: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val pageReady = CompletableDeferred<Unit>()
    private val inited = AtomicReference(CompletableDeferred<JSONObject>())
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val calls = ConcurrentHashMap<Int, Call>()
    private val seq = AtomicInteger(1)
    private var activeSources: List<SourceInfo> = emptyList()
    private var loadedEntry: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        main.post {
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.allowFileAccess = true
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!pageReady.isCompleted) pageReady.complete(Unit)
                }
            }
            web.addJavascriptInterface(Bridge(), "lxHost")
            web.loadUrl("file:///android_asset/lx_host.html")
        }
    }

    private fun log(text: String) {
        onLog?.invoke(text)
    }

    // ---------------- JS 调用 ----------------

    private fun eval(js: String): String? = withLock {
        var result: String? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                web.evaluateJavascript(js) { value ->
                    result = value
                    latch.countDown()
                }
            } catch (e: Throwable) {
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        result
    }

    private fun withLock(block: () -> String?): String? = synchronized(this) { block() }

    private fun q(s: String): String = JSONObject.quote(s)

    /** 加载（切换）音源脚本，返回它上报的 sources。 */
    suspend fun loadScript(entry: SourceEntry): List<SourceInfo> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(20_000) { pageReady.await() } ?: throw IllegalStateException("JS 引擎启动超时")
        if (loadedEntry == entry.file && activeSources.isNotEmpty()) return@withContext activeSources
        val fresh = CompletableDeferred<JSONObject>()
        inited.set(fresh)
        val code = File(repo.sourcesDir, entry.file).readText()
        val meta = JSONObject().put("name", entry.name).put("version", entry.version)
            .put("author", entry.author).put("rawScript", code)
        eval("window.__lxSetScriptInfo(${q(meta.toString())});")
        eval(
            "try{\n" + code + "\n}catch(e){lxHost.log('error', String(e&&e.message||e));" +
                " lxHost.send('__inited_error', JSON.stringify({message:String(e&&e.message||e)}))}"
        )
        val info = withTimeoutOrNull(60_000) { fresh.await() }
            ?: throw IllegalStateException("音源初始化超时")
        activeSources = parseSources(info)
        loadedEntry = entry.file
        activeSources
    }

    private fun parseSources(initedJson: JSONObject): List<SourceInfo> {
        val out = mutableListOf<SourceInfo>()
        val sources = initedJson.optJSONObject("sources") ?: return out
        sources.keys().forEach { key ->
            val o = sources.optJSONObject(key) ?: return@forEach
            val actions = o.optJSONArray("actions").toStringList()
            val qualities = o.optJSONArray("qualitys").toStringList()
            out.add(SourceInfo(key, o.optString("name", key), actions, qualities))
        }
        return out
    }

    suspend fun call(source: String, action: String, info: JSONObject, timeoutMs: Int = 90_000): Any? =
        withContext(Dispatchers.IO) {
            val id = seq.getAndIncrement()
            val d = CompletableDeferred<JSONObject>()
            pending[id] = d
            eval("window.__lxAction($id, ${q(source)}, ${q(action)}, ${q(info.toString())}, $timeoutMs)")
            val r = withTimeoutOrNull(timeoutMs + 10_000L) { d.await() }
                ?: run { pending.remove(id); throw IllegalStateException("音源响应超时") }
            if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("error", "音源返回错误"))
            r.opt("value")
        }

    // ---------------- JS 桥 ----------------

    inner class Bridge {
        @JavascriptInterface
        fun send(name: String, json: String) {
            runCatching {
                val o = JSONObject(json)
                when (name) {
                    "inited" -> if (!inited.get().isCompleted) inited.get().complete(o)
                    "updateAlert" -> log("音源提示：" + o.optString("log"))
                    "__inited_error" -> if (!inited.get().isCompleted)
                        inited.get().completeExceptionally(IllegalStateException(o.optString("message")))
                    "__result" -> pending.remove(o.optInt("id"))?.complete(o)
                }
            }
        }

        @JavascriptInterface
        fun log(level: String, msg: String) {
            log(msg)
        }

        @JavascriptInterface
        fun cancel(id: Int) {
            calls.remove(id)?.cancel()
        }

        @JavascriptInterface
        fun md5(text: String): String = Crypto.md5(text)

        @JavascriptInterface
        fun aesEncrypt(data: String, mode: String, key: String, iv: String): String =
            runCatching { Crypto.aes(data, mode, key, iv) }.getOrElse { "\u0001ERR:${it.message}" }

        @JavascriptInterface
        fun rsaEncrypt(data: String, pem: String): String =
            runCatching { Crypto.rsa(data, pem) }.getOrElse { "\u0001ERR:${it.message}" }

        @JavascriptInterface
        fun randomBytes(size: Int): String = Crypto.random(size)

        @JavascriptInterface
        fun request(id: Int, url: String, optsJson: String) {
            runCatching { fire(id, url, JSONObject(optsJson)) }.onFailure { e ->
                dispatch(id, e.message ?: "request error", 0, "", "{}", "", false)
            }
        }

        private fun fire(id: Int, url: String, o: JSONObject) {
            if (!url.startsWith("http")) throw IllegalArgumentException("bad url")
            val method = o.optString("method", "GET")
            val timeout = o.optLong("timeout", 20_000)
            val headers = o.optJSONObject("headers")
            val bodyText = if (o.isNull("body")) null else o.optString("body")
            val bodyEnc = o.optString("bodyEncoding", "text")
            val bodyBytes = bodyText?.let {
                if (bodyEnc == "base64") Base64.decode(it, Base64.NO_WRAP) else it.toByteArray()
            }
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            if (headers != null) {
                headers.keys().forEach { k -> builder.header(k, headers.optString(k)) }
            }
            val rb = bodyBytes?.toRequestBody("application/octet-stream".toMediaType())
            if (rb != null) builder.method(method, rb) else builder.method(method, null)
            val call = http.client.newCall(builder.build())
            call.timeout().timeout(timeout, TimeUnit.MILLISECONDS)
            calls[id] = call
            call.enqueue(object : Callback {
                override fun onFailure(c: Call, e: java.io.IOException) {
                    dispatch(id, e.message ?: "network error", 0, "", "{}", "", false)
                }

                override fun onResponse(c: Call, response: Response) {
                    response.use { r ->
                        val bytes = r.body?.bytes() ?: ByteArray(0)
                        val ctype = r.header("Content-Type") ?: ""
                        val binary = o.optString("responseType") == "arraybuffer" ||
                            !(ctype.startsWith("text/") || ctype.contains("json") || ctype.contains("xml"))
                        val headerObj = JSONObject()
                        r.headers.forEach { (k, v) -> headerObj.put(k.lowercase(), v) }
                        dispatch(
                            id, null, r.code, r.message, headerObj.toString(),
                            Base64.encodeToString(bytes, Base64.NO_WRAP), binary
                        )
                    }
                }
            })
        }

        private fun dispatch(
            id: Int, err: String?, status: Int, statusText: String,
            headersJson: String, bodyB64: String, binary: Boolean
        ) {
            val js = "window.__lxHttpDone($id, ${err?.let { q(it) } ?: "null"}, $status, " +
                "${q(statusText)}, ${q(headersJson)}, ${q(bodyB64)}, $binary)"
            main.post { runCatching { web.evaluateJavascript(js, null) } }
        }
    }

    // ---------------- 下载 ----------------

    suspend fun downloadSong(
        item: Track,
        onProgress: (Double, Long, Long?) -> Unit,
        onStatus: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val meta = mergeMeta(item)
        onStatus("取链中…")

        val entries = repo.listSources().take(3)
        if (entries.isEmpty()) throw IllegalStateException("没有音源")

        var active: SourceInfo? = null
        val candidates = mutableListOf<Candidate>()
        var unofficial = false
        for (entry in entries) {
            val infos = runCatching { loadScript(entry) }.getOrNull()
            if (infos == null) {
                log("音源 ${entry.name} 加载失败")
                continue
            }
            val si = infos.firstOrNull { it.key == item.platform } ?: continue
            active = si
            for (quality in qualityChain(si)) {
                val info = JSONObject().put("type", quality).put("musicInfo", musicInfo(item))
                val v = runCatching { call(item.platform, "musicUrl", info) }.getOrNull() ?: continue
                val url = extractUrl(v) ?: continue
                if (candidates.any { it.url == url }) continue
                val official = isOfficial(url)
                candidates.add(Candidate(url, quality, si.name, official))
                onStatus("渠道：${si.name} · $quality")
                if (!official) unofficial = true
                if (candidates.size >= 6) break
            }
            if (unofficial) break
        }
        if (candidates.isEmpty()) throw IllegalStateException("音源不可用：前 3 个音源都没能拿到链接")
        candidates.sortWith(compareBy({ it.official }, { -qualityRank(it.quality) }))

        onStatus("获取封面/歌词…")
        val cover = fetchCover(item, active, meta.cover)
        val lyrics = if (repo.cfg.lyrics) fetchLyrics(item, active) else null

        val expected = meta.durationSec
        var best: Triple<Candidate, File, Int>? = null
        var attempts = 0
        for (c in candidates) {
            if (attempts >= 3) break
            attempts++
            onStatus("下载 ${c.quality}…")
            val tmp = File(repo.cacheDir, "cand-${System.currentTimeMillis()}-$attempts.bin")
            val ok = runCatching {
                http.download(c.url, tmp) { done, total -> onProgress(
                    if (total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0,
                    done, total
                ) }
                tmp
            }.getOrNull()
            if (ok == null) {
                log("该渠道下载失败")
                tmp.delete()
                continue
            }
            val dur = durationOf(ok)
            log("渠道时长 ${dur}s")
            val better = best == null || dur > best!!.third
            if (better) {
                best?.second?.delete()
                best = Triple(c, ok, dur)
            } else {
                ok.delete()
            }
            val complete = if (expected > 5) dur + 1 >= expected * 0.92 else dur >= 60
            if (complete) break
        }
        val picked = best ?: throw IllegalStateException("所有渠道都下载失败")
        if (expected > 5 && picked.third + 1 < expected * 0.92) {
            log("注意：最长版本只有 ${picked.third}s，平台标注 ${expected}s，可能是试听片段")
        }

        val ext = sniffExt(picked.second)
        // jaudiotagger 按扩展名选解析器，先改成正确后缀再写标签
        val tagged = File(repo.cacheDir, "tagged-${System.currentTimeMillis()}.$ext")
        if (!picked.second.renameTo(tagged)) {
            picked.second.copyTo(tagged, overwrite = true)
            picked.second.delete()
        }
        onStatus("写标签…")
        val tagError = Tagger.write(
            tagged, meta.title, meta.artist, meta.album, meta.artist,
            lyrics, cover, guessMime(cover)
        )
        if (tagError != null) log("写标签失败：$tagError")

        val name = buildName(meta, ext)
        onStatus("导出…")
        val shown = Exporter.export(ctx, tagged, repo.cfg.outDir, name, Exporter.mimeFor(ext))
        tagged.delete()
        shown
    }

    private fun mergeMeta(item: Track): Track {
        val d = runCatching { providers.detail(item.platform, item.id) }.getOrNull()
        if (d == null) return item
        return Track(
            platform = item.platform, id = item.id,
            title = d.title.ifBlank { item.title },
            artist = d.artist.ifBlank { item.artist },
            album = d.album.ifBlank { item.album },
            cover = d.cover ?: item.cover,
            durationSec = if (d.durationSec > 0) d.durationSec else item.durationSec,
            isPlaylist = false
        )
    }

    private fun musicInfo(item: Track): JSONObject = JSONObject()
        .put("source", item.platform)
        .put("songmid", item.id)
        .put("id", item.id)
        .put("hash", item.id)
        .put("name", item.title)
        .put("singer", item.artist)
        .put("albumName", item.album)

    private fun qualityChain(si: SourceInfo): List<String> {
        if (si.qualities.isEmpty()) return listOf(repo.cfg.quality)
        return listOf(repo.cfg.quality, "flac24bit", "flac", "320k", "128k")
            .filter { si.qualities.contains(it) }
            .distinct()
    }

    private fun qualityRank(q: String): Int = when (q) {
        "flac24bit" -> 4
        "flac" -> 3
        "320k" -> 2
        "128k" -> 1
        else -> 0
    }

    private fun isOfficial(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("").lowercase()
        return listOf("music.126.net", "music.163.com", "qqmusic.qq.com", "y.qq.com",
            "gtimg.cn", "kugou.com", "kuwo.cn", "migu.cn", "music.tc.qq.com")
            .any { host.contains(it) }
    }

    private fun extractUrl(v: Any?): String? = when (v) {
        is String -> v.trim().ifEmpty { null }
        is JSONObject -> listOf("url", "data", "src", "link", "musicUrl")
            .firstNotNullOfOrNull { k -> v.optString(k).ifBlank { null } }
        else -> null
    }?.takeIf { it.startsWith("http") }

    private fun fetchCover(item: Track, si: SourceInfo?, fallbackUrl: String?): ByteArray? {
        var url: String? = null
        if (si != null && si.has("pic")) {
            runCatching {
                val v = call(item.platform, "pic", JSONObject().put("musicInfo", musicInfo(item)), 45_000)
                url = extractUrl(v)
            }
        }
        if (url == null) url = fallbackUrl
        if (url == null) url = runCatching { providers.detail(item.platform, item.id).cover }.getOrNull()
        if (url == null) return null
        return runCatching { http.bytes(url!!, timeoutMs = 25_000) }.getOrNull()
    }

    private fun fetchLyrics(item: Track, si: SourceInfo?): String? {
        if (si != null && si.has("lyric")) {
            runCatching {
                val v = call(item.platform, "lyric", JSONObject().put("musicInfo", musicInfo(item)), 45_000)
                val text = when (v) {
                    is String -> v
                    is JSONObject -> v.optString("lyric").ifBlank { v.optString("lrc") }
                    else -> ""
                }
                if (text.isNotBlank()) return text
            }
        }
        return runCatching { providers.lyric(item.platform, item.id)?.first }.getOrNull()
    }

    private fun durationOf(file: File): Int = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
        } finally {
            mmr.release()
        }
    }.getOrDefault(0)

    private fun sniffExt(file: File): String = runCatching {
        val head = ByteArray(12)
        file.inputStream().use { it.read(head) }
        when {
            String(head, 0, 4) == "fLaC" -> "flac"
            String(head, 0, 3) == "ID3" -> "mp3"
            head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            String(head, 4, 4) == "ftyp" -> "m4a"
            String(head, 0, 4) == "OggS" -> "ogg"
            String(head, 0, 4) == "RIFF" -> "wav"
            else -> "mp3"
        }
    }.getOrDefault("mp3")

    private fun guessMime(cover: ByteArray?): String? = when {
        cover == null || cover.size < 4 -> null
        cover[0] == 0x89.toByte() -> "image/png"
        cover[0] == 0xFF.toByte() -> "image/jpeg"
        else -> "image/jpeg"
    }

    private fun buildName(t: Track, ext: String): String {
        var s = repo.cfg.naming
            .replace("{title}", t.title.ifBlank { t.id })
            .replace("{artist}", t.artist)
            .replace("{album}", t.album)
            .replace("{platform}", t.platform)
            .replace("{quality}", repo.cfg.quality)
            .replace("{id}", t.id)
            .replace("{ext}", ext)
        s = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifBlank { t.id }
        return "$s.$ext"
    }
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it).ifBlank { null } }
}
