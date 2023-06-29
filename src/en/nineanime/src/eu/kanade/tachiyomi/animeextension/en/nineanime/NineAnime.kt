package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
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

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=trending&page=$page")

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.name").attr("href").substringBefore("?"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String =
        "nav > ul.pagination > li > a[aria-label=pagination.next]"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter?sort=recently_updated&page=$page")

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
        val vrf = if (query.isNotBlank()) callConsumet(query, "vrf") else ""
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

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = NineAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        title = document.select("h1.title").text()
        genre = document.select("div:contains(Genre) > span > a").joinToString { it.text() }
        description = document.select("div.synopsis > div.shorting > div.content").text()
        author = document.select("div:contains(Studio) > span > a").text()
        status = parseStatus(document.select("div:contains(Status) > span").text())

        val altName = "Other name(s): "
        document.select("h1.title").attr("data-jp").let {
            if (it.isNotBlank()) {
                description = when {
                    description.isNullOrBlank() -> altName + it
                    else -> description + "\n\n$altName" + it
                }
            }
        }
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
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = element.attr("data-sub").toInt().toBoolean()
        val dub = element.attr("data-dub").toInt().toBoolean()
        val extraInfo = if (element.hasClass("filler") && preferences.getBoolean(PREF_MARK_FILLERS_KEY, PREF_MARK_FILLERS_DEFAULT)) {
            " • Filler Episode"
        } else {
            ""
        }
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"

        return SEpisode.create().apply {
            this.name = "Episode $epNum" +
                if (name.isNotEmpty() && name != namePrefix) ": $name" else ""
            this.url = "$ids&epurl=$url/ep-$epNum"
            episode_number = epNum.toFloat()
            scanlator = ((if (sub) "Sub" else "") + if (dub) ", Dub" else "") + extraInfo
        }
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
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        val videoList = mutableListOf<Video>()

        val servers = mutableListOf<Triple<String, String, String>>()
        val ids = response.request.url.encodedPath.substringAfter("list/")
            .substringBefore("?")
            .split(",")
        ids.getOrNull(0)?.let { subId ->
            document.select("li[data-ep-id=$subId]").forEach { serverElement ->
                val server = serverElement.text().lowercase()
                if (hosterSelection.contains(server).not()) return@forEach

                val serverId = serverElement.attr("data-link-id")
                servers.add(Triple("Sub", serverId, server))
            }
        }
        ids.getOrNull(1)?.let { dubId ->
            document.select("li[data-ep-id=$dubId]").forEach { serverElement ->
                val server = serverElement.text().lowercase()
                if (hosterSelection.contains(server).not()) return@forEach

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

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

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
                        "vidstream" -> Pair("Vidstream", "rawVizcloud")
                        "mycloud" -> Pair("MyCloud", "rawMcloud")
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
                    .videosFromUrl(embedLink, headers, suffix = " - ${server.first}")
                    .let(videoList::addAll)
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
            GET("https://9anime.eltik.net/$action?query=$query&apikey=aniyomi"),
        ).execute().body.string().let {
            when (action) {
                "rawVizcloud", "rawMcloud" -> {
                    val rawURL = json.decodeFromString<RawResponse>(it).rawURL
                    val referer = if (action == "rawVizcloud") "https://vidstream.pro/" else "https://mcloud.to/"
                    val apiResponse = client.newCall(
                        GET(
                            url = rawURL,
                            headers = Headers.headersOf("Referer", referer),
                        ),
                    ).execute().body.string()

                    apiResponse.substringAfter("file\":\"").substringBefore("\"")
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
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!

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

    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> = runBlocking {
        map { async(Dispatchers.Default) { f(it) } }.awaitAll()
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

    @Serializable
    data class RawResponse(
        val rawURL: String,
    )

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://9anime.to"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_MARK_FILLERS_KEY = "mark_fillers"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf(
            "Vidstream",
            "MyCloud",
            "Filemoon",
            "StreamTape",
            "Mp4Upload",
        )
        private val HOSTERS_NAMES = arrayOf(
            "vidstream",
            "mycloud",
            "filemoon",
            "streamtape",
            "mp4upload",
        )
        private val PREF_HOSTER_DEFAULT = HOSTERS_NAMES.toSet()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("9anime.to", "9anime.gs", "9anime.pl", "9anime.id", "9anime.ph", "9animehq.to", "9animeto.io")
            entryValues = arrayOf("https://9anime.to", "https://9anime.gs", "https://9anime.pl", "https://9anime.id", "https://9anime.ph", "https://9animehq.to", "http://9animeto.io")
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
            key = PREF_LANG_KEY
            title = "Preferred language"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_MARK_FILLERS_KEY
            title = "Mark filler episodes"
            setDefaultValue(PREF_MARK_FILLERS_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS_NAMES
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
