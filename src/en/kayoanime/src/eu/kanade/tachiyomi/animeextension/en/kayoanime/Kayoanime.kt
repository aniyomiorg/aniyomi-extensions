package eu.kanade.tachiyomi.animeextension.en.kayoanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.CharacterIterator
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.util.Locale

class Kayoanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kayoanime"

    override val baseUrl = "https://kayoanime.com"

    override val lang = "en"

    private var authCookie = ""

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing-anime/")

    override fun popularAnimeSelector(): String = "ul#posts-container > li.post-item"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("p.post-excerpt")!!.text().substringBefore(" Episode")
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div#latest-tab-pane > div.row > div.col-md-6"

    override fun latestUpdatesNextPageSelector(): String? = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val thumbnailUrl = element.selectFirst("img")!!.attr("data-src")

        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a.animeparent")!!.attr("href"))
            thumbnail_url = if (thumbnailUrl.contains(baseUrl.toHttpUrl().host)) {
                thumbnailUrl
            } else {
                baseUrl + thumbnailUrl
            }
            title = element.selectFirst("span.animename")!!.text()
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = if (response.request.url.encodedPath.startsWith("/animelist/")) {
            document.select(searchAnimeSelectorFilter()).map { element ->
                searchAnimeFromElement(element)
            }
        } else {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

//    private fun searchAnimeRequest(page: Int, query: String, filters: AnimeDaoFilters.FilterSearchParams): Request {
//        return if (query.isNotBlank()) {
//            val cleanQuery = query.replace(" ", "+")
//            GET("$baseUrl/search/?search=$cleanQuery", headers = headers)
//        } else {
//            var url = "$baseUrl/animelist/".toHttpUrlOrNull()!!.newBuilder()
//                .addQueryParameter("status[]=", filters.status)
//                .addQueryParameter("order[]=", filters.order)
//                .build().toString()
//
//            if (filters.genre.isNotBlank()) url += "&${filters.genre}"
//            if (filters.rating.isNotBlank()) url += "&${filters.rating}"
//            if (filters.letter.isNotBlank()) url += "&${filters.letter}"
//            if (filters.year.isNotBlank()) url += "&${filters.year}"
//            if (filters.score.isNotBlank()) url += "&${filters.score}"
//            url += "&page=$page"
//
//            GET(url, headers = headers)
//        }
//    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun searchAnimeSelectorFilter(): String = "div.container div.col-12 > div.row > div.col-md-6"

    override fun searchAnimeNextPageSelector(): String = "ul.pagination > li.page-item:has(i.fa-arrow-right):not(.disabled)"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.just(anime)
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

//    override fun animeDetailsParse(document: Document): SAnime {
//        val thumbnailUrl = document.selectFirst("div.card-body img")!!.attr("data-src")
//        val moreInfo = document.select("div.card-body table > tbody > tr").joinToString("\n") { it.text() }
//
//        return SAnime.create().apply {
//            title = document.selectFirst("div.card-body h2")!!.text()
//            thumbnail_url = if (thumbnailUrl.contains(baseUrl.toHttpUrl().host)) {
//                thumbnailUrl
//            } else {
//                baseUrl + thumbnailUrl
//            }
//            status = document.selectFirst("div.card-body table > tbody > tr:has(>td:contains(Status)) td:not(:contains(Status))")?.let {
//                parseStatus(it.text())
//            } ?: SAnime.UNKNOWN
//            description = (document.selectFirst("div.card-body div:has(>b:contains(Description))")?.ownText() ?: "") + "\n\n$moreInfo"
//            genre = document.select("div.card-body table > tbody > tr:has(>td:contains(Genres)) td > a").joinToString(", ") { it.text() }
//        }
//    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select("div.toggle-content > a[href*=drive.google.com]").forEach {
            val url = it.attr("href").substringBeforeLast("?usp=share_link")
            Log.i("SOMEURL", url)
            val headers = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Connection", "keep-alive")
                .add("Host", "drive.google.com")
            val driveDocument = client.newCall(
                GET(url, headers = headers.build()),
            ).execute().asJsoup()

            val script = driveDocument.selectFirst("script:containsData(_DRIVE_ivd)") ?: return@forEach
            val data = script.data().substringAfter("['_DRIVE_ivd'] = '").substringBeforeLast("';")
            val decoded = Regex("\\\\x([0-9a-fA-F]{2})").replace(data) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }
            val folderArr = json.decodeFromString<List<JsonElement>>(decoded)

            folderArr.first().jsonArray.forEachIndexed { index, item ->
                val size = item.jsonArray.getOrNull(13)?.let { t -> formatBytes(t.toString().toLongOrNull()) }
                val name = item.jsonArray.getOrNull(2)?.jsonPrimitive?.content ?: "Name unavailable"
                val id = item.jsonArray.getOrNull(0)?.jsonPrimitive?.content ?: ""

                val episode = SEpisode.create()
                episode.scanlator = size
                episode.name = name
                episode.url = "https://drive.google.com/uc?id=$id"
                episode.episode_number = index.toFloat()
                episodeList.add(episode)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = "div#episodes-tab-pane > div.row > div > div.card"

    override fun episodeFromElement(element: Element): SEpisode {
        val episodeName = element.selectFirst("span.animename")!!.text()
        val episodeTitle = element.selectFirst("div.animetitle")?.text() ?: ""

        return SEpisode.create().apply {
            name = "$episodeName $episodeTitle"
            episode_number = if (episodeName.contains("Episode ", true)) {
                episodeName.substringAfter("Episode ").substringBefore(" ").toFloatOrNull() ?: 0F
            } else { 0F }
            date_upload = element.selectFirst("span.date")?.let { parseDate(it.text()) } ?: 0L
            setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
        }
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return OkHttpClient().newCall(videoListRequest(episode))
            .asObservableSuccess()
            .map { response ->
                videoListParse(response).sort()
            }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val cookieList = client.cookieJar.loadForRequest(episode.url.toHttpUrl())
        if (cookieList.isNotEmpty()) {
            authCookie = cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        }

        val videoHeaders = Headers.Builder()
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Connection", "keep-alive")
            .add("Cookie", authCookie)
            .add("Host", "drive.google.com")
            .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6)")
            .build()
        Log.i("AUTHCOOKIEURL", episode.url)
        Log.i("AUTHCOOKIE", authCookie)

        return GET(episode.url, headers = videoHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val noRedirectClient = OkHttpClient().newBuilder().followRedirects(false).build()
        val url = document.selectFirst("form#download-form")?.attr("action") ?: return emptyList()

        val redirectHeaders = Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add("Content-Length", "0")
            .add("Content-Type", "application/x-www-form-urlencoded")
            .add("Cookie", authCookie)
            .add("Host", "drive.google.com")
            .add("Origin", "https://drive.google.com")
            .add("Referer", url.substringBeforeLast("&at="))
            .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6)")
            .build()

        Log.i("SOMETHINGURL", url)

        var response = noRedirectClient.newCall(
            POST(url, headers = redirectHeaders, body = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())),
        ).execute()
//        if (response.code == 200) {
//            val dlUrl = response.asJsoup().selectFirst("form#download-form")?.attr("action") ?: return emptyList()
//            response = noRedirectClient.newCall(
//                GET(dlUrl.substringBefore("&at"), headers = redirectHeaders),
//            ).execute()
//        }
//        Log.i("SOMETHING", response.toString())
//        Log.i("SOMETHING2", response.asJsoup().toString())
//        Log.i("SOMETHINGCOOL", response.headers.joinToString("\n") { "${it.first} - ${it.second}" })

        var redirected: String? = response.headers["location"] ?: return emptyList()
        var videoHeaders = Headers.Builder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add("Host", redirected!!.toHttpUrl().host)
            .add("Referer", "https://drive.google.com/")
            .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6)")
            .build()

        videoList.add(
            Video(redirected, "Video", redirected, headers = videoHeaders),
        )

//        while (true) {
//            if (newUrl.toHttpUrl().host.endsWith("googleusercontent.com")) {
//                // Set usercontent cookie for use later
//                response.headers.forEach {
//                    if (it.first == "set-cookie" && it.second.startsWith("AUTH_")) {
//                        usercontentCookie = it.second.substringBefore(";")
//                    }
//                }
//                videoHeaders = Headers.Builder()
//                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
//                    .add("Connection", "keep-alive")
//                    .add("Host", redirected!!.toHttpUrl().host)
//                    .add("Referer", "https://drive.google.com/")
//                    .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6)")
//                    .build()
//                response = noRedirectClient.newCall(
//                    GET(redirected, headers = videoHeaders),
//                ).execute()
//                newUrl = redirected
//                redirected = response.headers["location"]
//            } else if (newUrl.toHttpUrl().host == "drive.google.com") {
//                videoHeaders = Headers.Builder()
//                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
//                    .add("Connection", "keep-alive")
//                    .add("Cookie", authCookie)
//                    .add("Host", "drive.google.com")
//                    .add("Referer", "https://drive.google.com/")
//                    .add("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6)")
//                    .build()
//                response = noRedirectClient.newCall(
//                    GET(redirected!!, headers = videoHeaders),
//                ).execute()
//                newUrl = redirected
//                redirected = response.headers["location"]
//            }
//
//            if (usercontentCookie.isNotBlank() && newUrl.contains("googleusercontent")) break
//
//            Log.i("NEWURL1", newUrl)
//            Log.i("NEWURL2", redirected.toString())
//        }

        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "vstream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    data class Server(
        val url: String,
        val name: String,
    )

    private fun parseDate(dateStr: String): Long {
        return runCatching { DateFormatter.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun formatBytes(bytes: Long?): String? {
        if (bytes == null) return null
        val absB = if (bytes == Long.MIN_VALUE) Long.MAX_VALUE else Math.abs(bytes)
        if (absB < 1024) {
            return "$bytes B"
        }
        var value = absB
        val ci: CharacterIterator = StringCharacterIterator("KMGTPE")
        var i = 40
        while (i >= 0 && absB > 0xfffccccccccccccL shr i) {
            value = value shr 10
            ci.next()
            i -= 10
        }
        value *= java.lang.Long.signum(bytes).toLong()
        return java.lang.String.format("%.1f %cB", value / 1024.0, ci.current())
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("animedao.to")
            entryValues = arrayOf("https://animedao.to")
            setDefaultValue("https://animedao.to")
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
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Vidstreaming", "Vidstreaming2", "Vidstreaming3", "Mixdrop", "Fembed", "StreamSB", "Streamtape", "Vidstreaming4", "Doodstream")
            entryValues = arrayOf("vstream", "src2", "src", "mixdrop", "vcdn", "streamsb", "streamtape", "vplayer", "doodstream")
            setDefaultValue("vstream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val episodeSortPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_episode_sorting"
            title = "Attempt episode sorting"
            summary = """AnimeDao displays the episodes in either ascending or descending order,
                | enable to attempt order or disable to set same as website.
            """.trimMargin()
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
        screen.addPreference(episodeSortPref)
    }
}
