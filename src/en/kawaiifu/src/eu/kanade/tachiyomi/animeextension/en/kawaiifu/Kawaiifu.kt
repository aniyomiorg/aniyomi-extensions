package eu.kanade.tachiyomi.animeextension.en.kawaiifu

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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Kawaiifu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kawaiifu"

    override val baseUrl = "https://kawaiifu.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/tv-series/${page.toPage()}", headers)

    override fun popularAnimeSelector(): String = "ul.list-film li"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.mv-namevn")!!.attr("abs:href"))
        title = element.selectFirst("a.mv-namevn")!!.text()
        thumbnail_url = element.selectFirst("a img")!!.attr("src")
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi a.nextpostslink"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/${page.toPage()}", headers)

    override fun latestUpdatesSelector(): String = "div.today-update > div.item"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val a = element.selectFirst("div.info a:not([style])")!!

        return SAnime.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            thumbnail_url = element.select("a.thumb img").attr("src")
            title = a.text()
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "div.pagination-content > span.current + a"

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = KawaiifuFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .awaitSuccess()
            .use(::searchAnimeParse)
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: KawaiifuFilters.FilterSearchParams): Request =
        GET("$baseUrl/search-movie/${page.toPage()}?keyword=$query&cat-get=${filters.category}${filters.tags}")

    override fun searchAnimeSelector(): String = latestUpdatesSelector()

    override fun searchAnimeFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = KawaiifuFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        genre = document.select("div.desc-top table tbody tr:has(td:contains(Genres)) td a").joinToString(", ") { it.text() }
        description = document.select("div.sub-desc > h5:contains(Summary) ~ p:not(:has(:not(i))):not(:empty)").joinToString("\n\n") { it.text() }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val doc = client.newCall(
            GET(baseUrl + anime.url, headers),
        ).execute().asJsoup()
        return GET(doc.selectFirst("div.list-server a")!!.attr("abs:href"), headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val epDocument = response.asJsoup()
        val epListTop = epDocument.select("div:has(>div:contains(Episode List)) > div.list-server >ul.list-ep > li")
        val document = if (epListTop.isEmpty()) {
            epDocument
        } else {
            client.newCall(
                GET(epListTop.first()!!.selectFirst("a[href]")!!.attr("abs:href"), headers),
            ).execute().asJsoup()
        }

        val episodeList = mutableListOf<SEpisode>()
        val episodesInfo = mutableListOf<List<EpisodeInfo>>()

        document.select("div#server_ep > div.list-server").forEach { server ->
            episodesInfo.add(
                server.select("ul.list-ep > li").map { ep ->
                    EpisodeInfo(
                        name = server.selectFirst("h4.server-name")!!.text(),
                        epName = ep.selectFirst("a")!!.text(),
                        url = ep.selectFirst("a")!!.attr("abs:href"),
                    )
                },
            )
        }

        episodesInfo.flatten().groupBy {
            it.epName
        }.values.toList().forEach { episode ->
            val first = episode.first()
            episodeList.add(
                SEpisode.create().apply {
                    name = first.epName
                    episode_number = first.epName.substringAfter("Ep ").toFloatOrNull() ?: 1F
                    url = json.encodeToString(
                        episode.map {
                            ServerInfo(
                                name = it.name,
                                url = it.url,
                            )
                        },
                    )
                },
            )
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val parsed = json.decodeFromString<List<ServerInfo>>(episode.url)

        parsed.forEach { server ->
            val document = client.newCall(
                GET(server.url),
            ).execute().asJsoup()

            val source = document.selectFirst(videoListSelector()) ?: return@forEach

            videoList.add(
                Video(
                    source.attr("src"),
                    "${source.attr("data-quality")}p (${server.name})",
                    source.attr("src"),
                ),
            )
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList
    }

    override fun videoListSelector() = "div#video_box div.player video source"

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun Int.toPage(): String {
        return if (this == 1) {
            ""
        } else {
            "page/$this"
        }
    }

    data class EpisodeInfo(
        val name: String,
        val epName: String,
        val url: String,
    )

    @Serializable
    data class ServerInfo(
        val name: String,
        val url: String,
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
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
}
