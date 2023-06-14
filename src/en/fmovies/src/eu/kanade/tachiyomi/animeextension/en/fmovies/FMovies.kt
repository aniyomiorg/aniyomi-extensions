package eu.kanade.tachiyomi.animeextension.en.fmovies

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.fmovies.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.en.fmovies.extractors.StreamtapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class FMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "FMovies"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending${page.toPageQuery()}", headers)

    override fun popularAnimeSelector(): String = "div.items > div.item"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val a = element.selectFirst("div.meta a")!!

        return SAnime.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            thumbnail_url = element.select("div.poster img").attr("data-src")
            title = a.text()
        }
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active + li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/filter?keyword=&sort=recently_updated${page.toPageQuery(false)}", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = FMoviesFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: FMoviesFilters.FilterSearchParams): Request =
        GET("$baseUrl/filter?keyword=$query${filters.filter}${page.toPageQuery(false)}", headers)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = FMoviesFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val info = document.selectFirst("section#w-info > div.info")!!
        val detail = info.selectFirst("div.detail")

        val descElement = info.selectFirst("div.description")
        val desc = descElement?.selectFirst("div[data-name=full]")?.ownText() ?: descElement?.ownText() ?: ""
        val extraInfo = detail?.select("> div")?.joinToString("\n") { it.text() } ?: ""

        return SAnime.create().apply {
            title = info.selectFirst("h1.name")!!.text()
            thumbnail_url = document.selectFirst("section#w-info > div.poster img")!!.attr("src")
            description = if (desc.isBlank()) extraInfo else "$desc\n\n$extraInfo"
            genre = detail?.let {
                it.select("> div:has(> div:contains(Genre:)) span").joinToString(", ") { it.text() }
            }
            author = detail?.let {
                it.select("> div:has(> div:contains(Production:)) span").joinToString(", ") { it.text() }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
            .selectFirst("div[data-id]")!!.attr("data-id")
        val vrf = callConsumet(id, "fmovies-vrf")

        val vrfHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Referer", baseUrl + anime.url)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return GET("$baseUrl/ajax/episode/list/$id?$vrf", headers = vrfHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(
            response.parseAs<AjaxResponse>().result,
        )
        val episodeList = mutableListOf<SEpisode>()
        val seasons = document.select("div.body > ul.episodes")
        seasons.forEach { season ->
            val seasonPrefix = if (seasons.size > 1) {
                "Season ${season.attr("data-season")} "
            } else {
                ""
            }

            season.select("li").forEach { ep ->
                val a = ep.selectFirst("a")!!
                episodeList.add(
                    SEpisode.create().apply {
                        name = "$seasonPrefix${ep.text().trim()}".replace("Episode ", "Ep. ")
                        episode_number = a.attr("data-num").toFloatOrNull() ?: 0F
                        url = json.encodeToString(
                            EpisodeInfo(
                                id = a.attr("data-id"),
                                url = "$baseUrl${a.attr("href")}",
                            ),
                        )
                    },
                )
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector() = throw Exception("Not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return client.newCall(videoListRequest(episode))
            .asObservableSuccess()
            .map { response ->
                videoListParse(response, episode).sort()
            }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val data = json.decodeFromString<EpisodeInfo>(episode.url)

        val vrf = callConsumet(data.id, "fmovies-vrf")

        val vrfHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Referer", data.url)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return GET("$baseUrl/ajax/server/list/${data.id}?$vrf", headers = vrfHeaders)
    }

    private fun videoListParse(response: Response, episode: SEpisode): List<Video> {
        val data = json.decodeFromString<EpisodeInfo>(episode.url)
        val document = Jsoup.parse(
            response.parseAs<AjaxResponse>().result,
        )
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        val videoList = mutableListOf<Video>()

        videoList.addAll(
            document.select("ul.servers > li.server").parallelMap { server ->
                runCatching {
                    val name = server.text().trim()
                    if (!hosterSelection.contains(name)) return@runCatching null

                    // Get decrypted url
                    val vrf = callConsumet(server.attr("data-link-id"), "fmovies-vrf")
                    val vrfHeaders = headers.newBuilder()
                        .add("Accept", "application/json, text/javascript, */*; q=0.01")
                        .add("Host", baseUrl.toHttpUrl().host)
                        .add("Referer", data.url)
                        .add("X-Requested-With", "XMLHttpRequest")
                        .build()
                    val encrypted = client.newCall(
                        GET("$baseUrl/ajax/server/${server.attr("data-link-id")}?$vrf", headers = vrfHeaders),
                    ).execute().parseAs<AjaxServerResponse>().result.url
                    val decrypted = callConsumet(encrypted, "fmovies-decrypt")

                    when (name) {
                        // Stolen from 9anime extension
                        "Vidstream", "MyCloud" -> {
                            val embedReferer = Headers.headersOf(
                                "referer",
                                "https://" + decrypted.toHttpUrl().host + "/",
                            )
                            val vidId = decrypted.substringAfterLast("/").substringBefore("?")
                            val (serverName, action) = when (name) {
                                "Vidstream" -> Pair("Vidstream", "vizcloud")
                                "MyCloud" -> Pair("MyCloud", "mcloud")
                                else -> return@parallelMap null
                            }
                            val playlistUrl = callConsumet(vidId, action)
                            val playlist = client.newCall(GET(playlistUrl, embedReferer)).execute()

                            parseVizPlaylist(
                                playlist.body.string(),
                                playlist.request.url,
                                serverName,
                                embedReferer,
                                decrypted.toHttpUrl().queryParameter("sub.info"),
                            )
                        }
                        "Filemoon" -> {
                            FilemoonExtractor(client, headers).videosFromUrl(decrypted, prefix = "Filemoon - ")
                        }
                        "Streamtape" -> {
                            StreamtapeExtractor(client, headers).videosFromUrl(decrypted)
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList.sort()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============== Utilities stolen from 9anime extension ================

    private fun callConsumet(query: String, action: String): String {
        return client.newCall(
            GET("https://api.consumet.org/anime/9anime/helper?query=$query&action=$action"),
        ).execute().body.string().let {
            when (action) {
                "vizcloud", "mcloud" -> {
                    it.substringAfter("file\":\"").substringBefore("\"")
                }
                "fmovies-decrypt" -> {
                    json.decodeFromString<VrfResponse>(it).url
                }
                else -> {
                    json.decodeFromString<VrfResponse>(it).let { vrf ->
                        "vrf=${java.net.URLEncoder.encode(vrf.url, "utf-8")}"
                    }
                }
            }
        }
    }

    private fun parseVizPlaylist(
        masterPlaylist: String,
        masterUrl: HttpUrl,
        prefix: String,
        embedReferer: Headers,
        subtitlesUrl: String?,
    ): List<Video> {
        val playlistHeaders = embedReferer.newBuilder()
            .add("accept", "*/*")
            // .add("host", masterUrl.host)
            .add("connection", "keep-alive")
            .build()

        val subtitleList = mutableListOf<Track>()
        runCatching {
            if (subtitlesUrl != null) {
                val subData = client.newCall(GET(subtitlesUrl, headers)).execute().parseAs<List<FMoviesSubs>>()
                subtitleList.addAll(
                    subData.map {
                        Track(it.file, it.label)
                    },
                )
            }
        }

        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = "$prefix - " + it.substringAfter("RESOLUTION=")
                    .substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = masterUrl.toString().substringBeforeLast("/") + "/" +
                    it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, playlistHeaders, subtitleTracks = subtitleList)
            }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun Int.toPageQuery(first: Boolean = true): String {
        return if (this == 1) "" else "${if (first) "?" else "&"}page=$this"
    }

    companion object {
        private val HOSTERS = arrayOf(
            "Vidstream",
            "MyCloud",
            "Filemoon",
            "Streamtape",
        )

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private val PREF_DOMAIN_DEFAULT = "https://fmovies.to"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidstream"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val PREF_HOSTER_DEFAULT = setOf("Vidstream", "Filemoon")
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf(
                "fmovies.to", "fmovies.wtf", "fmovies.taxi",
                "fmovies.pub", "fmovies.cafe", "fmovies.world"
            )
            entryValues = arrayOf(
                "https://fmovies.to", "https://fmovies.wtf", "https://fmovies.taxi",
                "https://fmovies.pub", "https://fmovies.cafe", "https://fmovies.world",
            )
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
