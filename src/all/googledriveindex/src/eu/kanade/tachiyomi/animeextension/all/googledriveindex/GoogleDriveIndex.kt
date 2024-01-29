package eu.kanade.tachiyomi.animeextension.all.googledriveindex

import android.app.Application
import android.content.SharedPreferences
import android.text.Editable
import android.text.TextWatcher
import android.util.Base64
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class GoogleDriveIndex : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "GoogleDriveIndex"

    override val baseUrl by lazy {
        preferences.domainList.split(",").first().removeName()
    }

    override val lang = "all"

    private var pageToken: String? = ""

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client = network.client.newBuilder()
        .addInterceptor { chain ->
            var request = chain.request()

            if (request.url.username.isNotBlank() && request.url.password.isNotBlank()) {

                val credential = Credentials.basic(request.url.username, request.url.password)
                request = request.newBuilder()
                    .header("Authorization", credential)
                    .build()

                val newUrl = request.url.newBuilder()
                    .username("")
                    .password("")
                    .build()

                request = request.newBuilder()
                    .url(newUrl)
                    .build()
            }

            chain.proceed(request)
        }
        .build()

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        require(baseUrl.isNotEmpty()) { "Enter drive path(s) in extension settings." }
        require(baseUrl.toHttpUrl().host != "drive.google.com") {
            "This extension is only for Google Drive Index sites, not drive.google.com folders."
        }

        if (page == 1) pageToken = ""
        val popHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Origin", "https://${baseUrl.toHttpUrl().host}")
            .add("Referer", baseUrl.asReferer())
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val popBody = "password=&page_token=$pageToken&page_index=${page - 1}".toRequestBody("application/x-www-form-urlencoded".toMediaType())

        return POST(baseUrl, body = popBody, headers = popHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parsePage(response, baseUrl)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        return if (urlFilter.state.isEmpty()) {
            val req = searchAnimeRequest(page, query, filters)
            client.newCall(req).awaitSuccess()
                .let { response ->
                    searchAnimeParse(response, req.url.toString())
                }
        } else {
            addSinglePage(urlFilter.state)
        }
    }

    private fun addSinglePage(inputUrl: String): AnimesPage {
        val match = URL_REGEX.matchEntire(inputUrl) ?: throw Exception("Invalid url")
        val anime = SAnime.create().apply {
            title = match.groups["name"]?.value?.substringAfter("[")?.substringBeforeLast("]") ?: "Folder"
            url = LinkData(
                type = "multi",
                url = match.groups["url"]!!.value,
                fragment = inputUrl.removeName().toHttpUrl().encodedFragment,
            ).toJsonString()
            thumbnail_url = ""
        }
        return AnimesPage(listOf(anime), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        require(baseUrl.isNotEmpty()) { "Enter drive path(s) in extension settings." }
        require(baseUrl.toHttpUrl().host != "drive.google.com") {
            "This extension is only for Google Drive Index sites, not drive.google.com folders."
        }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = serverFilter.toUriPart()

        if (page == 1) pageToken = ""
        val searchHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Host", serverUrl.toHttpUrl().host)
            .add("Origin", "https://${serverUrl.toHttpUrl().host}")
            .add("X-Requested-With", "XMLHttpRequest")

        return when {
            query.isBlank() -> {
                val popBody = "password=&page_token=$pageToken&page_index=${page - 1}".toRequestBody("application/x-www-form-urlencoded".toMediaType())

                POST(
                    serverUrl,
                    body = popBody,
                    headers = searchHeaders.add("Referer", serverUrl.asReferer()).build(),
                )
            }
            else -> {
                val cleanQuery = query.replace(" ", "+")
                val searchUrl = "https://${serverUrl.toHttpUrl().hostAndCred()}/${serverUrl.toHttpUrl().pathSegments[0]}search"
                val popBody = "q=$cleanQuery&page_token=$pageToken&page_index=${page - 1}".toRequestBody("application/x-www-form-urlencoded".toMediaType())

                POST(
                    searchUrl,
                    body = popBody,
                    headers = searchHeaders.add("Referer", "$searchUrl?q=$cleanQuery").build(),
                )
            }
        }
    }

    private fun searchAnimeParse(response: Response, url: String): AnimesPage = parsePage(response, url)

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search will only search inside selected server"),
        ServerFilter(getDomains()),
        AnimeFilter.Header("Add single folder"),
        URLFilter(),
    )

    private class ServerFilter(domains: Array<Pair<String, String>>) : UriPartFilter(
        "Select server",
        domains,
    )

    private fun getDomains(): Array<Pair<String, String>> {
        if (preferences.domainList.isBlank()) return emptyArray()
        return preferences.domainList.split(",").map {
            val match = URL_REGEX.matchEntire(it)!!
            val name = match.groups["name"]?.let {
                it.value.substringAfter("[").substringBeforeLast("]")
            }
            Pair(name ?: it.toHttpUrl().encodedPath, it.removeName())
        }.toTypedArray()
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : AnimeFilter.Text("Url")

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsed = json.decodeFromString<LinkData>(anime.url)
        val newParsed = if (parsed.type != "search") {
            parsed
        } else {
            val idParsed = json.decodeFromString<IdUrl>(parsed.url)
            val id2pathHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", idParsed.url.toHttpUrl().host)
                .add("Origin", "https://${idParsed.url.toHttpUrl().host}")
                .add("Referer", URLEncoder.encode(idParsed.referer, "UTF-8"))
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val postBody = "id=${idParsed.id}".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val slug = client.newCall(
                POST(idParsed.url + "id2path", body = postBody, headers = id2pathHeaders),
            ).execute().body.string()

            LinkData(
                idParsed.type,
                idParsed.url + slug,
                parsed.info,
            )
        }

        if (newParsed.type == "single") {
            return anime
        }

        var newToken: String? = ""
        var newPageIndex = 0
        while (newToken != null) {
            val popHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", newParsed.url.toHttpUrl().host)
                .add("Origin", "https://${newParsed.url.toHttpUrl().host}")
                .add("Referer", URLEncoder.encode(newParsed.url, "UTF-8"))
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val popBody = "password=&page_token=$newToken&page_index=$newPageIndex".toRequestBody("application/x-www-form-urlencoded".toMediaType())

            val parsed = client.newCall(
                POST(newParsed.url, body = popBody, headers = popHeaders),
            ).execute().parseAs<ResponseData> { it.decrypt() }

            parsed.data.files.forEach { item ->
                if (item.mimeType.startsWith("image/") && item.name.startsWith("cover", true)) {
                    anime.thumbnail_url = joinUrl(newParsed.url, item.name)
                }

                if (item.name.equals("details.json", true)) {
                    val details = client.newCall(
                        GET(joinUrl(newParsed.url, item.name)),
                    ).execute().body.string()
                    val detailsParsed = json.decodeFromString<Details>(details)
                    detailsParsed.title?.let { anime.title = it }
                    detailsParsed.author?.let { anime.author = it }
                    detailsParsed.artist?.let { anime.artist = it }
                    detailsParsed.description?.let { anime.description = it }
                    detailsParsed.genre?.let { anime.genre = it.joinToString(", ") }
                    detailsParsed.status?.let { anime.status = it.toIntOrNull() ?: SAnime.UNKNOWN }
                }
            }

            newToken = parsed.nextPageToken
            newPageIndex += 1
        }

        return anime
    }

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)
        var counter = 1
        val maxRecursionDepth = parsed.fragment?.substringBefore(",")?.toInt() ?: 2
        val (start, stop) = if (parsed.fragment?.contains(",") == true) {
            parsed.fragment.substringAfter(",").split(",").map { it.toInt() }
        } else {
            listOf(null, null)
        }

        val newParsed = if (parsed.type != "search") {
            parsed
        } else {
            val idParsed = json.decodeFromString<IdUrl>(parsed.url)
            val id2pathHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", idParsed.url.toHttpUrl().host)
                .add("Origin", "https://${idParsed.url.toHttpUrl().host}")
                .add("Referer", URLEncoder.encode(idParsed.referer, "UTF-8"))
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val postBody = "id=${idParsed.id}".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val slug = client.newCall(
                POST(idParsed.url + "id2path", body = postBody, headers = id2pathHeaders),
            ).execute().body.string()

            LinkData(
                idParsed.type,
                idParsed.url + slug,
                parsed.info,
            )
        }

        if (newParsed.type == "single") {
            val titleName = newParsed.url.toHttpUrl().pathSegments.last()
            episodeList.add(
                SEpisode.create().apply {
                    name = if (preferences.trimEpisodeName) titleName.trimInfo() else titleName
                    url = newParsed.url
                    episode_number = 1F
                    date_upload = -1L
                    scanlator = newParsed.info
                },
            )
        }

        if (newParsed.type == "multi") {
            val basePathCounter = newParsed.url.toHttpUrl().pathSize

            fun traverseDirectory(url: String, recursionDepth: Int = 0) {
                if (recursionDepth == maxRecursionDepth) return
                var newToken: String? = ""
                var newPageIndex = 0

                while (newToken != null) {
                    val popHeaders = headers.newBuilder()
                        .add("Accept", "*/*")
                        .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .add("Host", url.toHttpUrl().host)
                        .add("Origin", "https://${url.toHttpUrl().host}")
                        .add("Referer", URLEncoder.encode(url, "UTF-8"))
                        .add("X-Requested-With", "XMLHttpRequest")
                        .build()

                    val popBody = "password=&page_token=$newToken&page_index=$newPageIndex".toRequestBody("application/x-www-form-urlencoded".toMediaType())

                    val parsed = client.newCall(
                        POST(url, body = popBody, headers = popHeaders),
                    ).execute().parseAs<ResponseData> { it.decrypt() }

                    parsed.data.files.forEach { item ->
                        if (item.mimeType.endsWith("folder")) {
                            val newUrl = joinUrl(url, item.name).addSuffix("/")
                            traverseDirectory(newUrl, recursionDepth + 1)
                        }
                        if (item.mimeType.startsWith("video/")) {
                            if (start != null && maxRecursionDepth == 1 && counter < start) {
                                counter++
                                return@forEach
                            }
                            if (stop != null && maxRecursionDepth == 1 && counter > stop) return

                            val epUrl = joinUrl(url, item.name)
                            val paths = epUrl.toHttpUrl().pathSegments

                            // Get season stuff
                            val season = if (paths.size == basePathCounter) {
                                ""
                            } else {
                                paths[basePathCounter - 1]
                            }
                            val seasonInfoRegex = """(\([\s\w-]+\))(?: ?\[[\s\w-]+\])?${'$'}""".toRegex()
                            val seasonInfo = if (seasonInfoRegex.containsMatchIn(season)) {
                                "${seasonInfoRegex.find(season)!!.groups[1]!!.value} • "
                            } else {
                                ""
                            }

                            // Get other info
                            val extraInfo = if (paths.size > basePathCounter) {
                                "/" + paths.subList(basePathCounter - 1, paths.size - 1).joinToString("/") { it.trimInfo() }
                            } else {
                                "/"
                            }
                            val size = item.size?.toLongOrNull()?.let { formatFileSize(it) }

                            episodeList.add(
                                SEpisode.create().apply {
                                    name = if (preferences.trimEpisodeName) item.name.trimInfo() else item.name
                                    this.url = epUrl
                                    scanlator = "${if (size == null) "" else "$size"} • $seasonInfo$extraInfo"
                                    date_upload = -1L
                                    episode_number = counter.toFloat()
                                },
                            )
                            counter++
                        }
                    }

                    newToken = parsed.nextPageToken
                    newPageIndex += 1
                }
            }

            traverseDirectory(newParsed.url)
        }

        return episodeList.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = episode.url

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

    // ============================= Utilities ==============================
    private fun HttpUrl.hostAndCred(): String {
        return if (this.password.isNotBlank() && this.username.isNotBlank()) {
            "${this.username}:${this.password}@${this.host}"
        } else {
            this.host
        }
    }

    private fun joinUrl(path1: String, path2: String): String {
        return path1.removeSuffix("/") + "/" + path2.removePrefix("/")
    }

    private fun String.decrypt(): String {
        return Base64.decode(this.reversed().substring(24, this.length - 20), Base64.DEFAULT).toString(Charsets.UTF_8)
    }

    private fun String.addSuffix(suffix: String): String {
        return if (this.endsWith(suffix)) {
            this
        } else {
            this.plus(suffix)
        }
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[[\w-]+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.2f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.2f KB".format(bytes / 1_000.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
    }

    private fun String.asReferer(): String {
        return URLEncoder.encode(
            this.toHttpUrl().let {
                "https://${it.host}${it.encodedPath}"
            },
            "UTF-8",
        )
    }

    private fun String.removeName(): String = Regex("""^(\[[^\[\];]+\])""").replace(this, "")

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun IdUrl.toJsonString(): String {
        return json.encodeToString(this)
    }

    private fun parsePage(response: Response, url: String): AnimesPage {
        val parsed = json.decodeFromString<ResponseData>(response.body.string().decrypt())
        val animeList = mutableListOf<SAnime>()
        val isSearch = url.endsWith(":search")

        parsed.data.files.forEach { item ->
            if (item.mimeType.endsWith("folder")) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeName) item.name.trimInfo() else item.name
                        thumbnail_url = ""
                        this.url = if (isSearch) {
                            LinkData(
                                "search",
                                IdUrl(
                                    item.id,
                                    url.substringBeforeLast("search"),
                                    response.request.header("Referer")!!,
                                    "multi",
                                ).toJsonString(),
                            ).toJsonString()
                        } else {
                            LinkData(
                                "multi",
                                joinUrl(URL_REGEX.matchEntire(url)!!.groups["url"]!!.value, item.name).addSuffix("/"),
                                fragment = url.toHttpUrl().encodedFragment,
                            ).toJsonString()
                        }
                    },
                )
            }
            if (
                item.mimeType.startsWith("video/") &&
                !(preferences.ignoreFolder && isSearch)
            ) {
                animeList.add(
                    SAnime.create().apply {
                        title = if (preferences.trimAnimeName) item.name.trimInfo() else item.name
                        thumbnail_url = ""
                        this.url = if (isSearch) {
                            LinkData(
                                "search",
                                IdUrl(
                                    item.id,
                                    url.substringBeforeLast("search"),
                                    response.request.header("Referer")!!,
                                    "single",
                                ).toJsonString(),
                                item.size?.toLongOrNull()?.let { formatFileSize(it) },
                            ).toJsonString()
                        } else {
                            LinkData(
                                "single",
                                joinUrl(URL_REGEX.matchEntire(url)!!.groups["url"]!!.value, item.name),
                                item.size?.toLongOrNull()?.let { formatFileSize(it) },
                                fragment = url.toHttpUrl().encodedFragment,
                            ).toJsonString()
                        }
                    },
                )
            }
        }

        pageToken = parsed.nextPageToken

        return AnimesPage(animeList, parsed.nextPageToken != null)
    }

    private fun isUrl(text: String) = URL_REGEX matches text

    /*
     * Stolen from the MangaDex manga extension
     *
     * This will likely need to be removed or revisited when the app migrates the
     * extension preferences screen to Compose.
     */
    private fun setupEditTextUrlValidator(editText: EditText) {
        editText.addTextChangedListener(
            object : TextWatcher {

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                    // Do nothing.
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    // Do nothing.
                }

                override fun afterTextChanged(editable: Editable?) {
                    requireNotNull(editable)

                    val text = editable.toString()

                    val isValid = text.isBlank() || text
                        .split(",")
                        .map(String::trim)
                        .all(::isUrl)

                    editText.error = if (!isValid) "${text.split(",").first { !isUrl(it) }} is not a valid url" else null
                    editText.rootView.findViewById<Button>(android.R.id.button1)
                        ?.isEnabled = editText.error == null
                }
            },
        )
    }

    companion object {
        private const val DOMAIN_PREF_KEY = "domain_list"
        private const val DOMAIN_PREF_DEFAULT = ""

        private const val SEARCH_FOLDER_IGNORE_KEY = "ignore_non_folder"
        private const val SEARCH_FOLDER_IGNORE_DEFAULT = true

        private const val TRIM_EPISODE_NAME_KEY = "trim_episode_name"
        private const val TRIM_EPISODE_NAME_DEFAULT = true

        private const val TRIM_ANIME_NAME_KEY = "trim_anime_name"
        private const val TRIM_ANIME_NAME_DEFAULT = true

        private val URL_REGEX = Regex("""(?<name>\[[^\[\];]+\])?(?<url>https(?:[^,#]+))(?<depth>#\d+(?<range>,\d+,\d+)?)?${'$'}""")
    }

    private val SharedPreferences.domainList
        get() = getString(DOMAIN_PREF_KEY, DOMAIN_PREF_DEFAULT)!!

    private val SharedPreferences.ignoreFolder
        get() = getBoolean(SEARCH_FOLDER_IGNORE_KEY, SEARCH_FOLDER_IGNORE_DEFAULT)

    private val SharedPreferences.trimEpisodeName
        get() = getBoolean(TRIM_EPISODE_NAME_KEY, TRIM_EPISODE_NAME_DEFAULT)

    private val SharedPreferences.trimAnimeName
        get() = getBoolean(TRIM_ANIME_NAME_KEY, TRIM_ANIME_NAME_DEFAULT)

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = DOMAIN_PREF_KEY
            title = "Enter drive paths to be shown in extension"
            summary = """Enter drive paths to be shown in extension
                |Enter as comma separated list
            """.trimMargin()
            this.setDefaultValue(DOMAIN_PREF_DEFAULT)
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a comma. For password protected sites,
                |format as: "https://username:password@example.worker.dev/0:/"
                |- (optional) Add [] before url to customize name. For example: [drive 5]https://site.workers.dev/0:
                |- (optional) add #<integer> to limit the depth of recursion when loading episodes, defaults is 2. For example: https://site.workers.dev/0:#5
                |- (optional) add #depth,start,stop (all integers) to specify range when loading episodes. Only works if depth is 1. For example: https://site.workers.dev/0:#1,2,6
            """.trimMargin()

            setOnBindEditTextListener(::setupEditTextUrlValidator)

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(DOMAIN_PREF_KEY, newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = SEARCH_FOLDER_IGNORE_KEY
            title = "Only include folders on search"
            setDefaultValue(SEARCH_FOLDER_IGNORE_DEFAULT)
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
            key = TRIM_ANIME_NAME_KEY
            title = "Trim info from anime name"
            setDefaultValue(TRIM_ANIME_NAME_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
