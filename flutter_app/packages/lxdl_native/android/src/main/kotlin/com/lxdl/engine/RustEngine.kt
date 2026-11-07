package com.lxdl.engine

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 可选的 Rust(rquickjs) 脚本引擎。
 *
 * Rust 侧只负责跑 JS，网络/加密/日志通过 JNI 回调到这里：
 * `hostCall(op, a, b)` 和 `hostSend(name, json)`。
 */
class RustEngine(
    private val http: Http,
) : AutoCloseable {

    @Volatile
    private var handle: Long = 0

    fun start() {
        if (handle != 0L) return
        handle = nativeCreate(this)
        if (handle == 0L) throw IllegalStateException("rquickjs 初始化失败")
    }

    /** 返回脚本上报的 inited（含 sources）。 */
    fun loadScript(entry: SourceEntry, code: String, metaJson: String): JSONObject {
        start()
        val raw = nativeLoadScript(handle, entry.file, metaJson, code)
            ?: throw IllegalStateException("音源初始化失败")
        return JSONObject(raw)
    }

    fun call(source: String, action: String, info: JSONObject, timeoutMs: Int = 60_000): Any? {
        start()
        val raw = nativeCall(handle, source, action, info.toString(), timeoutMs)
            ?: throw IllegalStateException("音源响应超时")
        val o = JSONObject(raw)
        if (!o.optBoolean("ok")) throw IllegalStateException(o.optString("error", "音源返回错误"))
        return o.opt("value")
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) runCatching { nativeDestroy(h) }
    }

    // ---------------- Rust 回调（不要改名/改签名） ----------------

    @Suppress("unused")
    fun hostCall(op: String, a: String, b: String): String = when (op) {
        "log" -> {
            android.util.Log.d("lxdl-rs", "[$a] $b")
            ""
        }
        "fetch" -> runCatching { fetch(a, b) }.getOrElse { "" }
        "md5" -> Crypto.md5(a)
        "aes" -> {
            val data = a.substringBefore('|')
            val mode = a.substringAfter('|', "cbc")
            val key = b.substringBefore('|')
            val iv = b.substringAfter('|', "")
            runCatching { Crypto.aes(data, mode, key, iv) }.getOrElse { "\u0001ERR:${it.message}" }
        }
        "rsa" -> runCatching { Crypto.rsa(a, b) }.getOrElse { "\u0001ERR:${it.message}" }
        "random" -> Crypto.random(a.toIntOrNull() ?: 0)
        else -> ""
    }

    @Suppress("unused")
    fun hostSend(name: String, json: String) {
        // Rust 侧已经自己记录 inited/result，这里只留个日志口子
        android.util.Log.v("lxdl-rs", "send $name ${json.take(120)}")
    }

    // ---------------- HTTP（和 Engine.quickDoRequest 一致） ----------------

    private fun fetch(url: String, optsJson: String): String {
        if (!url.startsWith("http")) throw IllegalArgumentException("bad url")
        val o = JSONObject(optsJson)
        val method = o.optString("method", "GET")
        val timeout = o.optLong("timeout", 20_000)
        val headers = o.optJSONObject("headers")
        val bodyText = if (o.isNull("body")) null else o.optString("body")
        val bodyEnc = o.optString("bodyEncoding", "text")
        val bodyBytes = bodyText?.let {
            if (bodyEnc == "base64") Base64.decode(it, Base64.NO_WRAP) else it.toByteArray()
        }
        val builder = okhttp3.Request.Builder().url(url).header("User-Agent", UA)
        if (headers != null) {
            headers.keys().forEach { k -> builder.header(k, headers.optString(k)) }
        }
        val ctype = headers?.let { h ->
            h.keys().asSequence().firstOrNull { k -> k.equals("content-type", true) }
                ?.let { k -> h.optString(k) }
        }?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val media = ctype.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        val rb = bodyBytes?.toRequestBody(media)
        if (rb != null) builder.method(method, rb) else builder.method(method, null)
        val call = http.client.newBuilder()
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
        call.execute().use { r ->
            val bytes = r.body?.bytes() ?: ByteArray(0)
            val rt = r.header("Content-Type") ?: ""
            val binary = o.optString("responseType") == "arraybuffer" ||
                !(rt.startsWith("text/") || rt.contains("json") || rt.contains("xml"))
            val hdr = JSONObject()
            r.headers.forEach { (k, v) -> hdr.put(k.lowercase(), v) }
            return JSONObject().put("status", r.code).put("statusText", r.message)
                .put("headers", hdr).put("body", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("binary", binary).toString()
        }
    }

    // ---------------- JNI ----------------

    private external fun nativeCreate(host: RustEngine): Long
    private external fun nativeLoadScript(handle: Long, entry: String, meta: String, code: String): String?
    private external fun nativeCall(handle: Long, source: String, action: String, info: String, timeoutMs: Int): String?
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            runCatching { System.loadLibrary("lxdl_engine") }
        }
    }
}
