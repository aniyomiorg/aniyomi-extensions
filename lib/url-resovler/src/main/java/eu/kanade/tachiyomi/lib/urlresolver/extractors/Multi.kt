package eu.kanade.tachiyomi.lib.urlresolver.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class Multi(private val client: OkHttpClient) {
    fun extract(url: String, host: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val doc = client.newCall(GET(url)).execute().asJsoup()
        val script = (doc.select("script:containsData(sources)").firstOrNull() ?: doc.select("script:containsData(file)").firstOrNull())!!.data()
        val data = (if(script.contains("eval")) JsUnpacker.unpackAndCombine(script).toString() else script).substringAfter("sources:").substringBefore("]")
        if(LABELED.containsMatchIn(data))
            LABELED.findAll(data).forEach {
                videoList.addAll(m3u8ToLinks(it.groupValues[1], "${capitalize(host)}: ${it.groupValues[2]}"))
            }
        else if(GO_STREAM.containsMatchIn(data))
            GO_STREAM.findAll(data).forEach {
                videoList.addAll(m3u8ToLinks(it.groupValues[1], "${capitalize(host)}: ${it.groupValues[2]}p"))
            }
        else if(NOT_LABELED.containsMatchIn(data))
             NOT_LABELED.findAll(data).forEach {
                 videoList.addAll(m3u8ToLinks(it.groupValues[1], host))
            }
        else if(UQ_LOAD.containsMatchIn(data))
            UQ_LOAD.findAll(data).forEach {
                videoList.addAll(m3u8ToLinks(it.groupValues[1], "${capitalize(host)}: mirror"))
            }
        return videoList
    }
    private fun m3u8ToLinks(url: String, quality: String): List<Video> {
        return if(url.contains("m3u8"))
            M3U8.findAll(client.newCall(GET(url)).execute().body!!.string()).toList().map {
                Video(it.groupValues[2], "${capitalize(quality)}: ${it.groupValues[1]}p", it.groupValues[2])
            }
        else listOf(Video(url, quality, url))
    }
    private fun capitalize(host: String): String = host.replaceFirstChar { it.uppercaseChar() }
    companion object {
        val LABELED = "file:\\s*\"(.*?)\",\\s*label:\\s*\"(.*?)\"".toRegex()
        val NOT_LABELED = "file:\\s*\"(.*?)\"".toRegex()
        val M3U8 = "RESOLUTION=\\d+x(\\d+).*\\n(.*)".toRegex()
        val UQ_LOAD = "\\[\"(.*?)\"".toRegex()
        val GO_STREAM = "file:\\s*\"(.*?)\".*?height:(.*?),".toRegex()
    }
}
