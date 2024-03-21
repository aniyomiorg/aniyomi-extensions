package eu.kanade.tachiyomi.animeextension.en.ripcrabbyanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

class Ripcrabbyanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Ripcrabbyanime"

    override val id = 623659475482363776

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://ripcrabbyanime.com"

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ongoing-series/")

    override fun popularAnimeSelector(): String = "section#movies-list div.movies-box"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img[src]")?.attr("src") ?: ""
        title = element.selectFirst("a:matches(.)")!!.text().substringBefore(" | Episode").trimEnd()
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

        return when {
            query.isNotBlank() -> {
                val cleanQuery = query.replace(" ", "+")
                GET("$baseUrl/search/?s=$cleanQuery")
            }
            genreFilter.state != 0 -> {
                val url = "$baseUrl${genreFilter.toUriPart()}"
                val body = "start=${(page - 1) * 40}&limit=40".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                POST(url, body = body)
            }
            subPageFilter.state != 0 -> {
                val url = "$baseUrl${subPageFilter.toUriPart()}"
                val body = "start=${(page - 1) * 40}&limit=40".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                POST(url, body = body)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.encodedPath.startsWith("/search/")) {
            popularAnimeParse(response)
        } else {
            val animeList = response.asJsoup()
                .select(searchAnimeSelector())
                .map(::popularAnimeFromElement)

            return AnimesPage(animeList, animeList.size == 40)
        }
    }

    override fun searchAnimeSelector(): String = "div#infinite-list"

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String? = popularAnimeNextPageSelector()

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
            Pair("Anime Series", "/Get/get-animeserise/"),
            Pair("Anime Movie", "/Get/get-movies/"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/Get/get-action/"),
            Pair("Adventure", "/Get/get-adventure/"),
            Pair("Comedy", "/Get/get-comedy/"),
            Pair("Demons", "/Get/get-demons/"),
            Pair("Drama", "/Get/get-drama/"),
            Pair("Ecchi", "/Get/get-ecchi/"),
            Pair("Fantasy", "/Get/get-fantasy/"),
            Pair("Harem", "/Get/get-harem/"),
            Pair("Horror", "/Get/get-horror/"),
            Pair("Mecha", "/Get/get-mecha/"),
            Pair("Magic", "/Get/get-magic/"),
            Pair("Romance", "/Get/get-romance/"),
            Pair("School", "/Get/get-school/"),
            Pair("Sci-Fi", "/Get/get-sci-fi/"),
            Pair("Shounen", "/Get/get-shounen/"),
            Pair("Slice of Life", "/Get/get-slice-of-life/"),
            Pair("Sports", "/Get/get-sports/"),
            Pair("Super Power", "/Get/get-superpower/"),
            Pair("Supernatural", "/Get/get-supernatural/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val moreInfo = document.select("div.summery:not(:has(h2:contains(Summary))) ul li").joinToString("\n") { it.ownText().trim() }
        val realDesc = document.selectFirst("div.summery:has(h2:contains(Summary)) ul")?.let { "${it.text()}\n\n" } ?: ""

        return SAnime.create().apply {
            status = document.selectFirst("div.summery:not(:has(h2:contains(Summary))) ul li:contains(Status)")?.let {
                parseStatus(it.text().substringAfter("Status: "))
            } ?: SAnime.UNKNOWN
            description = realDesc + "\n\n$moreInfo"
            genre = document.selectFirst("div.summery:not(:has(h2:contains(Summary))) ul li:contains(Genres)")?.let {
                it.text().substringAfter("Genres: ")
            }
            author = document.selectFirst("div.summery:not(:has(h2:contains(Summary))) ul li:contains(Studios)")?.let {
                it.text().substringAfter("Studios: ").substringAfter("Studios ")
            }
        }
    }

    // ============================== Episodes ==============================

    // Lots of code borrowed from https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/googledrive.py under the `GoogleDriveFolderIE` class
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        fun traverseFolder(url: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == MAX_RECURSION_DEPTH) return

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
                KEY_REGEX.find(script.data()) != null
            }.data()
            val key = KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

            val versionScript = driveDocument.select("script").first { script ->
                KEY_REGEX.find(script.data()) != null
            }.data()
            val driveVersion = VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""
            val sapisid = client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

            var pageToken: String? = ""
            while (pageToken != null) {
                val requestUrl = "/drive/v2internal/files?openDrive=false&reason=102&syncType=0&errorRecovery=false&q=trashed%20%3D%20false%20and%20'$folderId'%20in%20parents&fields=kind%2CnextPageToken%2Citems(kind%2CmodifiedDate%2ChasVisitorPermissions%2CcontainsUnsubscribedChildren%2CmodifiedByMeDate%2ClastViewedByMeDate%2CalternateLink%2CfileSize%2Cowners(kind%2CpermissionId%2CemailAddressFromAccount%2Cdomain%2Cid)%2ClastModifyingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CcustomerId%2CancestorHasAugmentedPermissions%2ChasThumbnail%2CthumbnailVersion%2Ctitle%2Cid%2CresourceKey%2CabuseIsAppealable%2CabuseNoticeReason%2Cshared%2CaccessRequestsCount%2CsharedWithMeDate%2CuserPermission(role)%2CexplicitlyTrashed%2CmimeType%2CquotaBytesUsed%2Ccopyable%2Csubscribed%2CfolderColor%2ChasChildFolders%2CfileExtension%2CprimarySyncParentId%2CsharingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CflaggedForAbuse%2CfolderFeatures%2Cspaces%2CsourceAppId%2Crecency%2CrecencyReason%2Cversion%2CactionItems%2CteamDriveId%2ChasAugmentedPermissions%2CcreatedDate%2CprimaryDomainName%2CorganizationDisplayName%2CpassivelySubscribed%2CtrashingUser(kind%2CpermissionId%2CemailAddressFromAccount%2Cid)%2CtrashedDate%2Cparents(id)%2Ccapabilities(canMoveItemIntoTeamDrive%2CcanUntrash%2CcanMoveItemWithinTeamDrive%2CcanMoveItemOutOfTeamDrive%2CcanDeleteChildren%2CcanTrashChildren%2CcanRequestApproval%2CcanReadCategoryMetadata%2CcanEditCategoryMetadata%2CcanAddMyDriveParent%2CcanRemoveMyDriveParent%2CcanShareChildFiles%2CcanShareChildFolders%2CcanRead%2CcanMoveItemWithinDrive%2CcanMoveChildrenWithinDrive%2CcanAddFolderFromAnotherDrive%2CcanChangeSecurityUpdateEnabled%2CcanBlockOwner%2CcanReportSpamOrAbuse%2CcanCopy%2CcanDownload%2CcanEdit%2CcanAddChildren%2CcanDelete%2CcanRemoveChildren%2CcanShare%2CcanTrash%2CcanRename%2CcanReadTeamDrive%2CcanMoveTeamDriveItem)%2CcontentRestrictions(readOnly)%2CapprovalMetadata(approvalVersion%2CapprovalSummaries%2ChasIncomingApproval)%2CshortcutDetails(targetId%2CtargetMimeType%2CtargetLookupStatus%2CtargetFile%2CcanRequestAccessToTarget)%2CspamMetadata(markedAsSpamDate%2CinSpamView)%2Clabels(starred%2Ctrashed%2Crestricted%2Cviewed))%2CincompleteSearch&appDataFilter=NO_APP_DATA&spaces=drive&pageToken=$pageToken&maxResults=100&supportsTeamDrives=true&includeItemsFromAllDrives=true&corpora=default&orderBy=folder%2Ctitle_natural%20asc&retryCount=0&key=$key HTTP/1.1"
                val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|").toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

                val postUrl = buildString {
                    append("https://clients6.google.com/batch/drive/v2internal")
                    append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
                    append("&key=$key")
                }

                val postHeaders = headers.newBuilder()
                    .add("Content-Type", "text/plain; charset=UTF-8")
                    .add("Origin", "https://drive.google.com")
                    .add("Cookie", getCookie("https://drive.google.com"))
                    .build()

                val response = client.newCall(
                    POST(postUrl, body = body, headers = postHeaders),
                ).execute()

                val parsed = response.parseAs<PostResponse> {
                    JSON_REGEX.find(it)!!.groupValues[1]
                }

                if (parsed.items == null) throw Exception("Failed to load items, please log in to google drive through webview")
                parsed.items.forEachIndexed { index, it ->
                    if (it.mimeType.startsWith("video")) {
                        val size = it.fileSize?.toLongOrNull()?.let { formatBytes(it) }

                        episodeList.add(
                            SEpisode.create().apply {
                                name = if (preferences.trimEpisodeName) it.title.trimInfo() else it.title
                                this.url = "https://drive.google.com/uc?id=${it.id}"
                                episode_number = ITEM_NUMBER_REGEX.find(it.title.trimInfo())?.groupValues?.get(1)?.toFloatOrNull() ?: index.toFloat()
                                date_upload = -1L
                                scanlator = "$size • /$path"
                            },
                        )
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

        document.select("div.tokha > div > a[href]").distinctBy { t ->
            t.text()
        }.filter { t -> t.attr("href").isNotEmpty() }.forEach {
            val url = it.attr("abs:href").toHttpUrl()
            val noRedirectClient = client.newBuilder().followRedirects(false).build()

            if (url.host.contains("drive.google.com")) {
                val id = Regex("[\\w-]{28,}").find(
                    url.toString().substringBeforeLast("?usp=shar"),
                )!!.groupValues[0]

                traverseFolder("https://drive.google.com/drive/folders/$id", it.text())
            }
            if (url.host.contains("tinyurl")) {
                val redirected = noRedirectClient.newCall(GET(url.toString())).execute()
                redirected.headers["location"]?.let { location ->
                    if (location.toHttpUrl().host.contains("drive.google.com")) {
                        val id = Regex("[\\w-]{28,}").find(
                            location.substringBeforeLast("?usp=shar"),
                        )!!.groupValues[0]

                        traverseFolder("https://drive.google.com/drive/folders/$id", it.text())
                    }
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = GoogleDriveExtractor(client, headers).videosFromUrl(episode.url.substringAfter("?id="))
        return videoList
    }

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

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
    data class PostResponse(
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

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
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
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private val ITEM_NUMBER_REGEX = """ - (?:S\d+E)?(\d+)""".toRegex()
        private val KEY_REGEX = """"(\w{39})"""".toRegex()
        private val VERSION_REGEX = """"([^"]+web-frontend[^"]+)"""".toRegex()
        private val JSON_REGEX = """(?:)\s*(\{(.+)\})\s*(?:)""".toRegex(RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="

        private const val MAX_RECURSION_DEPTH = 2

        private const val TRIM_EPISODE_NAME_KEY = "trim_episode"
        private const val TRIM_EPISODE_NAME_DEFAULT = true
    }

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_NAME_KEY
            title = "Trim info from episode name"
            setDefaultValue(TRIM_EPISODE_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
