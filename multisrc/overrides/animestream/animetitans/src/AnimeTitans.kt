package eu.kanade.tachiyomi.animeextension.ar.animetitans

import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.AnimeTitansExtractor
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.animetitans.extractors.VidYardExtractor
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeTitans : AnimeStream(
    "ar",
    "AnimeTitans",
    "https://animetitans.com",
) {
    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale("ar"))
    }

    // =========================== Anime Details ============================
    override val animeArtistText = "الاستديو"
    override val animeStatusText = "الحالة"
    override val animeAuthorText = "المخرج"

    override val animeAltNamePrefix = " :أسماء أخرى"

    override fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "مكتمل" -> SAnime.COMPLETED
            "مستمر", "publishing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override val episodePrefix = "الحلقة"

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "Mp4Upload", "4shared")
    override val prefQualityEntries = prefQualityValues
    override val prefQualityDefault = "1080p"
    override val videoSortPrefDefault = prefQualityDefault

    override fun getVideoList(url: String, name: String): List<Video> {
        val vidbomDomains = listOf(
            "vidbom.com", "vidbem.com", "vidbm.com", "vedpom.com",
            "vedbom.com", "vedbom.org", "vadbom.com", "vidbam.org",
            "myviid.com", "myviid.net", "myvid.com", "vidshare.com",
            "vedsharr.com", "vedshar.com", "vedshare.com", "vadshar.com",
            "vidshar.org",
        )

        return when {
            baseUrl in url ->
                AnimeTitansExtractor(client).videosFromUrl(url, headers, baseUrl)
            vidbomDomains.any(url::contains) ->
                VidBomExtractor(client).videosFromUrl(url)
            "vidyard" in url ->
                VidYardExtractor(client).videosFromUrl(url, headers)
            "mp4upload" in url ->
                Mp4uploadExtractor(client).videosFromUrl(url, headers)
            "4shared" in url ->
                SharedExtractor(client).videoFromUrl(url, name)
                    ?.let(::listOf)
                    ?: emptyList()
            "drive.google" in url -> {
                val gdriveUrl = "https://gdriveplayer.to/embed2.php?link=$url"
                GdrivePlayerExtractor(client).videosFromUrl(gdriveUrl, name, headers)
            }
            else -> emptyList()
        }
    }
}
