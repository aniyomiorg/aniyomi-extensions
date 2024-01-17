package eu.kanade.tachiyomi.lib.okruextractor

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient

class OkruExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    private fun fixQuality(quality: String): String {
        val qualities = listOf(
            Pair("ultra", "2160p"),
            Pair("quad", "1440p"),
            Pair("full", "1080p"),
            Pair("hd", "720p"),
            Pair("sd", "480p"),
            Pair("low", "360p"),
            Pair("lowest", "240p"),
            Pair("mobile", "144p"),
        )
        return qualities.find { it.first == quality }?.second ?: quality
    }

    fun videosFromUrl(url: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoString = document.selectFirst("div[data-options]")
            ?.attr("data-options")
            ?: return emptyList<Video>()

        return when {
            "ondemandHls" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandHls")
                playlistUtils.extractFromHls(playlistUrl, videoNameGen = { "Okru:$it".addPrefix(prefix) })
            }
            "ondemandDash" in videoString -> {
                val playlistUrl = videoString.extractLink("ondemandDash")
                playlistUtils.extractFromDash(playlistUrl, videoNameGen = { it -> "Okru:$it".addPrefix(prefix) })
            }
            else -> videosFromJson(videoString, prefix, fixQualities)
        }
    }

    private fun String.addPrefix(prefix: String) =
        prefix.takeIf(String::isNotBlank)
            ?.let { "$prefix $this" }
            ?: this

    private fun String.extractLink(attr: String) =
        substringAfter("$attr\\\":\\\"")
            .substringBefore("\\\"")
            .replace("\\\\u0026", "&")

    private fun videosFromJson(videoString: String, prefix: String = "", fixQualities: Boolean = true): List<Video> {
        val arrayData = videoString.substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")

        return arrayData.split("{\\\"name\\\":\\\"").reversed().mapNotNull {
            val videoUrl = it.extractLink("url")
            val quality = it.substringBefore("\\\"").let {
                if (fixQualities) {
                    fixQuality(it)
                } else {
                    it
                }
            }
            val videoQuality = "Okru:$quality".addPrefix(prefix)

            if (videoUrl.startsWith("https://")) {
                Video(videoUrl, videoQuality, videoUrl)
            } else {
                null
            }
        }
    }
}
