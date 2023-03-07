package eu.kanade.tachiyomi.animeextension.pt.hinatasoul.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Response

class HinataSoulExtractor(private val headers: Headers) {

    fun getVideoList(response: Response): List<Video> {
        val html = response.body.string()
        val doc = response.asJsoup(html)
        val hasFHD = doc.selectFirst("div.Aba:contains(FULLHD)") != null
        val regex = Regex("""file: '(\S+?)',""")
        return regex.findAll(html).mapNotNull {
            val videoUrl = it.groupValues[1]
            // prevent some http 404 due to the source returning false-positives
            if ("appfullhd" in videoUrl && !hasFHD) {
                null
            } else {
                val quality = videoUrl.substringAfter("app")
                    .substringBefore("/")
                    .substringBefore("2") // prevents "HD2", "SD2" etc
                    .uppercase()
                Video(videoUrl, quality, videoUrl, headers = headers)
            }
        }.toList()
    }
}
