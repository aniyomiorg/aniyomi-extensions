package eu.kanade.tachiyomi.animeextension.pt.openanimes

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.openanimes.OpenAnimesFilters.FilterSearchParams
import eu.kanade.tachiyomi.animeextension.pt.openanimes.dto.SearchResultDto
import eu.kanade.tachiyomi.animeextension.pt.openanimes.extractors.BloggerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class OpenAnimes : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Open Animes"

    override val baseUrl = "https://openanimes.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    override fun popularAnimeRequest(page: Int) = searchAnimeRequest(page, "", FilterSearchParams())

    override fun popularAnimeParse(response: Response) = searchAnimeParse(response)

    override fun popularAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) =
        super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            val title = element.selectFirst("div.tituloEP > h3")!!.text().trim()
            name = title
            date_upload = element.selectFirst("span.data")?.text().toDate()
            episode_number = title.substringAfterLast(" ").toFloatOrNull() ?: 0F
        }
    }

    override fun episodeListSelector() = "div.listaEp div.episodioItem > a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            artist = doc.getInfo("Estúdio")
            author = doc.getInfo("Autor") ?: doc.getInfo("Diretor")
            description = doc.selectFirst("div.sinopseEP > p")?.text()
            genre = doc.select("div.info span.cat > a").eachText().joinToString()
            title = doc.selectFirst("div.tituloPrincipal > h1")!!.text()
                .removePrefix("Assistir ")
                .removeSuffix(" Temporada Online")
            thumbnail_url = doc.selectFirst("div.thumb > img")!!.attr("data-lazy-src")

            val statusStr = doc.selectFirst("li:contains(Status) > span[data]")?.text()
            status = when (statusStr) {
                "Completo" -> SAnime.COMPLETED
                "Lançamento" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val playerUrl = response.use { it.asJsoup() }
            .selectFirst("div.Link > a")
            ?.attr("href") ?: return emptyList()

        return client.newCall(GET(playerUrl, headers)).execute()
            .use {
                val doc = it.asJsoup()
                doc.selectFirst("iframe")?.attr("src")?.let { iframeUrl ->
                    BloggerExtractor(client).videosFromUrl(iframeUrl, headers)
                } ?: run {
                    val videoUrl = doc.selectFirst("script:containsData(var jw =)")
                        ?.data()
                        ?.substringAfter("file\":\"")
                        ?.substringBefore('"')
                        ?.replace("\\", "")
                        ?: return emptyList()
                    listOf(Video(videoUrl, "Default", videoUrl, headers))
                }
            }
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException("Not used.")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        throw UnsupportedOperationException("Not used.")
    }

    override fun searchAnimeNextPageSelector(): String? {
        throw UnsupportedOperationException("Not used.")
    }

    private val searchToken by lazy {
        client.newCall(GET("$baseUrl/lista-de-animes")).execute()
            .use {
                it.asJsoup().selectFirst("input#token")!!.attr("value")
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return searchAnimeRequest(page, query, OpenAnimesFilters.getSearchParameters(filters))
    }

    override fun getFilterList(): AnimeFilterList = OpenAnimesFilters.FILTER_LIST

    private fun searchAnimeRequest(page: Int, query: String, params: FilterSearchParams): Request {
        val body = FormBody.Builder().apply {
            add("action", "getListFilter")
            add("token", searchToken)
            add("filter_pagina", "$page")
            val filters = baseUrl.toHttpUrl().newBuilder().apply {
                addQueryParameter("filter_type", "animes")
                addQueryParameter("filter_audio", params.audio)
                addQueryParameter("filter_letter", params.initialLetter)
                addQueryParameter("filter_ordem", params.sortBy)
                addQueryParameter("filter_search", query.ifEmpty { "0" })
            }.build().encodedQuery

            val genres = params.genres.joinToString { "\"$it\"" }

            add("filters", """{"filter_data": "$filters", "filter_genre": [$genres]}""")
        }.build()

        return POST("$baseUrl/wp-admin/admin-ajax.php", body = body, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val data = response.use {
            json.decodeFromString<SearchResultDto>(it.body.string())
        }

        val animes = data.results.map {
            SAnime.create().apply {
                title = it.title
                thumbnail_url = it.thumbnail
                setUrlWithoutDomain(it.permalink)
            }
        }

        val hasNext = data.page.toIntOrNull()?.let { it < data.totalPage } ?: false
        return AnimesPage(animes, hasNext)
    }

    override fun searchAnimeSelector(): String {
        throw UnsupportedOperationException("Not used.")
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
                .asObservableSuccess()
                .map(::searchAnimeByIdParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            element.selectFirst("a.thumb")!!.let {
                setUrlWithoutDomain(it.attr("href"))
                thumbnail_url = it.selectFirst("img")!!.attr("data-lazy-src")
            }
            title = element.selectFirst("h3 > a")!!.text()
        }
    }

    override fun latestUpdatesNextPageSelector() = "div.pagination a.pagination__arrow--right"

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos/page/$page")

    override fun latestUpdatesSelector() = "div.contents div.itens > div"

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val preferredQuality = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(preferredQuality)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun getRealDoc(document: Document): Document {
        return document.selectFirst("a:has(i.fa-grid)")?.let { link ->
            client.newCall(GET(link.attr("href")))
                .execute()
                .asJsoup()
        } ?: document
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.info li:has(span:containsOwn($key))")
            ?.ownText()
            ?.trim()
    }

    private fun String?.toDate(): Long {
        return this?.let {
            runCatching {
                DATE_FORMATTER.parse(this)?.time
            }.getOrNull()
        } ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
        }

        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "720p")
    }
}
