package com.lxdl.engine

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import android.view.ViewGroup
import android.webkit.WebView
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class LxdlPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var pendingPick: MethodChannel.Result? = null
    private var pendingDir: MethodChannel.Result? = null

    private var repo: Repo? = null
    private var http: Http? = null
    private var providers: Providers? = null
    private val engines = mutableListOf<Engine>()
    private var interactive: Engine? = null
    private val controls = ConcurrentHashMap<Int, DownloadControl>()
    private var player: MediaPlayer? = null
    private var previewTicker: Job? = null
    private var pendingShare: String? = null

    // ---------------- 生命周期 ----------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appCtx = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "lxdl/methods")
        channel.setMethodCallHandler(this)
        Notifier.ensureChannel(appCtx)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        teardownEngines()
        Notifier.stop(appCtx)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        activity = binding.activity
        binding.addActivityResultListener(this)
        binding.addOnNewIntentListener { intent ->
            handleShare(intent)
            false
        }
        handleShare(binding.activity.intent)
        askPermissions(binding.activity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        binding?.removeActivityResultListener(this)
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        binding?.removeActivityResultListener(this)
        binding = null
        activity = null
        teardownEngines()
    }

    private fun askPermissions(activity: Activity) {
        val need = mutableListOf<String>()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) ActivityCompat.requestPermissions(activity, need.toTypedArray(), 1)
    }

    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_VIEW) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.dataString
            ?: return
        if (text.isBlank()) return
        pendingShare = text
        main.post {
            runCatching {
                channel.invokeMethod("event", JSONObject().put("type", "share").put("text", text).toString())
            }
        }
    }

    private fun teardownEngines() {
        synchronized(engines) {
            engines.forEach { e -> runCatching { e.detach() } }
            engines.clear()
            runCatching { interactive?.detach() }
            interactive = null
        }
    }

    // ---------------- 基础对象 ----------------

    private fun base(): Pair<Repo, Providers> {
        val r = repo ?: Repo(appCtx).also { repo = it }
        val p = providers ?: run {
            val client = Http().also { it.setProxy(r.cfg.proxy) }
            http = client
            Providers(client, r).also { providers = it }
        }
        return r to p
    }

    private fun h(): Http {
        http?.let { return it }
        base()
        return http!!
    }

    /**
     * 预览/测速/复制直链用的独立引擎，避免和下载任务抢同一个脚本上下文。
     * WebView 只能在有 Looper 的线程（主线程）创建，所以这里强制切主线程。
     */
    private suspend fun interactiveEngine(): Engine = withContext(Dispatchers.Main.immediate) {
        synchronized(engines) {
            interactive ?: createEngine().also { interactive = it }
        }
    }

    private suspend fun engineAt(index: Int): Engine = withContext(Dispatchers.Main.immediate) {
        synchronized(engines) {
            while (engines.size <= index) engines.add(createEngine())
            engines[index]
        }
    }

    private fun createEngine(): Engine {
        val act = activity ?: throw IllegalStateException("界面未就绪")
        val (r, p) = base()
        val web = WebView(act)
        web.isFocusable = false
        val root = act.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("找不到根视图")
        root.addView(web, ViewGroup.LayoutParams(1, 1))
        val e = Engine(act, r, h(), web, p)
        e.onLog = { sendEvent("log", it) }
        e.start()
        return e
    }

    private fun sendEvent(type: String, vararg data: Any?) {
        val o = JSONObject().put("type", type)
        when (type) {
            "log" -> o.put("text", data.getOrNull(0)?.toString() ?: "")
            "progress" -> {
                o.put("key", data.getOrNull(0))
                o.put("value", data.getOrNull(1))
            }
            "share" -> o.put("text", data.getOrNull(0)?.toString() ?: "")
            "preview" -> {
                o.put("pos", data.getOrNull(0))
                o.put("dur", data.getOrNull(1))
                o.put("playing", data.getOrNull(2))
            }
        }
        main.post { runCatching { channel.invokeMethod("event", o.toString()) } }
    }

    private fun reply(result: MethodChannel.Result, value: Any?) {
        main.post { runCatching { result.success(value) } }
    }

    private fun fail(result: MethodChannel.Result, e: Throwable) {
        main.post { runCatching { result.error("lxdl", e.message ?: e.toString(), null) } }
    }

    // ---------------- 方法分发 ----------------

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "ping" -> reply(result, true)
            "takeShare" -> {
                val t = pendingShare
                pendingShare = null
                reply(result, t)
            }
            "config" -> reply(result, runCatching { base().first.cfg.toJson().toString() }
                .getOrElse { fail(result, it); return })
            "setConfig" -> {
                val o = runCatching { JSONObject(call.argument<String>("json") ?: "{}") }
                    .getOrElse { fail(result, it); return }
                val r = base().first
                r.cfg.mirror = o.optString("mirror", r.cfg.mirror)
                r.cfg.proxy = o.optString("proxy", r.cfg.proxy)
                r.cfg.quality = o.optString("quality", r.cfg.quality)
                r.cfg.naming = o.optString("naming", r.cfg.naming)
                r.cfg.outDir = o.optString("outDir", r.cfg.outDir)
                r.cfg.theme = o.optString("theme", r.cfg.theme)
                r.cfg.lyrics = o.optBoolean("lyrics", r.cfg.lyrics)
                r.cfg.lrcSidecar = o.optBoolean("lrcSidecar", r.cfg.lrcSidecar)
                r.cfg.concurrency = o.optInt("concurrency", r.cfg.concurrency).coerceIn(1, 8)
                r.cfg.wifiOnly = o.optBoolean("wifiOnly", r.cfg.wifiOnly)
                r.cfg.outTree = o.optString("outTree", r.cfg.outTree)
                r.saveCfg()
                h().setProxy(r.cfg.proxy)
                reply(result, true)
            }
            "sources" -> { base(); reply(result, sourcesJson().toString()) }
            "importSource" -> {
                val text = call.argument<String>("text") ?: ""
                scope.launch {
                    runCatching { base().first.importSource(text, h()) }
                        .onSuccess { reply(result, sourcesJson().toString()) }
                        .onFailure { fail(result, it) }
                }
            }
            "pickSource" -> pickSource(result)
            "deleteSource" -> {
                base().first.deleteSource(call.argument<String>("name") ?: "")
                reply(result, sourcesJson().toString())
            }
            "moveSource" -> {
                base().first.moveSource(
                    call.argument<String>("name") ?: "",
                    call.argument<Int>("delta") ?: 0
                )
                reply(result, sourcesJson().toString())
            }
            "probeSources" -> scope.launch {
                val list = JSONArray()
                runCatching { base() }.getOrElse { fail(result, it); return@launch }
                repo!!.listSources().forEach { entry ->
                    val start = System.currentTimeMillis()
                    val o = JSONObject().put("name", entry.name)
                    runCatching { interactiveEngine().loadScript(entry) }
                        .onSuccess { infos ->
                            o.put("ok", true)
                            o.put("ms", System.currentTimeMillis() - start)
                            o.put("platforms", JSONArray(infos.map { it.key }))
                        }
                        .onFailure { e ->
                            o.put("ok", false)
                            o.put("ms", System.currentTimeMillis() - start)
                            o.put("platforms", JSONArray())
                            o.put("error", e.message ?: "")
                        }
                    list.put(o)
                }
                reply(result, list.toString())
            }
            "search" -> scope.launch {
                runCatching {
                    base().second.search(
                        call.argument<String>("platform") ?: "wy",
                        call.argument<String>("keyword") ?: "",
                        call.argument<Boolean>("playlist") ?: false
                    )
                }.onSuccess {
                    repo?.rememberHistory(call.argument<String>("keyword") ?: "")
                    reply(result, tracksJson(it).toString())
                }.onFailure { fail(result, it) }
            }
            "parse" -> scope.launch {
                runCatching {
                    val (_, p) = base()
                    val platform = call.argument<String>("platform") ?: "wy"
                    val text = call.argument<String>("text") ?: ""
                    val t = p.parse(platform, text)
                    repo?.rememberHistory(text)
                    // 深链接可能自带平台（netease:// 这类），以解析结果为准
                    if (t.isPlaylist) p.playlistMeta(t.platform, t.id)
                    else runCatching { p.detail(t.platform, t.id) }
                        .getOrElse { Track(t.platform, t.id, t.id) }
                }.onSuccess { reply(result, it.toJson().toString()) }
                    .onFailure { fail(result, it) }
            }
            "detail" -> scope.launch {
                runCatching {
                    base().second.detail(
                        call.argument<String>("platform") ?: "wy",
                        call.argument<String>("id") ?: ""
                    )
                }.onSuccess { reply(result, it.toJson().toString()) }
                    .onFailure { fail(result, it) }
            }
            "playlistTracks" -> scope.launch {
                runCatching {
                    base().second.playlistTracks(
                        call.argument<String>("platform") ?: "wy",
                        call.argument<String>("id") ?: ""
                    )
                }.onSuccess { reply(result, tracksJson(it).toString()) }
                    .onFailure { fail(result, it) }
            }
            "downloads" -> reply(result, downloadsJson().toString())
            "startDownload" -> {
                val raw = call.argument<String>("track") ?: "{}"
                val t = runCatching { Track.from(JSONObject(raw)) }
                    .getOrElse { fail(result, it); return }
                applyRunOptions(call)
                scope.launch { startDownload(listOf(t)) }
                reply(result, true)
            }
            "startTracks" -> {
                val raw = call.argument<String>("tracks") ?: "[]"
                val list = runCatching {
                    val arr = JSONArray(raw)
                    (0 until arr.length()).mapNotNull {
                        arr.optJSONObject(it)?.let { o -> Track.from(o) }
                    }
                }.getOrElse { fail(result, it); return }
                applyRunOptions(call)
                scope.launch { startDownload(list) }
                reply(result, true)
            }
            "deleteDownload" -> {
                base().first.removeItem(call.argument<Int>("key") ?: -1)
                reply(result, downloadsJson().toString())
            }
            "pauseDownload" -> {
                val key = call.argument<Int>("key") ?: -1
                controls[key]?.paused = true
                base().first.setStatus(key, "paused")
                reply(result, true)
            }
            "resumeDownload" -> {
                val key = call.argument<Int>("key") ?: -1
                controls[key]?.paused = false
                base().first.setStatus(key, "downloading")
                reply(result, true)
            }
            "cancelDownload" -> {
                val key = call.argument<Int>("key") ?: -1
                controls[key]?.let {
                    it.cancelled = true
                    it.paused = false
                    runCatching { it.call?.cancel() }
                    synchronized(it.calls) { it.calls.toList() }.forEach { c ->
                        runCatching { c.cancel() }
                    }
                }
                base().first.fail(key, "已取消")
                reply(result, true)
            }
            "resolveUrl" -> {
                val raw = call.argument<String>("track") ?: "{}"
                val quality = call.argument<String>("quality") ?: "320k"
                scope.launch {
                    runCatching {
                        val t = Track.from(JSONObject(raw))
                        interactiveEngine().resolveUrl(t, quality)
                    }.onSuccess { reply(result, it) }
                        .onFailure { fail(result, it) }
                }
            }
            "pickOutDir" -> pickOutDir(result)
            "openFile" -> {
                val uri = call.argument<String>("uri") ?: ""
                val path = call.argument<String>("path") ?: ""
                reply(result, openFile(uri, path))
            }
            "shareFile" -> {
                val uri = call.argument<String>("uri") ?: ""
                reply(result, shareFile(uri))
            }
            "retag" -> {
                val key = call.argument<Int>("key") ?: -1
                scope.launch {
                    runCatching {
                        retag(
                            key,
                            call.argument<String>("title") ?: "",
                            call.argument<String>("artist") ?: "",
                            call.argument<String>("album") ?: ""
                        )
                    }.onSuccess { reply(result, it) }
                        .onFailure { fail(result, it) }
                }
            }
            "warmUp" -> {
                // 提前把 WebView + 音源脚本跑起来，点下载时不用再等引擎冷启动
                scope.launch {
                    runCatching {
                        val entries = base().first.listSources()
                        val first = entries.firstOrNull() ?: return@runCatching
                        runCatching { interactiveEngine().loadScript(first) }
                        runCatching { engineAt(0).loadScript(first) }
                    }
                    reply(result, true)
                }
            }
            "cover" -> {
                val url = call.argument<String>("url") ?: ""
                scope.launch {
                    runCatching { coverPath(url) }
                        .onSuccess { reply(result, it) }
                        .onFailure { fail(result, it) }
                }
            }
            "playPreview" -> {
                val url = call.argument<String>("url") ?: ""
                val platform = call.argument<String>("platform") ?: ""
                scope.launch {
                    runCatching { playPreview(url, platform) }
                        .onSuccess { reply(result, it) }
                        .onFailure { fail(result, it) }
                }
            }
            "seekPreview" -> {
                val ms = (call.argument<Int>("ms") ?: 0).toLong()
                reply(result, seekPreview(ms))
            }
            "stopPreview" -> {
                stopPreview()
                reply(result, true)
            }
            else -> result.notImplemented()
        }
    }

    private fun applyRunOptions(call: MethodCall) {
        val r = base().first
        r.cfg.quality = call.argument<String>("quality") ?: r.cfg.quality
        r.cfg.naming = call.argument<String>("naming") ?: r.cfg.naming
        r.cfg.outDir = call.argument<String>("outDir") ?: r.cfg.outDir
        r.cfg.lyrics = call.argument<Boolean>("lyrics") ?: r.cfg.lyrics
        r.saveCfg()
    }

    // ---------------- 选择器 ----------------

    private fun pickSource(result: MethodChannel.Result) {
        val act = activity
        if (act == null) { fail(result, IllegalStateException("界面未就绪")); return }
        if (pendingPick != null) { fail(result, IllegalStateException("正在选择文件")); return }
        pendingPick = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/javascript", "text/javascript", "text/plain")
            )
        }
        runCatching { act.startActivityForResult(intent, PICK_REQUEST) }
            .onFailure { pendingPick = null; fail(result, it) }
    }

    private fun pickOutDir(result: MethodChannel.Result) {
        val act = activity
        if (act == null) { fail(result, IllegalStateException("界面未就绪")); return }
        if (pendingDir != null) { fail(result, IllegalStateException("正在选择目录")); return }
        pendingDir = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
        runCatching { act.startActivityForResult(intent, DIR_REQUEST) }
            .onFailure { pendingDir = null; fail(result, it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            PICK_REQUEST -> {
                val result = pendingPick ?: return true
                pendingPick = null
                val uri = data?.data
                if (resultCode != Activity.RESULT_OK || uri == null) {
                    fail(result, IllegalStateException("没有选择文件")); return true
                }
                scope.launch {
                    runCatching {
                        val text = appCtx.contentResolver.openInputStream(uri)?.use {
                            it.bufferedReader().readText()
                        } ?: throw IllegalStateException("读不到文件")
                        base().first.importSource(text, h())
                    }.onSuccess { reply(result, sourcesJson().toString()) }
                        .onFailure { fail(result, it) }
                }
                return true
            }
            DIR_REQUEST -> {
                val result = pendingDir ?: return true
                pendingDir = null
                val uri = data?.data
                if (resultCode != Activity.RESULT_OK || uri == null) {
                    fail(result, IllegalStateException("没有选择目录")); return true
                }
                runCatching {
                    appCtx.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                base().first.cfg.outTree = uri.toString()
                base().first.saveCfg()
                reply(result, uri.toString())
                return true
            }
        }
        return false
    }

    // ---------------- 下载 ----------------

    private fun startDownload(tracks: List<Track>) {
        val r = base().first
        if (tracks.isEmpty()) return
        if (r.cfg.wifiOnly && !isWifi()) {
            sendEvent("log", "当前不是 Wi-Fi，已跳过（可关闭「仅 Wi-Fi」）")
            return
        }
        val n = r.cfg.concurrency.coerceIn(1, 8)
        // 先把任务全部登记出来，列表立刻可见、随时可取消
        val items = tracks.map { r.addItem(it) }
        val controlsLocal = items.associate { it.key to DownloadControl() }
        controlsLocal.forEach { (k, c) -> controls[k] = c }
        sendEvent("downloads")

        // 槽位池：一个槽对应一个 WebView 引擎，保证同时在跑的任务不会共用同一个脚本上下文
        val slots = Channel<Int>(n)
        repeat(n) { runCatching { slots.trySend(it) } }

        // 批量时另开一条车道预取直链：链接比下载快得多，下载线程永不等链接
        val prefetch = tracks.size > 2
        val ready = ConcurrentHashMap<Int, CompletableDeferred<Prepared?>>()
        val prefetchErrors = ConcurrentHashMap<Int, String>()
        if (prefetch) {
            items.indices.forEach { ready[it] = CompletableDeferred() }
            scope.launch {
                runCatching {
                    val eng = interactiveEngine()
                    tracks.forEachIndexed { i, t ->
                        val c = controlsLocal[items[i].key]
                        if (c?.cancelled == true) {
                            ready[i]?.complete(null)
                            return@forEachIndexed
                        }
                        val p = try {
                            eng.prepare(t)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            prefetchErrors[i] = e.message ?: "取链失败"
                            null
                        }
                        ready[i]?.complete(p)
                    }
                }.onFailure {
                    items.indices.forEach { i -> ready[i]?.complete(null) }
                }
            }
        }

        tracks.forEachIndexed { i, t ->
            scope.launch {
                val worker = slots.receive()
                try {
                    val prepared = if (prefetch) ready[i]?.await() else null
                    downloadOne(
                        t, items[i], controlsLocal[items[i].key]!!, worker,
                        prepared, prefetchErrors[i]
                    )
                } finally {
                    runCatching { slots.trySend(worker) }
                }
            }
        }
    }

    private fun isWifi(): Boolean = runCatching {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(true)

    private suspend fun downloadOne(
        t: Track,
        item: Item,
        control: DownloadControl,
        worker: Int,
        prepared: Prepared?,
        prefetchError: String?,
    ) {
        val (r, _) = base()
        if (control.cancelled) {
            r.fail(item.key, "已取消")
            controls.remove(item.key)
            sendEvent("downloads")
            return
        }
        if (prepared == null && prefetchError != null) {
            // 预取已经失败过了，别再花几十秒重试一次
            r.fail(item.key, prefetchError)
            sendEvent("log", "失败：$prefetchError")
            controls.remove(item.key)
            sendEvent("downloads")
            if (controls.isEmpty()) Notifier.stop(appCtx)
            return
        }
        runCatching { Notifier.start(appCtx, t.title) }
        var lastNotify = 0L
        try {
            val engine = engineAt(worker)
            val onProgress: (Double, Long, Long?) -> Unit = { pr, done, total ->
                r.progress(item.key, done, total)
                sendEvent("progress", item.key, pr)
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1200) {
                    lastNotify = now
                    Notifier.update(appCtx, t.title, done, total ?: 0)
                }
            }
            val onStatus: (String) -> Unit = { s -> sendEvent("log", s) }
            val out = if (prepared != null) {
                engine.downloadPrepared(t, item.key, control, prepared, onProgress, onStatus)
            } else {
                // 预取失败/单曲下载：回退到现场解析
                engine.downloadSong(t, item.key, control, onProgress, onStatus)
            }
            r.finish(item.key, out.display, out.uri)
            Notifier.done(appCtx, t.title, out.uri, out.display)
            sendEvent("log", "完成：${out.display}")
        } catch (e: Cancelled) {
            r.fail(item.key, "已取消")
        } catch (e: Throwable) {
            r.fail(item.key, e.message ?: "失败")
            sendEvent("log", "失败：${e.message}")
        } finally {
            controls.remove(item.key)
            sendEvent("downloads")
            if (controls.isEmpty()) Notifier.stop(appCtx)
        }
    }

    // ---------------- 播放 / 文件 ----------------

    /** 界面要封面：先查磁盘缓存；没有就下缩略图，失败再退回原图。 */
    private fun coverPath(url: String): String? {
        if (url.isBlank()) return null
        CoverCache.get(appCtx, url, "thumb")?.let { return it.absolutePath }
        val headers = mapOf("User-Agent" to UA, "Referer" to "https://music.163.com/")
        val thumb = thumbUrl(url)
        val bytes = runCatching { h().bytes(thumb, headers, 20_000) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { h().bytes(url, headers, 20_000) }.getOrNull()
        if (bytes == null || bytes.isEmpty()) return null
        // 统一存在原 URL 名下，标签写入那边也能命中
        return CoverCache.put(appCtx, url, bytes, "thumb")?.absolutePath
    }

    /** 大图换成 CDN 的小图，界面显示够用还快很多。 */
    private fun thumbUrl(url: String): String = when {
        url.contains("music.126.net") -> url.substringBefore('?') + "?param=240y240"
        url.contains("y.gtimg.cn") -> url.replace("R500x500", "R300x300")
            .replace("R800x800", "R300x300")
        else -> url
    }

    /** 试听：带 Referer 交给 MediaPlayer，403/解码失败会真的报错而不是静默。 */
    private suspend fun playPreview(url: String, platform: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (url.isBlank()) throw IllegalStateException("没有可播放的直链")
            val act = activity ?: throw IllegalStateException("界面未就绪")
            val done = CompletableDeferred<Boolean>()
            runCatching { player?.release() }
            player = MediaPlayer().apply {
                setOnPreparedListener {
                    it.start()
                    startPreviewTicker()
                    if (!done.isCompleted) done.complete(true)
                }
                setOnCompletionListener { mp ->
                    if (!done.isCompleted) done.complete(true)
                    runCatching {
                        sendEvent("preview", mp.duration.toLong(), mp.duration.toLong(), false)
                    }
                }
                setOnErrorListener { _, what, extra ->
                    if (!done.isCompleted) {
                        done.completeExceptionally(IllegalStateException("播放器报错 $what/$extra"))
                    }
                    true
                }
                try {
                    if (url.startsWith("http")) {
                        setDataSource(act, Uri.parse(url), previewHeaders(platform))
                    } else {
                        setDataSource(url)
                    }
                    prepareAsync()
                } catch (e: Throwable) {
                    if (!done.isCompleted) done.completeExceptionally(e)
                }
            }
            val ok = withTimeoutOrNull(20_000) { done.await() }
            if (ok == null) {
                runCatching { player?.release() }
                player = null
                throw IllegalStateException("试听超时（CDN 没响应）")
            }
            ok
        }

    private fun previewHeaders(platform: String): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Referer" to refererOf(platform)
    )

    private fun startPreviewTicker() {
        previewTicker?.cancel()
        previewTicker = scope.launch {
            while (isActive) {
                val snapshot = withContext(Dispatchers.Main.immediate) {
                    val p = player
                    if (p == null) null
                    else runCatching {
                        Triple(p.currentPosition.toLong(), p.duration.toLong(), p.isPlaying)
                    }.getOrNull()
                } ?: break
                sendEvent("preview", snapshot.first, snapshot.second, snapshot.third)
                if (!snapshot.third && snapshot.second > 0 && snapshot.first >= snapshot.second - 300) break
                delay(500)
            }
        }
    }

    private fun seekPreview(ms: Long): Boolean = runCatching {
        main.post {
            runCatching { player?.seekTo(ms.toInt().coerceAtLeast(0)) }
        }
        true
    }.getOrDefault(false)

    private fun stopPreview() {
        previewTicker?.cancel()
        previewTicker = null
        main.post {
            runCatching { player?.release() }
            player = null
            sendEvent("preview", 0L, 0L, false)
        }
    }

    private fun openFile(uri: String, path: String): Boolean {
        val act = activity ?: return false
        if (uri.isBlank()) return false
        return runCatching {
            val intent = if (uri.isNotBlank()) {
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(File(path)), "audio/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            act.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun shareFile(uri: String): Boolean {
        val act = activity ?: return false
        if (uri.isBlank()) return false
        return runCatching {
            act.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "audio/*"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "分享"
                )
            )
            true
        }.getOrDefault(false)
    }

    private fun retag(key: Int, title: String, artist: String, album: String): Boolean {
        val r = base().first
        val item = r.item(key) ?: return false
        val tmp = File(r.cacheDir, "retag-$key")
        if (item.uri.isNotBlank()) {
            appCtx.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return false
        } else {
            File(item.path).takeIf { it.isFile }?.copyTo(tmp, overwrite = true) ?: return false
        }
        val err = Tagger.retag(tmp, title, artist, album)
        if (err != null) {
            tmp.delete()
            throw IllegalStateException(err)
        }
        if (item.uri.isNotBlank()) {
            appCtx.contentResolver.openOutputStream(Uri.parse(item.uri), "wt")?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("写不回文件")
        } else {
            tmp.copyTo(File(item.path), overwrite = true)
        }
        tmp.delete()
        r.updateMeta(key, title, artist, album)
        sendEvent("downloads")
        return true
    }

    // ---------------- JSON ----------------

    private fun sourcesJson(): JSONArray {
        val arr = JSONArray()
        repo?.listSources()?.forEach { s ->
            arr.put(
                JSONObject().put("name", s.name).put("file", s.file)
                    .put("version", s.version).put("author", s.author)
            )
        }
        return arr
    }

    private fun tracksJson(list: List<Track>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr
    }

    private fun downloadsJson(): JSONArray {
        val arr = JSONArray()
        repo?.listDownloads()?.forEach { arr.put(it.toJson()) }
        return arr
    }

    companion object {
        private const val PICK_REQUEST = 0x4C58
        private const val DIR_REQUEST = 0x4C59
    }
}
