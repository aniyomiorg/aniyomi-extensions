package eu.kanade.tachiyomi.animeextension.en.animekhor

import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.StreamHideExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AnimeKhor : AnimeStream(
    "en",
    "AnimeKhor",
    "https://animekhor.xyz",
) {
    // ============================ Video Links =============================

    override fun getVideoList(url: String, name: String): List<Video> {
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
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, prefix)
            }
            // TODO: Videos won't play
//            url.contains("animeabc.xyz") -> {
//                AnimeABCExtractor(client, headers).videosFromUrl(url, prefix = prefix)
//            }
            else -> emptyList()
        }
    }
}
