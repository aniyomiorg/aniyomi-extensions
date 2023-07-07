package eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient

class StreamWishExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(sources)")!!.data()
        val scriptData = if(script.contains("eval")) JsUnpacker.unpackAndCombine(script)!! else script
        val m3u8 = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(scriptData)!!.groupValues[1]
        val streamLink = Regex("(.*)_,(.*),\\.urlset/master(.*)").find(m3u8)!!
        val streamQuality = streamLink.groupValues[2].split(",").reversed()
        val qualities = scriptData.substringAfter("qualityLabels").substringBefore("}")
        val qRegex = Regex("\".*?\":\\s*\"(.*?)\"").findAll(qualities)
        return qRegex.mapIndexed { index, matchResult ->
            val src = streamLink.groupValues[1] + "_" + streamQuality[index] + "/index-v1-a1" + streamLink.groupValues[3]
            val quality = "Mirror: " +  matchResult.groupValues[1]
            Video(src, quality, src, headers)
        }.toList()
    }
}
