package eu.kanade.tachiyomi.animeextension.ar.animeblkom.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class Mp4uploadExtractor(private val client: OkHttpClient) {
    fun videosFromUrl(url: String, headers: Headers): List<Video> {
        val body = client.newCall(GET(url, headers = headers)).execute().body.string()

        val packed = "eval(function(p,a,c,k,e,d)" + body.substringAfter("<script type='text/javascript'>eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")

        val unpacked = JsUnpacker.unpackAndCombine(packed) ?: return emptyList()
        val qualityRegex = """\WHEIGHT=(\d+)""".toRegex()
        val videoQuality = qualityRegex.find(unpacked)?.groupValues?.let { "${it[1]}p" } ?: ""

        val videoUrl = unpacked.substringAfter("player.src(\"").substringBefore("\");")
        return listOf(
            Video(videoUrl, "Mp4upload - $videoQuality", videoUrl, headers = Headers.headersOf("Referer", "https://www.mp4upload.com/")),
        )
    }
}
