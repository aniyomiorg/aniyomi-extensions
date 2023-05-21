package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import android.app.Application
import android.content.SharedPreferences
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

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun popularAnimeNextPageSelector(): String? = null
    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)
    override fun popularAnimeSelector(): String = "div.ultAnisContainerItem > a"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            element.attr("href")!!.also {
                setUrlWithoutDomain(it)
                episode_number = try {
                    it.substringAfterLast("/").toFloat()
                } catch (e: NumberFormatException) { 0F }
            }
            name = element.text()
        }
    }

    override fun episodeListSelector(): String = "div#aba_epi > a"

    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val infos = document.selectFirst("div.animeInfos")!!
            val right = document.selectFirst("div.right")!!

            setUrlWithoutDomain(document.location())
            title = right.selectFirst("h1")!!.text()
            genre = right.select("ul.animeGen a").joinToString(", ") { it.text() }

            thumbnail_url = infos.selectFirst("img")!!.attr("data-lazy-src")
            description = right.selectFirst("div.animeSecondContainer > p:gt(0)")!!.text()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document: Document = response.asJsoup()
        val videoElement = document.selectFirst("div.playerBox > *")!!
        return if (videoElement.tagName() == "video") {
            MeusAnimesExtractor(client).videoListFromElement(videoElement)
        } else {
            IframeExtractor(client, headers).videoListFromIframe(videoElement)
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")
    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)
    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = "div.paginacao > a.next"
    override fun getFilterList(): AnimeFilterList = MAFilters.FILTER_LIST

    private fun searchAnimeRequest(page: Int, query: String, filters: MAFilters.FilterSearchParams): Request {
        val defaultUrl = "$baseUrl/lista-de-animes/$page"
        return when {
            filters.letter.isNotBlank() -> GET("$defaultUrl?letra=${filters.letter}")
            filters.year.isNotBlank() -> GET("$defaultUrl?ano=${filters.year}")
            filters.audio.isNotBlank() -> GET("$defaultUrl?audio=${filters.audio}")
            filters.genre.isNotBlank() -> GET("$defaultUrl?genero=${filters.genre}")
            query.isNotBlank() -> GET("$defaultUrl?s=$query")
            else -> GET(defaultUrl)
        }
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            val params = MAFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        details.url = "/animes/$id"
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.attr("title")
            thumbnail_url = element.selectFirst("img")?.attr("data-lazy-src")
            val epUrl = element.attr("href")

            if (epUrl.substringAfterLast("/").toIntOrNull() != null) {
                setUrlWithoutDomain(epUrl.substringBeforeLast("/") + "-todos-os-episodios")
            } else { setUrlWithoutDomain(epUrl) }
        }
    }

    override fun latestUpdatesNextPageSelector(): String? = null
    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)
    override fun latestUpdatesSelector(): String = "div.ultEpsContainerItem > a"

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_ENTRIES.last())
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
        val quality = preferences.getString(PREF_QUALITY_KEY, "HD")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private val PREF_QUALITY_ENTRIES = arrayOf("SD", "HD")
    }
}
