package eu.kanade.tachiyomi.animeextension.pt.megaflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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

class Megaflix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Megaflix"

    override val baseUrl = "https://megaflix.co"

    override val lang = "pt-BR"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.selectFirst("h2.entry-title")!!.text()
            setUrlWithoutDomain(element.selectFirst("a.lnk-blk")!!.attr("href"))
            thumbnail_url = "https:" + element.selectFirst("img")!!.attr("src")
        }
    }

    override fun popularAnimeNextPageSelector() = null

    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "section#widget_list_movies_series-5 li > article"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode {
        TODO("Not yet implemented")
    }

    override fun episodeListSelector(): String {
        TODO("Not yet implemented")
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            val infos = document.selectFirst("div.bd > article.post.single")!!
            title = infos.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = "https:" + infos.selectFirst("img")!!.attr("src")
            genre = infos.select("span.genres > a").eachText().joinToString()
            description = infos.selectFirst("div.description")?.text()
        }
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoListSelector(): String {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/page/$page/?s=$query")
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeByIdParse(response, id)
                }
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response, id: String): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        details.url = "/anime/$id"
        return AnimesPage(listOf(details), false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.nav-links > a:containsOwn(PRÓXIMO)"

    override fun latestUpdatesRequest(page: Int): Request {
        val pageType = preferences.getString(PREF_LATEST_PAGE_KEY, PREF_LATEST_PAGE_DEFAULT)!!
        return GET("$baseUrl/$pageType/page/$page")
    }

    override fun latestUpdatesSelector() = "li > article"

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val latestPage = ListPreference(screen.context).apply {
            key = PREF_LATEST_PAGE_KEY
            title = PREF_LATEST_PAGE_TITLE
            entries = PREF_LATEST_PAGE_ENTRIES
            entryValues = PREF_LATEST_PAGE_VALUES
            setDefaultValue(PREF_LATEST_PAGE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(latestPage)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_LATEST_PAGE_KEY = "pref_latest_page"
        private const val PREF_LATEST_PAGE_DEFAULT = "series"
        private const val PREF_LATEST_PAGE_TITLE = "Página de últimos adicionados"
        private val PREF_LATEST_PAGE_ENTRIES = arrayOf(
            "Filmes",
            "Séries",
        )
        private val PREF_LATEST_PAGE_VALUES = arrayOf(
            "filmes",
            "series",
        )
    }
}
