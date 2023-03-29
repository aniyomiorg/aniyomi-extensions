package eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.security.KeyManagementException
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class Mp4uploadExtractor {
    fun getVideoFromUrl(url: String, headers: Headers): Video {
        val id = url.substringAfterLast("embed-").substringBeforeLast(".html")
        return try {
            val videoUrl = Jsoup.connect(url).data(
                mutableMapOf(
                    "op" to "download2",
                    "id" to id,
                    "rand" to "",
                    "referer" to url,
                    "method_free" to "+",
                    "method_premiun" to "",
                ),
            ).method(Connection.Method.POST).ignoreContentType(true)
                .ignoreHttpErrors(true).sslSocketFactory(this.socketFactory()).execute().url().toString()
            Video(videoUrl, "Mp4Upload", videoUrl, headers)
        } catch (e: Exception) {
            Video("", "", "")
        }
    }
    fun socketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            },
        )

        return try {
            val sslContext: SSLContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            sslContext.socketFactory
        } catch (e: NoSuchAlgorithmException) {
            throw RuntimeException("Failed to create a SSL socket factory", e)
        } catch (e: KeyManagementException) {
            throw RuntimeException("Failed to create a SSL socket factory", e)
        }
    }
}
