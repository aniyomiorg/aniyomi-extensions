package eu.kanade.tachiyomi.animeextension.es.tioanimeh.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class yourUploadExtractor(private val client: OkHttpClient) {
    fun videofromurl(url: String, headers: Headers): Video {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val basicUrl = document.selectFirst("script:containsData(jwplayerOptions)").data().substringAfter("file: '").substringBefore("',")
        return Video(basicUrl, "YourUpload", basicUrl, null, headers)
    }
}
