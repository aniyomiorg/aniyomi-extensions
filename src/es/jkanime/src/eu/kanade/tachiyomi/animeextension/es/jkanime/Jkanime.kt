package eu.kanade.tachiyomi.animeextension.es.jkanime

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
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

class Jkanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Jkanime"

    override val baseUrl = "https://jkanime.net"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
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
                    }
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

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        document.select("div.col-lg-12.rounded.bg-servers.text-white.p-3.mt-2 a").forEach { it ->
            val serverId = it.attr("data-id")
            val lang = if (it.attr("class").contains("lg_3")) "[LAT]" else ""
            val scriptServers = document.selectFirst("script:containsData(var video = [];)")
            val url = scriptServers.data().substringAfter("video[$serverId] = '<iframe class=\"player_conte\" src=\"")
                .substringBefore("\"")
                .replace("/jkfembed.php?u=", "https://embedsito.com/v/")
                .replace("/jkokru.php?u=", "http://ok.ru/videoembed/")
                .replace("/jkvmixdrop.php?u=", "https://mixdrop.co/e/")
                .replace("/jk.php?u=", "$baseUrl/")

            when {
                "embedsito" in url -> FembedExtractor(client).videosFromUrl(url, lang).forEach { videos.add(it) }
                "ok" in url -> OkruExtractor(client).videosFromUrl(url, lang).forEach { videos.add(it) }
                "stream/jkmedia" in url -> videos.add(Video(url, "${lang}Xtreme S", url))
                "um2.php" in url -> JkanimeExtractor(client).getNozomiFromUrl(baseUrl + url, lang).let { if (it != null) videos.add(it) }
                "um.php" in url -> JkanimeExtractor(client).getDesuFromUrl(baseUrl + url, lang).let { if (it != null) videos.add(it) }
            }
        }
        return videos
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")

    private fun List<Video>.sortIfContains(item: String): List<Video> {
        val newList = mutableListOf<Video>()
        for (video in this) {
            if (item in video.quality) {
                newList.add(0, video)
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Nozomi")!!
        return sortIfContains(quality)
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

        var url = baseUrl
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

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")
    override fun searchAnimeNextPageSelector(): String = throw Exception("not used")
    override fun searchAnimeSelector(): String = throw Exception("not used")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.col-lg-3 div.anime__details__pic.set-bg").attr("data-setbg")
        anime.title = document.selectFirst("div.anime__details__text div.anime__details__title h3").text()
        anime.description = document.select("div.col-lg-9 div.anime__details__text p").first().ownText()
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
        anime.setUrlWithoutDomain(
            element.select("div.row.g-0 div.col-md-5.custom_thumb2 a").attr("href")
        )
        anime.title = element.select("div.row.g-0 div.col-md-7 div.card-body h5.card-title a").text()
        anime.thumbnail_url = element.select("div.row.g-0 div.col-md-5.custom_thumb2 a img").attr("src")
        anime.description = element.select("div.row.g-0 div.col-md-7 div.card-body p.card-text.synopsis").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/directorio/$page/desc/")

    override fun latestUpdatesSelector(): String = "div.card.mb-3.custom_item2"

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto no incluye todos los filtros"),
        GenreFilter(),
        TypeFilter(),
        StateFilter(),
        SeasonFilter(),
        AnimeFilter.Header("Busqueda por año"),
        Tags("Año"),
        AnimeFilter.Header("Filtros de ordenamiento"),
        OrderByFilter(),
        SortModifiers()
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
            Pair("Isekai", "isekai")
        )
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Animes", "tv"),
            Pair("Películas", "peliculas"),
            Pair("Especiales", "especiales"),
            Pair("OVAS", "ovas"),
            Pair("ONAS", "onas")
        )
    )

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Cualquiera>", ""),
            Pair("En emisión", "emision"),
            Pair("Finalizado", "finalizados"),
            Pair("Por Estrenar", "estrenos")
        )
    )

    private class SeasonFilter : UriPartFilter(
        "Temporada",
        arrayOf(
            Pair("<Cualquiera>", ""),
            Pair("Primavera", "primavera"),
            Pair("Verano", "verano"),
            Pair("Otoño", "otoño"),
            Pair("Invierno", "invierno"),
        )
    )

    private class OrderByFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("Por fecha", "fecha"),
            Pair("Por nombre", "nombre"),
        )
    )

    private class SortModifiers : UriPartFilter(
        "De forma",
        arrayOf(
            Pair("Descendente", "desc"),
            Pair("Ascendente", "asc"),
        )
    )

    private class Tags(name: String) : AnimeFilter.Text(name)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", // Fembed
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", // Okru
            "Xtreme S", "HentaiJk", "Nozomi", "Desu" // video servers without resolution
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Nozomi")
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
