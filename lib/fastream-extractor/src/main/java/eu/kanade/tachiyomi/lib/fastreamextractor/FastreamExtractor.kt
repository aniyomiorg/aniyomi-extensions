package eu.kanade.tachiyomi.lib.fastreamextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

class FastreamExtractor(private val client: OkHttpClient) {
    private val json: Json by injectLazy()
    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    fun videoFromUrl(url: String, prefix: String = "Fastream:", headers: Headers? = null): List<Video> {
        val videoList = mutableListOf<Video>()
        try {
            val document = client.newCall(GET(url)).execute().asJsoup()
            val videoHeaders = (headers?.newBuilder() ?: Headers.Builder())
                .set("Referer", "https://fastream.to/")
                .set("Origin", "https://fastream.to")
                .build()
            document.select("script").forEach {
                if (it!!.data().contains("jwplayer(jwplayer(\"vplayer\").setup({")) {
                    val basicUrl = it.data().substringAfter("file: '").substringBefore("',")
                    videoList.add(Video(basicUrl, prefix, basicUrl, headers = videoHeaders))
                } else {
                    val packedRegex = "eval\\(function\\(p,a,c,k,e,.*\\)\\)".toRegex()
                    packedRegex.findAll(it.data()).map { packed -> packed.value }.toList().map { eval ->
                        val unpack = JsUnpacker.unpack(eval)
                        val serverRegex = "fastream.*?\\.m3u8([^&\">]?)".toRegex()
                        fetchUrls(unpack.first()).filter { serverRegex.containsMatchIn(it) }.map { url ->
                            PlaylistUtils(client, videoHeaders).extractFromHls(url, videoNameGen = { "$prefix$it" }).let { videoList.addAll(it) }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return videoList
    }
}
