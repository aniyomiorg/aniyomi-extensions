package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Response
import org.jsoup.nodes.Document

object AnitubeExtractor {

    private val headers = Headers.headersOf("User-Agent", "VLC/3.0.16 LibVLC/3.0.16")

    fun getVideoList(response: Response): List<Video> {
        val doc: Document = response.asJsoup()
        val hasFHD: Boolean = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl: String = doc.selectFirst("meta[itemprop=contentURL]").attr("content")
        val type: String = serverUrl.substringAfter("cz/").substringBefore("/")
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = when (type) {
            "appsd" -> mutableListOf("mobilesd", "mobilehd")
            else -> mutableListOf("sdr2", "hdr2")
        }
        paths.add("fullhdr2")
        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = serverUrl.replace(type, path)
            Video(url, quality, url, headers = headers)
        }.reversed()
    }
}
