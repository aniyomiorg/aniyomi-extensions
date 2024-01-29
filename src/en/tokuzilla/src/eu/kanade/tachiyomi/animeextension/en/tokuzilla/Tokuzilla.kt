package eu.kanade.tachiyomi.animeextension.en.tokuzilla

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.chillxextractor.ChillxExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Tokuzilla : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Tokuzilla"

    override val baseUrl = "https://tokuzilla.net"

    override val lang = "en"

    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.col-sm-4.col-xs-12.item"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        element.selectFirst("a")!!.run {
            setUrlWithoutDomain(attr("href"))
            title = attr("title")
        }
        thumbnail_url = element.selectFirst("img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector() = "a.next.page-numbers"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        var url = baseUrl
        filters.forEach { filter ->
            when (filter) {
                is GenreFilter -> url += filter.toUriPart()
                else -> {}
            }
        }
        return GET("$url/page/$page?s=$query")
    }

    override fun getFilterList() = AnimeFilterList(
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("Any", ""),
            Pair("Series", "/series"),
            Pair("Movie", "/movie"),
            Pair("Kamen Rider", "/kamen-rider"),
            Pair("Super Sentai", "/super-sentai"),
            Pair("Armor Hero", "/armor-hero"),
            Pair("Garo", "/garo"),
            Pair("Godzilla", "/godzilla"),
            Pair("Metal Heroes", "/metal-heroes"),
            Pair("Power Rangers", "/power-ranger"),
            Pair("Ultraman", "/ultraman"),
            Pair("Other", "/other"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val details = document.selectFirst("div.video-details")!!
        title = details.selectFirst("h1")!!.text()
        thumbnail_url = details.selectFirst("img")?.run {
            absUrl("data-src").ifEmpty { absUrl("src") }
        }
        genre = details.select("span.meta > a").eachText().joinToString().takeIf(String::isNotBlank)
        description = document.selectFirst("h2#plot + p")!!.text()
        author = details.selectFirst("th:contains(Year) + td")?.text()?.let { "Year $it" }
        status = details.selectFirst("th:contains(Status) + td")?.text().orEmpty().let {
            when {
                it.contains("Ongoing") -> SAnime.ONGOING
                it.contains("Complete") -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.pagination.post-tape a"
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val episodes = document.select(episodeListSelector())
        return if (episodes.isNotEmpty()) {
            episodes.map {
                SEpisode.create().apply {
                    setUrlWithoutDomain(it.attr("href"))
                    val epNum = it.text()
                    name = "Episode $epNum"
                    episode_number = epNum.toFloatOrNull() ?: 1F
                }
            }.reversed()
        } else {
            SEpisode.create().apply {
                setUrlWithoutDomain(document.selectFirst("meta[property=og:url]")!!.attr("content"))
                episode_number = 1F
                name = "Movie"
            }.let(::listOf)
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val frameLink = document.selectFirst("iframe[id=frame]")!!.attr("src")
        return ChillxExtractor(client, headers).videoFromUrl(frameLink, baseUrl)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Preference =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
