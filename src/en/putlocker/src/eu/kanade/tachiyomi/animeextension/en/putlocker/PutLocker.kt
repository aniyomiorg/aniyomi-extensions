package eu.kanade.tachiyomi.animeextension.en.putlocker

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
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

@ExperimentalSerializationApi
class PutLocker : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "PutLocker"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://putlocker.vip")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/putlocker/")

    override fun popularAnimeSelector(): String = "div#movie-featured > div.ml-item"

    override fun popularAnimeNextPageSelector(): String = "Nothing"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("a").attr("abs:href"))
            thumbnail_url = element.select("a > img").attr("abs:data-original")
            title = element.select("a > div.mli-info > h2").text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter/$page?genre=all&country=all&types=all&year=all&sort=updated")

    override fun latestUpdatesSelector(): String = "div.movies-list > div.ml-item"

    override fun latestUpdatesNextPageSelector(): String = "div#pagination li a[title=Last]"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val re = Regex("[^A-Za-z0-9 ]")
        val cleanQuery = re.replace(query, "").replace(" ", "+").lowercase()
        return GET("$baseUrl/movie/search/$cleanQuery/$page/")
    }

    override fun searchAnimeSelector(): String = latestUpdatesSelector()

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val descElement = document.select("div.mvic-desc")
        anime.title = descElement.select("h3").text()
        anime.genre = descElement.select("div.mvic-info > div.mvici-left p:contains(Genre) a").joinToString { it.text() }
        anime.author = document.select("div.mvic-info > div.mvici-left p:contains(Director) a").joinToString { it.text() }
        anime.status = document.select("div.mvic-info > div.mvici-right p:contains(Episode)")?.let {
            if (it.text().isNullOrBlank()) SAnime.COMPLETED else SAnime.UNKNOWN
        } ?: SAnime.COMPLETED

        var description = descElement.select("div.desc").text()?.let { it + "\n" }
        val extraDescription = document.select("div.mvic-info > div.mvici-right")
        extraDescription.select("p:contains(Quality)").text().let {
            description += if (it.isNotBlank()) "\n$it" else ""
        }
        extraDescription.select("p:contains(Release)").text().let {
            description += if (it.isNotBlank()) "\n$it" else ""
        }
        extraDescription.select("p:contains(IMDb)").text().let {
            description += if (it.isNotBlank()) "\n$it" else ""
        }
        anime.description = description

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.split("-").last().replace("/", "")
        return GET("$baseUrl/ajax/movie_episodes/$id")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val html = json.decodeFromString<JsonObject>(response.body!!.string())["html"]!!.jsonPrimitive.content
        val parsedHtml = Jsoup.parse(JSONUtil.unescape(html))
        val rawEpisodes = parsedHtml.select("div[id^=sv]").mapNotNull { server ->
            val linkElement = server.select("div.les-content > a")
            linkElement.map { epLinkElement ->
                val dataId = epLinkElement.attr("data-id")!!
                val ep = dataId.substringAfter("_").substringBefore("_").toInt()
                val title = if (ep == 0) {
                    "Movie"
                } else {
                    "Episode $ep: " + epLinkElement.attr("title").substringAfter("Episode $ep")
                        .replace("-", "").trim()
                }
                Pair(title, dataId)
            }
        }.flatten()

        return rawEpisodes.groupBy { it.second.substringAfter("_").substringBefore("_").toInt() }
            .mapNotNull { group ->
                SEpisode.create().apply {
                    url = EpLinks(
                        ep_num = group.key,
                        ids = group.value.map { it.second }
                    ).toJson()
                    name = group.value.first().first
                    episode_number = group.key.toFloat()
                }
            }
    }

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not Used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<EpLinks>(episode.url)
        val videoList = urlJson.ids.parallelMap { dataId ->
            runCatching {
                extractVideo(dataId)
            }.getOrNull()
        }
            .filterNotNull()
            .flatten()
        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun extractVideo(dataId: String): List<Video> {
        val url = "$baseUrl/ajax/movie_embed/$dataId"
        val embedResp = client.newCall(GET(url)).execute().body!!.string()
        val embedUrl = json.decodeFromString<JsonObject>(embedResp)["src"]!!.jsonPrimitive.content
        val vidReferer = Headers.headersOf("Referer", embedUrl)
        val vidResponse = extractVideoEmbed(embedUrl, vidReferer)
        if (!vidResponse.startsWith("{\"sources\"")) return emptyList()
        val vidJson = json.decodeFromString<Sources>(vidResponse)
        val subsList = extractSubs(vidJson.tracks)

        val videoList = mutableListOf<Video>()
        val serverId = dataId.substringAfterLast("_")
        vidJson.sources.map { source ->
            videoList.addAll(extractVideoLinks(source, vidReferer, subsList, serverId))
        }
        if (!vidJson.backupLink.isNullOrBlank()) {
            vidJson.backupLink.let { bakUrl ->
                val bakReferer = Headers.headersOf("Referer", bakUrl)
                val bakResponse = extractVideoEmbed(bakUrl, bakReferer)
                if (bakResponse.startsWith("{\"sources\"")) {
                    val bakJson = json.decodeFromString<Sources>(bakResponse)
                    val bakSubsList = extractSubs(bakJson.tracks)
                    val bakserverId = "$serverId - Backup"
                    bakJson.sources.map { bakSource ->
                        videoList.addAll(
                            extractVideoLinks(
                                bakSource,
                                bakReferer,
                                bakSubsList,
                                bakserverId
                            )
                        )
                    }
                }
            }
        }
        return videoList
    }

    private fun extractSubs(tracks: List<SubTrack>?): List<Track> {
        val subsList = mutableListOf<Track>()
        try {
            tracks?.map { sub ->
                sub.file.let {
                    subsList.add(
                        Track(
                            sub.file,
                            sub.label
                        )
                    )
                }
            }
        } catch (_: Error) {}
        return subsList
    }

    private fun extractVideoEmbed(embedUrl: String, vidReferer: Headers): String {
        val embedHost = embedUrl.substringBefore("/embed-player")
        val referer = Headers.headersOf("Referer", baseUrl)

        val playerResp = client.newCall(GET(embedUrl, referer)).execute().asJsoup()
        val player = playerResp.select("div#player")
        val vidId = "\"" + player.attr("data-id") + "\""
        val vidHash = player.attr("data-hash")
        val cipher = CryptoAES.encrypt(vidHash, vidId)
        val vidUrl = "$embedHost/ajax/getSources/".toHttpUrl().newBuilder()
            .addQueryParameter("id", cipher.cipherText)
            .addQueryParameter("h", cipher.password)
            .addQueryParameter("a", cipher.iv)
            .addQueryParameter("t", cipher.salt)
            .build().toString()
        val resp = client.newCall(GET(vidUrl, vidReferer)).execute()
        return resp.body!!.string()
    }

    private fun extractVideoLinks(source: VidSource, vidReferer: Headers, subsList: List<Track>, serverId: String): List<Video> {
        val videoList = mutableListOf<Video>()
        if (source.file.endsWith(".m3u8")) {
            val videoLink = source.file
            val resp = client.newCall(GET(videoLink, vidReferer)).execute()
            val masterPlaylist = resp.body!!.string()
            if (resp.code == 200) {
                masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").map {
                        val quality = if (serverId == "1") {
                            it.substringAfter("NAME=\"").substringBefore("\"") + " - Server $serverId"
                        } else {
                            it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p - Server $serverId"
                        }

                        var videoUrl = it.substringAfter("\n").substringBefore("\n")
                        if (videoUrl.startsWith("https").not()) {
                            val host = videoLink.substringBefore("/m3u8")
                            videoUrl = host + videoUrl
                        }
                        try {
                            videoList.add(Video(videoUrl, quality, videoUrl, subtitleTracks = subsList, headers = vidReferer))
                        } catch (e: Error) {
                            videoList.add(Video(videoUrl, quality, videoUrl, headers = vidReferer))
                        }
                    }
            }
        } else {
            val quality = "${source.label} - Server $serverId (${source.type})"
            try {
                videoList.add(Video(source.file, quality, source.file, subtitleTracks = subsList, headers = vidReferer))
            } catch (e: Error) {
                videoList.add(Video(source.file, quality, source.file, headers = vidReferer))
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)

        val newList = mutableListOf<Video>()
        if (quality != null) {
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }

            return newList
        }
        return this
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("putlocker.vip")
            entryValues = arrayOf("https://putlocker.vip")
            setDefaultValue("https://putlocker.vip")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080", "720", "480")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }

    private fun EpLinks.toJson(): String {
        return json.encodeToString(this)
    }

    @Serializable
    data class EpLinks(
        val ep_num: Int,
        val ids: List<String>
    )

    @Serializable
    data class VidSource(
        val file: String,
        val label: String?,
        val type: String?
    )

    @Serializable
    data class SubTrack(
        val default: Boolean,
        val file: String,
        val label: String,
        val kind: String
    )

    @Serializable
    data class Sources(
        val sources: List<VidSource>,
        val tracks: List<SubTrack>?,
        val backupLink: String?
    )

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
