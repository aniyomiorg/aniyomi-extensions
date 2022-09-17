package eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import org.jsoup.Connection
import org.jsoup.Jsoup

class Mp4uploadExtractor {
    fun getVideoFromUrl(url: String, headers: Headers): Video {
        val id = url.substringAfterLast("embed-").substringBeforeLast(".html")
        return try {
            val videoUrl = Jsoup.connect(url).data(
                mutableMapOf(
                    "op" to "download2",
                    "id" to id,
                    "rand" to "",
                    "referer" to url,
                    "method_free" to "+",
                    "method_premiun" to "",
                )
            ).method(Connection.Method.POST).ignoreContentType(true)
                .ignoreHttpErrors(true).execute().url().toString()
            Video(videoUrl, "Mp4Upload", videoUrl, headers)
        } catch (e: Exception) {
            Video("", "", "")
        }
    }
}
