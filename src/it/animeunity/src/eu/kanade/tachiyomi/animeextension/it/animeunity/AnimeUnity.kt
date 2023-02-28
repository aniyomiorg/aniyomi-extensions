package eu.kanade.tachiyomi.animeextension.it.animeunity

import android.annotation.SuppressLint
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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat

class AnimeUnity : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeUnity"

    override val baseUrl = "https://www.animeunity.tv"

    private val workerUrl = "https://scws.work"

    override val lang = "it"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<AnimeResponse>(
            response.body.string().substringAfter("top-anime animes=\"").substringBefore("\"></top-anime>").replace("&quot;", "\""),
        )

        val animeList = parsed.data.map { ani ->
            SAnime.create().apply {
                title = ani.title_eng
                url = "${ani.id}-${ani.slug}"
                thumbnail_url = ani.imageurl ?: ""
            }
        }

        return AnimesPage(animeList, parsed.current_page < parsed.last_page)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top-anime?popular=true&page=$page", headers = headers)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?anime=$page", headers = headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList = document.select("div.home-wrapper-body > div.row > div.latest-anime-container").map {
            SAnime.create().apply {
                title = it.select("a > strong").text()
                url = it.selectFirst("a")!!.attr("href").substringAfter("/anime/")
                thumbnail_url = it.select("img").attr("src")
            }
        }

        val hasNextPage = document.select("ul.pagination > li.active ~ li").first() != null

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AnimeUnityFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response, page)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimeUnityFilters.FilterSearchParams): Request {
        val archivioResponse = client.newCall(
            GET("$baseUrl/archivio", headers = headers),
        ).execute()

        val document = archivioResponse.asJsoup()

        val crsfToken = document.select("meta[name=csrf-token]").attr("content")
        var newHeadersBuilder = headers.newBuilder()
        for (cookie in archivioResponse.headers) {
            if (cookie.first == "set-cookie" && cookie.second.startsWith("XSRF-TOKEN")) {
                newHeadersBuilder.add("X-XSRF-TOKEN", cookie.second.substringAfter("=").substringBefore(";").replace("%3D", "="))
            }

            if (cookie.first == "set-cookie" && cookie.second.startsWith("animeunity_session")) {
                newHeadersBuilder.add("Cookie", cookie.second.substringBefore(";").replace("%3D", "="))
            }
        }
        newHeadersBuilder.add("X-CSRF-TOKEN", crsfToken)
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")

        if (filters.top.isNotEmpty()) {
            val topHeaders = newHeadersBuilder.add("X-CSRF-TOKEN", crsfToken)
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Referer", "$baseUrl/${filters.top}")
            return GET("$baseUrl/${filters.top}", headers = topHeaders.build())
        }

        val searchHeaders = newHeadersBuilder
            .add("Accept", "application/json, text/plain, */*")
            .add("Content-Type", "application/json;charset=utf-8")
            .add("Origin", baseUrl)
            .add("Referer", archivioResponse.request.url.toString())
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = """
        {
            "title": ${query.falseIfEmpty()},
            "type": ${filters.type.falseIfEmpty()},
            "year": ${filters.year.falseIfEmpty()},
            "order": ${filters.order.falseIfEmpty()},
            "status": ${filters.state.falseIfEmpty()},
            "genres": ${filters.genre.ifEmpty { "false" }},
            "offset": ${(page - 1) * 30},
            "dubbed": ${if (filters.dub.isEmpty()) "false" else "true"},
            "season": ${filters.season.falseIfEmpty()}
        }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        return POST("$baseUrl/archivio/get-animes", body = body, headers = searchHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    private fun searchAnimeParse(response: Response, page: Int): AnimesPage {
        return if (response.request.method == "POST") {
            val data = json.decodeFromString<SearchResponse>(
                response.body.string(),
            )

            val animeList = data.records.map {
                SAnime.create().apply {
                    title = it.title_eng
                    thumbnail_url = it.imageurl
                    url = "${it.id}-${it.slug}"
                }
            }

            AnimesPage(animeList, data.tot - page * 30 >= 30 && data.tot > 30)
        } else {
            popularAnimeParse(response)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeUnityFilters.filterList

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$baseUrl/anime/${anime.url}")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        val document = response.asJsoup()

        val videoPlayer = document.selectFirst("video-player[episodes_count]")!!

        val animeDetails = json.decodeFromString<AnimeInfo>(
            videoPlayer.attr("anime").replace("&quot;", "\""),
        )

        anime.title = animeDetails.title_eng
        anime.status = parseStatus(animeDetails.status)
        anime.artist = animeDetails.studio ?: ""
        anime.genre = animeDetails.genres.joinToString(", ") { it.name }

        var description = animeDetails.plot + "\n"

        description += "\nTipo: ${animeDetails.type}"
        description += "\nStagione: ${animeDetails.season} ${animeDetails.date}"
        description += "\nValutazione: ★${animeDetails.score ?: "-"}"

        anime.description = description

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$baseUrl/anime/${anime.url}", headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val document = response.asJsoup()

        val crsfToken = document.select("meta[name=csrf-token]").attr("content")
        var newHeadersBuilder = headers.newBuilder()
        for (cookie in response.headers) {
            if (cookie.first == "set-cookie" && cookie.second.startsWith("XSRF-TOKEN")) {
                newHeadersBuilder.add("X-XSRF-TOKEN", cookie.second.substringAfter("=").substringBefore(";").replace("%3D", "="))
            }

            if (cookie.first == "set-cookie" && cookie.second.startsWith("animeunity_session")) {
                newHeadersBuilder.add("Cookie", cookie.second.substringBefore(";").replace("%3D", "="))
            }
        }
        newHeadersBuilder.add("X-CSRF-TOKEN", crsfToken)
            .add("Content-Type", "application/json")
            .add("Referer", response.request.url.toString())
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("X-Requested-With", "XMLHttpRequest")
        val newHeaders = newHeadersBuilder.build()

        val videoPlayer = document.selectFirst("video-player[episodes_count]")!!
        val episodeCount = videoPlayer.attr("episodes_count").toInt()
        val animeId = response.request.url.toString().substringAfter("/anime/").substringBefore("-")

        val episodes = json.decodeFromString<List<Episode>>(
            videoPlayer.attr("episodes").replace("&quot;", "\""),
        )

        episodeList.addAll(
            episodes.filter {
                it.scws_id != null && it.file_name != null
            }.map {
                SEpisode.create().apply {
                    name = "Episode ${it.number}"
                    url = LinkData(it.scws_id.toString(), it.file_name!!).toJsonString()
                    date_upload = parseDate(it.created_at)
                    episode_number = it.number.split("-")[0].toFloatOrNull() ?: 0F
                }
            },
        )

        if (episodeCount > 120) {
            var start = 121
            var end = 240

            while (end < episodeCount) {
                episodeList.addAll(
                    addFromApi(start, end, animeId, newHeaders),
                )
                start += 120
                end += 120
            }

            if (episodeCount >= start) {
                episodeList.addAll(
                    addFromApi(start, episodeCount, animeId, newHeaders),
                )
            }
        }

        return episodeList.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val newHeaders = Headers.headersOf(
            "Accept", "*/*",
            "Accept-Language", "en-US,en;q=0.5",
            "Host", "scws.work",
            "Origin", baseUrl,
            "Referer", "$baseUrl/",
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
        )

        val mediaId = json.decodeFromString<LinkData>(episode.url)
        val videoList = mutableListOf<Video>()

        val serverJson = json.decodeFromString<ServerResponse>(
            client.newCall(GET("https://scws.work/videos/${mediaId.id}", headers = newHeaders)).execute().body.string(),
        )

        val appJs = client.newCall(GET("$baseUrl/js/app.js", headers = headers)).execute().body.string()

        val tokenRegex = """(\d+),(?:\w+)\.client_ip,"(\w+)"""".toRegex()
        val (multiplier, key) = tokenRegex.find(appJs)!!.destructured

        val pKeyRegex = """(\d+),u,"(\w+)"""".toRegex()
        val (pMultiplier, pKey) = pKeyRegex.find(appJs)!!.destructured

        val playListToken = getToken(multiplier.toInt(), serverJson.client_ip, key)
        val downloadToken = getToken(pMultiplier.toInt(), serverJson.client_ip, pKey)

        val masterPlaylist = client.newCall(
            GET("$workerUrl/master/${mediaId.id}?token=$playListToken", headers = headers),
        ).execute().body.string()

        val qualities = mutableListOf<String>()
        masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            qualities.add(
                it.substringAfter("\n").substringBefore("\n").substringBefore("p/").substringAfterLast("/"),
            )
        }

        qualities.forEach {
            val url = "https://au-d1-0" +
                serverJson.proxy_download +
                ".scws-content.net/download/" +
                serverJson.storage_download.number +
                "/" +
                serverJson.folder_id +
                "/" +
                it +
                "p.mp4?token=" +
                downloadToken +
                "&filename=" +
                mediaId.file_name.replace("&", ".")
            videoList.add(
                Video(
                    "https://au-d1-0${serverJson.proxy_download}.scws-content.net",
                    "${it}p",
                    url,
                    headers = headers,
                ),
            )
        }

        return Observable.just(videoList.sort())
    }

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "In Corso" -> SAnime.ONGOING
            "Terminato" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun addFromApi(start: Int, end: Int, animeId: String, headers: Headers): List<SEpisode> {
        val response = client.newCall(
            GET("$baseUrl/info_api/$animeId/1?start_range=$start&end_range=$end", headers = headers),
        ).execute()
        val json = json.decodeFromString<ApiResponse>(response.body.string())
        return json.episodes.filter {
            it.scws_id != null && it.file_name != null
        }.map {
            SEpisode.create().apply {
                name = "Episode ${it.number}"
                url = LinkData(it.scws_id.toString(), it.file_name!!).toJsonString()
                date_upload = parseDate(it.created_at)
                episode_number = it.number.split("-")[0].toFloatOrNull() ?: 0F
            }
        }
    }

    private fun String.falseIfEmpty(): String {
        return if (this.isEmpty()) {
            "false"
        } else {
            "\"${this}\""
        }
    }

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    @SuppressLint("SimpleDateFormat")
    private fun parseDate(date: String): Long {
        val knownPatterns: MutableList<SimpleDateFormat> = ArrayList()
        knownPatterns.add(SimpleDateFormat("yyyy-MM-dd hh:mm:ss"))

        for (pattern in knownPatterns) {
            try {
                // Take a try
                return pattern.parse(date)!!.time
            } catch (e: Throwable) {
                // Loop on
            }
        }
        return System.currentTimeMillis()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.substringBefore("p").toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
    }
}
