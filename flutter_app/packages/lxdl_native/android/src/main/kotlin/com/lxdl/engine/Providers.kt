package com.lxdl.engine

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

data class Track(
    var platform: String = "",
    var id: String = "",
    var title: String = "",
    var artist: String = "",
    var album: String = "",
    var cover: String? = null,
    var durationSec: Int = 0,
    var isPlaylist: Boolean = false,
    var trackCount: Int = 0
) {
    fun subtitle(): String = when {
        isPlaylist -> if (trackCount > 0) "歌单 · ${trackCount} 首" else "歌单"
        album.isNotBlank() && durationSec > 0 -> "$album · ${clock(durationSec)}"
        durationSec > 0 -> clock(durationSec)
        else -> platform
    }

    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform)
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("cover", cover ?: JSONObject.NULL)
        .put("duration", durationSec)
        .put("playlist", isPlaylist)
        .put("trackCount", trackCount)
        .put("meta", subtitle())

    companion object {
        fun from(o: JSONObject): Track = Track(
            platform = o.optString("platform"),
            id = o.optString("id"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            cover = if (o.isNull("cover")) null else o.optString("cover"),
            durationSec = o.optInt("duration"),
            isPlaylist = o.optBoolean("playlist"),
            trackCount = o.optInt("trackCount")
        )

        private fun clock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)
    }
}

class Providers(private val http: Http, private val repo: Repo) {

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** 有镜像时把 API 请求改走镜像（保留 path/query），音频直链不动 */
    private fun api(url: String): String {
        val mirror = repo.cfg.mirror.trim()
        if (mirror.isEmpty()) return url
        return try {
            val base = if (mirror.contains("://")) mirror else "https://$mirror"
            val u = URI(url)
            base.trimEnd('/') + (u.rawPath ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) {
            url
        }
    }

    private fun ref(host: String) = mapOf("Referer" to host)

    // ---------------- 搜索 ----------------

    fun search(platform: String, keyword: String, playlist: Boolean, limit: Int = 20): List<Track> {
        if (keyword.isBlank()) return emptyList()
        return when (platform) {
            "wy" -> if (playlist) wyPlaylistSearch(keyword, limit) else wySearch(keyword, limit)
            "tx" -> if (playlist) throw IllegalStateException("QQ 音乐暂不支持搜索歌单") else txSearch(keyword, limit)
            "kg" -> if (playlist) throw IllegalStateException("酷狗暂不支持搜索歌单") else kgSearch(keyword, limit)
            else -> throw IllegalStateException("该平台暂不支持搜索，请粘贴链接或手填 id")
        }
    }

    private fun wySearch(kw: String, limit: Int): List<Track> {
        val o = http.json(
            api("https://music.163.com/api/search/get/web?s=${enc(kw)}&type=1&offset=0&limit=$limit"),
            ref("https://music.163.com/")
        )
        val arr = o.optJSONObject("result")?.optJSONArray("songs") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            Track(
                platform = "wy", id = s.optLong("id").toString(),
                title = s.optString("name"),
                artist = join(s.optJSONArray("artists")),
                album = s.optJSONObject("album")?.optString("name") ?: "",
                durationSec = (s.optLong("duration") / 1000).toInt()
            ).takeIf { it.id != "0" }
        }
    }

    private fun wyPlaylistSearch(kw: String, limit: Int): List<Track> {
        val o = http.json(
            api("https://music.163.com/api/search/get/web?s=${enc(kw)}&type=1000&offset=0&limit=$limit"),
            ref("https://music.163.com/")
        )
        val arr = o.optJSONObject("result")?.optJSONArray("playlists") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            Track(
                platform = "wy", id = p.optLong("id").toString(),
                title = p.optString("name"),
                artist = p.optJSONObject("creator")?.optString("nickname") ?: "",
                cover = p.optString("coverImgUrl"),
                trackCount = p.optInt("trackCount"),
                isPlaylist = true
            )
        }
    }

    private fun txSearch(kw: String, limit: Int): List<Track> {
        val o = http.json(
            api("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=${enc(kw)}&format=json&n=$limit&p=1&new_json=1&cr=1"),
            ref("https://y.qq.com/")
        )
        val arr = o.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            Track(
                platform = "tx", id = s.optString("mid"),
                title = s.optString("name"),
                artist = join(s.optJSONArray("singer")),
                album = s.optJSONObject("album")?.optString("name") ?: "",
                durationSec = s.optLong("interval").toInt()
            ).takeIf { it.id.isNotBlank() }
        }
    }

    private fun kgSearch(kw: String, limit: Int): List<Track> {
        val o = http.json(
            api("http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=${enc(kw)}&page=1&pagesize=$limit"),
            ref("https://m.kugou.com/")
        )
        val arr = o.optJSONObject("data")?.optJSONArray("info") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            Track(
                platform = "kg", id = s.optString("hash").lowercase(),
                title = s.optString("songname"),
                artist = s.optString("singername"),
                album = s.optString("album_name"),
                durationSec = s.optLong("duration").toInt()
            ).takeIf { it.id.isNotBlank() }
        }
    }

    // ---------------- 详情 / 歌词 ----------------

    fun detail(platform: String, id: String): Track = when (platform) {
        "wy" -> wyDetail(id)
        "tx" -> txDetail(id)
        "kg" -> kgDetail(id)
        else -> Track(platform = platform, id = id, title = id)
    }

    private fun wyDetail(id: String): Track {
        val o = http.json(
            api("https://music.163.com/api/song/detail/?ids=%5B${enc(id)}%5D"),
            ref("https://music.163.com/")
        )
        val s = o.optJSONArray("songs")?.optJSONObject(0) ?: throw IllegalStateException("没拿到详情")
        val album = s.optJSONObject("album")
        return Track(
            platform = "wy", id = id,
            title = s.optString("name"),
            artist = join(s.optJSONArray("artists")),
            album = album?.optString("name") ?: "",
            cover = album?.optString("picUrl"),
            durationSec = (s.optLong("duration") / 1000).toInt()
        )
    }

    private fun txDetail(id: String): Track {
        val payload = JSONObject()
            .put("comm", JSONObject().put("ct", "24").put("cv", "0"))
            .put(
                "req",
                JSONObject().put("module", "music.pf_song_detail_svr")
                    .put("method", "get_song_detail_yqq")
                    .put("param", JSONObject().put("song_mid", id))
            )
        val o = http.json(
            api("https://u.y.qq.com/cgi-bin/musicu.fcg?data=${enc(payload.toString())}"),
            ref("https://y.qq.com/")
        )
        val t = o.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("track_info")
            ?: throw IllegalStateException("没拿到详情")
        val album = t.optJSONObject("album")
        val mid = album?.optString("mid").orEmpty()
        return Track(
            platform = "tx", id = id,
            title = t.optString("name"),
            artist = join(t.optJSONArray("singer")),
            album = album?.optString("name") ?: "",
            cover = if (mid.isBlank()) null else "https://y.gtimg.cn/music/photo_new/T002R500x500M000$mid.jpg",
            durationSec = t.optLong("interval").toInt()
        )
    }

    private fun kgDetail(id: String): Track {
        val hash = id.lowercase()
        val o = http.json(
            api("https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=${enc(hash)}"),
            ref("https://m.kugou.com/")
        )
        if (o.optString("songName").isBlank()) throw IllegalStateException("没拿到详情")
        return Track(
            platform = "kg", id = hash,
            title = o.optString("songName"),
            artist = o.optString("author_name").ifBlank { o.optString("choricSinger") },
            cover = o.optString("album_img").replace("{size}", "480"),
            durationSec = o.optLong("timeLength").toInt()
        )
    }

    fun lyric(platform: String, id: String): Pair<String?, String?>? = runCatching {
        when (platform) {
            "wy" -> {
                val o = http.json(
                    api("https://music.163.com/api/song/lyric?id=${enc(id)}&lv=1&tv=1"),
                    ref("https://music.163.com/")
                )
                Pair(
                    o.optJSONObject("lrc")?.optString("lyric")?.ifBlank { null },
                    o.optJSONObject("tlyric")?.optString("lyric")?.ifBlank { null }
                )
            }
            "tx" -> {
                val o = http.json(
                    api("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=${enc(id)}&format=json&nobase64=1"),
                    ref("https://y.qq.com/")
                )
                Pair(o.optString("lyric").ifBlank { null }, o.optString("trans").ifBlank { null })
            }
            "kg" -> kgLyric(id)
            else -> null
        }
    }.getOrNull()

    private fun kgLyric(id: String): Pair<String?, String?>? {
        val search = http.json(
            api("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=${enc(id.lowercase())}"),
            ref("https://m.kugou.com/")
        )
        val c = search.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val lid = c.optString("id")
        val key = c.optString("accesskey")
        if (lid.isBlank() || key.isBlank()) return null
        val o = http.json(
            api("https://lyrics.kugou.com/download?ver=1&client=pc&id=$lid&accesskey=${enc(key)}&fmt=lrc&charset=utf8"),
            ref("https://m.kugou.com/")
        )
        val content = o.optString("content")
        if (content.isBlank()) return null
        val bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
        return Pair(String(bytes, Charsets.UTF_8).ifBlank { null }, null)
    }

    // ---------------- 歌单 ----------------

    fun playlistMeta(platform: String, id: String): Track {
        if (platform != "wy") throw IllegalStateException("歌单只支持网易云")
        val o = http.json(
            api("https://music.163.com/api/v6/playlist/detail?id=${enc(id)}"),
            ref("https://music.163.com/")
        )
        val p = o.optJSONObject("playlist") ?: throw IllegalStateException("歌单不存在")
        return Track(
            platform = "wy", id = id,
            title = p.optString("name"),
            artist = p.optJSONObject("creator")?.optString("nickname") ?: "",
            cover = p.optString("coverImgUrl"),
            trackCount = p.optInt("trackCount"),
            isPlaylist = true
        )
    }

    fun playlistTracks(platform: String, id: String): List<Track> {
        if (platform != "wy") throw IllegalStateException("歌单只支持网易云")
        val o = http.json(
            api("https://music.163.com/api/v6/playlist/detail?id=${enc(id)}"),
            ref("https://music.163.com/")
        )
        val idsArr = o.optJSONObject("playlist")?.optJSONArray("trackIds") ?: JSONArray()
        val ids = (0 until idsArr.length()).mapNotNull { idsArr.optJSONObject(it)?.optLong("id") }
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Track>()
        ids.chunked(100).forEach { chunk ->
            val list = chunk.joinToString(",")
            val d = http.json(
                api("https://music.163.com/api/song/detail/?ids=%5B${enc(list)}%5D"),
                ref("https://music.163.com/")
            )
            val songs = d.optJSONArray("songs") ?: return@forEach
            for (i in 0 until songs.length()) {
                val s = songs.optJSONObject(i) ?: continue
                out.add(
                    Track(
                        platform = "wy", id = s.optLong("id").toString(),
                        title = s.optString("name"),
                        artist = join(s.optJSONArray("ar") ?: s.optJSONArray("artists")),
                        album = s.optJSONObject("al")?.optString("name") ?: "",
                        durationSec = ((s.optLong("dt").takeIf { it > 0 } ?: s.optLong("duration")) / 1000).toInt()
                    )
                )
            }
        }
        return out
    }

    // ---------------- 链接解析 ----------------

    fun parse(platform: String, text: String): Track {
        val t = text.trim()
        if (t.isEmpty()) throw IllegalStateException("没有可解析的内容")
        val digit = { s: String, needle: String ->
            val i = s.indexOf(needle)
            if (i < 0) null else s.substring(i + needle.length).takeWhile { it.isDigit() }.ifEmpty { null }
        }
        val token = { s: String, needle: String, max: Int ->
            val i = s.indexOf(needle)
            if (i < 0) null else s.substring(i + needle.length).takeWhile { it.isLetterOrDigit() }.take(max).ifEmpty { null }
        }
        var src = t
        var playlist = t.contains("playlist") || t.contains("disstid") || t.contains("歌单")
        var id: String? = when (platform) {
            "wy" -> digit(src, "song?id=") ?: digit(src, "playlist?id=") ?: digit(src, "id=")
            ?: src.takeIf { it.length in 5..20 && it.all(Char::isDigit) }
            "tx" -> token(src, "songDetail/", 32) ?: token(src, "songmid=", 32)
            "kg" -> token(src, "hash=", 40)
            ?: src.split(Regex("[^0-9a-fA-F]")).firstOrNull { it.length == 32 }
            "kw" -> digit(src, "play_detail/") ?: digit(src, "musicId=") ?: digit(src, "mid=")
            ?: src.takeIf { it.length >= 4 && it.all(Char::isDigit) }
            "mg" -> digit(src, "musicId=") ?: digit(src, "song/")
            else -> null
        }
        if (id == null && (src.startsWith("http://") || src.startsWith("https://"))) {
            val finalUrl = runCatching {
                http.execute(src, timeoutMs = 20_000).use { it.request.url.toString() }
            }.getOrNull()
            if (finalUrl != null) {
                src = finalUrl
                playlist = src.contains("playlist") || src.contains("disstid")
                id = when (platform) {
                    "wy" -> digit(src, "song?id=") ?: digit(src, "playlist?id=") ?: digit(src, "id=")
                    "tx" -> token(src, "songDetail/", 32) ?: token(src, "songmid=", 32)
                    "kg" -> token(src, "hash=", 40)
                    "kw" -> digit(src, "play_detail/") ?: digit(src, "musicId=")
                    "mg" -> digit(src, "musicId=") ?: digit(src, "song/")
                    else -> null
                }
            }
        }
        if (id == null) throw IllegalStateException("无法解析：没认出这个平台的歌曲/歌单 id")
        return Track(
            platform = platform, id = id,
            isPlaylist = playlist && platform == "wy"
        )
    }

    private fun join(arr: JSONArray?): String {
        if (arr == null) return ""
        val names = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val name = arr.optJSONObject(i)?.optString("name") ?: ""
            if (name.isNotBlank()) names.add(name)
        }
        return names.joinToString("/")
    }
}
