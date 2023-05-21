package eu.kanade.tachiyomi.animeextension.all.googledriveindex

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
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
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class GoogleDriveIndex : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "GoogleDriveIndex"

    override val baseUrl by lazy {
        preferences.getString("domain_list", "")!!.split(",").first()
    }

    override val lang = "all"

    private var pageToken: String? = ""

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
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
        if (baseUrl.isEmpty()) {
            throw Exception("Enter drive path(s) in extension settings.")
        }

        if (baseUrl.toHttpUrl().host == "drive.google.com") {
            throw Exception("This extension is only for Google Drive Index sites, not drive.google.com folders.")
        }

        if (page == 1) pageToken = ""
        val popHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Origin", "https://${baseUrl.toHttpUrl().host}")
            .add("Referer", URLEncoder.encode(baseUrl, "UTF-8"))
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val popBody = "password=&page_token=$pageToken&page_index=${page - 1}".toRequestBody("application/x-www-form-urlencoded".toMediaType())

        return POST(baseUrl, body = popBody, headers = popHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parsePage(response, baseUrl)
    }

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
        return Observable.defer {
            try {
                client.newCall(req).asObservableSuccess()
            } catch (e: NoClassDefFoundError) {
                // RxJava doesn't handle Errors, which tends to happen during global searches
                // if an old extension using non-existent classes is still around
                throw RuntimeException(e)
            }
        }
            .map { response ->
                searchAnimeParse(response, req.url.toString())
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (baseUrl.isEmpty()) {
            throw Exception("Enter drive path(s) in extension settings.")
        }

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val serverFilter = filterList.find { it is ServerFilter } as ServerFilter
        val serverUrl = serverFilter.toUriPart()

        if (serverUrl.toHttpUrl().host == "drive.google.com") {
            throw Exception("This extension is only for Google Drive Index sites, not drive.google.com folders.")
        }

        if (page == 1) pageToken = ""
        val searchHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("Host", serverUrl.toHttpUrl().host)
            .add("Origin", "https://${serverUrl.toHttpUrl().host}")
            .add("X-Requested-With", "XMLHttpRequest")

        return if (query.isBlank()) {
            val popBody = "password=&page_token=$pageToken&page_index=${page - 1}".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            POST(
                serverUrl,
                body = popBody,
                headers = searchHeaders.add("Referer", URLEncoder.encode(serverUrl, "UTF-8")).build(),
            )
        } else {
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

    private fun searchAnimeParse(response: Response, url: String): AnimesPage {
        return parsePage(response, url)
    }

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search will only search inside selected server"),
        ServerFilter(getDomains()),
    )

    private class ServerFilter(domains: Array<Pair<String, String>>) : UriPartFilter(
        "Select server",
        domains,
    )

    private fun getDomains(): Array<Pair<String, String>> {
        return preferences.getString("domain_list", "")!!.split(",").map {
            Pair(it.substringAfter("https://"), it)
        }.toTypedArray()
    }

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
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
            return Observable.just(anime)
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

            val parsedBody = client.newCall(
                POST(newParsed.url, body = popBody, headers = popHeaders),
            ).execute().body.string().decrypt()
            val parsed = json.decodeFromString<ResponseData>(parsedBody)

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

        return Observable.just(anime)
    }

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)
        var counter = 1

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
            val episode = SEpisode.create()
            val size = if (newParsed.info == null) {
                ""
            } else {
                " - ${newParsed.info}"
            }
            episode.name = "${newParsed.url.toHttpUrl().pathSegments.last()}$size"
            episode.url = newParsed.url
            episode.episode_number = 1F
            episodeList.add(episode)
        }

        if (newParsed.type == "multi") {
            val basePathCounter = newParsed.url.toHttpUrl().pathSize

            fun traverseDirectory(url: String) {
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

                    val parsedBody = client.newCall(
                        POST(url, body = popBody, headers = popHeaders),
                    ).execute().body.string().decrypt()
                    val parsed = json.decodeFromString<ResponseData>(parsedBody)

                    parsed.data.files.forEach { item ->
                        if (item.mimeType.endsWith("folder")) {
                            if (
                                preferences.getString("blacklist_folders", "")!!.split("/")
                                    .any { it.equals(item.name, ignoreCase = true) }
                            ) {
                                return@forEach
                            }

                            val newUrl = joinUrl(url, item.name).addSuffix("/")
                            traverseDirectory(newUrl)
                        }
                        if (item.mimeType.startsWith("video/")) {
                            val episode = SEpisode.create()
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
                                ""
                            }
                            val size = item.size?.toLongOrNull()?.let { formatFileSize(it) }

                            episode.name = "${item.name.trimInfo()}${if (size == null) "" else " - $size"}"
                            episode.url = epUrl
                            episode.scanlator = seasonInfo + extraInfo
                            episode.episode_number = counter.toFloat()
                            counter++

                            episodeList.add(episode)
                        }
                    }

                    newToken = parsed.nextPageToken
                    newPageIndex += 1
                }
            }

            traverseDirectory(newParsed.url)
        }

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val url = episode.url

        val doc = client.newCall(
            GET("$url?a=view"),
        ).execute().asJsoup()

        val script = doc.selectFirst("script:containsData(videodomain)")?.data()
            ?: doc.selectFirst("script:containsData(downloaddomain)")?.data()
            ?: return Observable.just(listOf(Video(url, "Video", url)))

        if (script.contains("\"second_domain_for_dl\":false")) {
            return Observable.just(listOf(Video(url, "Video", url)))
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

        return Observable.just(
            listOf(Video(videoUrl, "Video", videoUrl)),
        )
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
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
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
            bytes >= 1073741824 -> "%.2f GB".format(bytes / 1073741824.0)
            bytes >= 1048576 -> "%.2f MB".format(bytes / 1048576.0)
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
    }

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
                val anime = SAnime.create()
                anime.title = item.name.trimInfo()
                anime.thumbnail_url = ""

                if (isSearch) {
                    anime.setUrlWithoutDomain(
                        LinkData(
                            "search",
                            IdUrl(
                                item.id,
                                url.substringBeforeLast("search"),
                                response.request.header("Referer")!!,
                                "multi",
                            ).toJsonString(),
                        ).toJsonString(),
                    )
                } else {
                    anime.setUrlWithoutDomain(
                        LinkData(
                            "multi",
                            joinUrl(url, item.name).addSuffix("/"),
                        ).toJsonString(),
                    )
                }
                animeList.add(anime)
            }
            if (
                item.mimeType.startsWith("video/") &&
                !(preferences.getBoolean("ignore_non_folder", true) && isSearch)
            ) {
                val anime = SAnime.create()
                anime.title = item.name.trimInfo()
                anime.thumbnail_url = ""

                if (isSearch) {
                    anime.setUrlWithoutDomain(
                        LinkData(
                            "search",
                            IdUrl(
                                item.id,
                                url.substringBeforeLast("search"),
                                response.request.header("Referer")!!,
                                "single",
                            ).toJsonString(),
                            item.size?.toLongOrNull()?.let { formatFileSize(it) },
                        ).toJsonString(),
                    )
                } else {
                    anime.setUrlWithoutDomain(
                        LinkData(
                            "single",
                            joinUrl(url, item.name),
                            item.size?.toLongOrNull()?.let { formatFileSize(it) },
                        ).toJsonString(),
                    )
                }
                animeList.add(anime)
            }
        }

        pageToken = parsed.nextPageToken

        return AnimesPage(animeList, parsed.nextPageToken != null)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainListPref = EditTextPreference(screen.context).apply {
            key = "domain_list"
            title = "Enter drive paths to be shown in extension"
            summary = """Enter drive paths to be shown in extension
                |Enter as comma separated list
            """.trimMargin()
            this.setDefaultValue("")
            dialogTitle = "Path list"
            dialogMessage = """Separate paths with a comma. For password protected sites,
                |format as: "https://username:password@example.worker.dev/0:/"
            """.trimMargin()

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString("domain_list", newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val blacklistFolders = EditTextPreference(screen.context).apply {
            key = "blacklist_folders"
            title = "Blacklist folder names"
            summary = """Enter names of folders to skip over
                |Enter as slash / separated list
            """.trimMargin()
            this.setDefaultValue("NC/Extras")
            dialogTitle = "Blacklisted folders"
            dialogMessage = "Separate folders with a slash (case insensitive)"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString("blacklist_folders", newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val ignoreNonFolder = SwitchPreferenceCompat(screen.context).apply {
            key = "ignore_non_folder"
            title = "Only include folders on search"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }

        screen.addPreference(domainListPref)
        screen.addPreference(blacklistFolders)
        screen.addPreference(ignoreNonFolder)
    }
}
