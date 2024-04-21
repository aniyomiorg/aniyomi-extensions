package eu.kanade.tachiyomi.animeextension.es.locopelis

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat

class LocoPelis : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "LocoPelis"

    override val baseUrl = "https://www.locopelis.com"

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
        private const val PREF_SERVER_DEFAULT = "DoodStream"
        private val SERVER_LIST = arrayOf("Okru", "DoodStream", "StreamTape", "StreamHideVid")

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd")
        }
    }

    override fun popularAnimeSelector(): String = "ul.peliculas li.peli_bx"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/pelicula/peliculas-mas-vistas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("h2.titpeli").text()
            thumbnail_url = element.select("div.peli_img div.peli_img_img a img").attr("src")
            description = element.select("div.peli_img div.peli_txt p").text().removeSurrounding("\"")
            setUrlWithoutDomain(element.select("div.peli_img div.peli_img_img a").attr("href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "#cn div ul.nav li ~ li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(".tab_container .tab_content iframe").map {
            SEpisode.create().apply {
                setUrlWithoutDomain(response.request.url.toString())
                name = "PELÍCULA"
                episode_number = 1f
                document.select("div.content div.details ul.dtalist li").map {
                    if (it.text().contains("Publicado:")) { date_upload = it.text().replace("Publicado:", "").trim().toDate() }
                }
            }
        }
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(".tab_container .tab_content iframe").parallelCatchingFlatMapBlocking { iframe ->
            with(iframe.attr("src")) {
                when {
                    contains("streamtape") || contains("stp") || contains("stape")
                    -> StreamTapeExtractor(client).videosFromUrl(this, quality = "StreamTape")
                    contains("doodstream") || contains("dood.") || contains("d000d") || contains("ds2play") || contains("doods.")
                    -> DoodExtractor(client).videosFromUrl(this, "DoodStream", true)
                    contains("ok.ru") || contains("okru") -> OkruExtractor(client).videosFromUrl(this)
                    contains("vidhide") || contains("streamhide") || contains("guccihide") || contains("streamvid")
                    -> StreamHideVidExtractor(client).videosFromUrl(this)
                    else -> emptyList()
                }
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar/?q=$query&page=$page")
            genreFilter.state != 0 && !genreFilter.toUriPart().contains("pelicula/") -> GET("$baseUrl/categoria/${genreFilter.toUriPart()}/?page=$page")
            genreFilter.state != 0 && genreFilter.toUriPart().contains("pelicula/") -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Últ. agregadas", "pelicula/ultimas-peliculas"),
            Pair("Últ. descargas", "pelicula/ultimas-descargas"),
            Pair("Más votadas", "pelicula/peliculas-mas-votadas"),
            Pair("Más visitadas", "pelicula/peliculas-mas-vistas"),
            Pair("Accion", "accion"),
            Pair("Adolescente", "adolescente"),
            Pair("Animacion e Infantil", "animacion-e-infantil"),
            Pair("Anime", "anime"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Asiaticas", "asiaticas"),
            Pair("Aventura", "aventura"),
            Pair("Bélico (Guerra)", "belico"),
            Pair("Ciencia Ficcion", "ciencia-ficcion"),
            Pair("Cine negro", "cine-negro"),
            Pair("Comedia", "comedia"),
            Pair("Deporte", "deporte"),
            Pair("Documentales", "documentales"),
            Pair("Drama", "drama"),
            Pair("Eroticas +18", "eroticas"),
            Pair("Fantasia", "fantasia"),
            Pair("Hindu", "hindu"),
            Pair("Intriga", "intriga"),
            Pair("Musical", "musical"),
            Pair("Religiosas", "religiosas"),
            Pair("Romance", "romance"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Western", "western"),
            Pair("Zombies", "zombies"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.intsd div.peli_img_int img")?.attr("abs:src")
            description = document.selectFirst("div.content span div.sinoptxt strong")?.text()?.removeSurrounding("\"")
            status = SAnime.COMPLETED
            document.select("div.content div.details ul.dtalist li").map { it.text().lowercase() }.map { textContent ->
                when {
                    "titulo latino" in textContent -> title = textContent.replace("titulo latino:", "").trim()
                    "genero" in textContent -> genre = textContent.replace("genero:", "").trim()
                }
            }
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/pelicula/ultimas-peliculas?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

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

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }
}
