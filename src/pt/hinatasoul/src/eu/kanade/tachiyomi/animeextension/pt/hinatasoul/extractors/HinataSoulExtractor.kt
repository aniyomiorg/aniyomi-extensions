package eu.kanade.tachiyomi.animeextension.pt.hinatasoul.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Response

class HinataSoulExtractor(private val headers: Headers) {

    fun getVideoList(response: Response): List<Video> {
        val doc = response.asJsoup()
        val hasFHD = doc.selectFirst("div.Aba:contains(FULLHD)") != null
        val serverUrl = doc.selectFirst("meta[itemprop=contentURL]").attr("content")
        val default = "appsd2"
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = listOf(default, "apphd2", "appfullhd")
        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = if (index > 0) serverUrl.replace(default, path) else serverUrl
            Video(url, quality, url, headers = headers)
        }
    }
}
