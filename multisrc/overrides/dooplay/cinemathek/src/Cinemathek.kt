package eu.kanade.tachiyomi.animeextension.de.cinemathek

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.de.cinemathek.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class Cinemathek : DooPlay(
    "de",
    "Cinemathek",
    "https://cinemathek.net",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.movies div.poster"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/page/$page/")

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "#nextpagination"

    // =========================== Anime Details ============================
    override val additionalInfoItems = listOf("Original", "Start", "Staffeln", "letzte", "Episoden")

    // Dont get the text from the <span> tag
    override fun Document.getDescription(): String {
        return selectFirst(".wp-content > p")!!
            .ownText() + "\n"
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val players = response.use { it.asJsoup().select("ul#playeroptionsul li") }
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return players.mapNotNull { player ->
            runCatching {
                val url = getPlayerUrl(player)
                getPlayerVideos(url, hosterSelection)
            }.getOrDefault(emptyList<Video>())
        }.flatten()
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    private fun getPlayerVideos(url: String, hosterSelection: Set<String>): List<Video>? {
        return when {
            url.contains("https://streamlare.com") && hosterSelection.contains("slare") -> {
                StreamlareExtractor(client).videosFromUrl(url)
            }
            url.contains("https://streamsb") && hosterSelection.contains("streamsb") -> {
                StreamSBExtractor(client).videosFromUrl(url, headers = headers, common = false)
            }
            url.contains("https://filemoon") && hosterSelection.contains("fmoon") -> {
                FilemoonExtractor(client).videoFromUrl(url)
            }
            else -> null
        }
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }

        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    override fun genresListSelector() = "li:contains($genresListMessage) ul.sub-menu li > a"

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.url.contains(hoster) },
                { it.quality.lowercase().contains(quality) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Standard-Hoster"
        private const val PREF_HOSTER_DEFAULT = "https://viewsb.com"
        private val PREF_HOSTER_ENTRIES = arrayOf("Streamlare", "StreamSB", "Filemoon")
        private val PREF_HOSTER_VALUES = arrayOf("https://streamlare", "https://viewsb.com", "https://filemoon")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = PREF_HOSTER_ENTRIES
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("slare", "streamsb", "fmoon")
        private val PREF_HOSTER_SELECTION_DEFAULT = PREF_HOSTER_SELECTION_VALUES.toSet()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
