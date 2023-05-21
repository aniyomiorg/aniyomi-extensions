package eu.kanade.tachiyomi.animeextension.all.googledrive

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.all.googledrive.extractors.GoogleDriveExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ProtocolException
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest
import java.text.CharacterIterator
import java.text.StringCharacterIterator

class GoogleDrive : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Google Drive"

    override val id = 4222017068256633289

    override var baseUrl = ""

    // Hack to manipulate what gets opened in webview
    private val baseUrlInternal by lazy {
        preferences.getString("domain_list", "")!!.split(";").firstOrNull()
    }

    override val lang = "all"

    private var nextPageToken: String? = ""

    override val supportsLatest = false

    private val driveFolderRegex = Regex("""(?<name>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#[^;]+)?""")
    private val keyRegex = """"(\w{39})"""".toRegex()
    private val versionRegex = """"([^"]+web-frontend[^"]+)"""".toRegex()
    private val jsonRegex = """(?:)\s*(\{(.+)\})\s*(?:)""".toRegex(RegexOption.DOT_MATCHES_ALL)
    private val boundary = "=====vc17a3rwnndj====="

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient

    // ============================== Popular ===============================

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return Observable.just(parsePage(popularAnimeRequest(page), page))
    }

    override fun popularAnimeRequest(page: Int): Request {
        if (baseUrlInternal.isNullOrEmpty()) {
            throw Exception("Enter drive path(s) in extension settings.")
        }

        val match = driveFolderRegex.matchEntire(baseUrlInternal!!)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"
        val driveHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Connection", "keep-alive")
            .add("Cookie", getCookie("https://drive.google.com"))
            .add("Host", "drive.google.com")
            .build()

        return GET("https://drive.google.com/drive/folders/$folderId$recurDepth", headers = driveHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> {
        val req = searchAnimeRequest(page, query, filters)

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        if (query.isNotEmpty()) throw Exception("Search is disabled. Use search in webview and add it as a single folder in filters.")

        return if (urlFilter.state.isEmpty()) {
            Observable.just(parsePage(req, page))
        } else {
            Observable.just(addSinglePage(urlFilter.state))
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (baseUrlInternal.isNullOrEmpty()) {
            throw Exception("Enter drive path(s) in extension settings.")
        }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = serverFilter.toUriPart()

        val match = driveFolderRegex.matchEntire(serverUrl)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"
        val driveHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Connection", "keep-alive")
            .add("Cookie", getCookie("https://drive.google.com"))
            .add("Host", "drive.google.com")
            .build()

        return GET("https://drive.google.com/drive/folders/$folderId$recurDepth", headers = driveHeaders)
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        ServerFilter(getDomains()),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Add single folder"),
        URLFilter(),
    )

    private class ServerFilter(domains: Array<Pair<String, String>>) : UriPartFilter(
        "Select server",
        domains,
    )

    private fun getDomains(): Array<Pair<String, String>> {
        if (preferences.getString("domain_list", "")!!.isBlank()) return emptyArray()
        return preferences.getString("domain_list", "")!!.split(";").map {
            val name = driveFolderRegex.matchEntire(it)!!.groups["name"]?.let {
                it.value.substringAfter("[").substringBeforeLast("]")
            }
            Pair(name ?: it.toHttpUrl().encodedPath, it)
        }.toTypedArray()
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : AnimeFilter.Text("Url")

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val parsed = json.decodeFromString<LinkData>(anime.url)
        return GET(parsed.url)
    }

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val parsed = json.decodeFromString<LinkData>(anime.url)
        val anime = anime

        if (parsed.type == "single") return Observable.just(anime)

        val folderId = driveFolderRegex.matchEntire(parsed.url)!!.groups["id"]!!.value
        val driveHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Connection", "keep-alive")
            .add("Cookie", getCookie("https://drive.google.com"))
            .add("Host", "drive.google.com")
            .build()

        val driveDocument = try {
            client.newCall(GET(parsed.url, headers = driveHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            null
        } ?: return Observable.just(anime)

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return Observable.just(anime)

        val keyScript = driveDocument.select("script").firstOrNull { script ->
            keyRegex.find(script.data()) != null
        }?.data() ?: return Observable.just(anime)
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
            val parsed = json.decodeFromString<PostResponse>(
                jsonRegex.find(response.body.string())!!.groupValues[1],
            )
            if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
            parsed.items.forEach {
                if (it.mimeType.startsWith("image/") && it.title.startsWith("cover.")) {
                    anime.thumbnail_url = "https://drive.google.com/uc?id=${it.id}"
                }
            }

            pageToken = parsed.nextPageToken
        }

        return Observable.just(anime)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)

        val maxRecursionDepth = parsed.url.toHttpUrl().encodedFragment?.toInt() ?: 2

        fun traverseFolder(url: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = driveFolderRegex.matchEntire(url)!!.groups["id"]!!.value
            val driveHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Connection", "keep-alive")
                .add("Cookie", getCookie("https://drive.google.com"))
                .add("Host", "drive.google.com")
                .build()

            val driveDocument = try {
                client.newCall(GET(url, headers = driveHeaders)).execute().asJsoup()
            } catch (a: ProtocolException) {
                throw Exception("Unable to get items, check webview")
            }

            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            val keyScript = driveDocument.select("script").firstOrNull { script ->
                keyRegex.find(script.data()) != null
            }?.data() ?: throw Exception("Unknown error occured, check webview")
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
                val parsed = json.decodeFromString<PostResponse>(
                    jsonRegex.find(response.body.string())!!.groupValues[1],
                )
                if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
                parsed.items.forEachIndexed { index, it ->
                    if (it.mimeType.startsWith("video")) {
                        val episode = SEpisode.create()
                        val size = formatBytes(it.fileSize?.toLongOrNull())
                        val pathName = if (preferences.getBoolean("trim_episode_info", false)) {
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
                        episode.name = if (preferences.getBoolean("trim_episode_name", false)) {
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
                            if (path.isEmpty()) it.title else "$path/${it.title}",
                            recursionDepth + 1,
                        )
                    }
                }

                pageToken = parsed.nextPageToken
            }
        }

        if (parsed.type == "single") {
            val episode = SEpisode.create()
            episode.name = parsed.info!!.title
            episode.scanlator = parsed.info!!.size
            episode.url = parsed.url
            episode.episode_number = 1F
            episode.date_upload = -1L
        } else {
            traverseFolder(parsed.url, "")
        }

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videoList = GoogleDriveExtractor(client, headers).videosFromUrl(episode.url)
        return Observable.just(videoList)
    }

    // ============================= Utilities ==============================

    private fun addSinglePage(folderUrl: String): AnimesPage {
        val match = driveFolderRegex.matchEntire(folderUrl) ?: throw Exception("Invalid drive url")
        val recurDepth = match.groups["depth"]?.value ?: ""

        val anime = SAnime.create()
        anime.title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]") ?: "Folder"
        anime.setUrlWithoutDomain(
            LinkData(
                "https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth",
                "multi",
            ).toJsonString(),
        )
        anime.thumbnail_url = ""
        return AnimesPage(listOf(anime), false)
    }

    private fun parsePage(request: Request, page: Int): AnimesPage {
        val animeList = mutableListOf<SAnime>()

        val recurDepth = request.url.encodedFragment?.let { "#$it" } ?: ""

        val folderId = driveFolderRegex.matchEntire(request.url.toString())!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(request).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return AnimesPage(emptyList(), false)
        }

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

        if (page == 1) nextPageToken = ""
        val requestUrl = "/drive/v2beta/files?openDrive=true&reason=102&syncType=0&errorRecovery=false&q=trashed%20%3D%20false%20and%20'$folderId'%20in%20parents&fields=kind%2CnextPageToken%2Citems(kind%2CmodifiedDate%2CmodifiedByMeDate%2ClastViewedByMeDate%2CfileSize%2Cowners(kind%2CpermissionId%2Cid)%2ClastModifyingUser(kind%2CpermissionId%2Cid)%2ChasThumbnail%2CthumbnailVersion%2Ctitle%2Cid%2CresourceKey%2Cshared%2CsharedWithMeDate%2CuserPermission(role)%2CexplicitlyTrashed%2CmimeType%2CquotaBytesUsed%2Ccopyable%2CfileExtension%2CsharingUser(kind%2CpermissionId%2Cid)%2Cspaces%2Cversion%2CteamDriveId%2ChasAugmentedPermissions%2CcreatedDate%2CtrashingUser(kind%2CpermissionId%2Cid)%2CtrashedDate%2Cparents(id)%2CshortcutDetails(targetId%2CtargetMimeType%2CtargetLookupStatus)%2Ccapabilities(canCopy%2CcanDownload%2CcanEdit%2CcanAddChildren%2CcanDelete%2CcanRemoveChildren%2CcanShare%2CcanTrash%2CcanRename%2CcanReadTeamDrive%2CcanMoveTeamDriveItem)%2Clabels(starred%2Ctrashed%2Crestricted%2Cviewed))%2CincompleteSearch&appDataFilter=NO_APP_DATA&spaces=drive&pageToken=$nextPageToken&maxResults=50&supportsTeamDrives=true&includeItemsFromAllDrives=true&corpora=default&orderBy=folder%2Ctitle_natural%20asc&retryCount=0&key=$key HTTP/1.1"
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
        val parsed = json.decodeFromString<PostResponse>(
            jsonRegex.find(response.body.string())!!.groupValues[1],
        )
        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
        parsed.items.forEachIndexed { index, it ->
            if (it.mimeType.startsWith("video")) {
                val anime = SAnime.create()
                anime.title = if (preferences.getBoolean("trim_anime_info", false)) {
                    it.title.trimInfo()
                } else {
                    it.title
                }
                anime.setUrlWithoutDomain(
                    LinkData(
                        "https://drive.google.com/drive/folders/${it.id}",
                        "single",
                        LinkDataInfo(it.title, formatBytes(it.fileSize?.toLongOrNull()) ?: ""),
                    ).toJsonString(),
                )
                anime.thumbnail_url = ""
                animeList.add(anime)
            }
            if (it.mimeType.endsWith(".folder")) {
                val anime = SAnime.create()
                anime.title = if (preferences.getBoolean("trim_anime_info", false)) {
                    it.title.trimInfo()
                } else {
                    it.title
                }
                anime.setUrlWithoutDomain(
                    LinkData(
                        "https://drive.google.com/drive/folders/${it.id}$recurDepth",
                        "multi",
                    ).toJsonString(),
                )
                anime.thumbnail_url = ""
                animeList.add(anime)
            }
        }

        nextPageToken = parsed.nextPageToken

        return AnimesPage(animeList, nextPageToken != null)
    }

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

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
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

    private fun getCookie(url: String): String {
        val cookieList = client.cookieJar.loadForRequest(url.toHttpUrl())
        return if (cookieList.isNotEmpty()) {
            cookieList.joinToString("; ") { "${it.name}=${it.value}" }
        } else {
            ""
        }
    }

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainListPref = EditTextPreference(screen.context).apply {
            key = "domain_list"
            title = "Enter drive paths to be shown in extension"
            summary = """Enter links of drive folders to be shown in extension
                |Enter as a semicolon `;` separated list
            """.trimMargin()
            this.setDefaultValue("")
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a semicolon.
                |- (optional) Add [] before url to customize name. For example: [drive 5]https://drive.google.com/drive/folders/whatever
                |- (optional) add #<integer> to limit the depth of recursion when loading epsiodes, defaults is 2. For example: https://drive.google.com/drive/folders/whatever#5
            """.trimMargin()

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    // Validate the urls
                    val domain = newValue as String
                    val domainList = domain.split(";")
                    var isValid = true
                    var message = ""

                    domainList.forEach { d ->
                        if (message.isNotBlank()) return@forEach
                        val matchResult = driveFolderRegex.matchEntire(d)
                        if (matchResult == null) {
                            message = "Invalid url for $d"
                            isValid = false
                        } else {
                            matchResult.groups["depth"]?.let {
                                if (it.value.substringAfter("#").toIntOrNull() == null) {
                                    isValid = false
                                    message = "Level depth must be an integer, got `${it.value.substringAfter("#")}`"
                                }
                            }
                        }
                    }

                    if (isValid) {
                        val res = preferences.edit().putString("domain_list", newValue).commit()
                        Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                        res
                    } else {
                        Toast.makeText(screen.context, message, Toast.LENGTH_SHORT).show()
                        false
                    }
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val trimAnimeInfo = SwitchPreferenceCompat(screen.context).apply {
            key = "trim_anime_info"
            title = "Trim info from anime titles"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        val trimEpisodeName = SwitchPreferenceCompat(screen.context).apply {
            key = "trim_episode_name"
            title = "Trim info from episode name"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        val trimEpisodeInfo = SwitchPreferenceCompat(screen.context).apply {
            key = "trim_episode_info"
            title = "Trim info from episode info"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }

        screen.addPreference(trimAnimeInfo)
        screen.addPreference(trimEpisodeName)
        screen.addPreference(trimEpisodeInfo)
        screen.addPreference(domainListPref)
    }
}
