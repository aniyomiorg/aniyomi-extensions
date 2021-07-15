package eu.kanade.tachiyomi.animeextension.en.twistmoe

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AESDecrypt {
    fun aesEncrypt(v: String, secretKey: ByteArray, initializationVector: ByteArray) = encrypt(v, secretKey, initializationVector)

    fun aesDecrypt(v: ByteArray, secretKey: ByteArray, initializationVector: ByteArray) = decrypt(v, secretKey, initializationVector)

    fun getIvAndKey(v: String): ByteArray {
        // credits: https://github.com/anime-dl/anime-downloader/blob/c030fded0b7f79d5bb8a07f5cf6b2ae8fa3954a1/anime_downloader/sites/twistmoe.py
        val byteStr = Base64.decode(v.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        val md5 = MessageDigest.getInstance("MD5")
        assert(byteStr.decodeToString(0, 8) == "Salted__")
        val salt = byteStr.sliceArray(8..15)
        assert(salt.lastIndex == 7)
        val secretStr = "267041df55ca2b36f2e322d05ee2c9cf"
        val secret = secretStr
            .map { it.toByte() }
            .toByteArray()
        val data = secret + salt
        var key = md5.digest(data)
        var finalKey = key
        while (finalKey.lastIndex < 47) {
            key = md5.digest(key + data)
            finalKey += key
        }
        return finalKey.sliceArray(0..47)
    }

    fun unpad(v: String): String {
        return v.substring(0..v.lastIndex - v.last().toInt())
    }

    fun getToDecode(v: String): ByteArray {
        val byteStr = Base64.decode(v.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        assert(byteStr.decodeToString(0, 8) == "Salted__")
        return byteStr.sliceArray(16..byteStr.lastIndex)
    }

    private fun cipher(opmode: Int, secretKey: ByteArray, initializationVector: ByteArray): Cipher {
        if (secretKey.lastIndex != 31) throw RuntimeException("SecretKey length is not 32 chars")
        if (initializationVector.lastIndex != 15) throw RuntimeException("IV length is not 16 chars")
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        val sk = SecretKeySpec(secretKey, "AES")
        val iv = IvParameterSpec(initializationVector)
        c.init(opmode, sk, iv)
        return c
    }

    private fun encrypt(str: String, secretKey: ByteArray, iv: ByteArray): String {
        val encrypted = cipher(Cipher.ENCRYPT_MODE, secretKey, iv).doFinal(str.toByteArray(Charsets.UTF_8))
        return String(Base64.encode(encrypted, Base64.DEFAULT))
    }

    private fun decrypt(str: ByteArray, secretKey: ByteArray, iv: ByteArray): String {
        return String(cipher(Cipher.DECRYPT_MODE, secretKey, iv).doFinal(str))
    }
}
