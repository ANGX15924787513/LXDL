package com.lxdl.engine

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
import kotlinx.coroutines.async
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

internal data class Candidate(val url: String, val quality: String, val source: String, val official: Boolean)

/** 预取好的下载素材：元数据 + 候选直链 */
internal class Prepared(
    val meta: Track,
    val candidates: List<Candidate>,
    val source: SourceInfo?
)

private const val MAX_CANDIDATES = 3

/** 试听/复制直链的短缓存：LX 也是取到链接后复用一段时间。 */
internal object UrlCache {
    private const val TTL_MS = 10 * 60 * 1000L
    private class Entry(val url: String, val at: Long)
    private val map = ConcurrentHashMap<String, Entry>()
    private fun key(platform: String, id: String, quality: String) = "$platform|$id|$quality"

    fun get(platform: String, id: String, quality: String): String? {
        val k = key(platform, id, quality)
        val e = map[k] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            map.remove(k)
            return null
        }
        return e.url
    }

    fun put(platform: String, id: String, quality: String, url: String) {
        if (map.size > 300) map.clear()
        map[key(platform, id, quality)] = Entry(url, System.currentTimeMillis())
    }
}

class Engine(
    private val ctx: Context,
    private val repo: Repo,
    private val http: Http,
    private val web: WebView,
    private val providers: Providers
) {
    var onLog: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val segmenter = SegmentDownloader(http)
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

    fun detach() {
        main.post {
            runCatching { (web.parent as? android.view.ViewGroup)?.removeView(web) }
            runCatching { web.destroy() }
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
        if (eval("(window.__lxReady === true) ? 1 : 0") != "1") {
            throw IllegalStateException("JS 宿主没加载起来（lx_host.html 缺失或被拦截）")
        }
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
        val info = withTimeoutOrNull(25_000) { fresh.await() }
            ?: throw IllegalStateException("音源初始化超时")
        activeSources = parseSources(info)
        repo.notePlatforms(entry.file, activeSources.map { it.key })
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
            // 音源脚本的 console 经常在“换备选方案”时刷错误，别弹给用户看
            android.util.Log.d("lxdl-js", "[$level] $msg")
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
            // 表单 POST 要靠请求头里的 Content-Type，别被默认的 octet-stream 盖掉
            // 注意 JSONObject.keys() 是 Iterator，没有带 predicate 的 firstOrNull，先转成 Sequence
            val ctype = headers?.let { h ->
                h.keys().asSequence()
                    .firstOrNull { k -> k.equals("content-type", true) }
                    ?.let { k -> h.optString(k) }
            }?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            val media = runCatching { ctype.toMediaType() }.getOrNull()
                ?: "application/octet-stream".toMediaType()
            val rb = bodyBytes?.toRequestBody(media)
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

    // ---------------- 取链（只解析不下载） ----------------

    /**
     * 取播放直链。对齐 LX Music 的做法：
     * 1. 按音源顺序，第一个能给出链接的就用（不再把整条音质链跑一遍）；
     * 2. 单个音源/音质失败全部静默，只有全都拿不到才报错；
     * 3. 结果缓存 10 分钟，同一首歌反复试听/复制直链是秒开。
     */
    suspend fun resolveUrl(item: Track, quality: String): String = withContext(Dispatchers.IO) {
        UrlCache.get(item.platform, item.id, quality)?.let { return@withContext it }
        // 预览不能无限等：最多 30 秒，超时就报错（LX 也会很快放弃）
        withTimeoutOrNull(30_000) { resolveUrlInner(item, quality) }
            ?: throw IllegalStateException("取链超时，换个音源试试")
    }

    private suspend fun resolveUrlInner(item: Track, quality: String): String {
        var supporting = 0
        for (entry in orderedSources(item.platform)) {
            if (supporting >= 2) break
            val infos = loadSources(entry) ?: continue
            val si = infos.firstOrNull { it.key == item.platform } ?: continue
            supporting++
            for (q in qualityChain(si, quality).take(2)) {
                val url = musicUrl(item, q) ?: continue
                UrlCache.put(item.platform, item.id, quality, url)
                return url
            }
        }
        throw IllegalStateException("音源不可用：所有音源都没拿到链接")
    }

    /** 已加载的音源优先、已知支持该平台的优先、已知不支持的排最后。 */
    private fun orderedSources(platform: String): List<SourceEntry> =
        repo.listSources()
            .map { e ->
                val known = repo.knownPlatforms(e.file)
                val support = when {
                    known == null -> 1
                    known.contains(platform) -> 0
                    else -> 2
                }
                val loaded = if (e.file == loadedEntry) 0 else 1
                Triple(e, support, loaded)
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { it.first }

    // ---------------- 下载 ----------------

    suspend fun downloadSong(
        item: Track,
        key: Int,
        control: DownloadControl,
        onProgress: (Double, Long, Long?) -> Unit,
        onStatus: (String) -> Unit
    ): Exporter.Out {
        val ready = prepare(item) ?: throw IllegalStateException("音源不可用：所有音源都没拿到链接")
        return downloadPrepared(item, key, control, ready, onProgress, onStatus)
    }

    /**
     * 只解析直链，不下载。批量下载时先把所有歌的链接并发预取出来，
     * 下载线程一拿到任务就直接开跑，不用现场等链接。
     */
    suspend fun prepare(item: Track): Prepared? = withContext(Dispatchers.IO) {
        val meta = mergeMeta(item)
        val entries = orderedSources(item.platform)
        if (entries.isEmpty()) return@withContext null
        val candidates = mutableListOf<Candidate>()
        var active: SourceInfo? = null
        var supporting = 0
        var unofficial = false
        for (entry in entries) {
            if (supporting >= 2 || candidates.size >= MAX_CANDIDATES) break
            val infos = loadSources(entry) ?: continue
            val si = infos.firstOrNull { it.key == item.platform } ?: continue
            supporting++
            active = si
            for (quality in qualityChain(si, repo.cfg.quality).take(2)) {
                if (candidates.size >= MAX_CANDIDATES) break
                val url = musicUrl(item, quality) ?: continue
                if (candidates.any { it.url == url }) continue
                val official = isOfficial(url)
                candidates.add(Candidate(url, quality, si.name, official))
                if (!official) unofficial = true
            }
            if (unofficial) break
        }
        if (candidates.isEmpty()) return@withContext null
        Prepared(
            meta = meta,
            candidates = candidates.sortedWith(compareBy({ it.official }, { -qualityRank(it.quality) })),
            source = active
        )
    }

    suspend fun downloadPrepared(
        item: Track,
        key: Int,
        control: DownloadControl,
        ready: Prepared,
        onProgress: (Double, Long, Long?) -> Unit,
        onStatus: (String) -> Unit
    ): Exporter.Out = withContext(Dispatchers.IO) {
        val meta = ready.meta
        val active = ready.source
        val candidates = ready.candidates
        onStatus("下载中…")

        // 封面/歌词和下载并行跑，不再串在关键路径上
        val coverJob = async {
            runCatching { fetchCover(item, active, meta.cover) }.getOrNull()
        }
        val lyricJob = async {
            if (!repo.cfg.lyrics) null
            else runCatching { fetchLyrics(item, active) }.getOrNull()
        }

        val expected = meta.durationSec
        var lastDownloadError: String? = null
        var best: Triple<Candidate, File, Int>? = null
        var attempts = 0
        for (c in candidates) {
            if (attempts >= 3) break
            attempts++
            if (control.cancelled) throw Cancelled()
            onStatus("下载 ${c.quality}…")
            val tmp = File(repo.cacheDir, "cand-$key-$attempts.bin")
            val ok = try {
                // 分段并发下载；服务器不支持 Range 会自动退回单连接
                segmenter.download(
                    c.url, tmp, control,
                    mapOf("User-Agent" to UA, "Referer" to refererOf(item.platform))
                ) { done, total ->
                    onProgress(
                        if (total != null && total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else 0.0,
                        done, total
                    )
                }
                tmp
            } catch (e: Cancelled) {
                cleanTmp(tmp)
                throw e
            } catch (e: Exception) {
                // 单个渠道失败不打扰用户，最后统一报错
                lastDownloadError = e.message
                cleanTmp(tmp)
                null
            }
            if (ok == null) continue
            val dur = durationOf(ok)
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
        val picked = best ?: throw IllegalStateException(
            lastDownloadError?.takeIf { it.isNotBlank() } ?: "所有渠道都下载失败"
        )
        val cover = coverJob.await()
        val lyrics = lyricJob.await()
        if (expected > 5 && picked.third + 1 < expected * 0.92) {
            log("注意：最长版本只有 ${picked.third}s，平台标注 ${expected}s，可能是试听片段")
        }

        val ext = sniffExt(picked.second)
        val tagged = File(repo.cacheDir, "tagged-$key.$ext")
        if (!picked.second.renameTo(tagged)) {
            picked.second.copyTo(tagged, overwrite = true)
            picked.second.delete()
        }
        onStatus("写标签…")
        val tagError = Tagger.write(
            tagged, meta.title, meta.artist, meta.album, meta.artist,
            lyrics, cover, guessMime(cover)
        )
        if (tagError != null) onStatus("标签写入跳过")

        val name = buildName(meta, ext)
        onStatus("导出…")
        val out = Exporter.export(ctx, tagged, repo.cfg.outDir, name, Exporter.mimeFor(ext), repo.cfg.outTree)
        tagged.delete()

        if (repo.cfg.lrcSidecar && !lyrics.isNullOrBlank()) {
            val lrc = File(repo.cacheDir, "lyric-$key.lrc")
            lrc.writeText(lyrics)
            val lrcName = name.substringBeforeLast('.') + ".lrc"
            runCatching { Exporter.export(ctx, lrc, repo.cfg.outDir, lrcName, "text/plain", repo.cfg.outTree) }
            lrc.delete()
        }
        out
    }

    private fun cleanTmp(tmp: File) {
        tmp.delete()
        File(tmp.absolutePath + ".part").delete()
        File(tmp.absolutePath + ".part.json").delete()
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

    /** 加载音源脚本；被取消时不要吞掉 CancellationException，其它错误静默。 */
    private suspend fun loadSources(entry: SourceEntry): List<SourceInfo>? = try {
        loadScript(entry)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null
    }

    /** 向音源要直链；失败静默返回 null。 */
    private suspend fun musicUrl(item: Track, quality: String): String? {
        val info = JSONObject().put("type", quality).put("musicInfo", musicInfo(item))
        val v = try {
            call(item.platform, "musicUrl", info, 25_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return null
        }
        return extractUrl(v)
    }

    private fun musicInfo(item: Track): JSONObject = JSONObject()
        .put("source", item.platform)
        .put("songmid", item.id)
        .put("id", item.id)
        .put("hash", item.id)
        .put("name", item.title)
        .put("singer", item.artist)
        .put("albumName", item.album)

    private fun qualityChain(si: SourceInfo, preferred: String): List<String> {
        if (si.qualities.isEmpty()) return listOf(preferred)
        return listOf(preferred, "flac24bit", "flac", "320k", "128k")
            .filter { si.qualities.contains(it) }
            .distinct()
            .ifEmpty { listOf(preferred) }
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

    private suspend fun fetchCover(item: Track, si: SourceInfo?, fallbackUrl: String?): ByteArray? {
        var url: String? = null
        if (si != null && si.has("pic")) {
            runCatching {
                val v = call(item.platform, "pic", JSONObject().put("musicInfo", musicInfo(item)), 20_000)
                url = extractUrl(v)
            }
        }
        if (url == null) url = fallbackUrl
        if (url == null) url = runCatching { providers.detail(item.platform, item.id).cover }.getOrNull()
        val u = url ?: return null
        CoverCache.get(ctx, u)?.let { return it.readBytes() }
        val bytes = runCatching {
            http.bytes(u, mapOf("User-Agent" to UA), timeoutMs = 25_000)
        }.getOrNull() ?: return null
        CoverCache.put(ctx, u, bytes)
        return bytes
    }

    private suspend fun fetchLyrics(item: Track, si: SourceInfo?): String? {
        if (si != null && si.has("lyric")) {
            runCatching {
                val v = call(item.platform, "lyric", JSONObject().put("musicInfo", musicInfo(item)), 20_000)
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
            ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000).toInt()
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
