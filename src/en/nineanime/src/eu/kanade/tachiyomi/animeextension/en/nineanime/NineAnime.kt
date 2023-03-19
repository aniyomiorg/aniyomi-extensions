package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.nineanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class NineAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "9anime"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://9anime.to")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/filter?sort=trending&page=$page")
    }

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.name").attr("href").substringBefore("?"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String =
        "nav > ul.pagination > li > a[aria-label=pagination.next]"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/filter?sort=recently_updated&page=$page")
    }

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = NineAnimeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw Exception("Not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: NineAnimeFilters.FilterSearchParams): Request {
        val vrf = if (query.isNotBlank()) callConsumet(query, "searchVrf") else ""
        var url = "$baseUrl/filter?keyword=$query"

        if (filters.genre.isNotBlank()) url += filters.genre
        if (filters.country.isNotBlank()) url += filters.country
        if (filters.season.isNotBlank()) url += filters.season
        if (filters.year.isNotBlank()) url += filters.year
        if (filters.type.isNotBlank()) url += filters.type
        if (filters.status.isNotBlank()) url += filters.status
        if (filters.language.isNotBlank()) url += filters.language
        if (filters.rating.isNotBlank()) url += filters.rating

        return GET(
            "$url&sort=${filters.sort}&$vrf&page=$page",
            headers = Headers.headersOf("Referer", "$baseUrl/"),
        )
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun getFilterList(): AnimeFilterList = NineAnimeFilters.filterList

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.title").text()
        anime.genre = document.select("div:contains(Genre) > span > a").joinToString { it.text() }
        anime.description = document.select("div.synopsis > div.shorting > div.content").text()
        anime.author = document.select("div:contains(Studio) > span > a").text()
        anime.status = parseStatus(document.select("div:contains(Status) > span").text())

        // add alternative name to anime description
        val altName = "Other name(s): "
        document.select("h1.title").attr("data-jp").let {
            if (it.isBlank().not()) {
                anime.description = when {
                    anime.description.isNullOrBlank() -> altName + it
                    else -> anime.description + "\n\n$altName" + it
                }
            }
        }
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
            .selectFirst("div[data-id]")!!.attr("data-id")
        val vrf = callConsumet(id, "vrf")
        return GET(
            "$baseUrl/ajax/episode/list/$id?$vrf",
            headers = Headers.headersOf("url", anime.url),
        )
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val episodeElements = document.select(episodeListSelector())
        return episodeElements.parallelMap { episodeFromElements(it, animeUrl) }.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not Used")

    private fun episodeFromElements(element: Element, url: String): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = element.attr("data-sub").toInt().toBoolean()
        val dub = element.attr("data-dub").toInt().toBoolean()
        episode.url = "$ids&epurl=$url/ep-$epNum"
        episode.episode_number = epNum.toFloat()
        episode.scanlator = (if (sub) "Sub" else "") + if (dub) ", Dub" else ""
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"
        episode.name = "Episode $epNum" +
            if (name.isNotEmpty() && name != namePrefix) ": $name" else ""
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val ids = episode.url.substringBefore("&")
        val vrf = callConsumet(ids, "vrf")
        val url = "/ajax/server/list/$ids?$vrf"
        val epurl = episode.url.substringAfter("epurl=")
        return GET(baseUrl + url, headers = Headers.headersOf("url", epurl))
    }

    override fun videoListParse(response: Response): List<Video> {
        val epurl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val videoList = mutableListOf<Video>()

        val servers = mutableListOf<Triple<String, String, String>>()
        val ids = response.request.url.encodedPath.substringAfter("list/")
            .substringBefore("?")
            .split(",")
        ids.getOrNull(0)?.let { subId ->
            document.select("li[data-ep-id=$subId]").map { serverElement ->
                val server = serverElement.text().lowercase()
                val serverId = serverElement.attr("data-link-id")
                servers.add(Triple("Sub", serverId, server))
            }
        }
        ids.getOrNull(1)?.let { dubId ->
            document.select("li[data-ep-id=$dubId]").map { serverElement ->
                val server = serverElement.text().lowercase()
                val serverId = serverElement.attr("data-link-id")
                servers.add(Triple("Dub", serverId, server))
            }
        }

        servers.parallelMap { videoList.addAll(extractVideoConsumet(it, epurl)) }
        if (videoList.isNotEmpty()) return videoList

        // If the above fail fallback to webview method
        // Sub
        ids.getOrNull(0)?.let { videoList.addAll(extractVizVideo("Sub", epurl)) }
        // Dub
        ids.getOrNull(1)?.let { videoList.addAll(extractVizVideo("Dub", epurl)) }

        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================= Utilities ==============================

    private fun extractVizVideo(lang: String, epurl: String): List<Video> {
        val jsInterceptor =
            client.newBuilder().addInterceptor(JsInterceptor(lang.lowercase())).build()
        val result = jsInterceptor.newCall(GET("$baseUrl$epurl")).execute()
        val masterUrl = result.request.url
        val masterPlaylist = result.body.string()
        return parseVizPlaylist(masterPlaylist, masterUrl, "Vidstream - $lang", headers)
    }

    private fun extractVideoConsumet(server: Triple<String, String, String>, epUrl: String): List<Video> {
        val vrf = callConsumet(server.second, "rawVrf")
        val referer = Headers.headersOf("referer", epUrl)
        val response = client.newCall(
            GET("$baseUrl/ajax/server/${server.second}?$vrf", headers = referer),
        ).execute()
        if (response.code != 200) return emptyList()
        val videoList = mutableListOf<Video>()
        runCatching {
            val parsed = json.decodeFromString<ServerResponse>(response.body.string())
            val embedLink = callConsumet(parsed.result.url, "decrypt")

            when (server.third) {
                "vidstream", "mycloud" -> {
                    val embedReferer = Headers.headersOf(
                        "referer",
                        "https://" + embedLink.toHttpUrl().host + "/",
                    )
                    val vidId = embedLink.substringAfterLast("/").substringBefore("?")
                    val (serverName, action) = when (server.third) {
                        "vidstream" -> Pair("Vidstream", "vizcloud")
                        "mycloud" -> Pair("MyCloud", "mcloud")
                        else -> return emptyList()
                    }
                    val playlistUrl = callConsumet(vidId, action)
                    val playlist = client.newCall(GET(playlistUrl, embedReferer)).execute()
                    videoList.addAll(
                        parseVizPlaylist(
                            playlist.body.string(),
                            playlist.request.url,
                            "$serverName - ${server.first}",
                            embedReferer,
                        ),
                    )
                }
                "filemoon" -> FilemoonExtractor(client)
                    .videoFromUrl(embedLink, "Filemoon - ${server.first}").let {
                        videoList.addAll(it)
                    }
                "streamtape" -> StreamTapeExtractor(client)
                    .videoFromUrl(embedLink, "StreamTape - ${server.first}")?.let {
                        videoList.add(it)
                    }
                "mp4upload" -> Mp4uploadExtractor(client)
                    .videoFromUrl(embedLink, "Mp4Upload - ${server.first}").let {
                        videoList.addAll(it)
                    }
                else -> null
            }
        }
        return videoList
    }

    private fun parseVizPlaylist(masterPlaylist: String, masterUrl: HttpUrl, prefix: String, embedReferer: Headers): List<Video> {
        val playlistHeaders = embedReferer.newBuilder()
            .add("host", masterUrl.host)
            .add("connection", "keep-alive")
            .build()

        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = "$prefix " + it.substringAfter("RESOLUTION=")
                    .substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = masterUrl.toString().substringBeforeLast("/") + "/" +
                    it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, playlistHeaders)
            }
    }

    private fun callConsumet(query: String, action: String): String {
        return client.newCall(
            GET("https://api.consumet.org/anime/9anime/helper?query=$query&action=$action"),
        ).execute().body.string().let {
            when (action) {
                "vizcloud", "mcloud" -> {
                    it.substringAfter("file\":\"").substringBefore("\"")
                }
                "decrypt" -> {
                    json.decodeFromString<VrfResponse>(it).url
                }
                else -> {
                    json.decodeFromString<VrfResponse>(it).let { vrf ->
                        "${vrf.vrfQuery}=${java.net.URLEncoder.encode(vrf.url, "utf-8")}"
                    }
                }
            }
        }
    }

    private fun Int.toBoolean() = this == 1

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val lang = preferences.getString("preferred_language", "Sub")!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenByDescending { it.quality.contains(lang) },
        )
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Releasing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    @Serializable
    data class ServerResponse(
        val result: Result,
    ) {

        @Serializable
        data class Result(
            val url: String,
            val skip_data: String? = null,
        )
    }

    @Serializable
    data class VrfResponse(
        val url: String,
        val vrfQuery: String? = null,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("9anime.to", "9anime.gs", "9anime.pl", "9anime.id")
            entryValues = arrayOf("https://9anime.to", "https://9anime.gs", "https://9anime.pl", "https://9anime.id")
            setDefaultValue("https://9anime.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoLanguagePref = ListPreference(screen.context).apply {
            key = "preferred_language"
            title = "Preferred language"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue("Sub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoLanguagePref)
    }

    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = runBlocking {
        map { async(Dispatchers.Default) { f(it) } }.awaitAll()
    }
}
