package eu.kanade.tachiyomi.animeextension.es.tiodonghua

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET

class Tiodonghua : AnimeStream(
    "es",
    "Tiodonghua.com",
    "https://anime.tiodonghua.com",
) {
    override val animeListUrl = "$baseUrl/anime"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime/?status=&type=&sub=&order=update", headers)

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val youruploadExtractor by lazy { YourUploadExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when (name) {
            "Okru" -> okruExtractor.videosFromUrl(url)
            "Voe" -> voeExtractor.videosFromUrl(url)
            "YourUpload" -> youruploadExtractor.videoFromUrl(url, headers)
            "MixDrop" -> mixdropExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    override val fetchFilters: Boolean
        get() = false

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
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
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")
    }
}
