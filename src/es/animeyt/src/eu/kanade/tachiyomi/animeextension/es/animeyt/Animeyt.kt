package eu.kanade.tachiyomi.animeextension.es.animeyt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animeyt : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeYT"

    override val baseUrl = "https://ytanime.tv"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Fastream"
        private val SERVER_LIST = arrayOf("Fastream")
    }

    override fun popularAnimeSelector(): String = "div.video-block div.row div.col-md-2 div.video-card"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/mas-populares?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.video-card div.video-card-body div.video-title a").attr("href"))
        anime.title = element.select("div.video-card div.video-card-body div.video-title a").text()
        anime.thumbnail_url = element.select("div.video-card div.video-card-image a:nth-child(2) img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item:last-child a"

    override fun episodeListSelector() = "#caps ul.list-group li.list-group-item a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("span.sa-series-link__number").text())
        episode.setUrlWithoutDomain(element.attr("href"))
        val epParsed = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        episode.episode_number = epParsed
        episode.name = "Episodio $epParsed"
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("#plays iframe").forEach { container ->
            val server = container.attr("src")
                .split(".")[0]
                .replace("https://", "")
                .replace("http://", "")

            var url = container.attr("src")
            if (server == "fastream") {
                if (url.contains("emb.html")) {
                    val key = url.split("/").last()
                    url = "https://fastream.to/embed-$key.html"
                }
                FastreamExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search?q=$query&page=$page")

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.sa-series-dashboard__poster div.sa-layout__line.sa-layout__line--sm div figure.sa-poster__fig img")!!.attr("src")
        anime.title = document.selectFirst("#info div.sa-layout__line div div.sa-title-series__title span")!!.html()
        anime.description = document.selectFirst("#info div.sa-layout__line p.sa-text")!!.text().removeSurrounding("\"")
        // anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("#info > div:nth-child(2) > button").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ultimos-animes?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
