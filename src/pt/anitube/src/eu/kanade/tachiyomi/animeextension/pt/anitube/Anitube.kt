package eu.kanade.tachiyomi.animeextension.pt.anitube

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.anitube.extractors.AnitubeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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
import java.text.SimpleDateFormat
import java.util.Locale

class Anitube : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anitube"

    override val baseUrl = "https://www.anitube.vip"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div.lista_de_animes div.ani_loop_item_img > a"
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val img = element.selectFirst("img")!!
            title = img.attr("title")
            thumbnail_url = img.attr("src")
        }
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

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "div.animepag_episodios_container > div.animepag_episodios_item > a"

    private fun getAllEps(response: Response): List<SEpisode> {
        val doc = response.asJsoup().let {
            if (response.request.url.toString().contains("/video/")) {
                getRealDoc(it)
            } else { it }
        }

        val epElementList = doc.select(episodeListSelector())
        val epList = mutableListOf<SEpisode>()
        epList.addAll(epElementList.map(::episodeFromElement))
        if (hasNextPage(doc)) {
            val next = doc.selectFirst(popularAnimeNextPageSelector())!!.attr("href")
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
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            episode_number = runCatching {
                element.selectFirst("div.animepag_episodios_item_views")!!
                    .text()
                    .substringAfter(" ")
                    .toFloat()
            }.getOrDefault(0F)
            name = element.selectFirst("div.animepag_episodios_item_nome")!!.text()
            date_upload = element.selectFirst("div.animepag_episodios_item_date")!!
                .text()
                .toDate()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response) = AnitubeExtractor.getVideoList(response)
    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")
    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeSelector() = throw Exception("not used")

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList = AnitubeFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isBlank()) {
            val params = AnitubeFilters.getSearchParameters(filters)
            val season = params.season
            val genre = params.genre
            val year = params.year
            val char = params.initialChar
            when {
                !season.isBlank() -> GET("$baseUrl/temporada/$season/$year")
                !genre.isBlank() -> GET("$baseUrl/genero/$genre/page/$page/${char.replace("todos", "")}")
                else -> GET("$baseUrl/anime/page/$page/letra/$char")
            }
        } else {
            GET("$baseUrl/busca.php?s=$query&submit=Buscar")
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            val content = doc.selectFirst("div.anime_container_content")!!
            val infos = content.selectFirst("div.anime_infos")!!

            title = doc.selectFirst("div.anime_container_titulo")!!.text()
            thumbnail_url = content.selectFirst("img")!!.attr("src")
            genre = infos.getInfo("Gêneros")
            author = infos.getInfo("Autor")
            artist = infos.getInfo("Estúdio")
            status = parseStatus(infos.getInfo("Status"))

            val infoItems = listOf("Ano", "Direção", "Episódios", "Temporada", "Título Alternativo")

            description = buildString {
                append(doc.selectFirst("div.sinopse_container_content")!!.text() + "\n")
                infoItems.forEach { item ->
                    infos.getInfo(item)?.let { append("\n$item: $it") }
                }
            }
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesSelector(): String = "div.mContainer_content.threeItensPerContent > div.epi_loop_item"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val img = element.selectFirst("img")!!
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            title = img.attr("title")
            thumbnail_url = img.attr("src")
        }
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

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_VALUES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
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

    // ============================= Utilities ==============================
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.controles_ep > a[href] > i.spr.listaEP")
        if (menu != null) {
            val parent = menu.parent()!!
            val parentPath = parent.attr("href")
            val req = client.newCall(GET(baseUrl + parentPath)).execute()
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
        if (pagination == null || items!!.size < 2) return false
        val currentPage = pagination.selectFirst("a.page-numbers.current")
            ?.attr("href")
            ?.toPageNum() ?: 1
        val lastPage = items[items.lastIndex - 1]
            .attr("href")
            .toPageNum()
        return currentPage != lastPage
    }

    private fun String.toPageNum(): Int = try {
        this.substringAfter("page/")
            .substringAfter("page=")
            .substringBefore("/")
            .substringBefore("&").toInt()
    } catch (e: NumberFormatException) { 1 }

    private fun Element.getInfo(key: String): String? {
        val parent = selectFirst("b:contains($key)")?.parent()
        val genres = parent?.select("a")
        val text = if (genres?.size == 0) {
            parent.ownText()
        } else {
            genres?.joinToString(", ") { it.text() }
        }
        if (text == "") return null
        return text
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.quality.equals(quality) },
        )
    }

    private fun String.toDate(): Long {
        return runCatching {
            DATE_FORMATTER.parse(this)?.time ?: 0L
        }.getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy { SimpleDateFormat("dd/MM/yyyy", Locale.ENGLISH) }

        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_VALUES = arrayOf("SD", "HD", "FULLHD")
    }
}
