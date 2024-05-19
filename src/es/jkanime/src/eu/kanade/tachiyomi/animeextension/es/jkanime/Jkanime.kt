package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.jkanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Jkanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Jkanime"

    override val baseUrl = "https://jkanime.net"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[JAP]"
        private val LANGUAGE_LIST = arrayOf("[JAP]", "[LAT]")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "Okru",
            "Mixdrop",
            "StreamWish",
            "Filemoon",
            "Mp4Upload",
            "StreamTape",
            "Desuka",
            "Nozomi",
            "Desu",
        )
    }

    override fun popularAnimeSelector(): String = "div.col-lg-12 div.list"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select("div#conb a").attr("title")
            thumbnail_url = element.select("div#conb a img").attr("src")
            description = element.select("div#conb div#animinfo p").text()
            setUrlWithoutDomain(element.select("div#conb a").attr("href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "uwu"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val episodeLink = response.request.url
        val pageBody = response.asJsoup()
        val animeId = pageBody.select("div.anime__details__text div.anime__details__title div#guardar-anime.btn.btn-light.btn-sm.ml-2")
            .attr("data-anime")
        val lastEp = client.newCall(GET("$baseUrl/ajax/last_episode/$animeId/")).execute().asJsoup().body().text()
            .substringAfter("number\":\"").substringBefore("\"").toIntOrNull() ?: 0

        // check if episode 0 exists
        // si no existe le navegador te redirige a https://jkanime.net/404.shtml
        client.newCall(GET("$episodeLink/0/")).execute().use { resp ->
            if (!resp.request.url.toString().contains("404.shtml")) {
                episodes.add(
                    SEpisode.create().apply {
                        name = "Episodio 0"
                        episode_number = 0f
                        setUrlWithoutDomain("$episodeLink/0/")
                    },
                )
            }
        }

        for (i in 1..lastEp) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain("$episodeLink/$i")
            episode.name = "Episodio $i"
            episode.episode_number = i.toFloat()
            episodes.add(episode)
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    private val languages = arrayOf(
        Pair("1", "[JAP]"),
        Pair("3", "[LAT]"),
        Pair("4", "[CHIN]"),
    )

    private fun String.getLang(): String {
        return languages.firstOrNull { it.first == this }?.second ?: ""
    }

    private fun getVideoLinks(document: Document): List<Pair<String, String>> {
        val scriptServers = document.selectFirst("script:containsData(var video = [];)")?.data() ?: return emptyList()
        val isRemote = scriptServers.contains("= remote+'", true)
        val jsServer = scriptServers.substringAfter("var remote = '").substringBefore("'")
        val jsPath = scriptServers.substringAfter("= remote+'").substringBefore("'")

        val jsLinks = if (isRemote && jsServer.isNotEmpty()) {
            client.newCall(GET(jsServer + jsPath)).execute().body.string()
        } else {
            scriptServers.substringAfter("var servers = ").substringBefore(";").substringBefore("var")
        }.parseAs<Array<JsLinks>>().map {
            Pair(String(Base64.decode(it.remote, Base64.DEFAULT)), "${it.lang}".getLang())
        }

        val htmlLinks = document.select("div.col-lg-12.rounded.bg-servers.text-white.p-3.mt-2 a").map {
            val serverId = it.attr("data-id")
            val lang = it.attr("class").substringAfter("lg_").substringBefore(" ").getLang()
            val url = scriptServers
                .substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.ag/e/")
                .replace("/jksw.php?u=", "https://sfastwish.com/e/")
                .replace("/jk.php?u=", "$baseUrl/")
            Pair(if (url.contains("um2.php") || url.contains("um.php")) baseUrl + url else url, lang)
        }

        return jsLinks + htmlLinks
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val jkanimeExtractor by lazy { JkanimeExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return getVideoLinks(document).parallelCatchingFlatMapBlocking { (url, lang) ->
            when {
                "ok" in url -> okruExtractor.videosFromUrl(url, "$lang ")
                "voe" in url -> voeExtractor.videosFromUrl(url, "$lang ")
                "filemoon" in url || "moonplayer" in url -> filemoonExtractor.videosFromUrl(url, "$lang Filemoon:")
                "streamtape" in url || "stp" in url || "stape" in url -> listOf(streamTapeExtractor.videoFromUrl(url, quality = "$lang StreamTape")!!)
                "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, prefix = "$lang ", headers = headers)
                "mixdrop" in url || "mdbekjwqa" in url -> mixDropExtractor.videosFromUrl(url, prefix = "$lang ")
                "sfastwish" in url || "wishembed" in url || "streamwish" in url || "strwish" in url || "wish" in url
                -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "$lang StreamWish:$it" })
                "stream/jkmedia" in url -> jkanimeExtractor.getDesukaFromUrl(url, "$lang ")
                "um2.php" in url -> jkanimeExtractor.getNozomiFromUrl(url, "$lang ")
                "um.php" in url -> jkanimeExtractor.getDesuFromUrl(url, "$lang ")
                else -> emptyList()
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val stateFilter = filterList.find { it is StateFilter } as StateFilter
        val seasonFilter = filterList.find { it is SeasonFilter } as SeasonFilter
        val orderByFilter = filterList.find { it is OrderByFilter } as OrderByFilter
        val sortModifiers = filterList.find { it is SortModifiers } as SortModifiers
        val tagFilter = filters.find { it is Tags } as Tags
        val dayFilter = filters.find { it is DayFilter } as DayFilter

        var url = baseUrl

        if (dayFilter.state != 0) {
            val day = dayFilter.toUriPart()
            return GET("$url/horario/#$day", headers)
        }

        if (query.isNotBlank()) {
            val types = listOf("TV", "Movie", "Special", "OVA", "ONA")
            url += "/buscar/$query/$page/"
            url += if (orderByFilter.state != 0) "?filtro=${orderByFilter.toUriPart()}" else "?filtro=nombre"
            url += if (typeFilter.state != 0) "&tipo=${ types.first {t -> t.lowercase() == typeFilter.toUriPart()} }" else "&tipo=none"
            url += if (stateFilter.state != 0) "&estado=${ if (stateFilter.toUriPart() == "emision") "1" else "2" }" else "&estado=none"
            url += if (sortModifiers.state != 0) "&orden=${sortModifiers.toUriPart()}" else "&orden=none"
        } else {
            url += "/directorio/$page/${orderByFilter.toUriPart()}"
            url += if (genreFilter.state != 0) "/${genreFilter.toUriPart()}" else ""
            url += if (typeFilter.state != 0) "/${typeFilter.toUriPart() }" else ""
            url += if (stateFilter.state != 0) "/${stateFilter.toUriPart()}" else ""
            url += if (tagFilter.state.isNotBlank()) "/${tagFilter.state}" else ""
            url += if (seasonFilter.state != 0) "/${seasonFilter.toUriPart()}" else ""
            url += "/${sortModifiers.toUriPart()}"
        }

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        if (document.location().startsWith("$baseUrl/horario")) {
            val day = document.location().substringAfterLast("#")
            val animeBox = document.selectFirst("div.horarybox div.box.semana:has(h2:contains($day))")
            val animeList = animeBox!!.select("div.box.img").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("a").attr("abs:href"))
                    title = it.select("a > h3").text()
                    thumbnail_url = it.select("a > img").attr("abs:src")
                }
            }
            return AnimesPage(animeList, false)
        }
        val hasNextPage = document.select("section.contenido.spad div.container div.navigation a.nav-next").any()
        val isSearch = document.select(".col-lg-2.col-md-6.col-sm-6").any()
        val animeList = if (isSearch) {
            document.select(".col-lg-2.col-md-6.col-sm-6").map { animeData ->
                val anime = SAnime.create()
                anime.title = animeData.select("div.anime__item #ainfo div.title").html()
                anime.thumbnail_url = animeData.select("div.anime__item a div.anime__item__pic").attr("data-setbg")
                anime.setUrlWithoutDomain(animeData.select("div.anime__item a").attr("href"))
                anime.status = parseStatus(animeData.select("div.anime__item div.anime__item__text ul li:nth-child(1)").html())
                anime.genre = animeData.select("div.anime__item div.anime__item__text ul li").joinToString { it.text() }
                anime
            }
        } else { // is filtered
            document.select(".card.mb-3.custom_item2").map { animeData ->
                latestUpdatesFromElement(animeData)
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()
    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.col-lg-3 div.anime__details__pic.set-bg")!!.attr("data-setbg")
        anime.title = document.selectFirst("div.anime__details__text div.anime__details__title h3")!!.text()
        anime.description = document.selectFirst("div.col-lg-9 div.anime__details__text p")!!.ownText()
        document.select("div.row div.col-lg-6.col-md-6 ul li").forEach { animeData ->
            val data = animeData.select("span").text()
            if (data.contains("Genero")) {
                anime.genre = animeData.select("a").joinToString { it.text() }
            }
            if (data.contains("Estado")) {
                anime.status = parseStatus(animeData.select("span").text())
            }
            if (data.contains("Studios")) {
                anime.author = animeData.select("a").text()
            }
        }

        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Por estrenar") -> SAnime.ONGOING
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Concluido") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "div.container div.navigation a.text.nav-next"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select(".custom_thumb2 > a").attr("abs:href"))
        anime.title = element.select(".card-title > a").text()
        anime.thumbnail_url = element.select(".custom_thumb2 a img").attr("abs:src")
        anime.description = element.select(".synopsis").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/directorio/$page/desc/")

    override fun latestUpdatesSelector(): String = "div.card.mb-3.custom_item2"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto no incluye todos los filtros"),
        DayFilter(),
        GenreFilter(),
        TypeFilter(),
        StateFilter(),
        SeasonFilter(),
        AnimeFilter.Header("Busqueda por año"),
        Tags("Año"),
        AnimeFilter.Header("Filtros de ordenamiento"),
        OrderByFilter(),
        SortModifiers(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", "none"),
            Pair("Español Latino", "espaol-latino"),
            Pair("Accion", "accion"),
            Pair("Aventura", "aventura"),
            Pair("Autos", "autos"),
            Pair("Comedia", "comedia"),
            Pair("Dementia", "dementia"),
            Pair("Demonios", "demonios"),
            Pair("Misterio", "misterio"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasìa", "fantasa"),
            Pair("Juegos", "juegos"),
            Pair("Hentai", "hentai"),
            Pair("Historico", "historico"),
            Pair("Terror", "terror"),
            Pair("Magia", "magia"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Mecha", "mecha"),
            Pair("Musica", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Samurai", "samurai"),
            Pair("Romance", "romance"),
            Pair("Colegial", "colegial"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Space", "space"),
            Pair("Deportes", "deportes"),
            Pair("Super Poderes", "super-poderes"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Harem", "harem"),
            Pair("Cosas de la vida", "cosas-de-la-vida"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Militar", "militar"),
            Pair("Policial", "policial"),
            Pair("Psicologico", "psicologico"),
            Pair("Thriller", "thriller"),
            Pair("Isekai", "isekai"),
        ),
    )

    private class DayFilter : UriPartFilter(
        "Dia de emisión",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Lunes", "Lunes"),
            Pair("Martes", "Martes"),
            Pair("Miércoles", "Miércoles"),
            Pair("Jueves", "Jueves"),
            Pair("Viernes", "Viernes"),
            Pair("Sábado", "Sábado"),
            Pair("Domingo", "Domingo"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Animes", "tv"),
            Pair("Películas", "peliculas"),
            Pair("Especiales", "especiales"),
            Pair("OVAS", "ovas"),
            Pair("ONAS", "onas"),
        ),
    )

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Cualquiera>", ""),
            Pair("En emisión", "emision"),
            Pair("Finalizado", "finalizados"),
            Pair("Por Estrenar", "estrenos"),
        ),
    )

    private class SeasonFilter : UriPartFilter(
        "Temporada",
        arrayOf(
            Pair("<Cualquiera>", ""),
            Pair("Primavera", "primavera"),
            Pair("Verano", "verano"),
            Pair("Otoño", "otoño"),
            Pair("Invierno", "invierno"),
        ),
    )

    private class OrderByFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Por fecha", "fecha"),
            Pair("Por nombre", "nombre"),
        ),
    )

    private class SortModifiers : UriPartFilter(
        "De forma",
        arrayOf(
            Pair("Descendente", "desc"),
            Pair("Ascendente", "asc"),
        ),
    )

    private class Tags(name: String) : AnimeFilter.Text(name)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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

    @Serializable
    data class JsLinks(
        val remote: String? = null,
        val server: String? = null,
        val lang: Long? = null,
        val slug: String? = null,
    )
}
