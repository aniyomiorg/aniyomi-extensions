package eu.kanade.tachiyomi.animeextension.en.animekhor

import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.StreamHideExtractor
import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AnimeKhor : AnimeStream(
    "en",
    "AnimeKhor",
    "https://animekhor.xyz",
) {
    // ============================ Video Links =============================

    override fun getVideoList(url: String, name: String): List<Video> {
        val streamSbDomains = listOf(
            "sbhight", "sbrity", "sbembed.com", "sbembed1.com", "sbplay.org",
            "sbvideo.net", "streamsb.net", "sbplay.one", "cloudemb.com",
            "playersb.com", "tubesb.com", "sbplay1.com", "embedsb.com",
            "watchsb.com", "sbplay2.com", "japopav.tv", "viewsb.com",
            "sbfast", "sbfull.com", "javplaya.com", "ssbstream.net",
            "p1ayerjavseen.com", "sbthe.com", "vidmovie.xyz", "sbspeed.com",
            "streamsss.net", "sblanh.com", "tvmshow.com", "sbanh.com",
            "streamovies.xyz", "sblona.com",
        )
        val prefix = "$name - "
        return when {
            url.contains("ahvsh.com") || name.equals("streamhide", true) -> {
                StreamHideExtractor(client, headers).videosFromUrl(url, prefix = prefix)
            }
            url.contains("ok.ru") -> {
                OkruExtractor(client).videosFromUrl(url, prefix = prefix)
            }
            url.contains("streamwish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Referer", "$baseUrl/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, prefix = prefix)
            }
            // TODO: Videos won't play
//            url.contains("animeabc.xyz") -> {
//                AnimeABCExtractor(client, headers).videosFromUrl(url, prefix = prefix)
//            }
            streamSbDomains.any { it in url } || name.equals("streamsb", true) -> {
                StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            }
            else -> emptyList()
        }
    }
}
