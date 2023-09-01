package eu.kanade.tachiyomi.animeextension.en.luciferdonghua

import android.util.Base64
import eu.kanade.tachiyomi.animeextension.en.luciferdonghua.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import org.jsoup.Jsoup

class LuciferDonghua : AnimeStream(
    "en",
    "LuciferDonghua",
    "https://luciferdonghua.in",
) {
    // ============================ Video Links =============================

    override fun getHosterUrl(encodedData: String): String {
        val doc = Base64.decode(encodedData, Base64.DEFAULT)
            .let(::String) // bytearray -> string
            .let(Jsoup::parse) // string -> document

        val url = doc.selectFirst("iframe[src~=.]")?.attr("src")
            ?: doc.selectFirst("meta[content~=.][itemprop=embedUrl]")!!.attr("content")

        return when {
            url.startsWith("http") -> url
            else -> "https:$url"
        }
    }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("ok.ru") -> {
                OkruExtractor(client).videosFromUrl(url, prefix = prefix)
            }
            url.contains("dailymotion") -> {
                DailymotionExtractor(client, headers).videosFromUrl(url, prefix = prefix)
            }
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
