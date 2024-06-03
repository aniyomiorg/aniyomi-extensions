package eu.kanade.tachiyomi.animeextension.pt.hentaistube

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.hentaistube.HentaisTubeFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.pt.hentaistube.dto.ItemsListDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class HentaisTube : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaisTube"

    override val baseUrl = "https://www.hentaistube.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Origin", baseUrl)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ranking-hentais?paginacao=$page", headers)

    override fun popularAnimeSelector() = "ul.ul_sidebar > li"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        thumbnail_url = element.selectFirst("img")?.attr("src")
        element.selectFirst("div.rt a.series")!!.run {
            setUrlWithoutDomain(attr("href"))
            title = text().substringBefore(" - Episódios")
        }
    }

    override fun popularAnimeNextPageSelector() = "div.paginacao > a:contains(»)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page/", headers)

    override fun latestUpdatesSelector() = "div.epiContainer:first-child div.epiItem > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href").substringBeforeLast("-") + "s")
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    private val animeList by lazy {
        val headers = headersBuilder().add("X-Requested-With", "XMLHttpRequest").build()
        client.newCall(GET("$baseUrl/json-lista-capas.php", headers)).execute()
            .parseAs<ItemsListDto>().items
            .asSequence()
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            val params = HentaisTubeFilters.getSearchParameters(filters).apply {
                animeName = query
            }
            val filtered = animeList.applyFilterParams(params)
            val results = filtered.chunked(30).toList()
            val hasNextPage = results.size > page
            val currentPage = if (results.size == 0) {
                emptyList<SAnime>()
            } else {
                results.get(page - 1).map {
                    SAnime.create().apply {
                        title = it.title.substringBefore("- Episódios")
                        url = "/" + it.url
                        thumbnail_url = it.thumbnail
                    }
                }
            }
            AnimesPage(currentPage, hasNextPage)
        }
    }

    override fun getFilterList(): AnimeFilterList = HentaisTubeFilters.FILTER_LIST

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException()
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException()
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val infos = document.selectFirst("div#anime")!!
        thumbnail_url = infos.selectFirst("img")?.attr("src")
        title = infos.getInfo("Hentai:")
        genre = infos.getInfo("Tags")
        artist = infos.getInfo("Estúdio")
        description = infos.selectFirst("div#sinopse2")?.text().orEmpty()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "ul.pagAniListaContainer > li > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.text()
        episode_number = element.text().substringAfter(" ").toFloatOrNull() ?: 1F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        return response.asJsoup().select(videoListSelector())
            .parallelCatchingFlatMapBlocking {
                client.newCall(GET(it.attr("src"), headers)).await().let { res ->
                    extractVideosFromIframe(res.asJsoup())
                }
            }
    }

    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    private fun extractVideosFromIframe(iframe: Document): List<Video> {
        val url = iframe.location()
        return when {
            url.contains("/hd.php") -> {
                val video = iframe.selectFirst("video > source")!!
                val videoUrl = video.attr("src")
                val quality = video.attr("label").ifEmpty { "Unknown" }
                listOf(Video(videoUrl, "Principal - $quality", videoUrl, headers))
            }
            url.contains("/index.php") -> {
                val bloggerUrl = iframe.selectFirst("iframe")!!.attr("src")
                bloggerExtractor.videosFromUrl(bloggerUrl, headers)
            }
            url.contains("/player.php") -> {
                val ahref = iframe.selectFirst("a")!!.attr("href")
                val internal = client.newCall(GET(ahref, headers)).execute().asJsoup()
                val videoUrl = internal.selectFirst("video > source")!!.attr("src")
                listOf(Video(videoUrl, "Alternativo", videoUrl, headers))
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector() = "iframe.meu-player"

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun Element.getInfo(key: String): String =
        select("div.boxAnimeSobreLinha:has(b:contains($key)) > a")
            .eachText()
            .joinToString()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "720p")
    }
}
