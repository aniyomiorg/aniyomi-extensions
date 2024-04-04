package eu.kanade.tachiyomi.animeextension.it.streamingcommunity

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class StreamingCommunity : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "StreamingCommunity"

    override val baseUrl = "https://streamingcommunity.forum"

    override val lang = "it"

    override val supportsLatest = true

    override val client: OkHttpClient = network.client

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/browse/trending", headers)
        } else {
            val apiHeaders = headers.newBuilder()
                .add("Accept", "application/json, text/plain, */*")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", "$baseUrl/browse/trending")
                .build()
            GET("$baseUrl/api/browse/trending?offset=${(page - 1) * 60}", headers = apiHeaders)
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = if (response.request.url.encodedPath.startsWith("/api/")) {
            json.decodeFromString(response.body.string())
        } else {
            val data = response.asJsoup().getData()
            json.decodeFromString<ShowsResponse>(data).props
        }

        val imageUrl = "https://cdn.${baseUrl.toHttpUrl().host}/images/"

        val animeList = parsed.titles.map { item ->
            SAnime.create().apply {
                title = item.name
                url = "${item.id}-${item.slug}"
                thumbnail_url = item.images.firstOrNull {
                    it.type == "poster"
                }?.let {
                    imageUrl + it.filename
                } ?: item.images.firstOrNull {
                    it.type == "cover"
                }?.let {
                    imageUrl + it.filename
                } ?: item.images.firstOrNull {
                    it.type == "background"
                }?.let {
                    imageUrl + it.filename
                }
            }
        }

        val hasNextPage = response.request.url.queryParameter("offset")
            ?.toIntOrNull()
            ?.let { it < 120 } ?: true && animeList.size == 60

        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        return if (page == 1) {
            GET("$baseUrl/browse/latest", headers)
        } else {
            val apiHeaders = headers.newBuilder()
                .add("Accept", "application/json, text/plain, */*")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", "$baseUrl/browse/trending")
                .build()
            GET("$baseUrl/api/browse/latest?offset=${(page - 1) * 60}", headers = apiHeaders)
        }
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val genreFilter = filters.find { it is GenreFilter } as GenreFilter

        val slug = if (genreFilter.state != 0) {
            "browse/genre?g=${URLEncoder.encode(genreFilter.toUriPart(), "utf-8")}"
        } else {
            "search?q=$query"
        }

        return if (page == 1) {
            GET("$baseUrl/$slug")
        } else {
            val apiHeaders = headers.newBuilder()
                .add("Accept", "application/json, text/plain, */*")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", "$baseUrl/$slug")
                .build()
            GET("$baseUrl/api/$slug&offset=${(page - 1) * 60}", headers = apiHeaders)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath

        val parsed = if (path.startsWith("/api/")) {
            if (path.contains("search")) {
                json.decodeFromString<SearchAPIResponse>(response.body.string()).data
            } else {
                json.decodeFromString<GenreAPIResponse>(response.body.string()).titles
            }
        } else {
            val data = response.asJsoup().getData()
            json.decodeFromString<ShowsResponse>(data).props.titles
        }

        val imageUrl = "https://cdn.${baseUrl.toHttpUrl().host}/images/"

        val animeList = parsed.map { item ->
            SAnime.create().apply {
                title = item.name
                url = "${item.id}-${item.slug}"
                thumbnail_url = item.images.firstOrNull {
                    it.type == "poster"
                }?.let {
                    imageUrl + it.filename
                } ?: item.images.firstOrNull {
                    it.type == "cover"
                }?.let {
                    imageUrl + it.filename
                } ?: item.images.firstOrNull {
                    it.type == "background"
                }?.let {
                    imageUrl + it.filename
                }
            }
        }

        val hasNextPage = response.request.url.queryParameter("offset")
            ?.toIntOrNull()
            ?.let { it < 120 } ?: true && animeList.size == 60

        return AnimesPage(animeList, hasNextPage)
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request = GET("$baseUrl/titles/${anime.url}", headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val parsed = json.decodeFromString<SingleShowResponse>(
            response.asJsoup().getData(),
        ).props.title!!

        return SAnime.create().apply {
            description = parsed.plot
            status = parseStatus(parsed.status)
            genre = parsed.genres?.joinToString(", ") { it.name }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request = GET("$baseUrl/titles/${anime.url}", headers)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val parsed = json.decodeFromString<SingleShowResponse>(response.asJsoup().getData())
        val data = parsed.props
        val episodeList = mutableListOf<SEpisode>()

        if (data.loadedSeason == null) {
            episodeList.add(
                SEpisode.create().apply {
                    name = "Film"
                    episode_number = 1F
                    url = data.title!!.id.toString()
                },
            )
        } else {
            data.title!!.seasons.forEach { season ->
                val episodeData = if (season.id == data.loadedSeason.id) {
                    data.loadedSeason.episodes
                } else {
                    val inertiaHeaders = headers.newBuilder()
                        .add("Accept", "text/html, application/xhtml+xml")
                        .add("Content-Type", "application/json")
                        .add("Host", baseUrl.toHttpUrl().host)
                        .add("Referer", "${response.request.url}/")
                        .add("X-Inertia", "true")
                        .add("X-Inertia-Partial-Component", "Titles/Title")
                        .add("X-Inertia-Partial-Data", "loadedSeason,flash")
                        .add("X-Inertia-Version", parsed.version!!)
                        .add("X-Requested-With", "XMLHttpRequest")
                        .build()

                    val body = client.newCall(
                        GET("${response.request.url}/stagione-${season.number}", headers = inertiaHeaders),
                    ).execute().body.string()

                    json.decodeFromString<SingleShowResponse>(body).props.loadedSeason!!.episodes
                }

                episodeData.forEach { episode ->
                    episodeList.add(
                        SEpisode.create().apply {
                            name = "Stagione ${season.number} episodio ${episode.number} - ${episode.name}"
                            episode_number = episode.number.toFloat()
                            url = "${data.title.id}?episode_id=${episode.id}&next_episode=1"
                        },
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()
        val doc =
            client
                .newCall(
                    GET("$baseUrl/iframe/${episode.url}", headers),
                ).execute()
                .asJsoup()
        val iframeUrl =
            doc.selectFirst("iframe[src]")?.attr("abs:src")
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
        val script = iframe.selectFirst("script:containsData(masterPlaylist)")!!.data().replace("\n", "\t")
        var playlistUrl = PLAYLIST_URL_REGEX.find(script)!!.groupValues[1]
        val filename = playlistUrl.substringAfterLast("/")
        if (!filename.endsWith(".m3u8")) {
            playlistUrl = playlistUrl.replace(filename, filename + ".m3u8")
        }

        val expires = EXPIRES_REGEX.find(script)!!.groupValues[1]
        val token = TOKEN_REGEX.find(script)!!.groupValues[1]

        // Get subtitles
        val masterPlUrl = "$playlistUrl?token=$token&expires=$expires&n=1"
        val masterPl =
            client
                .newCall(GET(masterPlUrl))
                .execute()
                .body
                .string()
        val subList =
            SUBTITLES_REGEX.findAll(masterPl)
                .map {
                    Track(it.groupValues[2], it.groupValues[1])
                }.toList()
        TOKEN_QUALITY_REGEX.findAll(script).forEach { match ->
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

    override fun videoListRequest(episode: SEpisode): Request = throw Exception("Not used")

    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    // ============================= Utilities ==============================

    private fun Document.getData(): String {
        return this.selectFirst("div#app[data-page]")!!
            .attr("data-page")
            .replace("&quot;", "\"")
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun parseStatus(statusString: String?): Int {
        return when (statusString) {
            "Ended" -> SAnime.COMPLETED
            "Released" -> SAnime.COMPLETED
            "Returning Series" -> SAnime.ONGOING
            "Canceled" -> SAnime.CANCELLED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private val PLAYLIST_URL_REGEX = Regex("""url: ?'(.*?)'""")
        private val EXPIRES_REGEX = Regex("""'expires': ?'(\d+)'""")
        private val TOKEN_REGEX = Regex("""'token': ?'([\w-]+)'""")
        private val TOKEN_QUALITY_REGEX = Regex("""'token(\d+p?)': ?'([\w-]+)'""")
        private val SUBTITLES_REGEX = Regex("""#EXT-X-MEDIA:TYPE=SUBTITLES.*?NAME="(.*?)".*?URI="(.*?)"""")
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action & Adventure", "Action & Adventure"),
            Pair("Animazione", "Animazione"),
            Pair("Avventura", "Avventura"),
            Pair("Azione", "Azione"),
            Pair("Commedia", "Commedia"),
            Pair("Crime", "Crime"),
            Pair("Documentario", "Documentario"),
            Pair("Dramma", "Dramma"),
            Pair("Famiglia", "Famiglia"),
            Pair("Fantascienza", "Fantascienza"),
            Pair("Fantasy", "Fantasy"),
            Pair("Guerra", "Guerra"),
            Pair("Horror", "Horror"),
            Pair("Kids", "Kids"),
            Pair("Korean drama", "Korean drama"),
            Pair("Mistero", "Mistero"),
            Pair("Musica", "Musica"),
            Pair("Reality", "Reality"),
            Pair("Romance", "Romance"),
            Pair("Sci-Fi & Fantasy", "Sci-Fi & Fantasy"),
            Pair("Soap", "Soap"),
            Pair("Storia", "Storia"),
            Pair("televisione film", "televisione film"),
            Pair("Thriller", "Thriller"),
            Pair("War & Politics", "War & Politics"),
            Pair("Western", "Western"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
