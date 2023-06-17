package eu.kanade.tachiyomi.animeextension.en.nineanime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class Mp4uploadExtractor(private val client: OkHttpClient, private val headers: Headers) {
    fun videoFromUrl(url: String, prefix: String = "Original (Mp4upload)"): List<Video> {
        val headers = headers.newBuilder()
            .add("referer", "https://mp4upload.com/")
            .build()
        val body = client.newCall(GET(url, headers = headers)).execute().body.string()
        val videoUrl = if (body.contains("eval(function(p,a,c,k,e,d)")) {
            val packed = body.substringAfter("<script type='text/javascript'>eval(function(p,a,c,k,e,d)")
                .substringBefore("</script>")
            body.substringAfter("<script type='text/javascript'>eval(function(p,a,c,k,e,d)")
                .substringBefore("</script>")
            val unpacked = JsUnpacker.unpackAndCombine("eval(function(p,a,c,k,e,d)" + packed) ?: return emptyList()
            unpacked.substringAfter("player.src(\"").substringBefore("\");")
        } else {
            val script = Jsoup.parse(body).selectFirst("script:containsData(player.src)")?.data() ?: return emptyList()
            script.substringAfter("src: \"").substringBefore("\"")
        }

        return listOf(
            Video(videoUrl, prefix, videoUrl, headers),
        )
    }
}
