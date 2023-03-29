package eu.kanade.tachiyomi.animeextension.all.netfilm

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.ceil
import kotlin.math.floor

class NetFilm : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "NetFilm"

    override val baseUrl = "https://net-film.vercel.app/api"

    private val hostName = baseUrl.toHttpUrl().host

    override val lang = "all"

    private var sort = ""

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient

    private val newHeaders = headers.newBuilder()
        .add("Accept", "application/json, text/plain, */*")
        .add("appid", "eyJhbGciOiJIUzI1NiJ9")
        .add("Host", hostName)
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        if (page == 1) sort = ""
        val popHeaders = newHeaders.add("Referer", "https://$hostName/explore").build()
        return GET("$baseUrl/category?area=&category=1&order=count&params=COMIC&size=30&sort=$sort&subtitles=&year=", headers = popHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<CategoryResponse>(response.body.string())
        if (parsed.data.isEmpty()) {
            return AnimesPage(emptyList(), false)
        }

        val animeList = parsed.data.map { ani ->
            SAnime.create().apply {
                title = ani.name
                thumbnail_url = ani.coverVerticalUrl
                setUrlWithoutDomain(
                    LinkData(
                        ani.domainType.toString(),
                        ani.id,
                        response.request.url.toString(),
                    ).toJsonString(),
                )
            }
        }

        sort = parsed.data.last().sort

        return AnimesPage(animeList, animeList.size == 30)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        if (page == 1) sort = ""
        val latestHeaders = newHeaders.add("Referer", "https://$hostName/explore").build()
        return GET("$baseUrl/category?area=&category=1&order=up&params=COMIC&size=30&sort=$sort&subtitles=&year=", headers = latestHeaders)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (page == 1) sort = ""
        return if (query.isNotEmpty()) {
            val searchHeaders = newHeaders.add("Referer", "$baseUrl/search?keyword=$query&size=30").build()
            GET("$baseUrl/search?keyword=$query&size=30", headers = searchHeaders)
        } else {
            val pageList = filters.find { it is SubPageFilter } as SubPageFilter
            val pageHeaders = newHeaders.add("Referer", "https://$hostName/explore").build()
            GET("$baseUrl${pageList.toUriPart()}&sort=$sort&subtitles=&year=", headers = pageHeaders)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.encodedPath
        return if (url.startsWith("/api/category")) {
            popularAnimeParse(response)
        } else {
            val parsed = json.decodeFromString<SearchResponse>(response.body.string())
            if (parsed.data.results.isEmpty()) {
                return AnimesPage(emptyList(), false)
            }

            val animeList = parsed.data.results.map { ani ->
                SAnime.create().apply {
                    title = ani.name
                    thumbnail_url = ani.coverVerticalUrl
                    setUrlWithoutDomain(
                        LinkData(
                            ani.domainType.toString(),
                            ani.id,
                            response.request.url.toString(),
                        ).toJsonString(),
                    )
                }
            }

            sort = parsed.data.results.last().sort

            AnimesPage(animeList, animeList.size == 30)
        }
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SubPageFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Sub Page",
        arrayOf(
            Pair("Popular Movie", "/category?area=&category=1&order=count&params=MOVIE,TVSPECIAL&size=30"),
            Pair("Recent Movie", "/category?area=&category=1&order=up&params=MOVIE,TVSPECIAL&size=30"),
            Pair("Popular TV Series", "/category?area=&category=1&order=count&params=TV,SETI,MINISERIES,VARIETY,TALK,DOCUMENTARY&size=30"),
            Pair("Recent TV Series", "/category?area=&category=1&order=up&params=TV,SETI,MINISERIES,VARIETY,TALK,DOCUMENTARY&size=30"),
            Pair("Popular Anime", "/category?area=&category=1&order=count&params=COMIC&size=30"),
            Pair("Recent Anime", "/category?area=&category=1&order=up&params=COMIC&size=30"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val parsed = json.decodeFromString<LinkData>(anime.url)
        val detailsHeader = newHeaders.add("Referer", parsed.url).build()

        val resp = client.newCall(
            GET("$baseUrl/detail?category=${parsed.category}&id=${parsed.id}", headers = detailsHeader),
        ).execute()
        val data = json.decodeFromString<AnimeInfoResponse>(resp.body.string()).data
        return Observable.just(
            anime.apply {
                title = data.name
                thumbnail_url = data.coverVerticalUrl
                description = data.introduction
                genre = data.tagList.joinToString(", ") { it.name }
            },
        )
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val parsed = json.decodeFromString<LinkData>(anime.url)

        val episodeHeader = newHeaders.add("Referer", parsed.url).build()
        val resp = client.newCall(
            GET("$baseUrl/detail?category=${parsed.category}&id=${parsed.id}", headers = episodeHeader),
        ).execute()
        val data = json.decodeFromString<AnimeInfoResponse>(resp.body.string()).data
        val episodeList = data.episodeVo.map { ep ->
            val formattedEpNum = if (floor(ep.seriesNo) == ceil(ep.seriesNo)) {
                ep.seriesNo.toInt()
            } else {
                ep.seriesNo
            }
            SEpisode.create().apply {
                episode_number = ep.seriesNo
                setUrlWithoutDomain(
                    LinkData(
                        data.category.toString(),
                        data.id,
                        "$baseUrl/detail?category=${parsed.category}&id=${parsed.id}",
                        ep.id.toString(),
                    ).toJsonString(),
                )
                name = "Episode $formattedEpNum"
            }
        }
        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val parsed = json.decodeFromString<LinkData>(episode.url)
        val videoHeaders = newHeaders.add("Referer", parsed.url).build()
        val resp = client.newCall(
            GET("$baseUrl/episode?category=${parsed.category}&id=${parsed.id}&episode=${parsed.episodeId!!}", headers = videoHeaders),
        ).execute()
        val episodeParsed = json.decodeFromString<EpisodeResponse>(resp.body.string())
        val subtitleList = episodeParsed.data.subtitles.map { sub ->
            Track(sub.url, sub.language)
        }
        val videoList = episodeParsed.data.qualities.map { quality ->
            Video(quality.url, "${quality.quality}p", quality.url, subtitleTracks = subtitleList)
        }
        return Observable.just(videoList.sort())
    }

    // ============================= Utilities ==============================

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
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
}
