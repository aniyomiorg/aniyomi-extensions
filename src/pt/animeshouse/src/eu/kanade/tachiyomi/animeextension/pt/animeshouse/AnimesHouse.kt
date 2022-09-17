package eu.kanade.tachiyomi.animeextension.pt.animeshouse

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.EdifierExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.EmbedExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.GenericExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.JsUnpacker
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.McpExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.MpFourDooExtractor
import eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors.RedplayBypasser
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
import okhttp3.FormBody
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

class AnimesHouse : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animes House"

    override val baseUrl = "https://animeshouse.net"

    override val lang = "pt-BR"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("Referer", baseUrl)
        .add("Accept-Language", AHConstants.ACCEPT_LANGUAGE)
        .add("User-Agent", AHConstants.USER_AGENT)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val img = element.selectFirst("img")
        anime.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
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

        episode.episode_number = origName.substring(origName.indexOf("-") + 1)
            .toFloat() + if ("Dub" in origName) 0.5F else 0F
        episode.name = "Temp " + origName.replace(" - ", ": Ep ")
        episode.setUrlWithoutDomain(element.selectFirst("a").attr("href"))
        return episode
    }

    // ============================ Video Links =============================
    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()
        val doc = client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", headers, body)
        )
            .execute()
            .asJsoup()
        val iframe = doc.selectFirst("iframe")
        return iframe.attr("src").let {
            if (it.startsWith("/redplay"))
                RedplayBypasser(client, headers).fromUrl(baseUrl + it)
            else it
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        val videoList = mutableListOf<Video>()
        players.forEach { player ->
            val url = getPlayerUrl(player)
            val videos = runCatching { getPlayerVideos(url) }
                .getOrNull() ?: emptyList<Video>()
            videoList.addAll(videos)
        }
        return videoList
    }

    private fun getPlayerVideos(url: String): List<Video> {
        val iframeBody = client.newCall(GET(url, headers)).execute()
            .body?.string() ?: throw Exception(AHConstants.MSG_ERR_BODY)

        val unpackedBody = JsUnpacker.unpack(iframeBody)

        return when {
            "embed.php?" in url ->
                EmbedExtractor(headers).getVideoList(url, iframeBody)
            "edifier" in url ->
                EdifierExtractor(client, headers).getVideoList(url)
            "mp4doo" in url ->
                MpFourDooExtractor(headers).getVideoList(unpackedBody)
            "clp-new" in url || "gcloud" in url ->
                GenericExtractor(client, headers).getVideoList(url, unpackedBody)
            "mcp_comm" in unpackedBody ->
                McpExtractor(client, headers).getVideoList(unpackedBody)
            else -> emptyList<Video>()
        }
    }

    override fun videoListSelector() = throw Exception("not used")
    override fun videoFromElement(element: Element) = throw Exception("not used")
    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    private fun searchAnimeBySlugParse(response: Response, slug: String): AnimesPage {
        val details = animeDetailsParse(response)
        details.url = "/anime/$slug"
        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()

        val animes = when {
            "/generos/" in url -> {
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
        return if (query.startsWith(AHConstants.PREFIX_SEARCH)) {
            val slug = query.removePrefix(AHConstants.PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$slug", headers))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeBySlugParse(response, slug)
                }
        } else {
            val params = AHFilters.getSearchParameters(filters)
            client.newCall(searchAnimeRequest(page, query, params))
                .asObservableSuccess()
                .map { response ->
                    searchAnimeParse(response)
                }
        }
    }

    override fun getFilterList(): AnimeFilterList = AHFilters.filterList

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AHFilters.FilterSearchParams): Request {
        return when {
            query.isBlank() -> {
                val genre = filters.genre
                var url = "$baseUrl/generos/$genre"
                if (page > 1) url += "/page/$page"
                GET(url, headers)
            }
            else -> GET("$baseUrl/page/$page/?s=$query", headers)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
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
        anime.thumbnail_url = sheader.selectFirst("div.poster > img").attr("src")
        anime.title = sheader.selectFirst("div.data > h1").text()
        anime.genre = sheader.select("div.data > div.sgeneros > a")
            .joinToString(", ") { it.text() }
        val info = doc.selectFirst("div#info")
        var description = info.selectFirst("p")?.let { it.text() + "\n" } ?: ""
        info.getInfo("Título")?.let { description += "$it" }
        info.getInfo("Ano")?.let { description += "$it" }
        info.getInfo("Temporadas")?.let { description += "$it" }
        info.getInfo("Episódios")?.let { description += "$it" }
        anime.description = description
        return anime
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "div.resppages > a > span.icon-chevron-right"

    override fun latestUpdatesSelector(): String = "div.content article > div.poster"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episodio/page/$page", headers)
    // ============================== Settings ============================== 
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = AHConstants.PREFERRED_QUALITY
            title = "Qualidade preferida"
            entries = AHConstants.QUALITY_LIST
            entryValues = AHConstants.QUALITY_LIST
            setDefaultValue(AHConstants.DEFAULT_QUALITY)
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

    private fun Element.getInfo(substring: String): String? {
        val target = this.selectFirst("div.custom_fields:contains($substring)")
            ?: return null
        val key = target.selectFirst("b").text()
        val value = target.selectFirst("span").text()
        return "\n$key: $value"
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(AHConstants.PREFERRED_QUALITY, AHConstants.DEFAULT_QUALITY)!!
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (quality in video.quality) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }
}
