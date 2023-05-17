package eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class UploadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, headers: Headers, quality: String): Video? {
        val newHeaders = headers.newBuilder().add("referer", "https://uqload.com/").build()
        return runCatching {
            val document = client.newCall(GET(url, newHeaders)).execute().asJsoup()
            val basicUrl = document.selectFirst("script:containsData(var player =)")!!
                .data()
                .substringAfter("sources: [\"")
                .substringBefore("\"],")
            Video(basicUrl, "$quality Uqload", basicUrl, headers = headers)
        }.getOrNull()
    }
}
