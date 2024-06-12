package eu.kanade.tachiyomi.animeextension.pt.animefire

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animefire.extractors.AnimeFireExtractor
import eu.kanade.tachiyomi.animeextension.pt.animefire.extractors.IframeExtractor
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
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeFire : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime Fire"

    override val baseUrl = "https://animefire.plus"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)
        .add("Accept-Language", ACCEPT_LANGUAGE)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/top-animes/$page")
    override fun popularAnimeSelector() = latestUpdatesSelector()
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/home/$page")

    override fun latestUpdatesSelector() = "article.cardUltimosEps > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        val url = element.attr("href")
        // get anime url from episode url
        when (url.substringAfterLast("/").toIntOrNull()) {
            null -> setUrlWithoutDomain(url)
            else -> {
                val substr = url.substringBeforeLast("/")
                setUrlWithoutDomain("$substr-todos-os-episodios")
            }
        }

        title = element.selectFirst("h3.animeTitle")!!.text()
        thumbnail_url = element.selectFirst("img")?.attr("data-src")
    }

    override fun latestUpdatesNextPageSelector() = "ul.pagination img.seta-right"

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AFFilters.getSearchParameters(filters)
        if (query.isBlank()) {
            return when {
                params.season.isNotBlank() -> GET("$baseUrl/temporada/${params.season}/$page")
                else -> GET("$baseUrl/genero/${params.genre}/$page")
            }
        }
        val fixedQuery = query.trim().replace(" ", "-").lowercase()
        return GET("$baseUrl/pesquisar/$fixedQuery/$page")
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val content = document.selectFirst("div.divDivAnimeInfo")!!
        val names = content.selectFirst("div.div_anime_names")!!
        val infos = content.selectFirst("div.divAnimePageInfo")!!
        setUrlWithoutDomain(document.location())
        thumbnail_url = content.selectFirst("div.sub_animepage_img > img")?.attr("data-src")
        title = names.selectFirst("h1")!!.text()
        genre = infos.select("a.spanGeneros").eachText().joinToString()
        author = infos.getInfo("Estúdios")
        status = parseStatus(infos.getInfo("Status"))

        description = buildString {
            content.selectFirst("div.divSinopse > span")?.also {
                append(it.text() + "\n")
            }
            names.selectFirst("h6")?.also { append("\nNome alternativo: ${it.text()}") }
            infos.getInfo("Dia de")?.also { append("\nDia de lançamento: $it") }
            infos.getInfo("Áudio")?.also { append("\nTipo: $it") }
            infos.getInfo("Ano")?.also { append("\nAno: $it") }
            infos.getInfo("Episódios")?.also { append("\nEpisódios: $it") }
            infos.getInfo("Temporada")?.also { append("\nTemporada: $it") }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()
    override fun episodeListSelector(): String = "div.div_video_list > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        val url = element.attr("href")
        setUrlWithoutDomain(url)
        name = element.text()
        episode_number = url.substringAfterLast("/").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoElement = document.selectFirst("video#my-video")
        return if (videoElement != null) {
            AnimeFireExtractor(client, json).videoListFromElement(videoElement, headers)
        } else {
            IframeExtractor(client).videoListFromDocument(document, headers)
        }
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

    override fun getFilterList(): AnimeFilterList = AFFilters.FILTER_LIST

    // ============================= Utilities ==============================
    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "Completo" -> SAnime.COMPLETED
            "Em lançamento" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun Element.getInfo(key: String): String? {
        return selectFirst("div.animeInfo:contains($key) span")?.text()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val ACCEPT_LANGUAGE = "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_VALUES = arrayOf("360p", "720p")
    }
}
