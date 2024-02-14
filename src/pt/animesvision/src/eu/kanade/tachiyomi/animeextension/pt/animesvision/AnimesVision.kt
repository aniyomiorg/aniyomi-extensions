package eu.kanade.tachiyomi.animeextension.pt.animesvision

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesvision.extractors.AnimesVisionExtractor
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class AnimesVision : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesVision"

    override val baseUrl = "https://animes.vision"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .addInterceptor(::loginInterceptor)
        .build()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    private fun nextPageSelector() = "ul.pagination li.page-item:contains(›):not(.disabled)"
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)
    override fun popularAnimeSelector() = "div#anime-trending div.item > a.film-poster"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        val img = element.selectFirst("img")!!
        setUrlWithoutDomain(element.attr("href"))
        title = img.attr("title")
        thumbnail_url = img.attr("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lancamentos?page=$page")
    override fun latestUpdatesSelector() = episodeListSelector()

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("src")
    }

    override fun latestUpdatesNextPageSelector() = nextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .awaitSuccess()
                .use(::searchAnimeByPathParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response)
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AVFilters.getSearchParameters(filters)
        val url = "$baseUrl/search-anime".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nome", query)
            .addQueryParameter("tipo", params.type)
            .addQueryParameter("idioma", params.language)
            .addQueryParameter("ordenar", params.sort)
            .addQueryParameter("ano_inicial", params.initial_year)
            .addQueryParameter("ano_final", params.last_year)
            .addQueryParameter("fansub", params.fansub)
            .addQueryParameter("status", params.status)
            .addQueryParameter("temporada", params.season)
            .addQueryParameter("estudios", params.studio)
            .addQueryParameter("produtores", params.producer)
            .addQueryParameter("generos", params.genres)
            .build()

        return GET(url, headers)
    }

    override fun searchAnimeSelector() = "div.film_list-wrap div.film-poster"

    override fun searchAnimeFromElement(element: Element) = SAnime.create().apply {
        val elementA = element.selectFirst("a")!!
        title = elementA.attr("title")
        setUrlWithoutDomain(elementA.attr("href"))
        thumbnail_url = element.selectFirst("img")?.attr("data-src")
    }

    override fun searchAnimeNextPageSelector() = nextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val doc = getRealDoc(document)
        setUrlWithoutDomain(doc.location())

        val content = doc.selectFirst("div#ani_detail div.anis-content")!!
        val detail = content.selectFirst("div.anisc-detail")!!
        val infos = content.selectFirst("div.anisc-info")!!

        thumbnail_url = content.selectFirst("img")?.attr("src")
        title = detail.selectFirst("h2.film-name")!!.text()
        genre = infos.getInfo("Gêneros")
        author = infos.getInfo("Produtores")
        artist = infos.getInfo("Estúdios")
        status = parseStatus(infos.getInfo("Status"))

        description = buildString {
            appendLine(infos.getInfo("Sinopse"))
            infos.getInfo("Inglês")?.also { append("\nTítulo em inglês: ", it) }
            infos.getInfo("Japonês")?.also { append("\nTítulo em japonês: ", it) }
            infos.getInfo("Foi ao ar em")?.also { append("\nFoi ao ar em: ", it) }
            infos.getInfo("Temporada")?.also { append("\nTemporada: ", it) }
            infos.getInfo("Duração")?.also { append("\nDuração: ", it) }
            infos.getInfo("Fansub")?.also { append("\nFansub: ", it) }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.container div.screen-items > div.item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        var doc = getRealDoc(response.asJsoup())

        return buildList {
            do {
                if (isNotEmpty()) {
                    val nextUrl = doc.selectFirst(nextPageSelector())!!
                        .selectFirst("a")!!
                        .attr("href")
                    doc = client.newCall(GET(nextUrl)).execute().asJsoup()
                }
                doc.select(episodeListSelector())
                    .map(::episodeFromElement)
                    .also(::addAll)
            } while (doc.selectFirst(nextPageSelector()) != null)
            reverse()
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        val epName = element.selectFirst("h3")!!.text().trim()
        name = epName
        episode_number = epName.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val encodedScript = doc.selectFirst("div.player-frame div#playerglobalapi ~ script")?.data()
            // "ERROR: Script not found."
            ?: throw Exception("ERRO: Script não encontrado.")
        return AnimesVisionExtractor.videoListFromScript(encodedScript)
    }

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

    override fun getFilterList() = AVFilters.FILTER_LIST

    // ============================= Utilities ==============================
    // i'll leave this here just in case the source starts requiring logins again
    private fun loginInterceptor(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

        if ("/login" in response.request.url.toString()) {
            response.close()
            throw IOException(ERROR_LOGIN_MISSING)
        }

        return response
    }

    private fun getRealDoc(document: Document): Document {
        val originalUrl = document.location()
        if ("/episodio-" in originalUrl || "/filme-" in originalUrl) {
            val url = document.selectFirst("h2.film-name > a")!!.attr("href")
            val req = client.newCall(GET(url)).execute()
            return req.asJsoup()
        }
        return document
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Fim da exibição" -> SAnime.COMPLETED
            "Atualmente sendo exibido" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(key: String): String? {
        val div = selectFirst("div.item:contains($key)")
            ?: return null

        val elementsA = div.select("a[href]")
        val text = if (elementsA.isEmpty()) {
            val selector = when {
                div.hasClass("w-hide") -> "div.text"
                else -> "span.name"
            }
            div.selectFirst(selector)!!.text().trim()
        } else {
            elementsA.joinToString { it.text().trim() }
        }

        return text.takeIf(String::isNotBlank)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    companion object {
        const val PREFIX_SEARCH = "path:"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val ERROR_LOGIN_MISSING = "Login necessário. " +
            "Abra a WebView, insira os dados de sua conta e realize o login."

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("480p", "720p", "1080p", "4K")
    }
}
