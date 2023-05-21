package eu.kanade.tachiyomi.animeextension.pt.megaflix

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.megaflix.extractors.MegaflixExtractor
import eu.kanade.tachiyomi.animeextension.pt.megaflix.extractors.MixDropExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
    override fun episodeListSelector() = "li > article.episodes"
    private fun seasonListSelector() = "section.episodes div.choose-season > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val seasons = response.asJsoup().select(seasonListSelector())
        return when {
            seasons.isEmpty() -> listOf(
                SEpisode.create().apply {
                    name = "Filme"
                    setUrlWithoutDomain(response.request.url.toString())
                    episode_number = 1F
                },
            )
            else -> seasons.parallelMap(::episodesFromSeason).flatten().reversed()
        }
    }

    private fun episodesFromSeason(seasonElement: Element): List<SEpisode> {
        return seasonElement.attr("href").let { url ->
            client.newCall(GET(url)).execute()
                .asJsoup()
                .select(episodeListSelector())
                .map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = element.selectFirst("h2.entry-title")!!.text()
            setUrlWithoutDomain(element.selectFirst("a.lnk-blk")!!.attr("href"))
            episode_number = element.selectFirst("span.num-epi")
                ?.text()
                ?.split("x")
                ?.let {
                    val season = it.first().toFloatOrNull() ?: 0F
                    val episode = it.last().toFloatOrNull() ?: 0F
                    (season * 100F) + episode
                }
                ?: 0F
        }
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
    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        return items
            .parallelMap { element ->
                val language = element.text().substringAfter("-")
                val id = element.attr("href")
                val url = element.parents().get(5)
                    ?.selectFirst("div$id a")
                    ?.attr("href")
                    ?.substringAfter("token=")
                    ?.let { Base64.decode(it, Base64.DEFAULT).let(::String) }
                    ?: return@parallelMap null

                runCatching { getVideoList(url, language) }.getOrNull()
            }.filterNotNull().flatten()
    }

    private fun getVideoList(url: String, language: String): List<Video>? {
        return when {
            "mixdrop.co" in url ->
                MixDropExtractor(client).videoFromUrl(url, language)?.let(::listOf)
            "fembed.com" in url ->
                FembedExtractor(client).videosFromUrl(url, language)
            "streamtape.com" in url ->
                StreamTapeExtractor(client).videoFromUrl(url, "StreamTape - $language")?.let(::listOf)
            "watchsb.com" in url ->
                StreamSBExtractor(client).videosFromUrl(url, headers, suffix = language)
            "mflix.vip" in url ->
                MegaflixExtractor(client).videosFromUrl(url, language)
            else -> null
        }
    }

    override fun videoListSelector() = "aside.video-options li a"

    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun getFilterList() = MegaflixFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val genre = MegaflixFilters.getGenre(filters)
            GET("$baseUrl/categoria/$genre/page/$page")
        }
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val path = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/$path"))
                .asObservableSuccess()
                .map(::searchAnimeByPathParse)
        } else {
            super.fetchSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup())
        details.setUrlWithoutDomain(response.request.url.toString())
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

        val preferredLanguage = ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_VALUES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val preferredLatestPage = ListPreference(screen.context).apply {
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

        screen.addPreference(preferredQuality)
        screen.addPreference(preferredLanguage)
        screen.addPreference(preferredLatestPage)
    }

    // ============================= Utilities ==============================
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(lang) },
            ),
        ).reversed()
    }

    companion object {
        const val PREFIX_SEARCH = "path:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("360p", "480p", "720p", "1080p")

        private const val PREF_LANGUAGE_KEY = "pref_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua/tipo preferido"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")

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
