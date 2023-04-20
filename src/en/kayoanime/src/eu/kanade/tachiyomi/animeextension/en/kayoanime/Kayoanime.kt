package eu.kanade.tachiyomi.animeextension.en.kayoanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.en.kayoanime.extractors.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
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
import java.text.CharacterIterator
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.util.Locale

class Kayoanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kayoanime (Experimental)"

    override val baseUrl = "https://kayoanime.com"

    override val lang = "en"

    // Used for loading anime
    private var infoQuery = ""
    private var max = ""
    private var latest_post = ""
    private var layout = ""
    private var settings = ""
    private var currentReferer = ""

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val MAX_RECURSION_DEPTH = 2

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

    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            infoQuery = ""
            max = ""
            latest_post = ""
            layout = ""
            settings = ""
            currentReferer = "https://kayoanime.com/ongoing-anime/"
            GET("$baseUrl/ongoing-anime/")
        } else {
            val formBody = FormBody.Builder()
                .add("action", "tie_archives_load_more")
                .add("query", infoQuery)
                .add("max", max)
                .add("page", page.toString())
                .add("latest_post", latest_post)
                .add("layout", layout)
                .add("settings", settings)
                .build()
            val formHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", "kayoanime.com")
                .add("Origin", "https://kayoanime.com")
                .add("Referer", currentReferer)
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            POST("$baseUrl/wp-admin/admin-ajax.php", body = formBody, headers = formHeaders)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.toString().endsWith("admin-ajax.php")) {
            val body = response.body.string()
            val rawParsed = json.decodeFromString<String>(body)
            val parsed = json.decodeFromString<PostResponse>(rawParsed)
            val soup = Jsoup.parse(parsed.code)

            val animes = soup.select("li.post-item").map { element ->
                popularAnimeFromElement(element)
            }

            AnimesPage(animes, !parsed.hide_next)
        } else {
            val document = response.asJsoup()

            val animes = document.select(popularAnimeSelector()).map { element ->
                popularAnimeFromElement(element)
            }

            val hasNextPage = popularAnimeNextPageSelector().let { selector ->
                document.select(selector).first()
            } != null
            if (hasNextPage) {
                val container = document.selectFirst("ul#posts-container")!!
                val pagesNav = document.selectFirst("div.pages-nav > a")!!
                layout = container.attr("data-layout")
                infoQuery = pagesNav.attr("data-query")
                max = pagesNav.attr("data-max")
                latest_post = pagesNav.attr("data-latest")
                settings = container.attr("data-settings")
            }

            AnimesPage(animes, hasNextPage)
        }
    }

    override fun popularAnimeSelector(): String = "ul#posts-container > li.post-item"

    override fun popularAnimeNextPageSelector(): String = "div.pages-nav > a[data-text=load more]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("h2.post-title")!!.text().substringBefore(" Episode")
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "ul.tabs:has(a:contains(Recent)) + div.tab-content li.widget-single-post-item"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
            title = element.selectFirst("a.post-title")!!.text().substringBefore(" Episode")
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (page == 1) {
            infoQuery = ""
            max = ""
            latest_post = ""
            layout = ""
            settings = ""

            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
            val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

            return when {
                query.isNotBlank() -> {
                    val cleanQuery = query.replace(" ", "+")
                    currentReferer = "$baseUrl?s=$cleanQuery"
                    GET("$baseUrl?s=$cleanQuery")
                }
                genreFilter.state != 0 -> {
                    val url = "$baseUrl${genreFilter.toUriPart()}"
                    currentReferer = url
                    GET(url)
                }
                subPageFilter.state != 0 -> {
                    val url = "$baseUrl${subPageFilter.toUriPart()}"
                    currentReferer = url
                    GET(url)
                }
                else -> popularAnimeRequest(page)
            }
        } else {
            val formBody = FormBody.Builder()
                .add("action", "tie_archives_load_more")
                .add("query", infoQuery)
                .add("max", max)
                .add("page", page.toString())
                .add("latest_post", latest_post)
                .add("layout", layout)
                .add("settings", settings)
                .build()
            val formHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", "kayoanime.com")
                .add("Origin", "https://kayoanime.com")
                .add("Referer", currentReferer)
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            POST("$baseUrl/wp-admin/admin-ajax.php", body = formBody, headers = formHeaders)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SubPageFilter(),
        GenreFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Sub-page",
        arrayOf(
            Pair("<select>", ""),
            Pair("Anime Series", "/anime-series/"),
            Pair("Anime Movie", "/anime-movie/"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Adventure", "/adventure/"),
            Pair("Comedy", "/comedy/"),
            Pair("Demons", "/demons/"),
            Pair("Drama", "/drama/"),
            Pair("Fantasy", "/fantasy/"),
            Pair("Mecha", "/mecha/"),
            Pair("Military", "/military/"),
            Pair("Romance", "/romance/"),
            Pair("School", "/school/"),
            Pair("Sci-Fi", "/sci-fi/"),
            Pair("Shounen", "/shounen/"),
            Pair("Slice of Life", "/slice-of-life/"),
            Pair("Sports", "/sports/"),
            Pair("Super Power", "/super-power/"),
            Pair("Supernatural", "/supernatural/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequest(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response, anime).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    private fun animeDetailsParse(response: Response, anime: SAnime): SAnime {
        val document = response.asJsoup()
        val moreInfo = document.select("div.toggle-content > ul > li").joinToString("\n") { it.text() }
        val realDesc = document.selectFirst("div.entry-content:has(div.toggle + div.clearfix + div.toggle:has(h3:contains(Information)))")?.let {
            it.selectFirst("div.toggle > div.toggle-content")!!.text() + "\n\n"
        } ?: ""

        return SAnime.create().apply {
            title = anime.title
            thumbnail_url = anime.thumbnail_url
            status = document.selectFirst("div.toggle-content > ul > li:contains(Status)")?.let {
                parseStatus(it.text())
            } ?: SAnime.UNKNOWN
            description = realDesc + "\n\n$moreInfo"
            genre = document.selectFirst("div.toggle-content > ul > li:contains(Genres)")?.let {
                it.text().substringAfter("Genres: ")
            }
            author = document.selectFirst("div.toggle-content > ul > li:contains(Studios)")?.let {
                it.text().substringAfter("Studios: ")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        fun traverseFolder(url: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == MAX_RECURSION_DEPTH) return
            val headers = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Connection", "keep-alive")
                .add("Cookie", getCookie("https://drive.google.com"))
                .add("Host", "drive.google.com")

            val driveDocument = client.newCall(
                GET(url, headers = headers.build()),
            ).execute().asJsoup()

            if (driveDocument.selectFirst("script:containsData(requestAccess)") != null) {
                throw Exception("Please log in through webview on google drive & join group")
            }

            val script = driveDocument.selectFirst("script:containsData(_DRIVE_ivd)") ?: return
            val data = script.data().substringAfter("['_DRIVE_ivd'] = '").substringBeforeLast("';")
            val decoded = Regex("\\\\x([0-9a-fA-F]{2})").replace(data) { matchResult ->
                Integer.parseInt(matchResult.groupValues[1], 16).toChar().toString()
            }.replace("\\\\\"", "\\\"") // Dirty fix, happens when item names includes `"`

            val folderArr = json.decodeFromString<List<JsonElement>>(decoded)

            folderArr.first().jsonArray.forEachIndexed { index, item ->
                val size = item.jsonArray.getOrNull(13)?.let { t -> formatBytes(t.toString().toLongOrNull()) }
                val name = item.jsonArray.getOrNull(2)?.jsonPrimitive?.content ?: "Name unavailable"
                val id = item.jsonArray.getOrNull(0)?.jsonPrimitive?.content ?: ""
                val type = item.jsonArray.getOrNull(3)?.jsonPrimitive?.content ?: "Unknown type"
                if (type.startsWith("video")) {
                    val episode = SEpisode.create()
                    episode.scanlator = if (preferences.getBoolean("scanlator_order", false)) {
                        "/${path.trim()} • $size"
                    } else {
                        "$size • /${path.trim()}"
                    }
                    episode.name = name.removePrefix("[Kayoanime] ")
                    episode.url = "https://drive.google.com/uc?id=$id"
                    episode.episode_number = index.toFloat()
                    episode.date_upload = -1L
                    episodeList.add(episode)
                }
                if (type.endsWith(".folder")) {
                    traverseFolder(
                        "https://drive.google.com/drive/folders/$id",
                        "$path/$name",
                        recursionDepth + 1,
                    )
                }
            }
        }

        document.select("div.toggle:has(> div.toggle-content > a[href*=drive.google.com])").distinctBy { t ->
            getVideoPathsFromElement(t)
        }.forEach { season ->
            season.select("a[href*=drive.google.com]").distinctBy { it.text() }.forEach {
                val url = it.selectFirst("a[href*=drive.google.com]")!!.attr("href").substringBeforeLast("?usp=share_link")
                traverseFolder(url, getVideoPathsFromElement(season) + " " + it.text())
            }
        }

        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val indexExtractor = DriveIndexExtractor(client, headers)
        document.select("div.toggle:has(> div.toggle-content > a[href*=tinyurl.com])").forEach { season ->
            season.select("a[href*=tinyurl.com]").forEach {
                val url = it.selectFirst("a[href*=tinyurl.com]")!!.attr("href")
                val redirected = noRedirectClient.newCall(GET(url)).execute()
                redirected.headers["location"]?.let { location ->
                    if (location.toHttpUrl().host.contains("workers.dev")) {
                        episodeList.addAll(
                            indexExtractor.getEpisodesFromIndex(
                                location,
                                getVideoPathsFromElement(season) + " " + it.text(),
                                preferences.getBoolean("scanlator_order", false),
                            ),
                        )
                        // getVideoPathsFromElement(season) + " " + it.text()
                    }
                }
            }
        }

        return episodeList.reversed()
    }

    private fun getVideoPathsFromElement(element: Element): String {
        return element.selectFirst("h3")!!.text()
            .substringBefore("480p").substringBefore("720p").substringBefore("1080p")
            .replace("Download The Anime From Drive", "", true)
    }

    override fun episodeListSelector(): String = throw Exception("Not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val host = episode.url.toHttpUrl().host
        val videoList = if (host == "drive.google.com") {
            GoogleDriveExtractor(client, headers).videosFromUrl(episode.url)
        } else if (host.contains("workers.dev")) {
            getIndexVideoUrl(episode.url)
        } else {
            emptyList()
        }

        return Observable.just(videoList)
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun getIndexVideoUrl(url: String): List<Video> {
        val doc = client.newCall(
            GET("$url?a=view"),
        ).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(videodomain)")?.data()
            ?: doc.selectFirst("script:containsData(downloaddomain)")?.data()
            ?: return listOf(Video(url, "Video", url))

        if (script.contains("\"second_domain_for_dl\":false")) {
            return listOf(Video(url, "Video", url))
        }

        val domainUrl = if (script.contains("videodomain", true)) {
            script
                .substringAfter("\"videodomain\":\"")
                .substringBefore("\"")
        } else {
            script
                .substringAfter("\"downloaddomain\":\"")
                .substringBefore("\"")
        }

        val videoUrl = if (domainUrl.isBlank()) {
            url
        } else {
            domainUrl + url.toHttpUrl().encodedPath
        }

        return listOf(Video(videoUrl, "Video", videoUrl))
    }

    @Serializable
    data class PostResponse(
        val hide_next: Boolean,
        val code: String,
    )

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

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Status: Currently Airing" -> SAnime.ONGOING
            "Status: Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val scanlatorOrder = SwitchPreferenceCompat(screen.context).apply {
            key = "scanlator_order"
            title = "Switch order of file path and size"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }

        screen.addPreference(scanlatorOrder)
    }
}
