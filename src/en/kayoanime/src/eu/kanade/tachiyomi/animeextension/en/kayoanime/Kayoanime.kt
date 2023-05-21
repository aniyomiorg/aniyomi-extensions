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
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.text.CharacterIterator
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.util.Locale

class Kayoanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kayoanime"

    override val id = 203922289858257167

    override val baseUrl = "https://kayoanime.com"

    override val lang = "en"

    // Used for loading anime
    private var infoQuery = ""
    private var max = ""
    private var latestPost = ""
    private var layout = ""
    private var settings = ""
    private var currentReferer = ""

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val maxRecursionDepth = 2

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            infoQuery = ""
            max = ""
            latestPost = ""
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
                .add("latest_post", latestPost)
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
                latestPost = pagesNav.attr("data-latest")
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
            latestPost = ""
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
                .add("latest_post", latestPost)
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

    // Lots of code borrowed from https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/googledrive.py under the `GoogleDriveFolderIE` class
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val keyRegex = """"(\w{39})"""".toRegex()
        val versionRegex = """"([^"]+web-frontend[^"]+)"""".toRegex()
        val jsonRegex = """(?:)\s*(\{(.+)\})\s*(?:)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val boundary = "=====vc17a3rwnndj====="

        fun traverseFolder(url: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = url.substringAfter("/folders/")
            val driveHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Connection", "keep-alive")
                .add("Cookie", getCookie("https://drive.google.com"))
                .add("Host", "drive.google.com")
                .build()

            val driveDocument = client.newCall(
                GET(url, headers = driveHeaders),
            ).execute().asJsoup()
            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            val keyScript = driveDocument.select("script").first { script ->
                keyRegex.find(script.data()) != null
            }.data()
            val key = keyRegex.find(keyScript)?.groupValues?.get(1) ?: ""

            val versionScript = driveDocument.select("script").first { script ->
                keyRegex.find(script.data()) != null
            }.data()
            val driveVersion = versionRegex.find(versionScript)?.groupValues?.get(1) ?: ""
            val sapisid = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

            var pageToken: String? = ""
            while (pageToken != null) {
                val requestUrl = "/drive/v2beta/files?openDrive=true&reason=102&syncType=0&errorRecovery=false&q=trashed%20%3D%20false%20and%20'$folderId'%20in%20parents&fields=kind%2CnextPageToken%2Citems(kind%2CmodifiedDate%2CmodifiedByMeDate%2ClastViewedByMeDate%2CfileSize%2Cowners(kind%2CpermissionId%2Cid)%2ClastModifyingUser(kind%2CpermissionId%2Cid)%2ChasThumbnail%2CthumbnailVersion%2Ctitle%2Cid%2CresourceKey%2Cshared%2CsharedWithMeDate%2CuserPermission(role)%2CexplicitlyTrashed%2CmimeType%2CquotaBytesUsed%2Ccopyable%2CfileExtension%2CsharingUser(kind%2CpermissionId%2Cid)%2Cspaces%2Cversion%2CteamDriveId%2ChasAugmentedPermissions%2CcreatedDate%2CtrashingUser(kind%2CpermissionId%2Cid)%2CtrashedDate%2Cparents(id)%2CshortcutDetails(targetId%2CtargetMimeType%2CtargetLookupStatus)%2Ccapabilities(canCopy%2CcanDownload%2CcanEdit%2CcanAddChildren%2CcanDelete%2CcanRemoveChildren%2CcanShare%2CcanTrash%2CcanRename%2CcanReadTeamDrive%2CcanMoveTeamDriveItem)%2Clabels(starred%2Ctrashed%2Crestricted%2Cviewed))%2CincompleteSearch&appDataFilter=NO_APP_DATA&spaces=drive&pageToken=$pageToken&maxResults=50&supportsTeamDrives=true&includeItemsFromAllDrives=true&corpora=default&orderBy=folder%2Ctitle_natural%20asc&retryCount=0&key=$key HTTP/1.1"
                val body = """--$boundary
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$boundary
                    |
                    """.trimMargin("|").toRequestBody("multipart/mixed; boundary=\"$boundary\"".toMediaType())

                val postUrl = "https://clients6.google.com/batch/drive/v2beta".toHttpUrl().newBuilder()
                    .addQueryParameter("${'$'}ct", "multipart/mixed;boundary=\"$boundary\"")
                    .addQueryParameter("key", key)
                    .build()
                    .toString()

                val postHeaders = headers.newBuilder()
                    .add("Content-Type", "text/plain; charset=UTF-8")
                    .add("Origin", "https://drive.google.com")
                    .add("Cookie", getCookie("https://drive.google.com"))
                    .build()

                val response = client.newCall(
                    POST(postUrl, body = body, headers = postHeaders),
                ).execute()
                val parsed = json.decodeFromString<GDrivePostResponse>(
                    jsonRegex.find(response.body.string())!!.groupValues[1],
                )
                if (parsed.items == null) throw Exception("Failed to load items, please log in to google drive through webview")
                parsed.items.forEachIndexed { index, it ->
                    if (it.mimeType.startsWith("video")) {
                        val episode = SEpisode.create()
                        val size = formatBytes(it.fileSize?.toLongOrNull())
                        val pathName = if (preferences.getBoolean("trim_info", false)) {
                            path.trimInfo()
                        } else {
                            path
                        }

                        val itemNumberRegex = """ - (?:S\d+E)?(\d+)""".toRegex()
                        episode.scanlator = if (preferences.getBoolean("scanlator_order", false)) {
                            "/$pathName • $size"
                        } else {
                            "$size • /$pathName"
                        }
                        episode.name = if (preferences.getBoolean("trim_episode", false)) {
                            it.title.trimInfo()
                        } else {
                            it.title
                        }
                        episode.url = "https://drive.google.com/uc?id=${it.id}"
                        episode.episode_number = itemNumberRegex.find(it.title.trimInfo())?.groupValues?.get(1)?.toFloatOrNull() ?: index.toFloat()
                        episode.date_upload = -1L
                        episodeList.add(episode)
                    }
                    if (it.mimeType.endsWith(".folder")) {
                        traverseFolder(
                            "https://drive.google.com/drive/folders/${it.id}",
                            "$path/${it.title}",
                            recursionDepth + 1,
                        )
                    }
                }

                pageToken = parsed.nextPageToken
            }
        }

        document.select("div.toggle:has(> div.toggle-content > a[href*=drive.google.com])").distinctBy { t ->
            getVideoPathsFromElement(t)
        }.forEach { season ->
            season.select("a[href*=drive.google.com]").distinctBy { it.text() }.forEach {
                val url = it.selectFirst("a[href*=drive.google.com]")!!.attr("href").substringBeforeLast("?usp=shar")
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

    // https://github.com/yt-dlp/yt-dlp/blob/8f0be90ecb3b8d862397177bb226f17b245ef933/yt_dlp/extractor/youtube.py#L573
    private fun generateSapisidhashHeader(SAPISID: String, origin: String = "https://drive.google.com"): String {
        val timeNow = System.currentTimeMillis() / 1000
        // SAPISIDHASH algorithm from https://stackoverflow.com/a/32065323
        val sapisidhash = MessageDigest
            .getInstance("SHA-1")
            .digest("$timeNow $SAPISID $origin".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${timeNow}_$sapisidhash"
    }

    @Serializable
    data class GDrivePostResponse(
        val nextPageToken: String? = null,
        val items: List<ResponseItem>? = null,
    ) {
        @Serializable
        data class ResponseItem(
            val id: String,
            val title: String,
            val mimeType: String,
            val fileSize: String? = null,
        )
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] """.toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

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
        val trimEpisodeName = SwitchPreferenceCompat(screen.context).apply {
            key = "trim_episode"
            title = "Trim info from episode name"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        val trimEpisodeInfo = SwitchPreferenceCompat(screen.context).apply {
            key = "trim_info"
            title = "Trim info from episode info"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }

        screen.addPreference(scanlatorOrder)
        screen.addPreference(trimEpisodeName)
        screen.addPreference(trimEpisodeInfo)
    }
}
