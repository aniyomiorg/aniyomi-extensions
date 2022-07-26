package eu.kanade.tachiyomi.animeextension.es.animeyt.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class FastreamExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, server: String): Video {
        var url1 = ""
        client.newCall(GET(url)).execute().asJsoup().select("script").forEach {
            if (it.data().contains("jwplayer(\"vplayer\").setup({")) {
                url1 = it.data().substringAfter("sources: [{file:\"").substringBefore("\"}],")
            }
        }
        return Video(url1, server, url1)
    }
}
