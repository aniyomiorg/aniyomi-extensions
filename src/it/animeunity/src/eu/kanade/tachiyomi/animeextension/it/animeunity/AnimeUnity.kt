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
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat

class AnimeUnity :
    AnimeHttpSource(),
    ConfigurableAnimeSource {
    override val name = "AnimeUnity"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://www.animeunity.to"

    override val lang = "it"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/top-anime?popular=true&page=$page", headers = headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed =
            response.parseAs<AnimeResponse> {
                it
                    .substringAfter("top-anime animes=\"")
                    .substringBefore("\"></top-anime>")
                    .replace("&quot;", "\"")
            }

        val animeList =
            parsed.data.map { ani ->
                SAnime.create().apply {
                    title = ani.title_eng
                    url = "${ani.id}-${ani.slug}"
                    thumbnail_url = ani.imageurl ?: ""
                }
            }

        return AnimesPage(animeList, parsed.current_page < parsed.last_page)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?anime=$page", headers = headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animeList =
            document.select("div.home-wrapper-body > div.row > div.latest-anime-container").map {
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

    override fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Request = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        val params = AnimeUnityFilters.getSearchParameters(filters)
        return client
            .newCall(searchAnimeRequest(page, query, params))
            .awaitSuccess()
            .use { response ->
                searchAnimeParse(response, page)
            }
    }

    private fun searchAnimeRequest(
        page: Int,
        query: String,
        filters: AnimeUnityFilters.FilterSearchParams,
    ): Request {
        val archivioResponse =
            client
                .newCall(
                    GET("$baseUrl/archivio", headers = headers),
                ).execute()

        val document = archivioResponse.asJsoup()

        val crsfToken = document.select("meta[name=csrf-token]").attr("content")
        var newHeadersBuilder = headers.newBuilder()
        for (cookie in archivioResponse.headers) {
            if (cookie.first == "set-cookie" && cookie.second.startsWith("XSRF-TOKEN")) {
                newHeadersBuilder.add(
                    "X-XSRF-TOKEN",
                    cookie
                        .second
                        .substringAfter("=")
                        .substringBefore(";")
                        .replace("%3D", "="),
                )
            }

            if (cookie.first == "set-cookie" && cookie.second.startsWith("animeunity_session")) {
                newHeadersBuilder.add("Cookie", cookie.second.substringBefore(";").replace("%3D", "="))
            }
        }
        newHeadersBuilder
            .add("X-CSRF-TOKEN", crsfToken)
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")

        if (filters.top.isNotEmpty()) {
            val topHeaders =
                newHeadersBuilder
                    .add("X-CSRF-TOKEN", crsfToken)
                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .add("Referer", "$baseUrl/${filters.top}")
            return GET("$baseUrl/${filters.top}", headers = topHeaders.build())
        }

        val searchHeaders =
            newHeadersBuilder
                .add("Accept", "application/json, text/plain, */*")
                .add("Content-Type", "application/json;charset=utf-8")
                .add("Origin", baseUrl)
                .add("Referer", archivioResponse.request.url.toString())
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

        val body =
            """
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

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private fun searchAnimeParse(
        response: Response,
        page: Int,
    ): AnimesPage =
        if (response.request.method == "POST") {
            val data = response.parseAs<SearchResponse>()

            val animeList =
                data.records.map {
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

    override fun getFilterList(): AnimeFilterList = AnimeUnityFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}")

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val videoPlayer = document.selectFirst("video-player[episodes_count]")!!

        val animeDetails =
            json.decodeFromString<AnimeInfo>(
                videoPlayer.attr("anime").replace("&quot;", "\""),
            )

        return SAnime.create().apply {
            title = animeDetails.title_eng
            status = parseStatus(animeDetails.status)
            artist = animeDetails.studio ?: ""
            genre = animeDetails.genres.joinToString(", ") { it.name }
            description =
                buildString {
                    append(animeDetails.plot)
                    append("\n\nTipo: ${animeDetails.type}")
                    append("\nStagione: ${animeDetails.season} ${animeDetails.date}")
                    append("\nValutazione: ★${animeDetails.score ?: "-"}")
                }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/anime/${anime.url}", headers = headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val document = response.asJsoup()

        val crsfToken = document.select("meta[name=csrf-token]").attr("content")
        var newHeadersBuilder = headers.newBuilder()
        for (cookie in response.headers) {
            if (cookie.first == "set-cookie" && cookie.second.startsWith("XSRF-TOKEN")) {
                newHeadersBuilder.add(
                    "X-XSRF-TOKEN",
                    cookie
                        .second
                        .substringAfter("=")
                        .substringBefore(";")
                        .replace("%3D", "="),
                )
            }

            if (cookie.first == "set-cookie" && cookie.second.startsWith("animeunity_session")) {
                newHeadersBuilder.add("Cookie", cookie.second.substringBefore(";").replace("%3D", "="))
            }
        }
        newHeadersBuilder
            .add("X-CSRF-TOKEN", crsfToken)
            .add("Content-Type", "application/json")
            .add("Referer", response.request.url.toString())
            .add("Accept", "application/json, text/plain, */*")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("X-Requested-With", "XMLHttpRequest")
        val newHeaders = newHeadersBuilder.build()

        val videoPlayer = document.selectFirst("video-player[episodes_count]")!!
        val episodeCount = videoPlayer.attr("episodes_count").toInt()
        val animeId =
            response
                .request
                .url
                .toString()
                .substringAfter("/anime/")
                .substringBefore("-")

        val episodes =
            json.decodeFromString<List<Episode>>(
                videoPlayer.attr("episodes").replace("&quot;", "\""),
            )

        episodeList.addAll(
            episodes
                .filter {
                    it.id != null
                }.map {
                    SEpisode.create().apply {
                        name = "Episode ${it.number}"
                        date_upload = parseDate(it.created_at)
                        episode_number = it.number.split("-")[0].toFloatOrNull() ?: 0F
                        setUrlWithoutDomain(
                            response
                                .request
                                .url
                                .newBuilder()
                                .addPathSegment(it.id.toString())
                                .toString(),
                        )
                    }
                },
        )

        if (episodeCount > 120) {
            var start = 121
            var end = 240

            while (end < episodeCount) {
                episodeList.addAll(
                    addFromApi(start, end, animeId, newHeaders, response.request.url),
                )
                start += 120
                end += 120
            }

            if (episodeCount >= start) {
                episodeList.addAll(
                    addFromApi(start, episodeCount, animeId, newHeaders, response.request.url),
                )
            }
        }

        return episodeList.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val doc =
            client
                .newCall(
                    GET(baseUrl + episode.url, headers),
                ).execute()
                .asJsoup()
        val iframeUrl =
            doc.selectFirst("video-player[embed_url]")?.attr("abs:embed_url")
                ?: error("Failed to extract iframe")
        val iframeHeaders =
            headers
                .newBuilder()
                .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .add("Host", iframeUrl.toHttpUrl().host)
                .add("Referer", "$baseUrl/")
                .build()

        val iframe =
            client
                .newCall(
                    GET(iframeUrl, headers = iframeHeaders),
                ).execute()
                .asJsoup()
        val scripts = iframe.select("script")
        val script = scripts.find { it.data().contains("masterPlaylist") }!!.data().replace("\n", "\t")
        var playlistUrl = Regex("""url: ?'(.*?)'""").find(script)!!.groupValues[1]
        val filename = playlistUrl.slice(playlistUrl.lastIndexOf("/") + 1 until playlistUrl.length)
        if (!filename.endsWith(".m3u8")) {
            playlistUrl = playlistUrl.replace(filename, filename + ".m3u8")
        }

        val expires = Regex("""'expires': ?'(\d+)'""").find(script)!!.groupValues[1]
        val token = Regex("""'token': ?'([\w-]+)'""").find(script)!!.groupValues[1]
        // Get subtitles
        val masterPlUrl = "$playlistUrl?token=$token&expires=$expires&n=1"
        val masterPl =
            client
                .newCall(GET(masterPlUrl))
                .execute()
                .body
                .string()
        val subList =
            Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")
                .findAll(masterPl)
                .map {
                    Track(it.groupValues[2], it.groupValues[1])
                }.toList()
        Regex("""'token(\d+p?)': ?'([\w-]+)'""").findAll(script).forEach { match ->
            val quality = match.groupValues[1]

            val videoUrl =
                buildString {
                    append(playlistUrl)
                    append("?type=video&rendition=")
                    append(quality)
                    append("&token=")
                    append(match.groupValues[2])
                    append("&expires=$expires")
                    append("&n=1")
                }
            videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subList))
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList.sort()
    }

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun parseStatus(statusString: String): Int =
        when (statusString) {
            "In Corso" -> SAnime.ONGOING
            "Terminato" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }

    private fun addFromApi(
        start: Int,
        end: Int,
        animeId: String,
        headers: Headers,
        url: HttpUrl,
    ): List<SEpisode> {
        val response =
            client
                .newCall(
                    GET("$baseUrl/info_api/$animeId/1?start_range=$start&end_range=$end", headers = headers),
                ).execute()
        val json = response.parseAs<ApiResponse>()
        return json
            .episodes
            .filter {
                it.id != null
            }.map {
                SEpisode.create().apply {
                    name = "Episode ${it.number}"
                    date_upload = parseDate(it.created_at)
                    episode_number = it.number.split("-")[0].toFloatOrNull() ?: 0F
                    setUrlWithoutDomain(
                        url
                            .newBuilder()
                            .addPathSegment(it.id.toString())
                            .toString(),
                    )
                }
            }
    }

    private fun String.falseIfEmpty(): String =
        if (this.isEmpty()) {
            "false"
        } else {
            "\"${this}\""
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
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this
            .sortedWith(
                compareBy(
                    { it.quality.contains(quality) },
                    { it.quality.substringBefore("p").toIntOrNull() ?: 0 },
                ),
            ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context)
            .apply {
                key = PREF_QUALITY_KEY
                title = "Preferred quality"
                entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
                entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
                setDefaultValue(PREF_QUALITY_DEFAULT)
                summary = "%s"

                setOnPreferenceChangeListener { _, newValue ->
                    val selected = newValue as String
                    val index = findIndexOfValue(selected)
                    val entry = entryValues[index] as String
                    preferences.edit().putString(key, entry).commit()
                }
            }.also(screen::addPreference)
    }
}
