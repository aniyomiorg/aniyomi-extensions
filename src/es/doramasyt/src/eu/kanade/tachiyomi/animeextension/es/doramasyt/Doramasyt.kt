package eu.kanade.tachiyomi.animeextension.es.doramasyt

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.doramasyt.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.doramasyt.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.es.doramasyt.extractors.SolidFilesExtractor
import eu.kanade.tachiyomi.animeextension.es.doramasyt.extractors.StreamTapeExtractor
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

class Doramasyt : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Doramasyt"

    override val baseUrl = "https://doramasyt.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.col-lg-2.col-md-4.col-6 div.animes"

    override fun popularAnimeRequest(page: Int): Request = GET("https://doramasyt.com/doramas/?p=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.anithumb a").attr("href")
        )
        anime.title = element.select("div.animedtls p").text()
        anime.thumbnail_url = element.select(" div.anithumb a img").attr("src")
        anime.description = element.select("div.animedtls p").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:last-child a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector(): String = "div.mainrowdiv.pagesdiv div.jpage div.col-item"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("a div.flimss div.dtlsflim p").text())
        Log.i("bruh ep", element.select("a").attr("href"))
        val formatedEp = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.episode_number = formatedEp
        episode.name = "Episodio $formatedEp"

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("div.playermain ul.dropcaps li#play-video a.cap").forEach { players ->
            val server = players.text()
            val urlEncoded = players.attr("data-player")
            val byte = android.util.Base64.decode(urlEncoded, android.util.Base64.DEFAULT)
            val url = String(byte, charset("UTF-8")).replace("https://doramasyt.com/reproductor?url=", "")
            Log.i("bruh server", url)
            if (server == "fembed") {
                val videos = FembedExtractor().videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "streamtape") {
                val video = StreamTapeExtractor(client).videoFromUrl(url, "Streamtape")
                if (video != null) {
                    videoList.add(video)
                }
            }
            if (server == "ok") {
                val videos = OkruExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
            if (server == "zeus") {
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
        val quality = preferences.getString("preferred_quality", "fembed")
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
            genreFilter.state != 0 -> GET("$baseUrl/doramas?categoria=false&genero=${genreFilter.toUriPart()}&fecha=false&letra=false&p=$page")
            else -> GET("$baseUrl/doramas/?p=$page")
        }
        // GET("$baseUrl/buscar?q=$query&p=$page")
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.selectFirst("a").attr("href")
        )
        anime.title = element.select("div.animedtls p").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        anime.description = element.select("div.animedtls p").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // anime.thumbnail_url = document.selectFirst("div.herohead div.heroheadmain").attr("style").substringAfter(",url(").substringBefore(") no-repeat;")
        val sub = document.selectFirst("div.herohead div.heroheadmain strong").text()
        val title = document.selectFirst("div.herohead div.heroheadmain h1").text().trim()
        anime.title = title + if (sub.isNotEmpty()) " ($sub)" else ""
        anime.description = document.selectFirst("div.herohead div.heroheadmain div.flimdtls p.textComplete")
            .text().removeSurrounding("\"").replace("Ver menos", "")
        anime.genre = document.select("div.herohead div.heroheadmain div.writersdiv div.nobel h6 a").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.herohead div.heroheadmain div.writersdiv div.state h6").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Estreno") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = "" // popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.selectFirst("a").attr("href")
        )
        anime.title = element.selectFirst("a div.chapter p").text()
        anime.thumbnail_url = element.select("a div.chapter img").attr("src")
        anime.description = element.select("div.animedtls p").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesSelector() = "div.heroarea div.heromain div.chapters div.row div.chaps" // popularAnimeSelector()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<selecionar>", "false"),
            Pair("Acción", "accion"),
            Pair("Amistad", "amistad"),
            Pair("Artes marciales", "artes-marciales"),
            Pair("Aventuras", "aventuras"),
            Pair("Bélico", "belico"),
            Pair("C-Drama", "c-drama"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Comida", "comida"),
            Pair("Crimen ", "crimen"),
            Pair("Deporte", "deporte"),
            Pair("Documental", "documental"),
            Pair("Drama", "drama"),
            Pair("Escolar", "escolar"),
            Pair("Familiar", "familiar"),
            Pair("Fantasia", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("HK-Drama", "hk-drama"),
            Pair("Horror", "horror"),
            Pair("Idols", "idols"),
            Pair("J-Drama", "j-drama"),
            Pair("Juvenil", "juvenil"),
            Pair("K-Drama", "k-drama"),
            Pair("Legal", "legal"),
            Pair("Médico", "medico"),
            Pair("Melodrama", "melodrama"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Musical", "musical"),
            Pair("Negocios", "negocios"),
            Pair("Policial", "policial"),
            Pair("Política", "politica"),
            Pair("Psicológico", "psicologico"),
            Pair("Reality Show", "reality-show"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Supervivencia", "supervivencia"),
            Pair("Suspenso", "suspenso"),
            Pair("Thai-Drama", "thai-drama"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("TW-Drama", "tw-drama"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
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
            entries = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "Streamtape", "SolidFiles", "Okru: mobile", "Okru: lowest", "Okru: low", "Okru: sd", "Okru: hd")
            entryValues = arrayOf("Fembed:480p", "Fembed:720p", "Fembed:1080p", "Streamtape", "SolidFiles", "Okru: mobile", "Okru: lowest", "Okru: low", "Okru: sd", "Okru: hd")
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
