package eu.kanade.tachiyomi.animeextension.es.hentaila

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.hentaila.extractors.FembedExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
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

    override val baseUrl = "https://hentaila.com"

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
        val animeId = response.request.url.toString().replace("https://hentaila.com/hentai-", "").toLowerCase()
        Log.i("bruh", "AnimeID: $animeId")
        val jsoup = response.asJsoup()

        jsoup.select("div.episodes-list article").forEach { it ->
            val epNum = it.select("a").attr("href").replace("/ver/$animeId-", "")
            val test = it.select("a").attr("href")
            Log.i("bruh", "TEST: $test")
            Log.i("bruh", "Episode-$epNum")
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                url = "/ver/$animeId-$epNum"
                date_upload = System.currentTimeMillis()
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
        Log.i("bruh", "${response.request.url}")
        document.select("script").forEach { it ->
            if (it.data().contains("var videos = [")) {
                val data = it.data().substringAfter("var videos = [").substringBefore("];")
                val arcUrl = data.substringAfter("[\"Arc\",\"").substringBefore("\",").replace("\\", "")
                val fembedUrl = data.substringAfter("[\"Fembed\",\"").substringBefore("\",").replace("\\", "")
                if (fembedUrl != null) {
                    val videos = FembedExtractor().videosFromUrl(fembedUrl)
                    videoList.addAll(videos)
                }
                if (arcUrl != null) {
                    val url = arcUrl.replace("/direct.html#", "")
                    videoList.add(Video(url, "Arc", url, null))
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Arc")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        val results = Jsoup.connect("https://hentaila.com/api/search").method(Connection.Method.POST).data("value", "$query").execute().body()
        val jsonObject = json.decodeFromString<JsonArray>(results)
        // val animeSlug = JSONObject(jsonObject[0].toString())["slug"]
        // for (i in jsonObject) {
        // val anime = JSONObject(i.toString())
        // val animeSlug = anime["slug"]

        // }
        Log.i("bruh", "$jsonObject.toString()")
        return when {
            query.isNotBlank() && jsonObject.toString() != "[]" -> GET("https://hentaila.com/hentai-${JSONObject(jsonObject[0].toString())["slug"]}")
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}?p=$page")
            else -> GET("https://hentaila.com/directorio?p=$page")
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        Log.i("bruh", "${element.select("article.hentai-single")}")

        val animeId = element.select("article.hentai-single header.h-header h1").text().replace(" ", "-").replace("!", "")

        val animeSearch = SAnime.create()
        animeSearch.setUrlWithoutDomain("https://hentaila.com/hentai-$animeId")
        animeSearch.title = element.select("article.hentai-single header.h-header h1").text()
        animeSearch.thumbnail_url = baseUrl + element.select("article.hentai-single div.h-thumb figure img").attr("src")

        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.grid.hentais article.hentai a").attr("href")
        )
        anime.title = element.select("div.grid.hentais article.hentai header.h-header h2").text()
        anime.thumbnail_url = baseUrl + element.select("div.grid.hentais article.hentai div.h-thumb figure img").attr("src")

        return when {
            element.select("article.hentai-single").toString() != "" -> animeSearch
            else -> anime
        }
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "div.bd.cont"

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
            Pair("Comedia", "comedia"),
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
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Arc", "Fembed:480p", "Fembed:720p", "Fembed:1080p")
            entryValues = arrayOf("Arc", "Fembed:480p", "Fembed:720p", "Fembed:1080p")
            setDefaultValue("Arc")
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
