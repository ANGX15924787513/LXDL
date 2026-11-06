package com.lxdl.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

class Http {
    @Volatile private var _proxy: String = ""
    @Volatile private var _client: OkHttpClient = build("")

    fun setProxy(proxy: String) {
        if (proxy == _proxy) return
        _proxy = proxy
        _client = build(proxy)
    }

    val client: OkHttpClient get() = _client

    private fun build(proxy: String): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .writeTimeout(40, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
        val p = parseProxy(proxy)
        if (p != null) b.proxy(p)
        return b.build()
    }

    private fun parseProxy(raw: String): Proxy? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val clean = s.removePrefix("http://").removePrefix("https://")
        val parts = clean.split(":", limit = 2)
        val host = parts[0]
        val port = parts.getOrNull(1)?.toIntOrNull() ?: 7890
        return try { Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port)) } catch (_: Exception) { null }
    }

    fun execute(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000,
        body: ByteArray? = null,
        method: String = "GET"
    ): Response {
        val req = Request.Builder().url(url).header("User-Agent", UA)
        headers.forEach { (k, v) -> req.header(k, v) }
        val c = _client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val rb: RequestBody? = body?.toRequestBody("application/octet-stream".toMediaType())
        if (rb != null) req.method(method, rb) else req.method(method, null)
        return c.newCall(req.build()).execute()
    }

    fun json(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 20_000): org.json.JSONObject {
        execute(url, headers, timeoutMs).use { r ->
            val text = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return org.json.JSONObject(text)
        }
    }

    fun text(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 20_000): String {
        execute(url, headers, timeoutMs).use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.string() ?: ""
        }
    }

    fun bytes(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Long = 30_000): ByteArray {
        execute(url, headers, timeoutMs).use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return r.body?.bytes() ?: throw IllegalStateException("empty body")
        }
    }

    fun download(url: String, dest: File, onProgress: (Long, Long?) -> Unit): String {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        val call = _client.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
            .newCall(req)
        call.execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val body = r.body ?: throw IllegalStateException("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            val ctype = r.header("Content-Type") ?: ""
            dest.parentFile?.mkdirs()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 200) {
                            last = now
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
            return ctype
        }
    }
}
