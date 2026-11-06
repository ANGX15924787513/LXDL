package com.lxdl.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 多连接分段下载（FluxDown 那种思路的极简版）：
 * 先探一次 Range 看服务器支不支持分段，支持就把文件切成若干块，
 * 由 [connections] 个 worker 抢着下（块小、动态抢占，慢连接不会拖住整个文件）。
 * 暂停/取消靠轮询 DownloadControl；中断后用 .part + .part.json 续传已完成的块。
 * 服务器不支持 Range 时自动退回单连接下载。
 */
internal class SegmentDownloader(
    private val http: Http,
    private val connections: Int = 4,
) {
    private val minChunk = 1L * 1024 * 1024
    private val maxChunk = 8L * 1024 * 1024

    suspend fun download(
        url: String,
        dest: File,
        control: DownloadControl,
        headers: Map<String, String>,
        onProgress: (Long, Long?) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val probe = probe(url, headers)
        if (probe == null || probe.first < 2 * minChunk) {
            // 不支持分段 / 文件太小：单连接（自带 .part 断点续传）
            return@withContext http.download(url, dest, control, headers, onProgress)
        }
        val total = probe.first
        val ctype = probe.second
        val part = File(dest.absolutePath + ".part")
        val metaFile = File(dest.absolutePath + ".part.json")
        // 大文件多给几条连接，小文件保持适中免得把 CDN 打限流
        val conns = if (total >= 64L * 1024 * 1024) maxOf(connections, 6) else connections
        val chunk = (total / (conns * 8)).coerceIn(minChunk, maxChunk)
        val count = ((total + chunk - 1) / chunk).toInt()
        val urlKey = Crypto.md5(url)

        var done = BooleanArray(count)
        var reusable = part.isFile && part.length() == total
        if (reusable) {
            runCatching {
                val o = JSONObject(metaFile.readText())
                if (o.optLong("total") == total &&
                    o.optLong("chunk") == chunk &&
                    o.optString("url") == urlKey
                ) {
                    val arr = o.optJSONArray("done") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val idx = arr.optInt(i, -1)
                        if (idx in 0 until count) done[idx] = true
                    }
                } else {
                    reusable = false
                }
            }.onFailure { reusable = false }
        }
        if (!reusable) {
            RandomAccessFile(part, "rw").use { it.setLength(total) }
            done = BooleanArray(count)
            metaFile.delete()
        }

        fun lengthOf(idx: Int): Long {
            val s = idx * chunk
            return minOf(total, s + chunk) - s
        }

        val pending = (0 until count).filter { !done[it] }
        if (pending.isEmpty()) {
            finish(part, dest, metaFile)
            return@withContext ctype
        }

        val completedBytes = AtomicLong(total - pending.sumOf { lengthOf(it) })
        val cursor = AtomicInteger(0)
        val lastReport = AtomicLong(0L)
        fun report(force: Boolean) {
            val now = System.currentTimeMillis()
            val last = lastReport.get()
            if (!force && now - last <= 200) return
            if (!force && !lastReport.compareAndSet(last, now)) return
            lastReport.set(now)
            onProgress(completedBytes.get(), total)
        }

        try {
            coroutineScope {
                val lanes = conns.coerceIn(1, pending.size)
                (0 until lanes).map {
                    async(Dispatchers.IO) {
                        RandomAccessFile(part, "rw").use { raf ->
                            while (true) {
                                if (control.cancelled) throw Cancelled()
                                val k = cursor.getAndIncrement()
                                if (k >= pending.size) break
                                val idx = pending[k]
                                val start = idx * chunk
                                val end = minOf(total, start + chunk)
                                fetch(url, headers, raf, start, end, control) { n ->
                                    completedBytes.addAndGet(n)
                                    report(false)
                                }
                                synchronized(done) {
                                    done[idx] = true
                                    metaFile.writeText(metaJson(total, chunk, urlKey, done))
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Throwable) {
            if (e is Cancelled) {
                part.delete()
                metaFile.delete()
            }
            throw e
        }
        report(true)
        finish(part, dest, metaFile)
        ctype
    }

    private fun finish(part: File, dest: File, metaFile: File) {
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        metaFile.delete()
    }

    private fun metaJson(total: Long, chunk: Long, urlKey: String, done: BooleanArray): String {
        val arr = JSONArray()
        done.forEachIndexed { i, b -> if (b) arr.put(i) }
        return JSONObject()
            .put("total", total)
            .put("chunk", chunk)
            .put("url", urlKey)
            .put("done", arr)
            .toString()
    }

    /** 返回 (总长度, Content-Type)，服务器不支持 Range 时返回 null。 */
    private fun probe(url: String, headers: Map<String, String>): Pair<Long, String>? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-0")
        headers.forEach { (k, v) -> req.header(k, v) }
        return runCatching {
            http.client.newCall(req.build()).execute().use { r ->
                if (r.code != 206) return@runCatching null
                val cr = r.header("Content-Range") ?: return@runCatching null
                val total = cr.substringAfter('/', "").trim().toLongOrNull() ?: return@runCatching null
                if (total <= 0) return@runCatching null
                Pair(total, r.header("Content-Type") ?: "")
            }
        }.getOrNull()
    }

    private fun fetch(
        url: String,
        headers: Map<String, String>,
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        control: DownloadControl,
        onBytes: (Long) -> Unit,
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=$start-${end - 1}")
        headers.forEach { (k, v) -> req.header(k, v) }
        val call = http.client.newCall(req.build())
        control.calls.add(call)
        try {
            call.execute().use { r ->
                if (r.code != 206) throw IllegalStateException("HTTP ${r.code}")
                val body = r.body ?: throw IllegalStateException("empty body")
                raf.seek(start)
                var pos = start
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (pos < end) {
                        if (control.cancelled) throw Cancelled()
                        if (control.paused) {
                            Thread.sleep(150)
                            continue
                        }
                        val n = input.read(buf)
                        if (n <= 0) break
                        raf.write(buf, 0, n)
                        pos += n
                        onBytes(n.toLong())
                    }
                }
                if (pos < end) throw IllegalStateException("分片没下全 ${pos - start}/${end - start}")
            }
        } finally {
            control.calls.remove(call)
        }
    }
}
