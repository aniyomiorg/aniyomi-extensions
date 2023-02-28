package eu.kanade.tachiyomi.animeextension.en.nineanime.extractors

import dev.datlag.jsunpacker.JsUnpacker
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class Mp4uploadExtractor(private val client: OkHttpClient) {
    fun videoFromUrl(url: String, prefix: String = "Original (Mp4upload)"): List<Video> {
        val headers = Headers.headersOf("referer", "https://mp4upload.com/")
        val body = client.newCall(GET(url, headers = headers)).execute().body.string()
        val packed = body.substringAfter("eval(function(p,a,c,k,e,d)")
            .substringBefore("</script>")
        val unpacked = JsUnpacker.unpackAndCombine("eval(function(p,a,c,k,e,d)$packed")
            ?: return emptyList()
        val videoUrl = unpacked.substringAfter("player.src(\"").substringBefore("\");")
        return listOf(
            Video(videoUrl, prefix, videoUrl, headers),
        )
    }
}
