package eu.kanade.tachiyomi.animeextension.en.animixplay.extractors

import android.net.Uri
import android.util.Base64
import android.util.Base64.NO_WRAP
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class StreamSbExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(serverUrl: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val downloadLink = serverUrl.replace("/e/", "/d/")
        val host = Uri.parse(downloadLink).host
        val rUrl = "https://$host/"
        val headers = Headers.headersOf(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0",
            "Referer", rUrl
        )
        val document = client.newCall(
            GET(downloadLink, headers)
        ).execute().asJsoup()
        val sources = document.select("tr a")
        sources.forEach {
            val videoQuality = it.text()
            val source = it.attr("onclick")
            val id = source.substringAfter("(").splitToSequence(",").first().replace("\'", "").replace(")", "")
            val hash = source.splitToSequence(",").last().replace("\'", "").replace(")", "")
            val mode =
                source.splitToSequence(",").elementAt(1).replace("\'", "").replace(")", "")
            val dlUrl =
                "https://$host/dl?op=download_orig&id=$id&mode=$mode&hash=$hash"
            val responseDoc = client.newCall(
                GET(
                    dlUrl,
                    headers
                )
            ).execute().asJsoup()
            val domain = Base64.encodeToString("https://$host:443".toByteArray(), NO_WRAP)
            val token = getToken(responseDoc, rUrl, domain)
            val postFormBody = getHiddenFormBody(responseDoc, token)
            val postHeaders = Headers.headersOf(
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0",
                "Referer", dlUrl,
                "Content-Type", "application/x-www-form-urlencoded",
                "Origin", rUrl,
                "authority", host!!,
            )
            val videoPostRequest = client.newCall(
                POST(
                    dlUrl,
                    postHeaders,
                    postFormBody
                )
            ).execute().asJsoup()
            val videoLink = videoPostRequest.select("span a").attr("href")
            videoList.add(Video(videoLink, videoQuality, videoLink, null))
        }
        return videoList
    }

    private fun getHiddenFormBody(document: Document, token: String): FormBody {
        val pageData = FormBody.Builder()
        val hiddenFormInputs = document.select("Form#F1 input")
        hiddenFormInputs.forEach {
            pageData.addEncoded(it.attr("name"), it.attr("value"))
        }
        pageData.addEncoded("g-recaptcha-response", token)
        return pageData.build()
    }

    private fun getToken(document: Document, url: String, domain: String): String {
        val headers = Headers.headersOf(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0",
            "Referer", url
        )
        val rUrl = "https://www.google.com/recaptcha/api.js"
        val aUrl = "https://www.google.com/recaptcha/api2"
        val key = document.select("button.g-recaptcha").attr("data-sitekey")
        val reCaptchaUrl = "$rUrl?render=$key"
        val resp = client.newCall(
            GET(
                reCaptchaUrl,
                headers
            )
        ).execute().asJsoup()
        val v = resp.text().substringAfter("releases/").substringBefore("/")
        val anchorLink = "$aUrl/anchor?ar=1&k=$key&co=$domain&hl=en&v=$v&size=invisible&cb=123456789"
        val callAnchorRes = client.newCall(
            GET(
                anchorLink,
                headers
            )
        ).execute().asJsoup()

        val rtoken = callAnchorRes.select("input#recaptcha-token").attr("value")

        val pageData = FormBody.Builder()
            .add("v", v)
            .add("reason", "q")
            .add("k", key)
            .add("c", rtoken)
            .add("sa", "")
            .add("co", domain)
            .build()
        val newHeaders = Headers.headersOf(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:88.0) Gecko/20100101 Firefox/88.0",
            "Referer", aUrl
        )
        val callReloadToken = client.newCall(
            POST("$aUrl/reload?k=$key", newHeaders, pageData)
        ).execute().asJsoup()

        return callReloadToken.text().substringAfter("rresp\",\"").substringBefore("\"")
    }
}
