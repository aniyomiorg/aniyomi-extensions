package eu.kanade.tachiyomi.animeextension.de.streamcloud

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StreamCloud : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "StreamCloud"

    override val baseUrl = "https://streamcloud.movie"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/beliebte-filme/")

    override fun popularAnimeSelector() = "div#dle-content > div.item > div.thumb > a"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        element.selectFirst("img")!!.run {
            thumbnail_url = absUrl("src")
            title = attr("alt")
        }
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/index.php?do=search&subaction=search&search_start=$page&full_search=0&story=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = "#nextlink"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("#title span.title")!!.text()
        status = SAnime.COMPLETED
        with(document.selectFirst("div#longInfo")!!) {
            thumbnail_url = selectFirst("img")?.absUrl("src")
            genre = selectFirst("span.masha_index10")?.let {
                it.text().split("/").joinToString()
            }
            description = select("#storyline > span > p").eachText().joinToString("\n")
            author = selectFirst("strong:contains(Regie:) + div > a")?.text()
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episode = SEpisode.create().apply {
            name = document.selectFirst("#title span.title")!!.text()
            episode_number = 1F
            setUrlWithoutDomain(document.location())
        }
        return listOf(episode)
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mixdropExtractor by lazy { MixDropExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframeurl = document.selectFirst("div.player-container-wrap > iframe")
            ?.attr("src")
            ?: error("No videos!")
        val iframeDoc = client.newCall(GET(iframeurl)).execute().asJsoup()

        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        val items = iframeDoc.select("div._player ul._player-mirrors li")

        return items.flatMap { element ->
            val url = element.attr("data-link").run {
                takeIf { startsWith("https") }
                    ?: "https:$this"
            }

            runCatching {
                when {
                    url.contains("streamtape") && hosterSelection.contains("stape") -> {
                        streamtapeExtractor.videosFromUrl(url)
                    }
                    url.startsWith("https://dood") && hosterSelection.contains("dood") -> {
                        doodExtractor.videosFromUrl(url)
                    }
                    url.contains("mixdrop.") && hosterSelection.contains("mixdrop") -> {
                        mixdropExtractor.videosFromUrl(url)
                    }
                    else -> emptyList()
                }
            }.getOrElse { emptyList() }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        return sortedWith(
            compareBy { it.url.contains(hoster) },
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Standard-Hoster"
        private const val PREF_HOSTER_DEFAULT = "https://streamtape.com"
        private val PREF_HOSTER_ENTRIES = arrayOf("Streamtape", "DoodStream")
        private val PREF_HOSTER_VALUES = arrayOf("https://streamtape.com", "https://dood.")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Streamtape", "DoodStream", "MixDrop")
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("stape", "dood", "mixdrop")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_VALUES.toSet() }
    }
}
