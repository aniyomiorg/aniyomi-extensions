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
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
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
import okhttp3.FormBody
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

class Aniwave : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Aniwave"

    override val id: Long = 98855593379717478

    override val baseUrl
        get() = preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=trending&page=$page")

    override fun popularAnimeSelector(): String = "div.ani.items > div.item"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(
            element.select("a.name")
                .attr("href")
                .substringBefore("?"),
        )
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String =
        "nav > ul.pagination > li > a[rel=next]" // TODO The last 2 pages will be ignored, need to override fetchPopular to fix

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter?sort=recently_updated&page=$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AniwaveFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw Exception("Not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AniwaveFilters.FilterSearchParams): Request {
        val vrf = if (query.isNotBlank()) callEnimax(query, "vrf") else ""
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
            "$url&sort=${filters.sort}&page=$page&$vrf",
            headers = Headers.headersOf("Referer", "$baseUrl/"),
        )
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AniwaveFilters.FILTER_LIST

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
        val vrf = callEnimax(id, "vrf")
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
        val vrf = callEnimax(ids, "vrf")
        val url = "/ajax/server/list/$ids?$vrf"
        val epurl = episode.url.substringAfter("epurl=")
        return GET(baseUrl + url, headers = Headers.headersOf("url", epurl))
    }

    override fun videoListParse(response: Response): List<Video> {
        val epurl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        return document.select("div.servers > div").parallelMap { elem ->
            val type = elem.attr("data-type").replaceFirstChar { it.uppercase() }
            elem.select("li").mapNotNull { serverElement ->
                val serverId = serverElement.attr("data-link-id")
                val serverName = serverElement.text().lowercase()
                if (hosterSelection.contains(serverName).not()) return@mapNotNull null
                Triple(type, serverId, serverName)
            }
        }
            .flatten()
            .parallelMap { extractVideo(it, epurl) }
            .flatten()
            .ifEmpty { throw Exception("Failed to fetch videos") }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================= Utilities ==============================

    private fun extractVideo(server: Triple<String, String, String>, epUrl: String): List<Video> {
        val vrf = callEnimax(server.second, "rawVrf")
        val referer = Headers.headersOf("referer", epUrl)
        val response = client.newCall(
            GET("$baseUrl/ajax/server/${server.second}?$vrf", headers = referer),
        ).execute()
        if (response.code != 200) return emptyList()
        val videoList = mutableListOf<Video>()
        runCatching {
            val parsed = json.decodeFromString<ServerResponse>(response.body.string())
            val embedLink = callEnimax(parsed.result.url, "decrypt")
            when (server.third) {
                "vidplay", "mycloud" -> {
                    val vidId = embedLink.substringAfterLast("/").substringBefore("?")
                    val (serverName, action) = when (server.third) {
                        "vidplay" -> Pair("VidPlay", "rawVizcloud")
                        "mycloud" -> Pair("MyCloud", "rawMcloud")
                        else -> return emptyList()
                    }
                    val rawURL = callEnimax(vidId, action) + "?${embedLink.substringAfter("?")}"
                    val rawReferer = Headers.headersOf(
                        "referer",
                        "$embedLink&autostart=true",
                        "x-requested-with",
                        "XMLHttpRequest",
                    )
                    val rawResponse = client.newCall(GET(rawURL, rawReferer)).execute().parseAs<MediaResponseBody>()
                    val playlistUrl = rawResponse.result.sources.first().file
                        .replace("#.mp4", "")
                    val embedReferer = Headers.headersOf(
                        "referer",
                        "https://${embedLink.toHttpUrl().host}/",
                    )
                    client.newCall(GET(playlistUrl, embedReferer)).execute().use {
                        parseVizPlaylist(
                            it.body.string(),
                            it.request.url,
                            "$serverName - ${server.first}",
                            embedReferer,
                            rawResponse.result.tracks,
                        )
                    }.also(videoList::addAll)
                }
                "filemoon" -> FilemoonExtractor(client)
                    .videosFromUrl(embedLink, "Filemoon - ${server.first}")
                    .also(videoList::addAll)

                "streamtape" -> StreamTapeExtractor(client)
                    .videoFromUrl(embedLink, "StreamTape - ${server.first}")
                    ?.let(videoList::add)
                "mp4upload" -> Mp4uploadExtractor(client)
                    .videosFromUrl(embedLink, headers, suffix = " - ${server.first}")
                    .let(videoList::addAll)
                else -> null
            }
        }
        return videoList
    }

    private fun parseVizPlaylist(
        masterPlaylist: String,
        masterUrl: HttpUrl,
        prefix: String,
        embedReferer: Headers,
        subTracks: List<MediaResponseBody.Result.SubTrack> = emptyList(),
    ): List<Video> {
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
                Video(videoUrl, quality, videoUrl, playlistHeaders, subtitleTracks = subTracks.toTracks())
            }
    }

    private fun callEnimax(query: String, action: String): String {
        return if (action in listOf("rawVizcloud", "rawMcloud")) {
            val referer = if (action == "rawVizcloud") "https://vidstream.pro/" else "https://mcloud.to/"
            val futoken = client.newCall(
                GET(referer + "futoken", headers),
            ).execute().use { it.body.string() }
            val formBody = FormBody.Builder()
                .add("query", query)
                .add("futoken", futoken)
                .build()
            client.newCall(
                POST(
                    url = "https://9anime.eltik.net/$action?apikey=aniyomi",
                    body = formBody,
                ),
            ).execute().parseAs<RawResponse>().rawURL
        } else {
            client.newCall(
                GET("https://9anime.eltik.net/$action?query=$query&apikey=aniyomi"),
            ).execute().use {
                val body = it.body.string()
                when (action) {
                    "decrypt" -> {
                        json.decodeFromString<VrfResponse>(body).url
                    }
                    else -> {
                        json.decodeFromString<VrfResponse>(body).let { vrf ->
                            "${vrf.vrfQuery}=${java.net.URLEncoder.encode(vrf.url, "utf-8")}"
                        }
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

    private inline fun <reified T> Response.parseAs(): T {
        val responseBody = use { it.body.string() }
        return json.decodeFromString(responseBody)
    }

    private fun List<MediaResponseBody.Result.SubTrack>.toTracks(): List<Track> {
        return filter {
            it.kind == "captions"
        }.mapNotNull {
            runCatching {
                Track(
                    it.file,
                    it.label,
                )
            }.getOrNull()
        }
    }

    @Serializable
    data class ServerResponse(
        val result: Result,
    ) {

        @Serializable
        data class Result(
            val url: String,
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

    @Serializable
    data class MediaResponseBody(
        val status: Int,
        val result: Result,
    ) {
        @Serializable
        data class Result(
            val sources: ArrayList<Source>,
            val tracks: ArrayList<SubTrack> = ArrayList(),
        ) {
            @Serializable
            data class Source(
                val file: String,
            )

            @Serializable
            data class SubTrack(
                val file: String,
                val label: String = "",
                val kind: String,
            )
        }
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://aniwave.to"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_LANG_KEY = "preferred_language"
        private const val PREF_LANG_DEFAULT = "Sub"

        private const val PREF_MARK_FILLERS_KEY = "mark_fillers"
        private const val PREF_MARK_FILLERS_DEFAULT = true

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val HOSTERS = arrayOf(
            "VidPlay",
            "MyCloud",
            "Filemoon",
            "StreamTape",
            "Mp4Upload",
        )
        private val HOSTERS_NAMES = arrayOf(
            "vidplay",
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
            title = "Preferred domain"
            entries = arrayOf("aniwave.to", "aniwave.bz", "aniwave.ws")
            entryValues = arrayOf("https://aniwave.to", "https://aniwave.bz", "https://aniwave.ws")
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
