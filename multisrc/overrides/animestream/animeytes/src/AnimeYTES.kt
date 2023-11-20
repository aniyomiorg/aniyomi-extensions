package eu.kanade.tachiyomi.animeextension.es.animeytes

import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET

class AnimeYTES : AnimeStream(
    "es",
    "AnimeYT.es",
    "https://animeyt.es",
) {
    override val animeListUrl = "$baseUrl/tv"

    // ============================ Video Links =============================
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when (name) {
            "OK" -> okruExtractor.videosFromUrl(url)
            "Stream" -> streamtapeExtractor.videosFromUrl(url)
            "Send" -> sendvidExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    // ============================ Latest Updates =============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/anime", headers)

    // ============================ Preferences =============================
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

        val vrfIterceptPref = CheckBoxPreference(screen.context).apply {
            key = PREF_VRF_INTERCEPT_KEY
            title = PREF_VRF_INTERCEPT_TITLE
            summary = PREF_VRF_INTERCEPT_SUMMARY
            setDefaultValue(PREF_VRF_INTERCEPT_DEFAULT)
        }

        screen.addPreference(vrfIterceptPref)
        screen.addPreference(langPref)
    }
}

private const val PREF_LANG_KEY = "preferred_lang"
private const val PREF_LANG_TITLE = "Preferred language"
private const val PREF_LANG_DEFAULT = "SUB"
private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")

private const val PREF_VRF_INTERCEPT_KEY = "vrf_intercept"
private const val PREF_VRF_INTERCEPT_TITLE = "Intercept VRF links (Requiere Reiniciar)"
private const val PREF_VRF_INTERCEPT_SUMMARY = "Intercept VRF links and open them in the browser"
private const val PREF_VRF_INTERCEPT_DEFAULT = false
