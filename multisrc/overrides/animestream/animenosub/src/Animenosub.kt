package eu.kanade.tachiyomi.animeextension.en.animenosub

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.VidMolyExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.VtubeExtractor
import eu.kanade.tachiyomi.animeextension.en.animenosub.extractors.WolfstreamExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import org.jsoup.nodes.Element

class Animenosub : AnimeStream(
    "en",
    "Animenosub",
    "https://animenosub.com",
) {
    // ============================== Episodes ==============================
    override fun getEpisodeName(element: Element, epNum: String): String {
        val episodeTitle = element.selectFirst("div.epl-title")?.text() ?: ""
        val complement = if (episodeTitle.contains("Episode $epNum", true)) "" else episodeTitle
        return "Ep. $epNum $complement"
    }

    // ============================ Video Links =============================

    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
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
                FilemoonExtractor(client).videosFromUrl(url, prefix, headers)
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
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val PREF_SERVER_VALUES = arrayOf(
            "StreamWish",
            "VidMoly",
            "Vtube",
            "WolfStream",
            "Filemoon",
        )
    }
}
