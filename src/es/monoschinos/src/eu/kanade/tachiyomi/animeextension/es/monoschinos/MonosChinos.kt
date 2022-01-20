package eu.kanade.tachiyomi.animeextension.es.monoschinos

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.es.monoschinos.extractors.SolidFilesExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
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

class MonosChinos : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MonosChinos"

    override val baseUrl = "https://monoschinos2.com"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.heromain div.row div.col-md-4"

    override fun popularAnimeRequest(page: Int): Request = GET("https://monoschinos2.com/animes?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.title = element.select("a div.series div.seriesdetails h5").text()
        anime.thumbnail_url = element.select("a div.series div.seriesimg img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val jsoup = response.asJsoup()
        val animeId = response.request.url.pathSegments.last().replace("-sub-espanol", "").replace("-080p", "-1080p")
        Log.i("bruh", "$animeId")
        jsoup.select("div.heroarea2 div.heromain2 div.allanimes div.row.jpage.row-cols-md-6 div.col-item").forEach { it ->

            val epNum = it.attr("data-episode")
            Log.i("bruh", "Episode-$epNum")
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                url = "/ver/$animeId-episodio-$epNum"
                date_upload = System.currentTimeMillis()
            }
            episodes.add(episode)
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("div.heroarea div.row div.col-md-12 ul.dropcaps li").forEach { it ->
            val server = it.select("a").text()
            val urlBase64 = it.select("a").attr("data-player")
            val url1 = Base64.decode(urlBase64, Base64.DEFAULT)
            Log.i("bruh", "$url1")
            val url = String(url1).replace("https://monoschinos2.com/reproductor?url=", "")
            Log.i("bruh", "$url")

            if (server == "fembed" || server == "Fembed") {
                val videos = FembedExtractor().videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "ok" || server == "Ok") {
                val videos = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "zeus" || server == "Zeus") {
                val videos = SolidFilesExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Fembed: 720p")
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

        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/animes?categoria=false&genero=${genreFilter.toUriPart()}&fecha=false&letra=false&p=$page")
            else -> GET("$baseUrl/animes?p=$page ")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.chapterpic img").attr("src")
        anime.title = document.selectFirst("div.chapterdetails h1").text()
        anime.description = document.select("p.textShort").first().ownText()
        anime.genre = document.select("ol.breadcrumb li.breadcrumb-item a").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.butns button.btn1").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Estreno") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
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
            Pair("Latino", "latino"),
            Pair("Castellano", "castellano"),
            Pair("Acción", "acción"),
            Pair("Aventura", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasía"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Lucha", "lucha"),
            Pair("Magia", "magia"),
            Pair("Josei", "josei"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "música"),
            Pair("Parodias", "parodias"),
            Pair("Psicológico", "psicológico"),
            Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
            Pair("Seinen", "seinen"),
            Pair("Shojo", "shojo"),
            Pair("Shonen", "shonen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Espacial", "espacial"),
            Pair("Histórico", "histórico"),
            Pair("Samurai", "samurai"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Demonios", "demonios"),
            Pair("Romance", "romance"),
            Pair("Policía", " policía"),
            Pair("Historia paralela", "historia-paralela"),
            Pair("Aenime", "aenime"),
            Pair("Donghua", "donghua"),
            Pair("Blu-ray", "blu-ray"),
            Pair("Monogatari", "monogatari")
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
            entries = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "SolidFiles", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile")
            entryValues = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "SolidFiles", "Okru: full", "Okru: sd", "Okru: low", "Okru: lowest", "Okru: mobile")
            setDefaultValue("Fembed:720p")
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
