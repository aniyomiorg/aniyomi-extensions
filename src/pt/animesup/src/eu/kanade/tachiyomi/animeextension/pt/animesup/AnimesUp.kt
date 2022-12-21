package eu.kanade.tachiyomi.animeextension.pt.animesup

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesup.extractors.AnimesUpExtractor
import eu.kanade.tachiyomi.animeextension.pt.animesup.extractors.LegacyFunExtractor
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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimesUp : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimesUp"

    override val baseUrl = "https://animesup.biz"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.w_item_b > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        anime.setUrlWithoutDomain(url)
        anime.title = img.attr("alt")
        anime.thumbnail_url = img.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }
        return AnimesPage(animes, false)
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector(): String = "ul.episodios > li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = getRealDoc(response.asJsoup())
        val epList = doc.select(episodeListSelector())
        if (epList.size < 1) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(response.request.url.toString())
            episode.episode_number = 1F
            episode.name = "Filme"
            return listOf(episode)
        }
        return epList.reversed().map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val origName = element.selectFirst("div.numerando").text()

        episode.episode_number = origName.substringAfter("- ")
            .replace("-", "")
            .toFloat()
        episode.name = "Temp " + origName.replace(" - ", ": Ep ")
        episode.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val urls = document.select("div.source-box:not(#source-player-trailer) a,iframe")
            .map { it.attr("href").ifEmpty { it.attr("src") } }
        val resolutions = document.select("ul#playeroptionsul > li:not(#player-option-trailer)")
            .mapIndexed { index, it ->
                val quality = it.selectFirst("span:not(.loader)").text()

                val videoUrl = urls.get(index)!!
                when {
                    videoUrl.contains("/Player/Play") -> {
                        val newHeaders = Headers.headersOf(
                            "referer", response.request.url.toString()
                        )
                        AnimesUpExtractor(client)
                            .videoFromUrl(videoUrl, quality, newHeaders)
                    }
                    videoUrl.contains("blog.legacy") -> {
                        LegacyFunExtractor(client)
                            .videoFromUrl("https:" + videoUrl, quality)
                    }
                    else -> null
                }
            }.filterNotNull()
        return resolutions
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/animes/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()

        val animes = when {
            "/genero/" in url -> {
                document.select(latestUpdatesSelector()).map { element ->
                    popularAnimeFromElement(element)
                }
            }
            else -> {
                document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
            }
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.startsWith(PREFIX_SEARCH)) {
            val slug = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/animes/$slug", headers))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        } else {
            val params = AnimesUpFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimesUpFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimesUpFilters.FilterSearchParams): Request {
        return when {
            query.isBlank() -> {
                val genre = filters.genre
                var url = "$baseUrl/genero/$genre/"
                if (page > 1) url += "page/$page"
                GET(url, headers)
            }
            else -> GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.selectFirst("img")?.attr("src")
        anime.title = element.text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeSelector(): String = "div.result-item div.details div.title a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        val img = sheader.selectFirst("div.poster img")
        anime.thumbnail_url = img.attr("src")
        anime.title = img.attr("alt")
        val info = sheader.selectFirst("div.data")
        anime.genre = info.select("div.sgeneros > a")
            .joinToString(", ") { it.text() }
        var description = doc.select("div.sinopse > div.texto")
            .joinToString("\n") { it.text() } + "\n\n"

        sheader.selectFirst("div.data > div.extra-title")?.let {
            description += "${it.text()}"
        }
        info.getInfo("ANO")?.let { description += "$it" }
        info.getInfo("TEMPORADAS")?.let { description += "$it" }
        info.getInfo("DURAÇÃO")?.let { description += "$it" }
        anime.description = description
        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.icon-chevron-right"

    override fun latestUpdatesSelector(): String = "div.content article > div.poster"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodios/page/$page", headers)

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
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
    private val animeMenuSelector = "div.pag_episodes div.item a[href] i.icon-bars"

    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst(animeMenuSelector)
        if (menu != null) {
            val originalUrl = menu.parent().attr("href")
            val req = client.newCall(GET(originalUrl, headers)).execute()
            return req.asJsoup()
        } else {
            return document
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status) {
            null -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun Element.getInfo(substring: String): String? {
        val target = this.selectFirst("div.custom_fields:contains($substring)")
            ?: return null
        val key = target.selectFirst("b").text()
        val value = target.selectFirst("span").text()
        return "\n$key $value"
    }

    private fun String.getParam(param: String): String? {
        return Uri.parse(this).getQueryParameter(param)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (quality == video.quality.substringAfterLast(" ")) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    companion object {
        const val PREFIX_SEARCH = "slug:"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualidade preferida"
        private const val PREF_QUALITY_DEFAULT = "HD"
        private val PREF_QUALITY_ENTRIES = arrayOf("SD", "HD", "FULL HD")
        private val PREF_QUALITY_VALUES = arrayOf("SD", "HD", "FHD")
    }
}
