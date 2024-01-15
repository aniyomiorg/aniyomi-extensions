package eu.kanade.tachiyomi.animeextension.all.googledrive

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ProtocolException
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.commonEmptyRequestBody
import org.jsoup.nodes.Document
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import java.security.MessageDigest

class GoogleDrive : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Google Drive"

    override val id = 4222017068256633289

    override var baseUrl = "https://drive.google.com"

    // Hack to manipulate what gets opened in webview
    private val baseUrlInternal by lazy {
        preferences.domainList.split(";").firstOrNull()
    }

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Overriding headersBuilder() seems to cause issues with webview
    private val getHeaders = headers.newBuilder().apply {
        add("Accept", "*/*")
        add("Connection", "keep-alive")
        add("Cookie", getCookie("https://drive.google.com"))
        add("Host", "drive.google.com")
    }.build()

    private var nextPageToken: String? = ""

    // ============================== Popular ===============================

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.just(parsePage(popularAnimeRequest(page), page))

    override fun popularAnimeRequest(page: Int): Request {
        require(!baseUrlInternal.isNullOrEmpty()) { "Enter drive path(s) in extension settings." }

        val match = DRIVE_FOLDER_REGEX.matchEntire(baseUrlInternal!!)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"

        return GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )
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
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        return if (urlFilter.state.isEmpty()) {
            val req = searchAnimeRequest(page, query, filters)

            if (query.isEmpty()) {
                Observable.just(parsePage(req, page))
            } else {
                val parentId = req.url.pathSegments.last()
                val cleanQuery = URLEncoder.encode(query, "UTF-8")
                val genMultiFormReq = searchReq(parentId, cleanQuery)

                Observable.just(parsePage(req, page, genMultiFormReq))
            }
        } else {
            Observable.just(addSinglePage(urlFilter.state))
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        require(!baseUrlInternal.isNullOrEmpty()) { "Enter drive path(s) in extension settings." }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = serverFilter.toUriPart()

        val match = DRIVE_FOLDER_REGEX.matchEntire(serverUrl)!!
        val folderId = match.groups["id"]!!.value
        val recurDepth = match.groups["depth"]?.value ?: ""
        baseUrl = "https://drive.google.com/drive/folders/$folderId"

        return GET(
            "https://drive.google.com/drive/folders/$folderId$recurDepth",
            headers = getHeaders,
        )
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        ServerFilter(getDomains()),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Add single folder"),
        URLFilter(),
    )

    private class ServerFilter(domains: Array<Pair<String, String>>) : UriPartFilter(
        "Select drive path",
        domains,
    )

    private fun getDomains(): Array<Pair<String, String>> {
        if (preferences.domainList.isBlank()) return emptyArray()
        return preferences.domainList.split(";").map {
            val name = DRIVE_FOLDER_REGEX.matchEntire(it)!!.groups["name"]?.let {
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
        return GET(parsed.url, headers = getHeaders)
    }

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val parsed = json.decodeFromString<LinkData>(anime.url)

        if (parsed.type == "single") return Observable.just(anime)

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(GET(parsed.url, headers = getHeaders)).execute().asJsoup()
        } catch (a: ProtocolException) {
            null
        } ?: return Observable.just(anime)

        // Get cover

        val coverResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "cover", IMAGE_MIMETYPE)),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        coverResponse.items?.firstOrNull()?.let {
            anime.thumbnail_url = "https://drive.google.com/uc?id=${it.id}"
        }

        // Get details

        val detailsResponse = client.newCall(
            createPost(driveDocument, folderId, nextPageToken, searchReqWithType(folderId, "details.json", "")),
        ).execute().parseAs<PostResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

        detailsResponse.items?.firstOrNull()?.let {
            val newPostHeaders = getHeaders.newBuilder().apply {
                add("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                set("Host", "drive.usercontent.google.com")
                add("Origin", "https://drive.google.com")
                add("Referer", "https://drive.google.com/")
                add("X-Drive-First-Party", "DriveWebUi")
                add("X-Json-Requested", "true")
            }.build()

            val newPostUrl = "https://drive.usercontent.google.com/uc?id=${it.id}&authuser=0&export=download"

            val newResponse = client.newCall(
                POST(newPostUrl, headers = newPostHeaders, body = commonEmptyRequestBody),
            ).execute().parseAs<DownloadResponse> { JSON_REGEX.find(it)!!.groupValues[1] }

            val downloadHeaders = headers.newBuilder().apply {
                add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                add("Connection", "keep-alive")
                add("Cookie", getCookie("https://drive.usercontent.google.com"))
                add("Host", "drive.usercontent.google.com")
            }.build()

            client.newCall(
                GET(newResponse.downloadUrl, headers = downloadHeaders),
            ).execute().parseAs<DetailsJson>().let { t ->
                t.title?.let { anime.title = it }
                t.author?.let { anime.author = it }
                t.artist?.let { anime.artist = it }
                t.description?.let { anime.description = it }
                t.genre?.let { anime.genre = it.joinToString(", ") }
                t.status?.let { anime.status = it.toIntOrNull() ?: SAnime.UNKNOWN }
            }
        }

        return Observable.just(anime)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)

        if (parsed.type == "single") {
            return Observable.just(
                listOf(
                    SEpisode.create().apply {
                        name = "Video"
                        scanlator = parsed.info!!.size
                        url = parsed.url
                        episode_number = 1F
                        date_upload = -1L
                    },
                ),
            )
        }

        val match = DRIVE_FOLDER_REGEX.matchEntire(parsed.url)!! // .groups["id"]!!.value
        val maxRecursionDepth = match.groups["depth"]?.let {
            it.value.substringAfter("#").substringBefore(",").toInt()
        } ?: 2
        val (start, stop) = match.groups["range"]?.let {
            it.value.substringAfter(",").split(",").map { it.toInt() }
        } ?: listOf(null, null)

        fun traverseFolder(folderUrl: String, path: String, recursionDepth: Int = 0) {
            if (recursionDepth == maxRecursionDepth) return

            val folderId = DRIVE_FOLDER_REGEX.matchEntire(folderUrl)!!.groups["id"]!!.value

            val driveDocument = try {
                client.newCall(GET(folderUrl, headers = getHeaders)).execute().asJsoup()
            } catch (a: ProtocolException) {
                throw Exception("Unable to get items, check webview")
            }

            if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) return

            var pageToken: String? = ""
            var counter = 1

            while (pageToken != null) {
                val response = client.newCall(
                    createPost(driveDocument, folderId, pageToken),
                ).execute()

                val parsed = response.parseAs<PostResponse> {
                    JSON_REGEX.find(it)!!.groupValues[1]
                }

                if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
                parsed.items.forEachIndexed { index, it ->
                    if (it.mimeType.startsWith("video")) {
                        val size = it.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: ""
                        val pathName = if (preferences.trimEpisodeInfo) path.trimInfo() else path

                        if (start != null && maxRecursionDepth == 1 && counter < start) {
                            counter++
                            return@forEachIndexed
                        }
                        if (stop != null && maxRecursionDepth == 1 && counter > stop) return

                        episodeList.add(
                            SEpisode.create().apply {
                                name =
                                    if (preferences.trimEpisodeName) it.title.trimInfo() else it.title
                                url = "https://drive.google.com/uc?id=${it.id}"
                                episode_number =
                                    ITEM_NUMBER_REGEX.find(it.title.trimInfo())?.groupValues?.get(1)
                                        ?.toFloatOrNull() ?: (index + 1).toFloat()
                                date_upload = -1L
                                scanlator = if (preferences.scanlatorOrder) {
                                    "/$pathName • $size"
                                } else {
                                    "$size • /$pathName"
                                }
                            },
                        )
                        counter++
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

        traverseFolder(parsed.url, "")

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        Observable.just(GoogleDriveExtractor(client, headers).videosFromUrl(episode.url))

    // ============================= Utilities ==============================

    private fun addSinglePage(folderUrl: String): AnimesPage {
        val match =
            DRIVE_FOLDER_REGEX.matchEntire(folderUrl) ?: throw Exception("Invalid drive url")
        val recurDepth = match.groups["depth"]?.value ?: ""

        val anime = SAnime.create().apply {
            title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]")
                ?: "Folder"
            url = LinkData(
                "https://drive.google.com/drive/folders/${match.groups["id"]!!.value}$recurDepth",
                "multi",
            ).toJsonString()
            thumbnail_url = ""
        }
        return AnimesPage(listOf(anime), false)
    }

    private fun createPost(
        document: Document,
        folderId: String,
        pageToken: String?,
        getMultiFormPath: (String, String, String) -> String = { folderIdStr, nextPageTokenStr, keyStr ->
            defaultGetRequest(folderIdStr, nextPageTokenStr, keyStr)
        },
    ): Request {
        val keyScript = document.select("script").first { script ->
            KEY_REGEX.find(script.data()) != null
        }.data()
        val key = KEY_REGEX.find(keyScript)?.groupValues?.get(1) ?: ""

        val versionScript = document.select("script").first { script ->
            KEY_REGEX.find(script.data()) != null
        }.data()
        val driveVersion = VERSION_REGEX.find(versionScript)?.groupValues?.get(1) ?: ""
        val sapisid =
            client.cookieJar.loadForRequest("https://drive.google.com".toHttpUrl()).firstOrNull {
                it.name == "SAPISID" || it.name == "__Secure-3PAPISID"
            }?.value ?: ""

        val requestUrl = getMultiFormPath(folderId, pageToken ?: "", key)
        val body = """--$BOUNDARY
                    |content-type: application/http
                    |content-transfer-encoding: binary
                    |
                    |GET $requestUrl
                    |X-Goog-Drive-Client-Version: $driveVersion
                    |authorization: ${generateSapisidhashHeader(sapisid)}
                    |x-goog-authuser: 0
                    |
                    |--$BOUNDARY--""".trimMargin("|")
            .toRequestBody("multipart/mixed; boundary=\"$BOUNDARY\"".toMediaType())

        val postUrl = buildString {
            append("https://clients6.google.com/batch/drive/v2internal")
            append("?${'$'}ct=multipart/mixed; boundary=\"$BOUNDARY\"")
            append("&key=$key")
        }

        val postHeaders = headers.newBuilder().apply {
            add("Content-Type", "text/plain; charset=UTF-8")
            add("Origin", "https://drive.google.com")
            add("Cookie", getCookie("https://drive.google.com"))
        }.build()

        return POST(postUrl, body = body, headers = postHeaders)
    }

    private fun parsePage(
        request: Request,
        page: Int,
        genMultiFormReq: ((String, String, String) -> String)? = null,
    ): AnimesPage {
        val animeList = mutableListOf<SAnime>()

        val recurDepth = request.url.encodedFragment?.let { "#$it" } ?: ""

        val folderId = DRIVE_FOLDER_REGEX.matchEntire(request.url.toString())!!.groups["id"]!!.value

        val driveDocument = try {
            client.newCall(request).execute().asJsoup()
        } catch (a: ProtocolException) {
            throw Exception("Unable to get items, check webview")
        }

        if (driveDocument.selectFirst("title:contains(Error 404 \\(Not found\\))") != null) {
            return AnimesPage(emptyList(), false)
        }

        if (page == 1) nextPageToken = ""
        val post = if (genMultiFormReq == null) {
            createPost(driveDocument, folderId, nextPageToken)
        } else {
            createPost(
                driveDocument,
                folderId,
                nextPageToken,
                genMultiFormReq,
            )
        }
        val response = client.newCall(post).execute()

        val parsed = response.parseAs<PostResponse> {
            JSON_REGEX.find(it)!!.groupValues[1]
        }

        if (parsed.items == null) throw Exception("Failed to load items, please log in through webview")
        parsed.items.forEachIndexed { index, it ->
            if (it.mimeType.startsWith("video")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/uc?id=${it.id}",
                            "single",
                            LinkDataInfo(
                                it.title,
                                it.fileSize?.toLongOrNull()?.let { formatBytes(it) } ?: "",
                            ),
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
            if (it.mimeType.endsWith(".folder")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeInfo) it.title.trimInfo() else it.title
                        url = LinkData(
                            "https://drive.google.com/drive/folders/${it.id}$recurDepth",
                            "multi",
                        ).toJsonString()
                        thumbnail_url = ""
                    },
                )
            }
        }

        nextPageToken = parsed.nextPageToken

        return AnimesPage(animeList, nextPageToken != null)
    }

    private inline fun <reified T> Response.parseAs(transform: (String) -> String = { it }): T {
        val responseBody = use { transform(it.body.string()) }
        return json.decodeFromString(responseBody)
    }

    // https://github.com/yt-dlp/yt-dlp/blob/8f0be90ecb3b8d862397177bb226f17b245ef933/yt_dlp/extractor/youtube.py#L573
    private fun generateSapisidhashHeader(
        SAPISID: String,
        origin: String = "https://drive.google.com",
    ): String {
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

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun isFolder(text: String) = DRIVE_FOLDER_REGEX matches text

    /*
     * Stolen from the MangaDex manga extension
     *
     * This will likely need to be removed or revisited when the app migrates the
     * extension preferences screen to Compose.
     */
    private fun setupEditTextFolderValidator(editText: EditText) {
        editText.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                    // Do nothing.
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Do nothing.
                }

                override fun afterTextChanged(editable: Editable?) {
                    requireNotNull(editable)

                    val text = editable.toString()

                    val isValid = text.isBlank() || text
                        .split(";")
                        .map(String::trim)
                        .all(::isFolder)

                    editText.error = if (!isValid) {
                        "${
                            text.split(";").first { !isFolder(it) }
                        } is not a valid google drive folder"
                    } else {
                        null
                    }
                    editText.rootView.findViewById<Button>(android.R.id.button1)
                        ?.isEnabled = editText.error == null
                }
            },
        )
    }

    companion object {
        private const val DOMAIN_PREF_KEY = "domain_list"
        private const val DOMAIN_PREF_DEFAULT = ""

        private const val TRIM_ANIME_KEY = "trim_anime_info"
        private const val TRIM_ANIME_DEFAULT = false

        private const val TRIM_EPISODE_NAME_KEY = "trim_episode_name"
        private const val TRIM_EPISODE_NAME_DEFAULT = true

        private const val TRIM_EPISODE_INFO_KEY = "trim_episode_info"
        private const val TRIM_EPISODE_INFO_DEFAULT = false

        private const val SCANLATOR_ORDER_KEY = "scanlator_order"
        private const val SCANLATOR_ORDER_DEFAULT = false

        private val DRIVE_FOLDER_REGEX = Regex(
            """(?<name>\[[^\[\];]+\])?https?:\/\/(?:docs|drive)\.google\.com\/drive(?:\/[^\/]+)*?\/folders\/(?<id>[\w-]{28,})(?:\?[^;#]+)?(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""",
        )
        private val KEY_REGEX = Regex(""""(\w{39})"""")
        private val VERSION_REGEX = Regex(""""([^"]+web-frontend[^"]+)"""")
        private val JSON_REGEX = Regex("""(?:)\s*(\{(.+)\})\s*(?:)""", RegexOption.DOT_MATCHES_ALL)
        private const val BOUNDARY = "=====vc17a3rwnndj====="

        private val ITEM_NUMBER_REGEX = Regex(""" - (?:S\d+E)?(\d+)\b""")
    }

    private val SharedPreferences.domainList
        get() = getString(DOMAIN_PREF_KEY, DOMAIN_PREF_DEFAULT)!!

    private val SharedPreferences.trimAnimeInfo
        get() = getBoolean(TRIM_ANIME_KEY, TRIM_ANIME_DEFAULT)

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    private val SharedPreferences.trimEpisodeInfo
        get() = getBoolean(TRIM_EPISODE_INFO_KEY, TRIM_EPISODE_INFO_DEFAULT)

    private val SharedPreferences.scanlatorOrder
        get() = getBoolean(SCANLATOR_ORDER_KEY, SCANLATOR_ORDER_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF_KEY
            title = "Enter drive paths to be shown in extension"
            summary = """Enter links of drive folders to be shown in extension
                |Enter as a semicolon `;` separated list
            """.trimMargin()
            this.setDefaultValue(DOMAIN_PREF_DEFAULT)
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a semicolon.
                |- (optional) Add [] before url to customize name. For example: [drive 5]https://drive.google.com/drive/folders/whatever
                |- (optional) add #<integer> to limit the depth of recursion when loading episodes, defaults is 2. For example: https://drive.google.com/drive/folders/whatever#5
                |- (optional) add #depth,start,stop (all integers) to specify range when loading episodes. Only works if depth is 1. For example: https://drive.google.com/drive/folders/whatever#1,2,6
            """.trimMargin()

            setOnBindEditTextListener(::setupEditTextFolderValidator)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res =
                        preferences.edit().putString(DOMAIN_PREF_KEY, newValue as String).commit()
                    Toast.makeText(
                        screen.context,
                        "Restart Aniyomi to apply changes",
                        Toast.LENGTH_LONG,
                    ).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_ANIME_KEY
            title = "Trim info from anime titles"
            setDefaultValue(TRIM_ANIME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_NAME_KEY
            title = "Trim info from episode name"
            setDefaultValue(TRIM_EPISODE_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = TRIM_EPISODE_INFO_KEY
            title = "Trim info from episode info"
            setDefaultValue(TRIM_EPISODE_INFO_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SCANLATOR_ORDER_KEY
            title = "Switch order of file path and size"
            setDefaultValue(SCANLATOR_ORDER_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
