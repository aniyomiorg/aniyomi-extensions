package eu.kanade.tachiyomi.animeextension.en.animenosub

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.StreamWishExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.VtubeExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.WolfstreamExtractor
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import org.jsoup.nodes.Element

class Animenosub : AnimeStream(
    "en",
    "AnimenosubBruh",
    "https://animenosub.com",
) {
    // ============================== Popular ===============================

    override fun popularAnimeSelector() = "div.serieslist.wpop-weekly li"

    // ============================== Episodes ==============================

    override fun episodeFromElement(element: Element): SEpisode {
        val episodeTitle = element.selectFirst("div.epl-title")?.text() ?: ""
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.selectFirst("div.epl-num")!!.text().let {
                name = "Ep. $it ${if (episodeTitle.contains("Episode $it", true)) "" else episodeTitle}"
                episode_number = it.substringBefore(" ").toFloatOrNull() ?: 0F
            }
            element.selectFirst("div.epl-sub")?.text()?.let { scanlator = it }
            date_upload = element.selectFirst("div.epl-date")?.text().toDate()
        }
    }

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
            "streamovies.xyz", "lvturbo.com", "sbrapid.com",
        )
        val prefix = "$name - "
        return when {
            streamSbDomains.any { it in url } -> {
                StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            }
            url.contains("streamwish") -> {
                StreamWishExtractor(client, headers).videosFromUrl(url, prefix)
            }
            url.contains("vidmoly") -> {
                VidMolyExtractor(client).getVideoList(url, name)
            }
            url.contains("https://vtbe") -> {
                VtubeExtractor(client, headers).videosFromUrl(url, baseUrl, prefix)
            }
            url.contains("wolfstream") -> {
                WolfstreamExtractor(client).videosFromUrl(url, prefix)
            }
            url.contains("filemoon") -> {
                FilemoonExtractor(client, headers).videosFromUrl(url, prefix)
            }
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences
        val videoTypePref = ListPreference(screen.context).apply {
            key = PREF_TYPE_KEY
            title = PREF_TYPE_TITLE
            entries = PREF_TYPE_VALUES
            entryValues = PREF_TYPE_VALUES
            setDefaultValue(PREF_TYPE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoServer = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_VALUES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoTypePref)
        screen.addPreference(videoServer)
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val type = preferences.getString(PREF_TYPE_KEY, PREF_TYPE_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.startsWith(type, true) },
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_TYPE_KEY = "preferred_type"
        private const val PREF_TYPE_TITLE = "Preferred Video Type"
        private const val PREF_TYPE_DEFAULT = "SUB"
        private val PREF_TYPE_VALUES = arrayOf("SUB", "RAW")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred Video Server"
        private const val PREF_SERVER_DEFAULT = "StreamSB"
        private val PREF_SERVER_VALUES = arrayOf(
            "StreamSB",
            "StreamWish",
            "VidMoly",
            "Vtube",
            "WolfStream",
            "Filemoon",
        )
    }
}
