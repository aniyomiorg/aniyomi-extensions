package eu.kanade.tachiyomi.animeextension.pt.pifansubs.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class BlembedExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val json: Json by injectLazy()

    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url, headers)).execute()
            .asJsoup()

        val script = doc.selectFirst("script:containsData(player =)")
            ?.data()
            ?: return emptyList()

        val token = script.substringAfter("kaken = \"").substringBefore('"')
        val timestamp = System.currentTimeMillis().toString()

        val reqUrl = "https://blembed.com/api/?$token&_=$timestamp"
        val reqHeaders = headers.newBuilder().add("X-Requested-With", "XMLHttpRequest").build()
        val res = client.newCall(GET(reqUrl, reqHeaders)).execute()
            .parseAs<ResponseData>()

        return res.sources.map { Video(it.file, "Blembed - ${it.label}", it.file, headers) }
    }
}

@Serializable
data class ResponseData(val sources: List<VideoDto>) {
    @Serializable
    data class VideoDto(val file: String, val label: String)
}
