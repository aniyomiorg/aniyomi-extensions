package eu.kanade.tachiyomi.animeextension.en.luciferdonghua

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class LuciferDonghua : AnimeStream(
    "en",
    "LuciferDonghua",
    "https://luciferdonghua.in",
) {
    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.eplister > ul > li a"

    // ============================ Video Links =============================
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
