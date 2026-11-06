package com.lxdl.native

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

object Tagger {
    /** 写标签；失败不影响导出。返回 null 表示成功，否则返回错误文本。 */
    fun write(
        file: File,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String?
    ): String? = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tagOrCreateAndSetDefault
        if (title.isNotBlank()) tag.setField(FieldKey.TITLE, title)
        if (artist.isNotBlank()) tag.setField(FieldKey.ARTIST, artist)
        if (album.isNotBlank()) tag.setField(FieldKey.ALBUM, album)
        if (albumArtist.isNotBlank()) tag.setField(FieldKey.ALBUM_ARTIST, albumArtist)
        if (!lyrics.isNullOrBlank()) tag.setField(FieldKey.LYRICS, lyrics)
        tag.setField(FieldKey.COMMENT, "洛雪音源 · lxdl")
        if (cover != null && cover.isNotEmpty()) {
            val art = ArtworkFactory.getNew()
            art.binaryData = cover
            art.mimeType = coverMime ?: "image/jpeg"
            art.description = "cover"
            tag.setField(FieldKey.COVER_ART, art)
        }
        AudioFileIO.write(audio)
        null
    }.getOrElse { e -> e.message ?: e.toString() }
}
