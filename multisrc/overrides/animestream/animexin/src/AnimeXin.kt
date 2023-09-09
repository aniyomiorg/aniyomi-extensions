package eu.kanade.tachiyomi.animeextension.all.animexin

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.YouTubeExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream

class AnimeXin : AnimeStream(
    "all",
    "AnimeXin",
    "https://animexin.vip",
) {
    override val id = 4620219025406449669

    // ============================ Video Links =============================
    override fun getVideoList(url: String, name: String): List<Video> {
        val prefix = "$name - "
        return when {
            url.contains("ok.ru") -> {
                OkruExtractor(client).videosFromUrl(url, prefix = prefix)
            }

            url.contains("dailymotion") -> {
                DailymotionExtractor(client, headers).videosFromUrl(url, prefix)
            }
            url.contains("https://dood") -> {
                DoodExtractor(client).videosFromUrl(url, quality = name)
            }
            url.contains("gdriveplayer") -> {
                val gdriveHeaders = headersBuilder()
                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .add("Host", "gdriveplayer.to")
                    .add("Referer", "$baseUrl/")
                    .build()
                GdrivePlayerExtractor(client).videosFromUrl(url, name = name, headers = gdriveHeaders)
            }
            url.contains("youtube.com") -> {
                YouTubeExtractor(client).videosFromUrl(url, prefix = prefix)
            }
            url.contains("vidstreaming") -> {
                VidstreamingExtractor(client).videosFromUrl(url, prefix = prefix)
            }
            else -> emptyList()
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences
        val videoLangPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_VALUES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoLangPref)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val language = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(language, true) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_TITLE = "Preferred Video Language"
        private const val PREF_LANG_DEFAULT = "All Sub"
        private val PREF_LANG_VALUES = arrayOf(
            "All Sub", "Arabic", "English", "German", "Indonesia", "Italian",
            "Polish", "Portuguese", "Spanish", "Thai", "Turkish",
        )
    }
}
