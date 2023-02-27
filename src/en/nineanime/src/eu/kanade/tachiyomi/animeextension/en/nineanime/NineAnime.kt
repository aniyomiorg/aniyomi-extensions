package eu.kanade.tachiyomi.animeextension.en.nineanime

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
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.en.nineanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
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

    private val vrfInterceptor by lazy { JsVrfInterceptor(baseUrl) }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder().add("Referer", baseUrl)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        // make the vrf webview available beforehand
        vrfInterceptor.wake()
        return GET("$baseUrl/filter?sort=trending&page=$page")
    }

    override fun popularAnimeSelector(): String = "div.ani.items > div"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.name").attr("href").substringBefore("?"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String =
        "nav > ul.pagination > li > a[aria-label=pagination.next]"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        // make the vrf webview available beforehand.
        vrfInterceptor.wake()
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
        val vrf = if (query.isNotBlank()) vrfInterceptor.getVrf(query) else ""

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
            "$url&sort=${filters.sort}&vrf=${java.net.URLEncoder.encode(vrf, "utf-8")}&page=$page",
            headers = Headers.headersOf("Referer", "$baseUrl/")
        )
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("div.poster a").attr("href"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

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
        document.select("h1.title").attr("data-jp")?.let {
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
            .selectFirst("div[data-id]").attr("data-id")
        val vrf = vrfInterceptor.getVrf(id)
        return GET(
            "$baseUrl/ajax/episode/list/$id?vrf=${java.net.URLEncoder.encode(vrf, "utf-8")}",
            headers = Headers.headersOf("url", anime.url)
        )
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeUrl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
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
        episode.url = "/ajax/server/list/$ids?vrf=&epurl=$url/ep-$epNum"
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
        val ids = episode.url.substringAfter("list/").substringBefore("?vrf")
        val vrf = vrfInterceptor.getVrf(ids)
        val url = "/ajax/server/list/$ids?vrf=${java.net.URLEncoder.encode(vrf, "utf-8")}"
        val epurl = episode.url.substringAfter("epurl=")
        return GET(baseUrl + url, headers = Headers.headersOf("url", epurl))
    }

    override fun videoListParse(response: Response): List<Video> {
        val epurl = response.request.header("url").toString()
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val videoList = mutableListOf<Video>()

        val servers = mutableListOf<Triple<String, String, String>>()
        val ids = response.request.url.encodedPath.substringAfter("list/")
            .substringBefore("?")
            .split(",")
        ids.getOrNull(0)?.let { subId ->
            document.select("li[data-ep-id=$subId]").map { serverElement ->
                val server = serverElement.text().let {
                    if (it == "Vidstream") "vizcloud" else it?.lowercase() ?: "vizcloud"
                }
                servers.add(Triple("Sub", subId, server))
            }
        }
        ids.getOrNull(1)?.let { dubId ->
            document.select("li[data-ep-id=$dubId]").map { serverElement ->
                val server = serverElement.text().let {
                    if (it == "Vidstream") "vizcloud" else it?.lowercase() ?: "vizcloud"
                }
                servers.add(Triple("Dub", dubId, server))
            }
        }

        servers.filter {
            listOf("vizcloud", "filemoon", "streamtape").contains(it.third)
        }.parallelMap { videoList.addAll(extractVideoConsumet(it)) }
        if (videoList.isNotEmpty()) return videoList

        // If the above fail fallback to webview method
        // Sub
        document.select("div[data-type=sub] > ul > li[data-sv-id=41]")
            .firstOrNull()?.attr("data-link-id")
            ?.let { videoList.addAll(extractVizVideo("Sub", epurl)) }
        // Dub
        document.select("div[data-type=dub] > ul > li[data-sv-id=41]")
            .firstOrNull()?.attr("data-link-id")
            ?.let { videoList.addAll(extractVizVideo("Dub", epurl)) }
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
        val masterUrl = result.request.url.toString()
        val masterPlaylist = result.body!!.string()
        return parseVizPlaylist(masterPlaylist, masterUrl, "Vizcloud - $lang")
    }

    private fun extractVideoConsumet(server: Triple<String, String, String>): List<Video> {
        val response = client.newCall(
            GET("https://api.consumet.org/anime/9anime/watch/${server.second}?server=${server.third}")
        ).execute()
        if (response.code != 200) return emptyList()
        val videoList = mutableListOf<Video>()
        val parsed = json.decodeFromString<WatchResponse>(response.body!!.string())
        val embedLink = parsed.embedURL ?: parsed.headers.referer
        when (server.third) {
            "vizcloud" -> {
                parsed.sources?.map { source ->
                    val playlist = client.newCall(GET(source.url)).execute()
                    videoList.addAll(
                        parseVizPlaylist(
                            playlist.body!!.string(),
                            playlist.request.url.toString(),
                            "Vizcloud - ${server.first}"
                        )
                    )
                }
            }
            "filemoon" -> FilemoonExtractor(client)
                .videoFromUrl(embedLink, "Filemoon - ${server.first}").let {
                    videoList.addAll(it)
                }
            "streamtape" -> StreamTapeExtractor(client)
                .videoFromUrl(embedLink, "StreamTape - ${server.first}")?.let {
                    videoList.add(it)
                }
            // For later use if we can get the embed link
            "mp4upload" -> Mp4uploadExtractor(client)
                .videoFromUrl(embedLink, "Mp4Upload - ${server.first}").let {
                    videoList.addAll(it)
                }
        }
        return videoList
    }

    private fun parseVizPlaylist(masterPlaylist: String, masterUrl: String, prefix: String): List<Video> {
        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = "$prefix " + it.substringAfter("RESOLUTION=")
                    .substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = masterUrl.substringBeforeLast("/") + "/" +
                    it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl)
            }
    }

    private fun Int.toBoolean() = this == 1

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val lang = preferences.getString("preferred_language", "Sub")!!

        return this.sortedWith(
            compareByDescending<Video> { it.quality.contains(quality) }
                .thenBy { it.quality.contains(lang) }
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
    data class WatchResponse(
        val headers: Header,
        val sources: List<Source>? = null,
        val embedURL: String? = null
    ) {
        @Serializable
        data class Header(
            @SerialName("Referer")
            val referer: String,
            @SerialName("User-Agent")
            val agent: String
        )

        @Serializable
        data class Source(
            val url: String,
            @SerialName("isM3U8")
            val hls: Boolean
        )
    }

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
