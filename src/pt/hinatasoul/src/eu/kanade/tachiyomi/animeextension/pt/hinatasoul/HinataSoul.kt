package eu.kanade.tachiyomi.animeextension.pt.hinatasoul

import android.app.Application
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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class HinataSoul : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Hinata Soul"

    override val baseUrl = "https://www.hinatasoul.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.FsssItem:contains(Mais Vistos) > a"

    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesSelector() =
        "div.tituloContainer:contains(lançamento) + div.epiContainer a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")!!
        thumbnail_url = img.attr("src")
        title = img.attr("alt")
    }

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$slug"))
                .awaitSuccess()
                .use(::searchAnimeBySlugParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeBySlugParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$baseUrl/busca?busca=$query&page=$page")

    override fun searchAnimeSelector() = episodeListSelector()

    override fun searchAnimeNextPageSelector() = null

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("src")
        title = element.selectFirst("div.ultimosAnimesHomeItemInfosNome")!!.text()
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
        val hasNext = hasNextPage(document)
        return AnimesPage(animes, hasNext)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())
        val infos = doc.selectFirst("div.aniInfosSingle")!!
        val img = infos.selectFirst("img")!!
        thumbnail_url = img.attr("src")
        title = img.attr("alt")
        genre = infos.select("div.aniInfosSingleGeneros > span")
            .eachText()
            .joinToString()

        author = infos.getInfo("AUTOR")
        artist = infos.getInfo("ESTÚDIO")
        status = parseStatus(infos.selectFirst("div.anime_status")!!)

        description = buildString {
            append(infos.selectFirst("div.aniInfosSingleSinopse > p")!!.text() + "\n")
            infos.getInfo("Título")?.also { append("\nTítulos Alternativos: $it") }
            infos.selectFirst("div.aniInfosSingleNumsItem:contains(Ano)")?.also {
                append("\nAno: ${it.ownText()}")
            }
            infos.getInfo("Temporada")?.also { append("\nTemporada: $it") }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.aniContainer a"
    override fun episodeListParse(response: Response): List<SEpisode> {
        var doc = getRealDoc(response.asJsoup())
        val totalEpisodes = buildList {
            do {
                if (isNotEmpty()) {
                    val url = doc.selectFirst("div.mwidth > a:containsOwn(»)")!!.absUrl("href")
                    doc = client.newCall(GET(url, headers)).execute().asJsoup()
                }
                doc.select(episodeListSelector())
                    .map(::episodeFromElement)
                    .also(::addAll)
            } while (hasNextPage(doc))
            reverse()
        }
        return totalEpisodes
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val title = element.attr("title")
        setUrlWithoutDomain(element.attr("href"))
        name = title
        episode_number = title.substringBeforeLast(" - FINAL").substringAfterLast(" ").toFloatOrNull() ?: 0F
        date_upload = element.selectFirst("div.lancaster_episodio_info_data")!!
            .text()
            .toDate()
    }

    // ============================ Video Links =============================
    private val extractor by lazy { HinataSoulExtractor(headers) }

    override fun videoListParse(response: Response) = extractor.getVideoList(response)

    override fun videoListSelector() = throw UnsupportedOperationException()
    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()
    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)
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
        val nextUrl = doc.selectFirst("a:contains(»)")!!.attr("href")
        val endings = listOf("/1", "page=1")
        return endings.none(nextUrl::endsWith) && currentUrl != nextUrl
    }

    private val animeMenuSelector = "div.controlesBoxItem > a:has(i.iconLista)"
    private fun getRealDoc(document: Document): Document {
        if (!document.location().contains("/videos/")) {
            return document
        }

        return document.selectFirst(animeMenuSelector)?.let {
            client.newCall(GET(it.attr("href"), headers)).execute()
                .asJsoup()
        } ?: document
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.aniInfosSingleInfoItem:contains($key) span")
            ?.text()
            ?.trim()
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd/MM/yyyy à's' HH:mm", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "FULLHD"
        private val PREF_QUALITY_VALUES = arrayOf("SD", "HD", "FULLHD")
    }
}
