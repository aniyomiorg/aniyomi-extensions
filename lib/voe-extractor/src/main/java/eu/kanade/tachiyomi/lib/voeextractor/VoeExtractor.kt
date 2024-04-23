package eu.kanade.tachiyomi.lib.voeextractor

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class VoeExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    private val playlistUtils by lazy { PlaylistUtils(client) }

    @Serializable
    data class VideoLinkDTO(val file: String)

    fun videosFromUrl(url: String, prefix: String = ""): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(const sources), script:containsData(var sources), script:containsData(wc0)")
            ?.data()
            ?: return emptyList()
        val playlistUrl = when {
            // Layout 1
            script.contains("sources") -> {
                val link = script.substringAfter("hls': '").substringBefore("'")
                val linkRegex = "(http|https)://([\\w_-]+(?:\\.[\\w_-]+)+)([\\w.,@?^=%&:/~+#-]*[\\w@?^=%&/~+#-])".toRegex()
                if (linkRegex.matches(link)) link else String(Base64.decode(link, Base64.DEFAULT))
            }
            // Layout 2
            script.contains("wc0") -> {
                val base64 = Regex("'.*'").find(script)!!.value
                val decoded = Base64.decode(base64, Base64.DEFAULT).let(::String)
                json.decodeFromString<VideoLinkDTO>(decoded).file
            }
            else -> return emptyList()
        }
        return playlistUtils.extractFromHls(playlistUrl,
            videoNameGen = { quality -> "${prefix}Voe: $quality" }
        )
    }
}
