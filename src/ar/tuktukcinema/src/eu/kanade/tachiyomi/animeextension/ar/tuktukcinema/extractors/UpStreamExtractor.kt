package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import eu.kanade.tachiyomi.lib.unpacker.Unpacker

class UpStreamExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")?.data() ?: return emptyList()
        val scriptData = if("eval" in script) Unpacker.unpack(script) else script
        val m3u8 = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(scriptData)!!.groupValues[1]
        val qualities = scriptData.substringAfter("'qualityLabels':").substringBefore("},")
        val quality = qualities.substringAfter(": \"").substringBefore("\"")
        return Video(m3u8, "Upstream: $quality", m3u8).let(::listOf)
    }
}
