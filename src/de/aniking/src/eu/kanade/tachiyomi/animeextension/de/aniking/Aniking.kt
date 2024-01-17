package eu.kanade.tachiyomi.animeextension.de.aniking

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.aniking.extractors.StreamZExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Aniking : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Aniking"

    override val baseUrl = "https://aniking.cc"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.item-container div.item > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page/?order=rating", headers = headers)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("data-src")
        title = element.selectFirst("h2")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "div.pagination i#nextpagination"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.use { it.asJsoup() }
        return if (document.selectFirst("#movie-js-extra") == null) {
            val episodeElement = document.selectFirst("script[id=\"tv-js-after\"]")!!
            episodeElement.data()
                .substringAfter("var streaming = {")
                .substringBefore("}; var")
                .split(",")
                .map(::episodeFromString)
                .reversed()
        } else {
            SEpisode.create().apply {
                name = document.selectFirst("h1.entry-title")!!.text()
                episode_number = 1F
                setUrlWithoutDomain(document.location())
            }.let(::listOf)
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromString(string: String) = SEpisode.create().apply {
        val season = string.substringAfter("\"s").substringBefore("_")
        val ep = string.substringAfter("_").substringBefore("\":")
        episode_number = ep.toFloatOrNull() ?: 1F
        name = "Staffel $season Folge $ep"
        url = string.substringAfter(":\"").substringBefore('"').replace("\\", "")
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode): Request {
        if (!episode.url.contains("https://")) {
            return GET("$baseUrl${episode.url}")
        } else {
            return GET(episode.url.replace(baseUrl, ""))
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        val hosterSelection = preferences.getStringSet(PREF_SELECTION_KEY, PREF_SELECTION_DEFAULT)!!
        return if (!url.contains(baseUrl)) {
            videoListFromUrl(url, hosterSelection)
        } else {
            val document = response.asJsoup()
            document.select("div.multi a").flatMap {
                videoListFromUrl(it.attr("href"), hosterSelection)
            }
        }
    }

    private fun videoListFromUrl(url: String, hosterSelection: Set<String>): List<Video> {
        return runCatching {
            when {
                "https://dood" in url && "dood" in hosterSelection -> {
                    DoodExtractor(client)
                        .videoFromUrl(url, "Doodstream", redirect = false)
                        ?.let(::listOf)
                }

                "https://streamtape" in url && "stape" in hosterSelection -> {
                    StreamTapeExtractor(client).videoFromUrl(url, "Streamtape")
                        ?.let(::listOf)
                }

                ("https://streamz" in url || "https://streamcrypt.net" in url) && "streamz" in hosterSelection -> {
                    val realUrl = when {
                        "https://streamcrypt.net" in url -> {
                            client.newCall(GET(url, headers)).execute().use {
                                it.request.url.toString()
                            }
                        }
                        else -> url
                    }

                    StreamZExtractor(client).videoFromUrl(realUrl, "StreamZ")
                        ?.let(::listOf)
                }

                else -> null
            }
        }.getOrNull() ?: emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    // TODO: Implement search filters
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query", headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        thumbnail_url = document.selectFirst("div.tv-poster img")!!.attr("src")
        title = document.selectFirst("h1.entry-title")!!.text()
        genre = document.select("span[itemprop=genre] a").eachText().joinToString()
        description = document.selectFirst("p.movie-description > span")!!
            .ownText()
            .substringBefore("Tags:")
        author = document.select("div.name a").eachText().joinToString().takeIf(String::isNotBlank)
        status = parseStatus(document.selectFirst("span.stato")?.text())
    }

    private fun parseStatus(status: String?) = when (status) {
        "Returning Series" -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

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
            key = PREF_SELECTION_KEY
            title = PREF_SELECTION_TITLE
            entries = PREF_SELECTION_ENTRIES
            entryValues = PREF_SELECTION_VALUES
            setDefaultValue(PREF_SELECTION_DEFAULT)

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
        private val PREF_HOSTER_ENTRIES = arrayOf("Streamtape", "Doodstream", "StreamZ")
        private val PREF_HOSTER_VALUES = arrayOf("https://streamz.ws", "https://dood", "https://voe.sx")

        private const val PREF_SELECTION_KEY = "hoster_selection"
        private const val PREF_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_SELECTION_ENTRIES = PREF_HOSTER_ENTRIES
        private val PREF_SELECTION_VALUES = arrayOf("stape", "dood", "streamz")
        private val PREF_SELECTION_DEFAULT = PREF_SELECTION_VALUES.toSet()
    }
}
