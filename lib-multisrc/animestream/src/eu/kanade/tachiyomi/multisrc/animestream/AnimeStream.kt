package eu.kanade.tachiyomi.multisrc.animestream

import android.app.Application
import android.util.Base64
import android.util.Log
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
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.GenresFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.OrderFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.SeasonFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.StatusFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.StudioFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.SubFilter
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters.TypeFilter
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

abstract class AnimeStream(
    override val lang: String,
    override val name: String,
    override val baseUrl: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val supportsLatest = true

    protected open val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
    }

    protected open val prefQualityDefault = "720p"
    protected open val prefQualityKey = "preferred_quality"
    protected open val prefQualityTitle = when (lang) {
        "pt-BR" -> "Qualidade preferida"
        else -> "Preferred quality"
    }
    protected open val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p")
    protected open val prefQualityEntries = prefQualityValues

    protected open val videoSortPrefKey = prefQualityKey
    protected open val videoSortPrefDefault = prefQualityDefault

    protected open val dateFormatter by lazy {
        val locale = when (lang) {
            "pt-BR" -> Locale("pt", "BR")
            else -> Locale.ENGLISH
        }
        SimpleDateFormat("MMMM d, yyyy", locale)
    }

    protected open val animeListUrl = "$baseUrl/anime"

    // ============================== Popular ===============================
    override suspend fun getPopularAnime(page: Int): AnimesPage {
        fetchFilterList()
        return super.getPopularAnime(page)
    }

    override fun popularAnimeRequest(page: Int) = GET("$animeListUrl/?page=$page&order=popular")

    override fun popularAnimeSelector() = searchAnimeSelector()

    override fun popularAnimeFromElement(element: Element) = searchAnimeFromElement(element)

    override fun popularAnimeNextPageSelector(): String? = searchAnimeNextPageSelector()

    // =============================== Latest ===============================
    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        fetchFilterList()
        return super.getLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$animeListUrl/?page=$page&order=update")

    override fun latestUpdatesSelector() = searchAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String? = searchAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    protected open fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeStreamFilters.getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val multiString = buildString {
                if (params.genres.isNotEmpty()) append(params.genres + "&")
                if (params.seasons.isNotEmpty()) append(params.seasons + "&")
                if (params.studios.isNotEmpty()) append(params.studios + "&")
            }

            GET("$animeListUrl/?page=$page&$multiString&status=${params.status}&type=${params.type}&sub=${params.sub}&order=${params.order}")
        }
    }

    override fun searchAnimeSelector() = "div.listupd article a.tip"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.selectFirst("div.tt, div.ttl")!!.ownText()
            thumbnail_url = element.selectFirst("img")?.getImageUrl()
        }
    }

    override fun searchAnimeNextPageSelector(): String? = "div.pagination a.next, div.hpage > a.r"

    // ============================== Filters ===============================

    /**
     * Disable it if you don't want the filters to be automatically fetched.
     */
    protected open val fetchFilters = true

    protected open val filtersSelector = "span.sec1 > div.filter > ul"

    private fun fetchFilterList() {
        if (fetchFilters && !AnimeStreamFilters.filterInitialized()) {
            AnimeStreamFilters.filterElements = runBlocking {
                withContext(Dispatchers.IO) {
                    client.newCall(GET(animeListUrl)).execute()
                        .asJsoup()
                        .select(filtersSelector)
                }
            }
        }
    }

    protected open val filtersHeader = when (lang) {
        "pt-BR" -> "NOTA: Filtros serão ignorados se usar a pesquisa por nome!"
        else -> "NOTE: Filters are going to be ignored if using search text!"
    }

    protected open val filtersMissingWarning: String = when (lang) {
        "pt-BR" -> "Aperte 'Redefinir' para tentar mostrar os filtros"
        else -> "Press 'Reset' to attempt to show the filters"
    }

    protected open val genresFilterText = when (lang) {
        "pt-BR" -> "Gêneros"
        else -> "Genres"
    }

    protected open val seasonsFilterText = when (lang) {
        "pt-BR" -> "Temporadas"
        else -> "Seasons"
    }

    protected open val studioFilterText = when (lang) {
        "pt-BR" -> "Estúdios"
        else -> "Studios"
    }

    protected open val statusFilterText = "Status"

    protected open val typeFilterText = when (lang) {
        "pt-BR" -> "Tipo"
        else -> "Type"
    }

    protected open val subFilterText = when (lang) {
        "pt-BR" -> "Legenda"
        else -> "Subtitle"
    }

    protected open val orderFilterText = when (lang) {
        "pt-BR" -> "Ordem"
        else -> "Order"
    }

    override fun getFilterList(): AnimeFilterList {
        return if (fetchFilters && AnimeStreamFilters.filterInitialized()) {
            AnimeFilterList(
                GenresFilter(genresFilterText),
                SeasonFilter(seasonsFilterText),
                StudioFilter(studioFilterText),
                AnimeFilter.Separator(),
                StatusFilter(statusFilterText),
                TypeFilter(typeFilterText),
                SubFilter(subFilterText),
                OrderFilter(orderFilterText),
            )
        } else if (fetchFilters) {
            AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
        } else {
            AnimeFilterList()
        }
    }

    // =========================== Anime Details ============================
    protected open val animeDetailsSelector = "div.info-content, div.right ul.data"
    protected open val animeAltNameSelector = ".alter"
    protected open val animeTitleSelector = "h1.entry-title"
    protected open val animeThumbnailSelector = "div.thumb > img, div.limage > img"
    protected open val animeGenresSelector = "div.genxed > a, li:contains(Genre:) a"
    protected open val animeDescriptionSelector = ".entry-content[itemprop=description], .desc"
    protected open val animeAdditionalInfoSelector = "div.spe > span, li:has(b)"

    protected open val animeStatusText = "Status"
    protected open val animeAuthorText = "Fansub"
    protected open val animeArtistText = when (lang) {
        "pt-BR" -> "Estudio"
        else -> "Studio"
    }

    protected open val animeAltNamePrefix = when (lang) {
        "pt-BR" -> "Nome(s) alternativo(s): "
        else -> "Alternative name(s): "
    }

    protected open fun getAnimeDescription(document: Document) =
        document.selectFirst(animeDescriptionSelector)?.text()

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst(animeTitleSelector)!!.text()
            thumbnail_url = document.selectFirst(animeThumbnailSelector)?.getImageUrl()

            val infos = document.selectFirst(animeDetailsSelector)!!
            genre = infos.select(animeGenresSelector).eachText().joinToString()

            status = parseStatus(infos.getInfo(animeStatusText))
            artist = infos.getInfo(animeArtistText)
            author = infos.getInfo(animeAuthorText)

            description = buildString {
                getAnimeDescription(document)?.also {
                    append("$it\n\n")
                }

                document.selectFirst(animeAltNameSelector)?.text()
                    ?.takeIf(String::isNotBlank)
                    ?.also { append("$animeAltNamePrefix$it\n") }

                infos.select(animeAdditionalInfoSelector).eachText().forEach {
                    append("$it\n")
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        return doc.select(episodeListSelector()).map(::episodeFromElement)
    }

    override fun episodeListSelector() = "div.eplister > ul > li > a"

    protected open val episodePrefix = when (lang) {
        "pt-BR" -> "Episódio"
        else -> "Episode"
    }

    @Suppress("unused_parameter")
    protected open fun getEpisodeName(element: Element, epNum: String) = "$episodePrefix $epNum"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            element.selectFirst(".epl-num")!!.text().let {
                name = getEpisodeName(element, it)
                episode_number = it.substringBefore(" ").toFloatOrNull() ?: 0F
            }
            element.selectFirst(".epl-sub")?.text()?.let { scanlator = it }
            date_upload = element.selectFirst(".epl-date")?.text().toDate()
        }
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = "select.mirror > option[data-index], ul.mirror a[data-em]"

    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        return items.parallelCatchingFlatMapBlocking { element ->
            val name = element.text()
            val url = getHosterUrl(element)
            getVideoList(url, name)
        }
    }

    protected open fun getHosterUrl(element: Element): String {
        val encodedData = when (element.tagName()) {
            "option" -> element.attr("value")
            "a" -> element.attr("data-em")
            else -> throw Exception()
        }

        return getHosterUrl(encodedData)
    }

    // Taken from LuciferDonghua
    protected open fun getHosterUrl(encodedData: String): String {
        val doc = if (encodedData.toHttpUrlOrNull() == null) {
            Base64.decode(encodedData, Base64.DEFAULT)
                .let(::String) // bytearray -> string
                .let(Jsoup::parse) // string -> document
        } else {
            client.newCall(GET(encodedData, headers)).execute().asJsoup()
        }

        return doc.selectFirst("iframe[src~=.]")?.safeUrl()
            ?: doc.selectFirst("meta[content~=.][itemprop=embedUrl]")!!.safeUrl("content")
    }

    private fun Element.safeUrl(attribute: String = "src"): String {
        val value = attr(attribute)
        return when {
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            else -> absUrl(attribute).ifEmpty { value }
        }
    }

    protected open fun getVideoList(url: String, name: String): List<Video> {
        Log.i(name, "getVideoList -> URL => $url || Name => $name")
        return emptyList()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = prefQualityKey
            title = prefQualityTitle
            entries = prefQualityEntries
            entryValues = prefQualityValues
            setDefaultValue(prefQualityDefault)
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
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy { it.quality.contains(quality, true) },
        ).reversed()
    }

    protected open fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "completed", "completo" -> SAnime.COMPLETED
            "ongoing", "lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    protected open fun Element.getInfo(text: String): String? {
        return selectFirst("span:contains($text)")
            ?.run {
                selectFirst("a")?.text() ?: ownText()
            }
    }

    protected open fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                dateFormatter.parse(trim())?.time
            }.getOrNull()
        } ?: 0L
    }

    /**
     * Tries to get the image url via various possible attributes.
     * Taken from Tachiyomi's Madara multisrc.
     */
    protected open fun Element.getImageUrl(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }.substringBefore("?resize")
    }
}
