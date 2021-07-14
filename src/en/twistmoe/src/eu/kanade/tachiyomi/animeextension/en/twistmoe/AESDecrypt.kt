package eu.kanade.tachiyomi.animeextension.en.twistmoe
import android.annotation.TargetApi
import android.os.Build
import android.util.Log
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@TargetApi(Build.VERSION_CODES.O)
class AESDecrypt {
    private val decoder = Base64.getDecoder()
    private val encoder = Base64.getEncoder()
    fun aesEncrypt(v: String, secretKey: ByteArray, initializationVector: ByteArray) = encrypt(v, secretKey, initializationVector)
    fun aesDecrypt(v: ByteArray, secretKey: ByteArray, initializationVector: ByteArray) = decrypt(v, secretKey, initializationVector)
    fun getIvAndKey(v: String): ByteArray {
        val byteStr = decoder.decode(v.toByteArray(Charsets.UTF_8))
        val md5 = MessageDigest.getInstance("MD5")
        assert(byteStr.decodeToString(0, 8) == "Salted__")
        val salt = byteStr.sliceArray(8..15)
        val secretStr = "267041df55ca2b36f2e322d05ee2c9cf"
        val secret = secretStr
            .map { it.toByte() }
            .toByteArray()
        val step1 = md5.digest(salt)
        val step2 = step1 + md5.digest(step1 + secret)
        val step3 = step2 + md5.digest(step2 + secret)
        Log.i("lol", step3.decodeToString())
        assert(step3.lastIndex == 47)
        return step3
    }
    fun getToDecode(v: String): ByteArray {
        val byteStr = decoder.decode(v.toByteArray(Charsets.UTF_8))
        assert(byteStr.decodeToString(0, 8) == "Salted__")
        return byteStr.sliceArray(16..byteStr.lastIndex)
    }
    private fun cipher(opmode: Int, secretKey: ByteArray, initializationVector: ByteArray): Cipher {
        if (secretKey.lastIndex != 31) throw RuntimeException("SecretKey length is not 32 chars")
        val c = Cipher.getInstance("AES/CBC/NoPadding")
        val sk = SecretKeySpec(secretKey, "AES")
        val iv = IvParameterSpec(initializationVector)
        c.init(opmode, sk, iv)
        return c
    }
    private fun encrypt(str: String, secretKey: ByteArray, iv: ByteArray): String {
        val encrypted = cipher(Cipher.ENCRYPT_MODE, secretKey, iv).doFinal(str.toByteArray(Charsets.UTF_8))
        return String(encoder.encode(encrypted))
    }
    private fun decrypt(str: ByteArray, secretKey: ByteArray, iv: ByteArray): String {
        return String(cipher(Cipher.DECRYPT_MODE, secretKey, iv).doFinal(str))
    }
}
