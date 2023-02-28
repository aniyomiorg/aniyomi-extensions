package eu.kanade.tachiyomi.animeextension.it.animeforce

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimeForce : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeForce"

    override val baseUrl = "https://www.animeforce.it"

    override val lang = "it"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers = headers)
    }

    override fun popularAnimeSelector(): String = "div.container > div.row > div.anime-card"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("p").text()
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.page-item.disabled ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val interceptor = client.newBuilder().addInterceptor(CloudflareInterceptor()).build()
        val cfResponse = interceptor.newCall(GET(baseUrl)).execute()

        val inputEl = cfResponse.asJsoup().selectFirst("input[type=hidden]")!!
        val headers = cfResponse.request.headers

        return if (query.isNotBlank()) {
            GET("$baseUrl/?s=$query&${inputEl.attr("name")}=${inputEl.attr("value")}", headers = headers)
        } else {
            val url = "$baseUrl/genre/".toHttpUrl().newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET("$url/", headers = headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("p").text()
        anime.thumbnail_url = element.select("img").attr("src")
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "nav[aria-label=navigation] > ul > li.disabled ~ li"

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Nota: ignora la query di ricerca"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList()),
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Generi", vals)

    private fun getGenreList() = arrayOf(
        Pair("Arti Marziali", "arti-marziali"),
        Pair("Avventura", "avventura"),
        Pair("Azione", "azione"),
        Pair("Bambini", "bambini"),
        Pair("Cars", "cars"),
        Pair("Combattimento", "combattimento"),
        Pair("Commedia", "commedia"),
        Pair("Crimine", "crimine"),
        Pair("Cucina", "cucina"),
        Pair("Demenziale", "demenziale"),
        Pair("Demoni", "demoni"),
        Pair("Drammatico", "drammatico"),
        Pair("Ecchi", "ecchi"),
        Pair("Fantascienza", "fantascienza"),
        Pair("Fantasy", "fantasy"),
        Pair("Giallo", "giallo"),
        Pair("Gioco", "gioco"),
        Pair("Guerra", "guerra"),
        Pair("Harem", "harem"),
        Pair("Hentai", "hentai"),
        Pair("Horror", "horror"),
        Pair("Josei", "josei"),
        Pair("Magia", "magia"),
        Pair("Majokko", "majokko"),
        Pair("Mecha", "mecha"),
        Pair("Militare", "militare"),
        Pair("Mistero", "mistero"),
        Pair("Musica", "musica"),
        Pair("Parodia", "parodia"),
        Pair("Poliziesco", "poliziesco"),
        Pair("Psicologico", "psicologico"),
        Pair("Reverse-harem", "reverse-harem"),
        Pair("Samurai", "samurai"),
        Pair("Scolastico", "scolastico"),
        Pair("Seinen", "seinen"),
        Pair("Sentimentale", "sentimentale"),
        Pair("Shoujo", "shoujo"),
        Pair("Shoujo Ai", "shoujo-ai"),
        Pair("Shounen", "shounen"),
        Pair("Shounen Ai", "shounen-ai"),
        Pair("Slice of Life", "slice-of-life"),
        Pair("Soprannaturale", "soprannaturale"),
        Pair("Spazio", "spazio"),
        Pair("Splatter", "splatter"),
        Pair("Sport", "sport"),
        Pair("Storico", "storico"),
        Pair("Superpoteri", "superpoteri"),
        Pair("Thriller", "thriller"),
        Pair("Vampiri", "vampiri"),
        Pair("Visual Novel", "visual-novel"),
        Pair("Yaoi", "yaoi"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers = headers)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        val descElement = document.selectFirst("div.details-text > div:has(span:contains(Descrizione))")
        var desc = if (descElement == null) "" else descElement.ownText() + "\n"

        val stateElement = document.selectFirst("div.details-text > div:has(span:contains(Stato))")
        if (stateElement != null) desc += "\nStato: ${stateElement.select("a").text()}"
        anime.status = if (stateElement != null) parseStatus(stateElement.select("a").text()) else SAnime.UNKNOWN

        val typeElement = document.selectFirst("div.details-text > div:has(span:contains(Tipologia))")
        if (typeElement != null) desc += "\nTipologia: ${typeElement.select("a").text()}"

        val seasonElement = document.selectFirst("div.details-text > div:has(span:contains(Stagione))")
        if (seasonElement != null) desc += "\nStagione: ${seasonElement.select("a").text()}"

        anime.description = desc
        anime.title = document.selectFirst("div.details-text > div.anime-title")!!.text()
        anime.genre = document.selectFirst("div.details-text > div:has(span:contains(Genere))")?.let { gen ->
            gen.select("a").joinToString(", ") { it.text() }
        } ?: ""
        anime.thumbnail_url = document.selectFirst("div.info-content > div.info-image > img")!!.attr("src")

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(baseUrl + anime.url, headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val tabCElement = document.selectFirst("div.servers-container > div.tab-content > div.active")!!
        val selector = if (
            tabCElement.selectFirst(
                "div > div[id=pills-tabContent]",
            ) == null
        ) {
            "div.m-2 > a"
        } else {
            "div[id=pills-tabContent] > div > a"
        }

        episodeList.addAll(
            tabCElement.select(selector).map { ani ->
                SEpisode.create().apply {
                    name = ani.attr("title")
                    episode_number = ani.text().substringBefore("-").toFloatOrNull() ?: 0F
                    url = ani.attr("href").substringAfter(baseUrl)
                }
            },
        )

        document.select("div.servers-container > div.tab-content > div[role=tabpanel]").not(".active").forEach {
            episodeList.addAll(
                specialEpisodesFromElement(it, it.attr("id").substringAfter("-"), episodeList.size.toFloat()),
            )
        }

        return episodeList.reversed()
    }

    private fun specialEpisodesFromElement(element: Element, typeName: String, offset: Float): List<SEpisode> {
        val selector = if (element.selectFirst("div > div[id=pills-tabContent]") == null) "div.m-2 > a" else "div[id=pills-tabContent] > div > a"
        return element.select(selector).map { ani ->
            SEpisode.create().apply {
                name = "$typeName ${ani.attr("title")}"
                episode_number = offset + (ani.text().substringBefore("-").toFloatOrNull() ?: 0F)
                url = ani.attr("href").substringAfter(baseUrl)
            }
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    override fun episodeListSelector(): String = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers = headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val headers = Headers.headersOf(
            "Accept",
            "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
            "Accept-Language",
            "en-US,en;q=0.5",
            "Referer",
            "$baseUrl/",
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
        )

        val sourceElement = response.asJsoup().selectFirst("video > source")
        return if (sourceElement == null) {
            emptyList()
        } else {
            listOf(
                Video(
                    response.request.url.toString(),
                    "Best",
                    sourceElement.attr("src"),
                    headers = headers,
                ),
            )
        }
    }

    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    override fun videoFromElement(element: Element): Video = throw Exception("Not used")

    override fun videoListSelector(): String = throw Exception("Not used")

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "In Corso" -> SAnime.ONGOING
            "Completo" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) { }
}
