package eu.kanade.tachiyomi.animeextension.pt.megaflix

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.megaflix.extractors.MegaflixExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Megaflix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Megaflix"

    override val baseUrl = "https://megaflix.co"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "section#widget_list_movies_series-5 li > article"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.selectFirst("h2.entry-title")!!.text()
        setUrlWithoutDomain(element.selectFirst("a.lnk-blk")!!.attr("href"))
        thumbnail_url = element.selectFirst("img")?.absUrl("src")
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val pageType = preferences.getString(PREF_LATEST_PAGE_KEY, PREF_LATEST_PAGE_DEFAULT)!!
        return GET("$baseUrl/$pageType/page/$page")
    }

    override fun latestUpdatesSelector() = "li > article"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = "div.nav-links > a:containsOwn(PRÓXIMO)"

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

    private fun searchAnimeByPathParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val genre = MegaflixFilters.getGenre(filters)
            GET("$baseUrl/categoria/$genre/page/$page")
        }
    }

    override fun searchAnimeSelector() = latestUpdatesSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun getFilterList() = MegaflixFilters.FILTER_LIST

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        val infos = document.selectFirst("div.bd > article.post.single")!!
        title = infos.selectFirst("h1.entry-title")!!.text()
        thumbnail_url = infos.selectFirst("img")?.absUrl("src")
        genre = infos.select("span.genres > a").eachText().joinToString()
        description = infos.selectFirst("div.description")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "li > article.episodes"
    private fun seasonListSelector() = "section.episodes div.choose-season > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val seasons = doc.select(seasonListSelector())
        return when {
            seasons.isEmpty() -> listOf(
                SEpisode.create().apply {
                    name = "Filme"
                    setUrlWithoutDomain(doc.location())
                    episode_number = 1F
                },
            )
            else -> seasons.parallelFlatMapBlocking(::episodesFromSeason).reversed()
        }
    }

    private suspend fun episodesFromSeason(seasonElement: Element): List<SEpisode> {
        return seasonElement.attr("href").let { url ->
            client.newCall(GET(url, headers)).await()
                .asJsoup()
                .select(episodeListSelector())
                .map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        name = element.selectFirst("h2.entry-title")!!.text()
        setUrlWithoutDomain(element.selectFirst("a.lnk-blk")!!.attr("href"))
        episode_number = element.selectFirst("span.num-epi")?.run {
            text().split("x").let {
                val season = it.first().toFloatOrNull() ?: 0F
                val episode = it.last().toFloatOrNull() ?: 0F
                season * 100F + episode
            }
        } ?: 0F
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val items = response.asJsoup().select(videoListSelector())
        return items
            .parallelCatchingFlatMapBlocking { element ->
                val language = element.text().substringAfter("-")
                val id = element.attr("href")
                val url = element.parents().get(5)?.selectFirst("div$id a")
                    ?.run {
                        attr("href")
                            .substringAfter("token=")
                            .let { String(Base64.decode(it, Base64.DEFAULT)) }
                            .substringAfter("||")
                    } ?: return@parallelCatchingFlatMapBlocking emptyList()

                getVideoList(url, language)
            }
    }

    private val mixdropExtractor by lazy { MixDropExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }
    private val megaflixExtractor by lazy { MegaflixExtractor(client, headers) }

    private fun getVideoList(url: String, language: String): List<Video> {
        return when {
            "mixdrop.co" in url -> mixdropExtractor.videoFromUrl(url, language)
            "streamtape.com" in url -> streamtapeExtractor.videosFromUrl(url, "StreamTape - $language")
            "mflix.vip" in url -> megaflixExtractor.videosFromUrl(url, language)
            else -> null
        }.orEmpty()
    }

    override fun videoListSelector() = "aside.video-options li a"

    override fun videoFromElement(element: Element): Video {
        TODO("Not yet implemented")
    }

    override fun videoUrlParse(document: Document): String {
        TODO("Not yet implemented")
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

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
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
