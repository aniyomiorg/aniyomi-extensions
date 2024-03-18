package eu.kanade.tachiyomi.animeextension.es.animeflv

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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.Exception

class AnimeFlv : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFLV"

    override val baseUrl = "https://www3.animeflv.net"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("StreamWish", "YourUpload", "Okru", "Streamtape")
    }

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?order=rating&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.Description a.Button").attr("abs:href"))
        anime.title = element.select("a h3").text()
        anime.thumbnail_url = try {
            element.select("a div.Image figure img").attr("src")
        } catch (e: Exception) {
            element.select("a div.Image figure img").attr("data-cfsrc")
        }
        anime.description = element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=\"next\"]"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        document.select("script").forEach { script ->
            if (script.data().contains("var anime_info =")) {
                val animeInfo = script.data().substringAfter("var anime_info = [").substringBefore("];")
                val arrInfo = json.decodeFromString<List<String>>("[$animeInfo]")

                val animeUri = arrInfo[2]!!.replace("\"", "")
                val episodes = script.data().substringAfter("var episodes = [").substringBefore("];").trim()
                val arrEpisodes = episodes.split("],[")
                arrEpisodes!!.forEach { arrEp ->
                    val noEpisode = arrEp!!.replace("[", "")!!.replace("]", "")!!.split(",")!![0]
                    val ep = SEpisode.create()
                    val url = "$baseUrl/ver/$animeUri-$noEpisode"
                    ep.setUrlWithoutDomain(url)
                    ep.name = "Episodio $noEpisode"
                    ep.episode_number = noEpisode.toFloat()
                    episodeList.add(ep)
                }
            }
        }
        return episodeList
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    /*--------------------------------Video extractors------------------------------------*/
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers.newBuilder().add("Referer", "$baseUrl/").build()) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val jsonString = document.selectFirst("script:containsData(var videos = {)")?.data() ?: return emptyList()
        val responseString = jsonString.substringAfter("var videos =").substringBefore(";").trim()
        return json.decodeFromString<ServerModel>(responseString).sub.parallelCatchingFlatMapBlocking {
            when (it.title) {
                "Stape" -> listOf(streamTapeExtractor.videoFromUrl(it.url ?: it.code)!!)
                "Okru" -> okruExtractor.videosFromUrl(it.url ?: it.code)
                "YourUpload" -> yourUploadExtractor.videoFromUrl(it.url ?: it.code, headers = headers)
                "SW" -> streamWishExtractor.videosFromUrl(it.url ?: it.code, videoNameGen = { "StreamWish:$it" })
                else -> emptyList()
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val stateFilter = filterList.find { it is StateFilter } as StateFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val orderByFilter = filterList.find { it is OrderByFilter } as OrderByFilter
        var uri = "$baseUrl/browse?"
        uri += if (query.isNotBlank()) "&q=$query" else ""
        uri += if (genreFilter.state != 0) "&genre[]=${genreFilter.toUriPart()}" else ""
        uri += if (stateFilter.state != 0) "&status[]=${stateFilter.toUriPart()}" else ""
        uri += if (typeFilter.state != 0) "&type[]=${typeFilter.toUriPart()}" else ""
        uri += "&order=${orderByFilter.toUriPart()}"
        uri += "&page=$page"
        return when {
            query.isNotBlank() || genreFilter.state != 0 || stateFilter.state != 0 || orderByFilter.state != 0 || typeFilter.state != 0 -> GET(uri)
            else -> GET("$baseUrl/browse?page=$page&order=rating")
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
        StateFilter(),
        TypeFilter(),
        OrderByFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", "all"),
            Pair("Todo", "all"),
            Pair("Acción", "accion"),
            Pair("Artes Marciales", "artes_marciales"),
            Pair("Aventuras", "aventura"),
            Pair("Carreras", "carreras"),
            Pair("Ciencia Ficción", "ciencia_ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Espacial", "espacial"),
            Pair("Fantasía", "fantasia"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Infantil", "infantil"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policía", "policia"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos de la vida", "recuentos_de_la_vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shounen", "shounen"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoderes", "superpoderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("En emisión", "1"),
            Pair("Finalizado", "2"),
            Pair("Próximamente", "3"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("TV", "tv"),
            Pair("Película", "movie"),
            Pair("Especial", "special"),
            Pair("OVA", "ova"),
        ),
    )

    private class OrderByFilter : UriPartFilter(
        "Ordenar Por",
        arrayOf(
            Pair("Por defecto", "default"),
            Pair("Recientemente Actualizados", "updated"),
            Pair("Recientemente Agregados", "added"),
            Pair("Nombre A-Z", "title"),
            Pair("Calificación", "rating"),
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
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.AnimeCover div.Image figure img")!!.attr("abs:src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container .Title")!!.text()
        anime.description = document.selectFirst("div.Description")!!.text().removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/browse?order=added&page=$page")

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

    @Serializable
    data class ServerModel(
        @SerialName("SUB")
        val sub: List<Sub> = emptyList(),
    )

    @Serializable
    data class Sub(
        val server: String? = "",
        val title: String? = "",
        val ads: Long? = null,
        val url: String? = null,
        val code: String = "",
        @SerialName("allow_mobile")
        val allowMobile: Boolean? = false,
    )
}
