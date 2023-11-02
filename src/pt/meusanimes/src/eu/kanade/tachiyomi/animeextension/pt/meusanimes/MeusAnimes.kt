package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.meusanimes.extractors.IframeExtractor
import eu.kanade.tachiyomi.animeextension.pt.meusanimes.extractors.MeusAnimesExtractor
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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MeusAnimes : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Meus Animes"

    override val baseUrl = "https://meusanimes.org"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector(): String = "div.ultAnisContainerItem > a"

    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesSelector() = "div.ultEpsContainerItem > a"

    override fun latestUpdatesFromElement(element: Element) = SAnime.create().apply {
        title = element.attr("title")
        thumbnail_url = element.selectFirst("img")?.attr("data-lazy-src")
        val epUrl = element.attr("href")

        if (epUrl.substringAfterLast("/").toIntOrNull() != null) {
            setUrlWithoutDomain(epUrl.substringBeforeLast("/") + "-todos-os-episodios")
        } else { setUrlWithoutDomain(epUrl) }
    }

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = MAFilters.FILTER_LIST

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val defaultUrl = "$baseUrl/lista-de-animes/$page"
        val params = MAFilters.getSearchParameters(filters)
        return when {
            params.letter.isNotBlank() -> GET("$defaultUrl?letra=${params.letter}")
            params.year.isNotBlank() -> GET("$defaultUrl?ano=${params.year}")
            params.audio.isNotBlank() -> GET("$defaultUrl?audio=${params.audio}")
            params.genre.isNotBlank() -> GET("$defaultUrl?genero=${params.genre}")
            query.isNotBlank() -> GET("$defaultUrl?s=$query")
            else -> GET(defaultUrl)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector() = "div.paginacao > a.next"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        val infos = document.selectFirst("div.animeInfos")!!
        val right = document.selectFirst("div.right")!!

        setUrlWithoutDomain(document.location())
        title = right.selectFirst("h1")!!.text()
        genre = right.select("ul.animeGen a").eachText().joinToString()

        thumbnail_url = infos.selectFirst("img")?.attr("data-lazy-src")
        description = right.selectFirst("div.animeSecondContainer > p:gt(0)")!!.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div#aba_epi > a"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        element.attr("href").also {
            setUrlWithoutDomain(it)
            episode_number = it.substringAfterLast("/").toFloatOrNull() ?: 0F
        }
        name = element.text()
    }

    // ============================ Video Links =============================
    private val meusanimesExtractor by lazy { MeusAnimesExtractor(client) }
    private val iframeExtractor by lazy { IframeExtractor(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }
        val videoElement = document.selectFirst("div.playerBox > *")!!
        return when (videoElement.tagName()) {
            "video" -> meusanimesExtractor.videoListFromElement(videoElement)
            else -> iframeExtractor.videoListFromIframe(videoElement)
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("SD", "HD")
    }
}
