package eu.kanade.tachiyomi.animeextension.de.cinemathek

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Cinemathek : DooPlay(
    "de",
    "Cinemathek",
    "https://cinemathek.net",
) {
    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.movies div.poster"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/filme/page/$page/")

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "#nextpagination"
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/episoden/page/$page")

    // =========================== Anime Details ============================
    override val additionalInfoItems = listOf("Original", "Start", "Staffeln", "letzte", "Episoden")

    // Dont get the text from the <span> tag
    override fun Document.getDescription(): String {
        return selectFirst(".wp-content > p")!!
            .ownText() + "\n"
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val players = response.asJsoup().select("ul#playeroptionsul li")
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return players.parallelCatchingFlatMapBlocking { player ->
            val url = getPlayerUrl(player).takeUnless(String::isEmpty)!!
            getPlayerVideos(url, hosterSelection)
        }
    }

    private suspend fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        if (num == "trailer") return ""
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
            .await()
            .body.string()
            .substringAfter("\"embed_url\":\"")
            .substringBefore("\",")
            .replace("\\", "")
    }

    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }

    private fun getPlayerVideos(url: String, hosterSelection: Set<String>): List<Video> {
        return when {
            url.contains("https://streamlare.com") && hosterSelection.contains("slare") -> {
                streamlareExtractor.videosFromUrl(url)
            }

            url.contains("https://filemoon") && hosterSelection.contains("fmoon") -> {
                filemoonExtractor.videosFromUrl(url)
            }
            (url.contains("ds2play") || url.contains("https://doo")) && hosterSelection.contains("dood") -> {
                doodExtractor.videosFromUrl(url)
            }
            url.contains("streamtape") && hosterSelection.contains("stape") -> {
                streamtapeExtractor.videosFromUrl(url)
            }
            (url.contains("filelions") || url.contains("streamwish")) && hosterSelection.contains("swish") -> {
                streamwishExtractor.videosFromUrl(url)
            }
            else -> null
        }.orEmpty()
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
        private const val PREF_HOSTER_DEFAULT = "https://filemoon"
        private val PREF_HOSTER_ENTRIES = arrayOf("Streamlare", "Filemoon", "DoodStream", "StreamTape", "StreamWish/Filelions")
        private val PREF_HOSTER_VALUES = arrayOf("https://streamlare", "https://filemoon", "https://doo", "https://streamtape", "https://streamwish")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = PREF_HOSTER_ENTRIES
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("slare", "fmoon", "dood", "stape", "swish")
        private val PREF_HOSTER_SELECTION_DEFAULT = PREF_HOSTER_SELECTION_VALUES.toSet()

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
