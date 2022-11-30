package eu.kanade.tachiyomi.animeextension.en.zoro.utils

import android.util.Base64
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Decryptor {

    fun decrypt(encodedData: String, remoteKey: String): String? {
        val saltedData = Base64.decode(encodedData, Base64.DEFAULT)
        val salt = saltedData.copyOfRange(8, 16)
        val ciphertext = saltedData.copyOfRange(16, saltedData.size)
        val password = remoteKey.toByteArray()
        val (key, iv) = GenerateKeyAndIv(password, salt) ?: return null
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        val decryptedData = String(cipher.doFinal(ciphertext))
        return decryptedData
    }

    // https://stackoverflow.com/a/41434590/8166854
    private fun GenerateKeyAndIv(
        password: ByteArray,
        salt: ByteArray,
        hashAlgorithm: String = "MD5",
        keyLength: Int = 32,
        ivLength: Int = 16,
        iterations: Int = 1
    ): List<ByteArray>? {

        val md = MessageDigest.getInstance(hashAlgorithm)
        val digestLength = md.getDigestLength()
        val targetKeySize = keyLength + ivLength
        val requiredLength = (targetKeySize + digestLength - 1) / digestLength * digestLength
        var generatedData = ByteArray(requiredLength)
        var generatedLength = 0

        try {
            md.reset()

            while (generatedLength < targetKeySize) {
                if (generatedLength > 0)
                    md.update(
                        generatedData,
                        generatedLength - digestLength,
                        digestLength
                    )

                md.update(password)
                md.update(salt, 0, 8)
                md.digest(generatedData, generatedLength, digestLength)

                for (i in 1 until iterations) {
                    md.update(generatedData, generatedLength, digestLength)
                    md.digest(generatedData, generatedLength, digestLength)
                }

                generatedLength += digestLength
            }
            val result = listOf(
                generatedData.copyOfRange(0, keyLength),
                generatedData.copyOfRange(keyLength, targetKeySize)
            )
            return result
        } catch (e: DigestException) {
            return null
        }
    }
}
