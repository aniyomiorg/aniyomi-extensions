package eu.kanade.tachiyomi.animeextension.en.putlocker

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Conforming with CryptoJS AES method
 * https://stackoverflow.com/questions/49384768/encrypt-from-android-and-decrypt-in-cryptojs#63701411
 *
 * see https://gist.github.com/thackerronak/554c985c3001b16810af5fc0eb5c358f
 */

@Suppress("unused", "FunctionName")
object CryptoAES {

    private const val KEY_SIZE = 256
    private const val IV_SIZE = 128
    private const val HASH_CIPHER = "AES/CBC/PKCS7Padding"
    private const val AES = "AES"
    private const val KDF_DIGEST = "MD5"

    // Seriously crypto-js, what's wrong with you?
    private const val APPEND = "Salted__"

    /**
     * Encrypt
     * @param password passphrase
     * @param plainText plain string
     */
    fun encrypt(password: String, plainText: String): CipherResult {
        val saltBytes = generateSalt(8)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        EvpKDF(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, iv)
        val keyS = SecretKeySpec(key, AES)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keyS, ivSpec)
        val cipherText = cipher.doFinal(plainText.toByteArray())
        // Thanks kientux for this: https://gist.github.com/kientux/bb48259c6f2133e628ad
        // Create CryptoJS-like encrypted!
        // val sBytes = APPEND.toByteArray()
        // val b = ByteArray(sBytes.size + saltBytes.size + cipherText.size)
        // System.arraycopy(sBytes, 0, b, 0, sBytes.size)
        // System.arraycopy(saltBytes, 0, b, sBytes.size, saltBytes.size)
        // System.arraycopy(cipherText, 0, b, sBytes.size + saltBytes.size, cipherText.size)
        // val bEncode = Base64.encode(b, Base64.NO_WRAP)
        // return String(bEncode)

        // For our use case we don't need the first 16 bytes cause we are sending the salt and iv separately
        // I'm leaving it as is and taking what is needed in case anyone want's to use the original code
        val bEncode = Base64.encode(cipherText, Base64.NO_WRAP)
        return CipherResult(
            String(bEncode).toHex(),
            password.toHex(),
            saltBytes.toHex(),
            iv.toHex(),
        )
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }

    private fun String.toHex(): String = toByteArray().toHex()

    data class CipherResult(
        val cipherText: String,
        val password: String,
        val salt: String,
        val iv: String,
    )

    /**
     * Decrypt
     * Thanks Artjom B. for this: http://stackoverflow.com/a/29152379/4405051
     * @param password passphrase
     * @param cipherText encrypted string
     */
    fun decrypt(password: String, cipherText: String): String {
        val ctBytes = Base64.decode(cipherText.toByteArray(), Base64.NO_WRAP)
        val saltBytes = Arrays.copyOfRange(ctBytes, 8, 16)
        val cipherTextBytes = Arrays.copyOfRange(ctBytes, 16, ctBytes.size)
        val key = ByteArray(KEY_SIZE / 8)
        val iv = ByteArray(IV_SIZE / 8)
        EvpKDF(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, iv)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val keyS = SecretKeySpec(key, AES)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))
        val plainText = cipher.doFinal(cipherTextBytes)
        return String(plainText)
    }

    private fun EvpKDF(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        resultKey: ByteArray,
        resultIv: ByteArray,
    ): ByteArray {
        return EvpKDF(password, keySize, ivSize, salt, 1, KDF_DIGEST, resultKey, resultIv)
    }

    private fun EvpKDF(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        iterations: Int,
        hashAlgorithm: String,
        resultKey: ByteArray,
        resultIv: ByteArray,
    ): ByteArray {
        val keySize = keySize / 32
        val ivSize = ivSize / 32
        val targetKeySize = keySize + ivSize
        val derivedBytes = ByteArray(targetKeySize * 4)
        var numberOfDerivedWords = 0
        var block: ByteArray? = null
        val hash = MessageDigest.getInstance(hashAlgorithm)
        while (numberOfDerivedWords < targetKeySize) {
            if (block != null) {
                hash.update(block)
            }
            hash.update(password)
            block = hash.digest(salt)
            hash.reset()
            // Iterations
            for (i in 1 until iterations) {
                block = hash.digest(block!!)
                hash.reset()
            }
            System.arraycopy(
                block!!,
                0,
                derivedBytes,
                numberOfDerivedWords * 4,
                min(block.size, (targetKeySize - numberOfDerivedWords) * 4),
            )
            numberOfDerivedWords += block.size / 4
        }
        System.arraycopy(derivedBytes, 0, resultKey, 0, keySize * 4)
        System.arraycopy(derivedBytes, keySize * 4, resultIv, 0, ivSize * 4)
        return derivedBytes // key + iv
    }

    private fun generateSalt(length: Int): ByteArray {
        return ByteArray(length).apply {
            SecureRandom().nextBytes(this)
        }
    }
}
