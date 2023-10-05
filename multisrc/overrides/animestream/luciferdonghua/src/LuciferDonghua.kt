package eu.kanade.tachiyomi.animeextension.en.luciferdonghua

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

class LuciferDonghua : AnimeStream(
    "en",
    "LuciferDonghua",
    "https://luciferdonghua.in",
) {
    // ============================ Video Links =============================

    override fun getHosterUrl(encodedData: String): String {
        val doc = if (encodedData.toHttpUrlOrNull() == null) {
            Base64.decode(encodedData, Base64.DEFAULT)
                .let(::String) // bytearray -> string
                .let(Jsoup::parse) // string -> document
        } else {
            client.newCall(GET(encodedData, headers)).execute().use { it.asJsoup() }
        }

        return doc.selectFirst("iframe[src~=.]")?.attr("abs:src")
            ?: doc.selectFirst("meta[content~=.][itemprop=embedUrl]")!!.attr("abs:content")
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val filelionsExtractor by lazy { StreamWishExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, prefix = prefix)
            url.contains("dailymotion") -> dailymotionExtractor.videosFromUrl(url, prefix)
            url.contains("filelions") -> filelionsExtractor.videosFromUrl(url, videoNameGen = { quality -> "FileLions - $quality" })
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
