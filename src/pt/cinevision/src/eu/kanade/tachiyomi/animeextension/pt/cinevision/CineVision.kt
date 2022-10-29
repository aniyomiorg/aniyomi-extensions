package eu.kanade.tachiyomi.animeextension.pt.cinevision

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animeextension.pt.cinevision.extractors.VidmolyExtractor
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class CineVision : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "CineVision"

    override val baseUrl = "https://cinevision.app"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "article.w_item_b > a"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        val url = element.selectFirst("a")?.attr("href") ?: element.attr("href")
        anime.setUrlWithoutDomain(url)
        anime.title = img.attr("alt")
        anime.thumbnail_url = img.attr("data-src")
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

        episode.episode_number = origName.substring(origName.indexOf("-") + 1)
            .toFloat() + if ("Dub" in origName) 0.5F else 0F
        episode.name = "Temp " + origName.replace(" - ", ": Ep ")
        episode.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        episode.date_upload = element.selectFirst("span.date")?.text()?.toDate() ?: 0L
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        val videoList = mutableListOf<Video>()
        players.forEach { player ->
            videoList.addAll(getPlayerVideos(player))
        }
        return videoList
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        val name = player.selectFirst("span.title").text()
        val json = Json.decodeFromString<JsonObject>(
            client.newCall(GET("$baseUrl/wp-json/dooplayer/v2/$id/$type/$num"))
                .execute().body!!.string()
        )
        val url = "https:" + json["embed_url"]!!.jsonPrimitive.content

        return when {
            "vidmoly.to" in url ->
                VidmolyExtractor(client).getVideoList(url, name)
            "streamlare.com" in url ->
                StreamlareExtractor(client).videosFromUrl(url, name)
            else -> emptyList<Video>()
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/serie/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()

        val animes = when {
            "/categorias/" in url -> {
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
            client.newCall(GET("$baseUrl/serie/$slug", headers))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        } else {
            val params = CVFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    override fun getFilterList(): AnimeFilterList = CVFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: CVFilters.FilterSearchParams): Request {
        return when {
            query.isBlank() -> {
                val genre = filters.genre
                var url = "$baseUrl/categorias/assistir-filmes-e-series-de-$genre"
                if (page > 1) url += "/page/$page"
                GET(url, headers)
            }
            else -> GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val img = element.selectFirst("img")
        anime.title = img.attr("alt")
        anime.thumbnail_url = img.attr("data-src").replace("/p/w92", "/p/w185")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeSelector(): String = "div.result-item div.image a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val doc = getRealDoc(document)
        val sheader = doc.selectFirst("div.sheader")
        val img = sheader.selectFirst("div.poster > img")
        anime.thumbnail_url = img.attr("abs:data-src")
        anime.title = sheader.selectFirst("div.data > h1").text()
        anime.genre = sheader.select("div.data > div.sgeneros > a")
            .joinToString(", ") { it.text() }
        val info = doc.selectFirst("div#info")
        var description = info.selectFirst("p").text() + "\n"
        info.getInfo("Título")?.let { description += "$it" }
        info.getInfo("Ano")?.let { description += "$it" }
        info.getInfo("Temporadas")?.let { description += "$it" }
        info.getInfo("Episódios")?.let { description += "$it" }
        anime.description = description
        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.fa-chevron-right"

    override fun latestUpdatesSelector(): String = "div#contenedor article > div.poster"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page/", headers)

    // ============================== Settings ============================== 
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(DEFAULT_QUALITY)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val languagePref = ListPreference(screen.context).apply {
            key = PREFERRED_LANGUAGE
            title = "Tipo/Língua preferida"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(DEFAULT_LANGUAGE)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(languagePref)
    }

    // ============================= Utilities ==============================
    private val animeMenuSelector = "div.pag_episodes div.item a[href] > i.fa-bars"

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

    private fun Element.getInfo(substring: String): String? {
        val target = this.selectFirst("div.custom_fields:contains($substring)")
            ?: return null
        val key = target.selectFirst("b").text()
        val value = target.selectFirst("span").text()
        return "\n$key: $value"
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private fun List<Video>.sortIfContains(item: String): List<Video> {
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (item in video.quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREFERRED_QUALITY, DEFAULT_QUALITY)!!
        val language = preferences.getString(PREFERRED_LANGUAGE, DEFAULT_LANGUAGE)!!
        val newList = sortIfContains(language).sortIfContains(quality)
        return newList
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMM. dd, yyyy", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "slug:"

        private const val PREFERRED_QUALITY = "preferred_quality"
        private const val DEFAULT_QUALITY = "720p"
        private val QUALITY_LIST = arrayOf("480p", "720p")

        private const val PREFERRED_LANGUAGE = "preferred_language"
        private const val DEFAULT_LANGUAGE = "Legendado"
        private val LANGUAGE_LIST = arrayOf("Legendado", "Dublado")
    }
}
