package eu.kanade.tachiyomi.animeextension.es.hentaila

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Hentaila : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Hentaila"

    override val baseUrl = "https://www3.hentaila.com"

    override val lang = "es"

    private val json: Json by injectLazy()

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.columns main section.section div.grid.hentais article.hentai"

    override fun popularAnimeRequest(page: Int): Request = GET("https://hentaila.com/directorio?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.title = element.select("header.h-header h2").text()
        anime.thumbnail_url = baseUrl + element.select("div.h-thumb figure img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.btn.rnd.npd.fa-arrow-right"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val animeId = response.request.url.toString().substringAfter("hentai-").lowercase()
        val jsoup = response.asJsoup()

        jsoup.select("div.episodes-list article").forEach { it ->
            val epNum = it.select("a").attr("href").replace("/ver/$animeId-", "")
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                url = "/ver/$animeId-$epNum"
            }
            episodes.add(episode)
        }

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val videoServers = document.selectFirst("script:containsData(var videos = [)").data().substringAfter("videos = ").substringBefore(";")
            .replace("[[", "").replace("]]", "")
        val videoServerList = videoServers.split("],[")
        videoServerList.forEach {

            val server = it.split(",").map { a -> a.replace("\"", "") }
            val urlServer = server[1].replace("\\/", "/")
            val nameServer = server[0]

            if (nameServer.lowercase() == "fembed") {
                val videos = FembedExtractor(client).videosFromUrl(urlServer)
                videoList.addAll(videos)
            }
            if (nameServer.lowercase() == "arc") {
                val videoUrl = urlServer.substringAfter("#")
                videoList.add(Video(videoUrl, "Arc", videoUrl))
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

    @OptIn(ExperimentalSerializationApi::class)
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        if (genreFilter.state == 0) {
            val results = Jsoup.connect("https://www3.hentaila.com/api/search").method(Connection.Method.POST).data("value", query).execute().body()
            val jsonObject = json.decodeFromString<JsonArray>(results)
            val animeSlug = JSONObject(jsonObject[0].toString())["slug"]
            val ultimateHentaiLink = "$baseUrl/hentai-$animeSlug"
            if (query.isNotBlank() && jsonObject.toString() != "[]") {
                return GET(ultimateHentaiLink)
            }
        }

        return when {
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}?p=$page")
            else -> GET("$baseUrl/directorio?p=$page")
        }
    }

    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val anime = mutableListOf<SAnime>()
        val element = response.asJsoup()

        if (!element.select("article.hentai-single").isNullOrEmpty()) {
            val animeSearch = SAnime.create()
            val mainUrl = element.select("section.section:nth-child(2) > script:nth-child(3)").toString().substringAfter("this.page.url = \"").substringBefore("\"")
            animeSearch.setUrlWithoutDomain(mainUrl)
            animeSearch.title = element.select("article.hentai-single header.h-header h1").text()
            animeSearch.thumbnail_url = baseUrl + element.select("article.hentai-single div.h-thumb figure img").attr("src")
            anime.add(animeSearch)
            return AnimesPage(anime, false)
        }
        val animes = element.select("div.columns main section.section div.grid.hentais article.hentai").map {
            popularAnimeFromElement(it)
        }

        return AnimesPage(animes, false)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = throw Exception("not used")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.selectFirst("div.h-thumb figure img").attr("src")
        anime.title = document.selectFirst("article.hentai-single header.h-header h1").text()
        anime.description = document.select("article.hentai-single div.h-content p").text()
        anime.genre = document.select("article.hentai-single footer.h-footer nav.genres a.btn.sm").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("3D", "3d"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Casadas", "casadas"),
            Pair("Chikan", "chikan"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Futanari", "futanari"),
            Pair("Harem", "Harem"),
            Pair("Gore", "gore"),
            Pair("Hardcore", "hardcore"),
            Pair("Incesto", "incesto"),
            Pair("Juegos Sexuales", "juegos-sexuales"),
            Pair("Milfs", "milfs"),
            Pair("Netorare", "netorare"),
            Pair("Ninfomania", "ninfomania"),
            Pair("Ninjas", "ninjas"),
            Pair("Orgia", "orgia"),
            Pair("Romance", "romance"),
            Pair("Shota", "shota"),
            Pair("Softcore", "softcore"),
            Pair("Succubus", "succubus"),
            Pair("Teacher", "teacher"),
            Pair("Tentaculos", "tentaculos"),
            Pair("Tetonas", "tetonas"),
            Pair("Vanilla", "vanilla"),
            Pair("Violacion", "violacion"),
            Pair("Virgenes(como tu)", "virgenes"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Bondage", "bondage")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", // Fembed
            "Arc" // video servers without resolution
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
