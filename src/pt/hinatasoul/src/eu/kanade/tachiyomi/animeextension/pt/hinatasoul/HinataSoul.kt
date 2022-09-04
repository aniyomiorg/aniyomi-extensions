package eu.kanade.tachiyomi.animeextension.pt.hinatasoul

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.hinatasoul.extractors.HinataSoulExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class HinataSoul : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Hinata Soul"

    override val baseUrl = "https://www.hinatasoul.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.FsssItem:contains(Mais Vistos) > a"
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)
    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.text()
    }
    override fun popularAnimeNextPageSelector(): String? = null

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.aniContainer a"
    override fun episodeListParse(response: Response): List<SEpisode> {
        val totalEpisodes = mutableListOf<SEpisode>()
        var doc = getRealDoc(response.asJsoup())
        val originalUrl = doc.location()
        var pageNum = 1
        do {
            if (pageNum > 1) {
                doc = client.newCall(GET(originalUrl + "/page/$pageNum"))
                    .execute()
                    .asJsoup()
            }
            doc.select(episodeListSelector()).forEach {
                totalEpisodes.add(episodeFromElement(it))
            }
            pageNum++
        } while (hasNextPage(doc))
        return totalEpisodes.reversed()
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val title = element.attr("title")
        setUrlWithoutDomain(element.attr("href"))
        name = title
        episode_number = runCatching { title.substringAfterLast(" ").toFloat() }
            .getOrNull() ?: 0F
        date_upload = element.selectFirst("div.lancaster_episodio_info_data")
            .text()
            .toDate()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        return HinataSoulExtractor(headers).getVideoList(response)
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeSelector(): String = episodeListSelector()
    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img").attr("src")
        title = element.selectFirst("div.ultimosAnimesHomeItemInfosNome").text()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map {
            searchAnimeFromElement(it)
        }
        val hasNext = hasNextPage(document)
        return AnimesPage(animes, hasNext)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$slug"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        } else {
            client.newCall(searchAnimeRequest(page, query, filters))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/animes/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/busca?busca=$query&page=$page")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)
        val infos = doc.selectFirst("div.aniInfosSingle")
        val img = infos.selectFirst("img")
        anime.thumbnail_url = img.attr("src")
        anime.title = img.attr("alt")
        anime.genre = infos.select("div.aniInfosSingleGeneros > span")
            .joinToString(", ") { it.text() }

        anime.author = infos.getInfo("AUTOR")
        anime.artist = infos.getInfo("ESTÚDIO")
        anime.status = parseStatus(infos.selectFirst("div.anime_status"))

        var desc = infos.selectFirst("div.aniInfosSingleSinopse > p").text() + "\n"
        infos.getInfo("Título")?.let { desc += "\nTítulos Alternativos: $it" }
        infos.selectFirst("div.aniInfosSingleNumsItem:contains(Ano)")?.let {
            desc += "\nAno: ${it.ownText()}"
        }
        infos.getInfo("Temporada")?.let { desc += "\nTemporada: $it" }
        anime.description = desc

        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesSelector(): String =
        "div.tituloContainer:contains(lançamento) + div.epiContainer a"
    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        thumbnail_url = img.attr("src")
        title = img.attr("alt")
    }
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(QUALITY_LIST.last())
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

    private fun parseStatus(element: Element): Int {
        return when {
            element.hasClass("completed") -> SAnime.COMPLETED
            element.hasClass("airing") -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun hasNextPage(doc: Document): Boolean {
        val currentUrl = doc.location()
        val nextUrl = doc.selectFirst("a:contains(»)").attr("href")
        return !nextUrl.endsWith("1") && currentUrl != nextUrl
    }

    private val animeMenuSelector = "div.controlesBoxItem > a > i.iconLista"
    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        if (menu != null) {
            val originalUrl = menu.parent().attr("href")
            val req = client.newCall(GET(originalUrl, headers)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun Element.getInfo(key: String): String? {
        val div = this.selectFirst("div.aniInfosSingleInfoItem:contains($key)")
        if (div == null) return div
        val span = div.selectFirst("span")
        return span.text()
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREFERRED_QUALITY, "FULLHD")!!
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (video.quality.trim() == quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd/MM/yyyy à's' HH:mm", Locale.ENGLISH)
        }

        private const val PREFERRED_QUALITY = "preferred_quality"
        private val QUALITY_LIST = arrayOf("SD", "HD", "FULLHD")

        const val PREFIX_SEARCH = "slug:"
    }
}
