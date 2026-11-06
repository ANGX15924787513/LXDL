package com.lxdl.engine

import android.util.Base64
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private const val B64 = Base64.NO_WRAP

    fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    fun aes(dataB64: String, mode: String, keyB64: String, ivB64: String): String {
        val data = Base64.decode(dataB64, B64)
        val key = normalize(Base64.decode(keyB64, B64))
        val m = mode.lowercase()
        val cipher = when {
            m.contains("cbc") -> Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(normalize(Base64.decode(ivB64, B64))))
            }
            m.contains("ecb") -> Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            }
            else -> throw IllegalArgumentException("unsupported aes mode: $mode")
        }
        return Base64.encodeToString(cipher.doFinal(data), B64)
    }

    fun rsa(dataB64: String, pem: String): String {
        val body = pem.replace(Regex("-----[^-]+-----"), "").replace("\\s".toRegex(), "")
        val der = Base64.decode(body, B64)
        val key: PublicKey = runCatching {
            java.security.KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        }.getOrThrow()
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(Base64.decode(dataB64, B64)), B64)
    }

    fun random(size: Int): String {
        val bytes = ByteArray(size.coerceIn(0, 1 shl 20))
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, B64)
    }

    private fun normalize(raw: ByteArray): ByteArray {
        val out = ByteArray(16)
        raw.copyInto(out, 0, 0, minOf(16, raw.size))
        return out
    }
}
