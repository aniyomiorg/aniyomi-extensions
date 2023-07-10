package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class UpStreamExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")?.data() ?: return emptyList()
        val scriptData = if("eval" in script) JsUnpacker.unpackAndCombine(script)!! else script
        val m3u8 = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(scriptData)!!.groupValues[1]
        return Video(m3u8, "Upstream", m3u8).let(::listOf)
    }
}
