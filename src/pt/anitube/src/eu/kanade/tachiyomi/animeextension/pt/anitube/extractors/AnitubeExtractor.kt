package eu.kanade.tachiyomi.animeextension.pt.anitube.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Response

object AnitubeExtractor {
    fun getVideoList(response: Response, headers: Headers): List<Video> {
        val doc = response.asJsoup()
        val hasFHD = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl = doc.selectFirst("meta[itemprop=contentURL]")!!
            .attr("content")
            .replace("cdn1", "cdn3")
        val type = serverUrl.split("/").get(3)
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = listOf("appsd", "apphd").let {
            if (type.endsWith("2")) {
                it.map { path -> path + "2" }
            } else {
                it
            }
        } + listOf("appfullhd")
        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = serverUrl.replace(type, path)
            Video(url, quality, url, headers = headers)
        }.reversed()
    }
}
