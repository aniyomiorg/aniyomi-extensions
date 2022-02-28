package eu.kanade.tachiyomi.animeextension.pt.anitube

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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
class Anitube : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anitube"

    override val baseUrl = "https://www.anitube.vip"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // Popular
    override fun popularAnimeSelector(): String = "div.lista_de_animes div.ani_loop_item_img > a"
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.selectFirst("img").attr("title")
        anime.thumbnail_url = element.selectFirst("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-numbers:contains(Próximo)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        val hasNextPage = hasNextPage(document)
        return AnimesPage(animes, hasNextPage)
    }

    // Episodes
    override fun episodeListSelector(): String = "div.animepag_episodios_container > div.animepag_episodios_item > a"

    private fun getAllEps(response: Response): List<SEpisode> {
        val doc = if (response.request.url.toString().contains("/video/")) {
            getRealDoc(response.asJsoup())
        } else { response.asJsoup() }

        val epElementList = doc.select(episodeListSelector())
        val epList = mutableListOf<SEpisode>()
        epList.addAll(epElementList.map { episodeFromElement(it) })
        if (hasNextPage(doc)) {
            val next = doc.selectFirst(popularAnimeNextPageSelector()).attr("href")
            val request = GET(baseUrl + next)
            val newResponse = client.newCall(request).execute()
            epList.addAll(getAllEps(newResponse))
        }
        return epList
    }
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getAllEps(response).reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = try {
            element.selectFirst("div.animepag_episodios_item_views")
                .text()
                .substringAfter(" ").toFloat()
        } catch (e: NumberFormatException) { 0F }
        episode.name = element.selectFirst("div.animepag_episodios_item_nome").text()
        episode.date_upload = element.selectFirst("div.animepag_episodios_item_date")
            .text().toDate()
        return episode
    }

    // Video links
    override fun videoListParse(response: Response): List<Video> {
        val doc: Document = response.asJsoup()
        val hasFHD: Boolean = doc.selectFirst("div.abaItem:contains(FULLHD)") != null
        val serverUrl: String = doc.selectFirst("meta[itemprop=contentURL]").attr("content")
        val type: String = serverUrl.substringAfter("cz/").substringBefore("/")
        val qualities = listOfNotNull("SD", "HD", if (hasFHD) "FULLHD" else null)
        val paths = when (type) {
            "appsd" -> mutableListOf("mobilesd", "mobilehd")
            else -> mutableListOf("sdr2", "hdr2")
        }
        paths.add("fullhdr2")

        return qualities.mapIndexed { index, quality ->
            val path = paths[index]
            val url = serverUrl.replace(type, path)
            Video(url, quality, url, null)
        }.reversed()
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")
    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeSelector() = throw Exception("not used")

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isBlank()) {
            val year = filters.asUriPart<YearFilter>()
            val season = filters.asUriPart<SeasonFilter>()
            val genre = filters.asUriPart<GenreFilter>()
            val char = filters.asUriPart<CharacterFilter>()
            when {
                !season.isBlank() -> GET("$baseUrl/temporada/$season/$year")
                !genre.isBlank() -> GET("$baseUrl/genero/$genre/page/$page/${char.replace("todos", "")}")
                else -> GET("$baseUrl/anime/page/$page/letra/$char")
            }
        } else GET("$baseUrl/busca.php?s=$query&submit=Buscar")
    }

    // Anime Details
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)
        val content = doc.selectFirst("div.anime_container_content")
        val infos = content.selectFirst("div.anime_infos")

        anime.title = doc.selectFirst("div.anime_container_titulo").text()
        anime.thumbnail_url = content.selectFirst("img").attr("src")
        anime.genre = infos.getInfo("Gêneros")?.split(" ")?.joinToString(", ")
        anime.author = infos.getInfo("Autor")
        anime.artist = infos.getInfo("Estúdio")
        anime.status = parseStatus(infos.getInfo("Status"))

        var desc = doc.selectFirst("div.sinopse_container_content").text() + "\n"
        infos.getInfo("Ano")?.let { desc += "\nAno: $it" }
        infos.getInfo("Direção")?.let { desc += "\nDireção: $it" }
        infos.getInfo("Episódios")?.let { desc += "\nEpisódios: $it" }
        infos.getInfo("Temporada")?.let { desc += "\nTemporada: $it" }
        infos.getInfo("Alternativo")?.let { desc += "\nTítulo alternativo: $it" }
        anime.description = desc

        return anime
    }

    // Latest
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesSelector(): String = "div.mContainer_content.threeItensPerContent > div.epi_loop_item"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        anime.title = img.attr("title")
        anime.thumbnail_url = img.attr("src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(latestUpdatesSelector()).map { element ->
            latestUpdatesFromElement(element)
        }
        val hasNextPage = hasNextPage(document)
        return AnimesPage(animes, hasNextPage)
    }

    // Settings
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val values = arrayOf("SD", "HD", "FULLHD")
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualidade preferida"
            entries = values
            entryValues = values
            setDefaultValue("FULLHD")
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

    // Filters
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header(FILTER_IGNORE_MESSAGE),
        GenreFilter(),
        CharacterFilter(),
        AnimeFilter.Header(FILTER_SEASON_MESSAGE),
        SeasonFilter(),
        YearFilter(),
    )

    private class SeasonFilter() : UriPartFilter(
        "Temporada",
        arrayOf(
            Pair("Qualquer uma", ""),
            Pair("Inverno", "inverno"),
            Pair("Primavera", "primavera"),
            Pair("Verão", "verao"),
            Pair("Outono", "outono")
        )
    )

    private class YearFilter() : UriPartFilter(
        "Ano",
        (2022 downTo 1979).map {
            Pair(it.toString(), it.toString())
        }.toTypedArray()
    )

    private class CharacterFilter() : UriPartFilter(
        "Inicia com",
        arrayOf(
            Pair("Qualquer letra", "todos")
        ) + ('A'..'Z').map { Pair(it.toString(), it.toString()) }.toTypedArray()
    )

    private class GenreFilter() : UriPartFilter(
        "Gênero",
        arrayOf(
            Pair("Qualquer um", ""),
            Pair("Ação", "acao"),
            Pair("Artes marciais", "artes-marciais"),
            Pair("Aventura", "aventura"),
            Pair("CGI", "cgi"),
            Pair("Comédia", "comedia"),
            Pair("Demencia", "demencia"),
            Pair("Demônios", "demonios"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Espaço", "espaco"),
            Pair("Esporte", "esporte"),
            Pair("Fantasia", "fantasia"),
            Pair("Ficção Científica", "ficcao-cientifica"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harém", "harem"),
            Pair("Harém Reverso", "harem-reverso"),
            Pair("Hentai", "hentai"),
            Pair("Histórico", "historico"),
            Pair("Idol", "idol"),
            Pair("Isekai", "isekai"),
            Pair("Jogos", "jogos"),
            Pair("Josei", "josei"),
            Pair("Kodomo", "kodomo"),
            Pair("Live Action", "live-action"),
            Pair("Magia", "magia"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Mistério", "misterio"),
            Pair("Mundo Virtual", "mundo-virtual"),
            Pair("Musical", "musical"),
            Pair("Paródia", "parodia"),
            Pair("Policial", "policial"),
            Pair("Pós-Apocalíptico", "pos-apocaliptico"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo-ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen-ai", "shounen-ai"),
            Pair("Slice of life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Superpoder", "superpoder"),
            Pair("Suspense", "suspense"),
            Pair("Terror", "terror"),
            Pair("Thriller", "thriller"),
            Pair("Tokusatsu", "tokusatsu"),
            Pair("Tragédia", "tragedia"),
            Pair("Vampiros", "vampiros"),
            Pair("Vida Escolar", "vida-escolar"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
        )
    )
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // New functions
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.controles_ep > a[href] > i.spr.listaEP")
        if (menu != null) {
            val req = client.newCall(GET(baseUrl + menu.parent().attr("href"))).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            "Em Progresso" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun hasNextPage(document: Document): Boolean {
        val pagination = document.selectFirst("div.pagination")
        val items = pagination?.select("a.page-numbers")
        if (pagination == null || items!!.size == 0) return false
        val currentPage: Int = pagination.selectFirst("a.page-numbers.current")
            ?.attr("href")
            ?.toPageNum() ?: 1
        val lastPage: Int = items[items.lastIndex - 1]
            .attr("href")
            .toPageNum()
        return currentPage != lastPage
    }

    private inline fun <reified R> AnimeFilterList.asUriPart(): String {
        return this.filterIsInstance<R>().joinToString("") {
            (it as UriPartFilter).toUriPart()
        }
    }

    private fun String.toPageNum(): Int = try {
        this.substringAfter("page/")
            .substringAfter("page=")
            .substringBefore("/")
            .substringBefore("&").toInt()
    } catch (e: NumberFormatException) { 1 }

    private fun Element.getInfo(key: String): String? {
        val elementB: Element? = this.selectFirst("b:contains($key)")
        val parent = elementB?.parent()
        val text = parent?.text()
            ?.replace(elementB.html(), "")?.trim()
        if (text == "") return null
        return text
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.equals(quality)) {
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

    private fun String.toDate(): Long {
        return try {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        } catch (e: ParseException) {
            0L
        }
    }

    companion object {
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"
        private val DATE_FORMATTER by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH) }
        private val FILTER_IGNORE_MESSAGE = "Nota: Os filtros abaixo são IGNORADOS durante a busca."
        private val FILTER_SEASON_MESSAGE = "Nota: o filtro de temporada IGNORA o filtro de gênero/letra."
    }
}
