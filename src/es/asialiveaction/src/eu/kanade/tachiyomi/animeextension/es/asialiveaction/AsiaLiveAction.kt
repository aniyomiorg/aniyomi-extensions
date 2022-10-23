package eu.kanade.tachiyomi.animeextension.es.asialiveaction

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.util.Calendar

class AsiaLiveAction : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AsiaLiveAction"

    override val baseUrl = "https://asialiveaction.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.TpRwCont main section ul.MovieList li.TPostMv article.TPost"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/todos/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a h3.Title").text()
        anime.thumbnail_url = element.select("a div.Image figure img").attr("src").trim().replace("//", "https://")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.TpRwCont main div a.next.page-numbers"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("header div.Image figure img").attr("src").trim().replace("//", "https://")
        anime.title = document.selectFirst("header div.asia-post-header h1.Title").text()
        anime.description = document.selectFirst("header div.asia-post-main div.Description p:nth-child(2)").text().removeSurrounding("\"")
        anime.genre = document.select("div.asia-post-main p.Info span.tags a").joinToString { it.text() }
        val year = document.select("header div.asia-post-main p.Info span.Date a").text().toInt()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        anime.status = when {
            (year < currentYear) -> SAnime.COMPLETED
            (year == currentYear) -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "#ep-list div.TPTblCn span a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("div.flex-grow-1 p").text())
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("div.flex-grow-1 p").text().trim()
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("var videosJap = [") || script.data().contains("var videosCor = [")) {
                val content = script.data()
                val sbDomains = arrayOf("sbfull", "sbplay", "cloudemb", "sbplay", "embedsb", "pelistop", "streamsb", "sbplay", "sbspeed")
                if (sbDomains.any { s -> content.contains(s) }) {
                    val url = content.substringAfter(",['SB','").substringBefore("',0,0]")
                    val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                    videoList.addAll(videos)
                }
                if (content.contains("fembed")) {
                    val url = content.substringAfter(",['FD','").substringBefore("',0,0]")
                    val videos = FembedExtractor(client).videosFromUrl(url)
                    videoList.addAll(videos)
                }
                if (content.contains("okru")) {
                    val url = content.substringAfter(",['OK','").substringBefore("',0,0]")
                    val videos = OkruExtractor(client).videosFromUrl(url)
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "Fembed:1080p")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET("$baseUrl/tag/${genreFilter.toUriPart()}/page/$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", "all"),
            Pair("Acción", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Drama", "drama"),
            Pair("Deporte", "deporte"),
            Pair("Erótico", "erotico"),
            Pair("Escolar", "escolar"),
            Pair("Extraterrestres", "extraterrestres"),
            Pair("Fantasía", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Lucha", "lucha"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Psicológico", "psicologico"),
            Pair("Romance", "romance"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Yaoi / BL", "yaoi-bl"),
            Pair("Yuri / GL", "yuri-gl")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", // Fembed
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", // Okru
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p" // StreamSB
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Fembed:1080p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
