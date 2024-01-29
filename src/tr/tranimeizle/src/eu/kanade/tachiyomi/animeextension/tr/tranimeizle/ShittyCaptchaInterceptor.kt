package eu.kanade.tachiyomi.animeextension.tr.tranimeizle

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest

class ShittyCaptchaInterceptor(private val baseUrl: String, private val headers: Headers) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val originalResponse = chain.proceed(request)
        val currentUrl = originalResponse.request.url.toString()
        if (!currentUrl.contains("/api/CaptchaChallenge")) {
            return originalResponse
        }

        originalResponse.close()

        val body = FormBody.Builder()
            .add("cID", "0")
            .add("rT", "1")
            .add("tM", "light")
            .build()

        val newHeaders = headers.newBuilder()
            .set("Referer", currentUrl)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val imagesIDs = chain.proceed(POST("$baseUrl/api/Captcha/", newHeaders, body))
            .body.string()
            .removeSurrounding("[", "]")
            .split(',')
            .map { it.removeSurrounding("\"") }

        val hashes = imagesIDs.map { id ->
            chain.proceed(GET("$baseUrl/api/Captcha/?cid=0&hash=$id")).use { req ->
                // TODO: Use OKIO built-in md5 function
                // for some reason it refused to work well
                val hash = req.body.use { md5Hash(it.bytes()) }
                Pair(id, hash)
            }
        }

        val correctHash = hashes.groupingBy { it.second }.eachCount()
            .minByOrNull { it.value }
            ?.let { entry -> hashes.firstOrNull { it.second == entry.key }?.first }
            ?: throw IOException("Error while bypassing captcha!")

        val finalBody = FormBody.Builder()
            .add("cID", "0")
            .add("rT", "2")
            .add("pC", correctHash)
            .build()

        chain.proceed(POST("$baseUrl/api/Captcha/", newHeaders, finalBody))
            .close()

        return chain.proceed(GET(currentUrl, headers))
    }

    private fun md5Hash(byteArray: ByteArray) =
        MessageDigest.getInstance("MD5")
            .digest(byteArray)
            .joinToString("") { "%02x".format(it) } // create hex
}
