package com.lxdl.native

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flutter 插件：通道 + 引擎。
 *
 * 用插件而不是自定义 Activity，是因为 Activity 可能被模板重新生成覆盖，
 * 而插件的 onAttachedToEngine 一定会被自动注册流程调用。
 */
class LxdlPlugin : FlutterPlugin, MethodCallHandler, ActivityAware,
    PluginRegistry.ActivityResultListener {

    private lateinit var channel: MethodChannel
    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var activity: Activity? = null
    private var binding: ActivityPluginBinding? = null
    private var pendingPick: MethodChannel.Result? = null

    private var repo: Repo? = null
    private var http: Http? = null
    private var providers: Providers? = null
    private var engine: Engine? = null
    private var web: WebView? = null

    // ---------------- FlutterPlugin ----------------

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appCtx = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "lxdl/methods")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        teardown()
    }

    // ---------------- ActivityAware ----------------

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        this.binding = binding
        activity = binding.activity
        binding.addActivityResultListener(this)
        askLegacyStorage(binding.activity)
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
        teardown()
    }

    private fun askLegacyStorage(activity: Activity) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    private fun teardown() {
        web?.let { w ->
            runCatching { (w.parent as? ViewGroup)?.removeView(w) }
            runCatching { w.destroy() }
        }
        web = null
        engine = null
    }

    // ---------------- 懒初始化 ----------------

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

    private fun engineOrCreate(): Engine {
        engine?.let { return it }
        val act = activity ?: throw IllegalStateException("界面未就绪")
        val (r, p) = base()
        val w = WebView(act)
        w.isFocusable = false
        val root = act.findViewById<ViewGroup>(android.R.id.content)
            ?: throw IllegalStateException("找不到根视图")
        root.addView(w, ViewGroup.LayoutParams(1, 1))
        web = w
        val e = Engine(act, r, h(), w, p)
        e.onLog = { sendEvent("log", it) }
        e.start()
        engine = e
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
                r.cfg.lyrics = o.optBoolean("lyrics", r.cfg.lyrics)
                r.saveCfg()
                h().setProxy(r.cfg.proxy)
                reply(result, true)
            }
            "sources" -> {
                base()
                reply(result, sourcesJson().toString())
            }
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
            "search" -> scope.launch {
                runCatching {
                    base().second.search(
                        call.argument<String>("platform") ?: "wy",
                        call.argument<String>("keyword") ?: "",
                        call.argument<Boolean>("playlist") ?: false
                    )
                }.onSuccess { reply(result, tracksJson(it).toString()) }
                    .onFailure { fail(result, it) }
            }
            "parse" -> scope.launch {
                runCatching {
                    val (_, p) = base()
                    val platform = call.argument<String>("platform") ?: "wy"
                    val text = call.argument<String>("text") ?: ""
                    val t = p.parse(platform, text)
                    if (t.isPlaylist) p.playlistMeta(platform, t.id)
                    else runCatching { p.detail(platform, t.id) }
                        .getOrElse { Track(platform, t.id, t.id) }
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
            "downloads" -> reply(result, downloadsJson().toString())
            "startDownload" -> {
                val raw = call.argument<String>("track") ?: "{}"
                val t = runCatching { Track.from(JSONObject(raw)) }
                    .getOrElse { fail(result, it); return }
                val r = base().first
                r.cfg.quality = call.argument<String>("quality") ?: r.cfg.quality
                r.cfg.naming = call.argument<String>("naming") ?: r.cfg.naming
                r.cfg.outDir = call.argument<String>("outDir") ?: r.cfg.outDir
                r.cfg.lyrics = call.argument<Boolean>("lyrics") ?: r.cfg.lyrics
                r.saveCfg()
                startDownload(t)
                reply(result, true)
            }
            else -> result.notImplemented()
        }
    }

    // ---------------- 系统文件选择器 ----------------

    private fun pickSource(result: MethodChannel.Result) {
        val act = activity
        if (act == null) {
            fail(result, IllegalStateException("界面未就绪"))
            return
        }
        if (pendingPick != null) {
            fail(result, IllegalStateException("正在选择文件"))
            return
        }
        pendingPick = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/javascript", "text/javascript", "text/plain", "*/*"))
        }
        runCatching { act.startActivityForResult(intent, PICK_REQUEST) }
            .onFailure {
                pendingPick = null
                fail(result, it)
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != PICK_REQUEST) return false
        val result = pendingPick ?: return true
        pendingPick = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            fail(result, IllegalStateException("没有选择文件"))
            return true
        }
        scope.launch {
            runCatching {
                val text = appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: throw IllegalStateException("读不到文件")
                base().first.importSource(text, h())
            }.onSuccess { reply(result, sourcesJson().toString()) }
                .onFailure { fail(result, it) }
        }
        return true
    }

    // ---------------- 下载 ----------------

    private fun startDownload(t: Track) {
        scope.launch {
            val (r, p) = base()
            val songs = runCatching {
                if (t.isPlaylist) p.playlistTracks(t.platform, t.id) else listOf(t)
            }.getOrElse { e ->
                sendEvent("log", "展开歌单失败：${e.message}")
                return@launch
            }
            val e = runCatching { engineOrCreate() }.getOrElse { ex ->
                sendEvent("log", "引擎初始化失败：${ex.message}")
                return@launch
            }
            sendEvent("log", "开始下载 ${songs.size} 首")
            for ((idx, song) in songs.withIndex()) {
                val item = r.addItem(song)
                sendEvent("downloads")
                val label = "[${idx + 1}/${songs.size}]"
                runCatching {
                    e.downloadSong(
                        song,
                        onProgress = { pr, done, total ->
                            r.progress(item.key, done, total)
                            sendEvent("progress", item.key, pr)
                        },
                        onStatus = { s -> sendEvent("log", "$label $s") }
                    )
                }.onSuccess {
                    r.finish(item.key, it)
                    sendEvent("log", "$label 完成：$it")
                }.onFailure {
                    r.fail(item.key, it.message ?: "失败")
                    sendEvent("log", "$label 失败：${it.message}")
                }
                sendEvent("downloads")
            }
            sendEvent("log", "全部结束")
        }
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
    }
}
