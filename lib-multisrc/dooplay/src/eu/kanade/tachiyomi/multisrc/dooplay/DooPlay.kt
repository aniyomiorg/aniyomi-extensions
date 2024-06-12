package eu.kanade.tachiyomi.multisrc.dooplay

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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Multisrc class for the DooPlay wordpress theme.
 * This class takes some inspiration from Tachiyomi's Madara multisrc class.
 */
abstract class DooPlay(
    override val lang: String,
    override val name: String,
    override val baseUrl: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    protected open val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        /**
         * Useful for the URL intent handler.
         */
        const val PREFIX_SEARCH = "path:"
    }

    protected open val prefQualityDefault = "720p"
    protected open val prefQualityKey = "preferred_quality"
    protected open val prefQualityTitle = when (lang) {
        "pt-BR" -> "Qualidade preferida"
        else -> "Preferred quality"
    }
    protected open val prefQualityValues = arrayOf("480p", "720p")
    protected open val prefQualityEntries = prefQualityValues

    protected open val videoSortPrefKey = prefQualityKey
    protected open val videoSortPrefDefault = prefQualityDefault

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "article.w_item_a > a"

    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val img = element.selectFirst("img")!!
            val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")
            setUrlWithoutDomain(url)
            title = img.attr("alt")
            thumbnail_url = img.getImageUrl()
        }
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeParse(response: Response): AnimesPage {
        fetchGenresList()
        return super.popularAnimeParse(response)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "ul.episodios > li"

    protected open val episodeNumberRegex by lazy { "(\\d+)$".toRegex() }
    protected open val seasonListSelector = "div#seasons > div"
    protected open val episodeDateSelector = ".date"

    protected open val episodeMovieText = when (lang) {
        "pt-BR" -> "Filme"
        else -> "Movie"
    }

    protected open val episodeSeasonPrefix = when (lang) {
        "pt-BR" -> "Temporada"
        else -> "Season"
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealAnimeDoc(response.asJsoup())
        val seasonList = doc.select(seasonListSelector)
        return if (seasonList.size < 1) {
            SEpisode.create().apply {
                setUrlWithoutDomain(doc.location())
                episode_number = 1F
                name = episodeMovieText
            }.let(::listOf)
        } else {
            seasonList.flatMap(::getSeasonEpisodes).reversed()
        }
    }

    protected open fun getSeasonEpisodes(season: Element): List<SEpisode> {
        val seasonName = season.selectFirst("span.se-t")!!.text()
        return season.select(episodeListSelector()).mapNotNull { element ->
            runCatching {
                episodeFromElement(element, seasonName)
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    protected open fun episodeFromElement(element: Element, seasonName: String): SEpisode {
        return SEpisode.create().apply {
            val epNum = element.selectFirst("div.numerando")!!.text()
                .trim()
                .let(episodeNumberRegex::find)
                ?.groupValues
                ?.last() ?: "0"
            val href = element.selectFirst("a[href]")!!
            val episodeName = href.ownText()
            episode_number = epNum.toFloatOrNull() ?: 0F
            date_upload = element.selectFirst(episodeDateSelector)
                ?.text()
                ?.toDate() ?: 0L
            name = "$episodeSeasonPrefix $seasonName x $epNum - $episodeName"
            setUrlWithoutDomain(href.attr("href"))
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        val animes = when {
            "/?s=" in url -> { // Search by name.
                document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
            }
            else -> { // Search by some kind of filter, like genres or popularity.
                document.select(latestUpdatesSelector()).map { element ->
                    popularAnimeFromElement(element)
                }
            }
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path", headers))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isBlank() -> {
                filters
                    .firstOrNull { it.state != 0 }
                    ?.let {
                        val filter = it as UriPartFilter
                        val filterUrl = buildString {
                            append("$baseUrl/${filter.toUriPart()}")
                            if (page > 1) append("/page/$page")
                        }
                        GET(filterUrl, headers)
                    } ?: popularAnimeRequest(page)
            }
            else -> GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val img = element.selectFirst("img")!!
            title = img.attr("alt")
            thumbnail_url = img.getImageUrl()
        }
    }

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeSelector() = "div.result-item div.image a"

    // =========================== Anime Details ============================

    /**
     * Selector for the element on the anime details page that have some
     * additional info about the anime.
     *
     * @see [Element.getInfo]
     */
    protected open val additionalInfoSelector = "div#info"

    protected open val additionalInfoItems = when (lang) {
        "pt-BR" -> listOf("Título", "Ano", "Temporadas", "Episódios")
        else -> listOf("Original", "First", "Last", "Seasons", "Episodes")
    }

    protected open fun Document.getDescription(): String {
        return selectFirst("$additionalInfoSelector p")
            ?.let { it.text() + "\n" }
            ?: ""
    }
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        val sheader = doc.selectFirst("div.sheader")!!
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            sheader.selectFirst("div.poster > img")!!.let {
                thumbnail_url = it.getImageUrl()
                title = it.attr("alt").ifEmpty {
                    sheader.selectFirst("div.data > h1")!!.text()
                }
            }

            genre = sheader.select("div.data > div.sgeneros > a")
                .eachText()
                .joinToString()

            doc.selectFirst(additionalInfoSelector)?.let { info ->
                description = buildString {
                    append(doc.getDescription())
                    additionalInfoItems.forEach {
                        info.getInfo(it)?.let(::append)
                    }
                }
            }
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.resppages > a > span.fa-chevron-right"

    override fun latestUpdatesSelector() = "div.content article > div.poster"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    protected open val latestUpdatesPath = when (lang) {
        "pt-BR" -> "episodio"
        else -> "episodes"
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/$latestUpdatesPath/page/$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        fetchGenresList()
        return super.latestUpdatesParse(response)
    }

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

    // ============================== Filters ===============================

    /**
     * Disable it if you don't want the genres to be fetched.
     */
    protected open val fetchGenres = true

    /**
     * Automatically fetched genres from the source to be used in the filters.
     */
    protected open lateinit var genresArray: FilterItems

    override fun getFilterList(): AnimeFilterList {
        return if (this::genresArray.isInitialized) {
            AnimeFilterList(
                AnimeFilter.Header(genreFilterHeader),
                FetchedGenresFilter(genresListMessage, genresArray),
            )
        } else if (fetchGenres) {
            AnimeFilterList(AnimeFilter.Header(genresMissingWarning))
        } else {
            AnimeFilterList()
        }
    }

    /**
     * Fetch the genres from the source to be used in the filters.
     */
    protected open fun fetchGenresList() {
        if (!this::genresArray.isInitialized && fetchGenres) {
            runCatching {
                client.newCall(genresListRequest())
                    .execute()
                    .asJsoup()
                    .let(::genresListParse)
                    .let { items ->
                        if (items.isNotEmpty()) {
                            genresArray = items
                        }
                    }
            }.onFailure { it.printStackTrace() }
        }
    }

    /**
     * The request to the page that have the genres list.
     */
    protected open fun genresListRequest() = GET(baseUrl)

    /**
     * Get the genres from the document.
     */
    protected open fun genresListParse(document: Document): FilterItems {
        val items = document.select(genresListSelector()).map {
            val name = it.text()
            val value = it.attr("href").substringAfter("$baseUrl/")
            Pair(name, value)
        }.toTypedArray()

        return if (items.isEmpty()) {
            items
        } else {
            arrayOf(Pair(selectFilterText, "")) + items
        }
    }

    protected open val selectFilterText = when (lang) {
        "pt-BR" -> "<Selecione>"
        else -> "<Select>"
    }

    protected open val genreFilterHeader = when (lang) {
        "pt-BR" -> "NOTA: Filtros serão ignorados se usar a pesquisa por nome!"
        else -> "NOTE: Filters are going to be ignored if using search text!"
    }

    protected open val genresMissingWarning: String = when (lang) {
        "pt-BR" -> "Aperte 'Redefinir' para tentar mostrar os gêneros"
        else -> "Press 'Reset' to attempt to show the genres"
    }

    protected open val genresListMessage = when (lang) {
        "pt-BR" -> "Gênero"
        else -> "Genre"
    }

    protected open fun genresListSelector() = "li:contains($genresListMessage) ul.sub-menu li > a"

    class FetchedGenresFilter(title: String, items: FilterItems) : UriPartFilter(title, items)

    open class UriPartFilter(
        displayName: String,
        private val vals: FilterItems,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toUriPart() = vals[state].second
    }

    @Suppress("UNUSED")
    private inline fun <reified R> AnimeFilterList.asUriPart(): String {
        return this.first { it is R }.let { it as UriPartFilter }.toUriPart()
    }

    // ============================= Utilities ==============================

    /**
     * The selector to the item in the menu (in episodes page) with the
     * anime page url.
     */
    protected open val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-bars"

    /**
     * If the document comes from a episode page, this function will get the
     * real/expected document from the anime details page. else, it will return the
     * original document.
     *
     * @return A document from a anime details page.
     */
    protected open fun getRealAnimeDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        return if (menu != null) {
            val originalUrl = menu.parent()!!.attr("href")
            val req = client.newCall(GET(originalUrl, headers)).execute()
            req.asJsoup()
        } else {
            document
        }
    }

    /**
     * Tries to get additional info from an element at a anime details page,
     * like how many seasons it have, the year it was aired, etc.
     * Useful for anime description.
     */
    protected open fun Element.getInfo(substring: String): String? {
        val target = selectFirst("div.custom_fields:contains($substring)")
            ?: return null
        val key = target.selectFirst("b")!!.text()
        val value = target.selectFirst("span")!!.text()
        return "\n$key: $value"
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
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy { it.quality.lowercase().contains(quality.lowercase()) },
        ).reversed()
    }

    protected open val dateFormatter by lazy {
        SimpleDateFormat("MMMM. dd, yyyy", Locale.ENGLISH)
    }

    protected open fun String.toDate(): Long {
        return runCatching { dateFormatter.parse(trim())?.time }
            .getOrNull() ?: 0L
    }
}

typealias FilterItems = Array<Pair<String, String>>
