package com.lxdl.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FlutterActivity() {
    private lateinit var repo: Repo
    private lateinit var http: Http
    private lateinit var providers: Providers
    private lateinit var engine: Engine
    private lateinit var web: WebView
    private lateinit var channel: MethodChannel
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        repo = Repo(this)
        http = Http()
        http.setProxy(repo.cfg.proxy)
        providers = Providers(http, repo)

        web = WebView(this)
        val root = findViewById<ViewGroup>(android.R.id.content)
        root.addView(web, ViewGroup.LayoutParams(1, 1))
        engine = Engine(this, repo, http, web, providers)
        engine.onLog = { sendEvent("log", it) }
        engine.start()

        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "lxdl/methods")
        channel.setMethodCallHandler { call, result -> handle(call, result) }
    }

    override fun onDestroy() {
        runCatching { findViewById<ViewGroup>(android.R.id.content).removeView(web) }
        runCatching { web.destroy() }
        super.onDestroy()
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
        runOnUiThread {
            runCatching { channel.invokeMethod("event", o.toString()) }
        }
    }

    private fun reply(result: MethodChannel.Result, value: Any?) {
        runOnUiThread { result.success(value) }
    }

    private fun fail(result: MethodChannel.Result, e: Throwable) {
        runOnUiThread { result.error("lxdl", e.message ?: e.toString(), null) }
    }

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "config" -> reply(result, repo.cfg.toJson().toString())
            "setConfig" -> {
                val o = JSONObject(call.argument<String>("json") ?: "{}")
                repo.cfg.mirror = o.optString("mirror", repo.cfg.mirror)
                repo.cfg.proxy = o.optString("proxy", repo.cfg.proxy)
                repo.cfg.quality = o.optString("quality", repo.cfg.quality)
                repo.cfg.naming = o.optString("naming", repo.cfg.naming)
                repo.cfg.outDir = o.optString("outDir", repo.cfg.outDir)
                repo.cfg.lyrics = o.optBoolean("lyrics", repo.cfg.lyrics)
                repo.saveCfg()
                http.setProxy(repo.cfg.proxy)
                reply(result, true)
            }
            "sources" -> reply(result, sourcesJson().toString())
            "importSource" -> scope.launch {
                runCatching { repo.importSource(call.argument<String>("text") ?: "", http) }
                    .onSuccess { reply(result, sourcesJson().toString()) }
                    .onFailure { fail(result, it) }
            }
            "deleteSource" -> {
                repo.deleteSource(call.argument<String>("name") ?: "")
                reply(result, sourcesJson().toString())
            }
            "moveSource" -> {
                repo.moveSource(call.argument<String>("name") ?: "", call.argument<Int>("delta") ?: 0)
                reply(result, sourcesJson().toString())
            }
            "search" -> scope.launch {
                runCatching {
                    providers.search(
                        call.argument<String>("platform") ?: "wy",
                        call.argument<String>("keyword") ?: "",
                        call.argument<Boolean>("playlist") ?: false
                    )
                }.onSuccess { list -> reply(result, tracksJson(list).toString()) }
                    .onFailure { fail(result, it) }
            }
            "parse" -> scope.launch {
                runCatching {
                    val platform = call.argument<String>("platform") ?: "wy"
                    val text = call.argument<String>("text") ?: ""
                    val t = providers.parse(platform, text)
                    if (t.isPlaylist) providers.playlistMeta(platform, t.id)
                    else runCatching { providers.detail(platform, t.id) }
                        .getOrElse { Track(platform, t.id, t.id) }
                }.onSuccess { reply(result, it.toJson().toString()) }
                    .onFailure { fail(result, it) }
            }
            "detail" -> scope.launch {
                val platform = call.argument<String>("platform") ?: "wy"
                val id = call.argument<String>("id") ?: ""
                runCatching { providers.detail(platform, id) }
                    .onSuccess { reply(result, it.toJson().toString()) }
                    .onFailure { fail(result, it) }
            }
            "downloads" -> reply(result, downloadsJson().toString())
            "startDownload" -> {
                val t = Track.from(JSONObject(call.argument<String>("track") ?: "{}"))
                repo.cfg.quality = call.argument<String>("quality") ?: repo.cfg.quality
                repo.cfg.naming = call.argument<String>("naming") ?: repo.cfg.naming
                repo.cfg.outDir = call.argument<String>("outDir") ?: repo.cfg.outDir
                repo.cfg.lyrics = call.argument<Boolean>("lyrics") ?: repo.cfg.lyrics
                repo.saveCfg()
                startDownload(t)
                reply(result, true)
            }
            else -> result.notImplemented()
        }
    }

    private fun startDownload(t: Track) {
        scope.launch {
            val songs = runCatching {
                if (t.isPlaylist) providers.playlistTracks(t.platform, t.id) else listOf(t)
            }.getOrElse { e ->
                sendEvent("log", "展开歌单失败：${e.message}")
                return@launch
            }
            sendEvent("log", "开始下载 ${songs.size} 首")
            for ((idx, song) in songs.withIndex()) {
                val item = repo.addItem(song)
                sendEvent("downloads")
                val label = "[${idx + 1}/${songs.size}]"
                val path = runCatching {
                    engine.downloadSong(
                        song,
                        onProgress = { p, done, total ->
                            repo.progress(item.key, done, total)
                            sendEvent("progress", item.key, p)
                        },
                        onStatus = { s -> sendEvent("log", "$label $s") }
                    )
                }
                path.onSuccess {
                    repo.finish(item.key, it)
                    sendEvent("log", "$label 完成：$it")
                }.onFailure {
                    repo.fail(item.key, it.message ?: "失败")
                    sendEvent("log", "$label 失败：${it.message}")
                }
                sendEvent("downloads")
            }
            sendEvent("log", "全部结束")
        }
    }

    private fun sourcesJson(): JSONArray {
        val arr = JSONArray()
        repo.listSources().forEach { s ->
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
        repo.listDownloads().forEach { arr.put(it.toJson()) }
        return arr
    }
}
