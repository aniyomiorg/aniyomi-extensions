package eu.kanade.tachiyomi.lib.cryptoaes

/*
 * Copyright (C) The Tachiyomi Open Source Project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

// Thanks to Vlad on Stackoverflow: https://stackoverflow.com/a/63701411

import android.util.Base64
import java.security.MessageDigest
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Conforming with CryptoJS AES method
 */
@Suppress("unused")
object CryptoAES {

    private const val KEY_SIZE = 32 // 256 bits
    private const val IV_SIZE = 16 // 128 bits
    private const val SALT_SIZE = 8 // 64 bits
    private const val HASH_CIPHER = "AES/CBC/PKCS7PADDING"
    private const val HASH_CIPHER_FALLBACK = "AES/CBC/PKCS5PADDING"
    private const val AES = "AES"
    private const val KDF_DIGEST = "MD5"

    /**
     * Decrypt using CryptoJS defaults compatible method.
     * Uses KDF equivalent to OpenSSL's EVP_BytesToKey function
     *
     * http://stackoverflow.com/a/29152379/4405051
     * @param cipherText base64 encoded ciphertext
     * @param password passphrase
     */
    fun decrypt(cipherText: String, password: String): String {
        return try {
            val ctBytes = Base64.decode(cipherText, Base64.DEFAULT)
            val saltBytes = Arrays.copyOfRange(ctBytes, SALT_SIZE, IV_SIZE)
            val cipherTextBytes = Arrays.copyOfRange(ctBytes, IV_SIZE, ctBytes.size)
            val md5 = MessageDigest.getInstance("MD5")
            val keyAndIV = generateKeyAndIV(KEY_SIZE, IV_SIZE, 1, saltBytes, password.toByteArray(Charsets.UTF_8), md5)
            decryptAES(
                cipherTextBytes,
                keyAndIV?.get(0) ?: ByteArray(KEY_SIZE),
                keyAndIV?.get(1) ?: ByteArray(IV_SIZE),
            )
        } catch (e: Exception) {
            ""
        }
    }

    fun decryptWithSalt(cipherText: String, salt: String, password: String): String {
        return try {
            val ctBytes = Base64.decode(cipherText, Base64.DEFAULT)
            val md5: MessageDigest = MessageDigest.getInstance("MD5")
            val keyAndIV = generateKeyAndIV(
                KEY_SIZE,
                IV_SIZE,
                1,
                salt.decodeHex(),
                password.toByteArray(Charsets.UTF_8),
                md5,
            )
            decryptAES(
                ctBytes,
                keyAndIV?.get(0) ?: ByteArray(KEY_SIZE),
                keyAndIV?.get(1) ?: ByteArray(IV_SIZE),
            )
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Decrypt using CryptoJS defaults compatible method.
     *
     * @param cipherText base64 encoded ciphertext
     * @param keyBytes key as a bytearray
     * @param ivBytes iv as a bytearray
     */
    fun decrypt(cipherText: String, keyBytes: ByteArray, ivBytes: ByteArray): String {
        return try {
            val cipherTextBytes = Base64.decode(cipherText, Base64.DEFAULT)
            decryptAES(cipherTextBytes, keyBytes, ivBytes)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Encrypt using CryptoJS defaults compatible method.
     *
     * @param plainText plaintext
     * @param keyBytes key as a bytearray
     * @param ivBytes iv as a bytearray
     */
    fun encrypt(plainText: String, keyBytes: ByteArray, ivBytes: ByteArray): String {
        return try {
            val cipherTextBytes = plainText.toByteArray()
            encryptAES(cipherTextBytes, keyBytes, ivBytes)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Decrypt using CryptoJS defaults compatible method.
     *
     * @param cipherTextBytes encrypted text as a bytearray
     * @param keyBytes key as a bytearray
     * @param ivBytes iv as a bytearray
     */
    private fun decryptAES(cipherTextBytes: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): String {
        return try {
            val cipher = try {
                Cipher.getInstance(HASH_CIPHER)
            } catch (e: Throwable) { Cipher.getInstance(HASH_CIPHER_FALLBACK) }
            val keyS = SecretKeySpec(keyBytes, AES)
            cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(ivBytes))
            cipher.doFinal(cipherTextBytes).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Encrypt using CryptoJS defaults compatible method.
     *
     * @param plainTextBytes encrypted text as a bytearray
     * @param keyBytes key as a bytearray
     * @param ivBytes iv as a bytearray
     */
    private fun encryptAES(plainTextBytes: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): String {
        return try {
            val cipher = try {
                Cipher.getInstance(HASH_CIPHER)
            } catch (e: Throwable) { Cipher.getInstance(HASH_CIPHER_FALLBACK) }
            val keyS = SecretKeySpec(keyBytes, AES)
            cipher.init(Cipher.ENCRYPT_MODE, keyS, IvParameterSpec(ivBytes))
            Base64.encodeToString(cipher.doFinal(plainTextBytes), Base64.DEFAULT)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Generates a key and an initialization vector (IV) with the given salt and password.
     *
     * https://stackoverflow.com/a/41434590
     * This method is equivalent to OpenSSL's EVP_BytesToKey function
     * (see https://github.com/openssl/openssl/blob/master/crypto/evp/evp_key.c).
     * By default, OpenSSL uses a single iteration, MD5 as the algorithm and UTF-8 encoded password data.
     *
     * @param keyLength the length of the generated key (in bytes)
     * @param ivLength the length of the generated IV (in bytes)
     * @param iterations the number of digestion rounds
     * @param salt the salt data (8 bytes of data or `null`)
     * @param password the password data (optional)
     * @param md the message digest algorithm to use
     * @return an two-element array with the generated key and IV
     */
    private fun generateKeyAndIV(
        keyLength: Int,
        ivLength: Int,
        iterations: Int,
        salt: ByteArray,
        password: ByteArray,
        md: MessageDigest,
    ): Array<ByteArray?>? {
        val digestLength = md.digestLength
        val requiredLength = (keyLength + ivLength + digestLength - 1) / digestLength * digestLength
        val generatedData = ByteArray(requiredLength)
        var generatedLength = 0
        return try {
            md.reset()

            // Repeat process until sufficient data has been generated
            while (generatedLength < keyLength + ivLength) {
                // Digest data (last digest if available, password data, salt if available)
                if (generatedLength > 0) md.update(generatedData, generatedLength - digestLength, digestLength)
                md.update(password)
                md.update(salt, 0, SALT_SIZE)
                md.digest(generatedData, generatedLength, digestLength)

                // additional rounds
                for (i in 1 until iterations) {
                    md.update(generatedData, generatedLength, digestLength)
                    md.digest(generatedData, generatedLength, digestLength)
                }
                generatedLength += digestLength
            }

            // Copy key and IV into separate byte arrays
            val result = arrayOfNulls<ByteArray>(2)
            result[0] = generatedData.copyOfRange(0, keyLength)
            if (ivLength > 0) result[1] = generatedData.copyOfRange(keyLength, keyLength + ivLength)
            result
        } catch (e: Exception) {
            throw e
        } finally {
            // Clean out temporary data
            Arrays.fill(generatedData, 0.toByte())
        }
    }

    // Stolen from AnimixPlay(EN) / GogoCdnExtractor
    fun String.decodeHex(): ByteArray {
        check(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
