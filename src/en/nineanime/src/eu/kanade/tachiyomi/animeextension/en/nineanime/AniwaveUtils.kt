package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.util.Base64
import java.net.URLDecoder
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AniwaveUtils {

    fun vrfEncrypt(input: String): String {
        val rc4Key = SecretKeySpec("tGn6kIpVXBEUmqjD".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)

        var vrf = cipher.doFinal(input.toByteArray())
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
        vrf = Base64.encode(vrf, Base64.DEFAULT or Base64.NO_WRAP)
        vrf = vrfShift(vrf)
        vrf = vrf.reversed().toByteArray()
        vrf = Base64.encode(vrf, Base64.URL_SAFE or Base64.NO_WRAP)
        // vrf = rot13(vrf)
        val stringVrf = vrf.toString(Charsets.UTF_8)
        return "vrf=${java.net.URLEncoder.encode(stringVrf, "utf-8")}"
    }

    fun vrfDecrypt(input: String): String {
        var vrf = input.toByteArray()
        vrf = Base64.decode(vrf, Base64.URL_SAFE)

        val rc4Key = SecretKeySpec("LUyDrL4qIxtIxOGs".toByteArray(), "RC4")
        val cipher = Cipher.getInstance("RC4")
        cipher.init(Cipher.DECRYPT_MODE, rc4Key, cipher.parameters)
        vrf = cipher.doFinal(vrf)

        return URLDecoder.decode(vrf.toString(Charsets.UTF_8), "utf-8")
    }

    private fun rot13(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val byte = vrf[i]
            if (byte in 'A'.code..'Z'.code) {
                vrf[i] = ((byte - 'A'.code + 13) % 26 + 'A'.code).toByte()
            } else if (byte in 'a'.code..'z'.code) {
                vrf[i] = ((byte - 'a'.code + 13) % 26 + 'a'.code).toByte()
            }
        }
        return vrf
    }

    private fun vrfShift(vrf: ByteArray): ByteArray {
        for (i in vrf.indices) {
            val shift = arrayOf(-2, -4, -5, 6, 2, -3, 3, 6)[i % 8]
            vrf[i] = vrf[i].plus(shift).toByte()
        }
        return vrf
    }
}
