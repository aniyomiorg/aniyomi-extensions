package eu.kanade.tachiyomi.animeextension.en.asiaflix

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.DetailsResponseDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.EncryptedResponseDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.EpisodeResponseDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.SearchDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.SearchEntry
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.SourceDto
import eu.kanade.tachiyomi.animeextension.en.asiaflix.dto.StreamHeadDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import kotlin.math.min

class AsiaFlix : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AsiaFlix"

    override val baseUrl = "https://asiaflix.app"

    private val apiUrl = "https://api.asiaflix.app/api/v2"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiHeaders by lazy {
        headersBuilder()
            .set("Accept", "application/json, text/plain, */*")
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .set("X-Requested-By", "asiaflix-web")
            .build()
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val url = "$apiUrl/drama/explore/full?schedule=0&sort=1&fields=name,+image,+altNames,+synopsis,+genre,+tvStatus&limit=$LIMIT&page=$page"

        return GET(url, apiHeaders)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<List<JsonElement>>()
        val series = result[1].parseAs<List<DetailsResponseDto>>()

        val entries = series.map(DetailsResponseDto::toSAnime)
        val hasNextPage = entries.size == LIMIT

        return AnimesPage(entries, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$apiUrl/drama/explore/full?schedule=0&sort=3&fields=name,+image,+altNames,+synopsis,+genre,+tvStatus&limit=$LIMIT&page=$page"

        return GET(url, apiHeaders)
    }

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    private lateinit var searchEntries: SearchDto

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (page == 1) {
            super.getSearchAnime(page, query, filters)
        } else {
            paginatedSearchParse(page)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$apiUrl/drama/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .build()
            .toString()

        return GET(url, apiHeaders)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        searchEntries = response.parseAs()

        return paginatedSearchParse(1)
    }

    private fun paginatedSearchParse(page: Int): AnimesPage {
        val end = min(page * 20, searchEntries.size)
        val entries = searchEntries.subList((page - 1) * 20, end).map(SearchEntry::toSAnime)

        return AnimesPage(entries, end < searchEntries.size)
    }

    // =========================== Anime Details ============================
    // workaround to get correct WebView url
    override fun getAnimeUrl(anime: SAnime): String {
        val slug = anime.title.titleToSlug()
        return "$baseUrl/show-details/$slug/${anime.url}"
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$apiUrl/drama?id=${anime.url}", apiHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        return response.parseAs<DetailsResponseDto>().toSAnime()
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val result = response.parseAs<EpisodeResponseDto>()

        return result.episodes.map {
            SEpisode.create().apply {
                name = "Episode ${it.number}"
                episode_number = it.number.floatOrNull ?: -1f
                scanlator = it.sub
                url = it.url
            }
        }.sortedByDescending { it.episode_number }
    }

    // ============================ Video Links =============================
    private val streamHead by lazy {
        client.newCall(GET("$apiUrl/utility/get-stream-headers", apiHeaders))
            .execute()
            .parseAs<StreamHeadDto>()
            .source
    }

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(streamHead + episode.url, headers)
    }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodStreamExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val hostUrls = document.select("ul.list-server-items li").map {
            it.attr("data-video")
        }

        val videos = hostUrls.parallelCatchingFlatMapBlocking { hostUrl ->
            when {
                hostUrl.contains("dwish") -> {
                    streamWishExtractor.videosFromUrl(hostUrl)
                }
                hostUrl.contains("dood") -> {
                    doodStreamExtractor.videosFromUrl(hostUrl)
                }
                hostUrl.contains("streamtape") -> {
                    streamTapeExtractor.videoFromUrl(hostUrl).let(::listOfNotNull)
                }
                hostUrl.contains("mixdrop") -> {
                    mixDropExtractor.videoFromUrl(hostUrl)
                }
                else -> { emptyList() }
            }
        }.toMutableList()

        runCatching {
            videos.addAll(getSelfVideo(document))
        }

        return videos
    }

    private fun getSelfVideo(document: Document): List<Video> {
        val crypto = document.select("script[data-name=crypto]")
            .attr("data-value").let { CryptoAES.decrypt(it, PASSWORD, IV) }

        val urlPart = crypto.substringAfter("&")
        val id = crypto.substringBefore("&")
        val encId = CryptoAES.encrypt(id, PASSWORD, IV)

        val newHeaders = headersBuilder().set("Referer", document.location()).build()

        val encRequest = GET("$streamHead/encrypt-ajax.php?id=$encId&$urlPart&alias=$id", newHeaders)

        val encResponse = client.newCall(encRequest).execute()

        val encryptedData = encResponse.parseAs<EncryptedResponseDto>().data

        val decryptedData = CryptoAES.decrypt(encryptedData, PASSWORD, IV)

        val masterPlaylist = decryptedData.parseAs<SourceDto>().source.first().file

        return playlistUtils.extractFromHls(masterPlaylist, document.location(), videoNameGen = { quality -> "Default Server - $quality" })
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================ Preference =============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
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

    // ============================ Utilities =============================

    private inline fun <reified T> JsonElement.parseAs(): T =
        json.decodeFromJsonElement(this)

    companion object {
        private const val LIMIT = 20

        // TODO: find way to dynamically extract password and iv
        private val PASSWORD = "93422192433952489752342908585752".toByteArray()
        private val IV = "9262859232435825".toByteArray()

        fun String.titleToSlug() = trim()
            .lowercase(Locale.US)
            .replace(TITLE_SPECIAL_CHAR_REGEX, "-")
            .replace(TRAILING_HYPHEN_REGEX, "")

        private val TITLE_SPECIAL_CHAR_REGEX = "[^a-z0-9]+".toRegex()
        private val TRAILING_HYPHEN_REGEX = "-+$".toRegex()

        private const val PREF_QUALITY_KEY = "Preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape", "MixDrop")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape", "MixDrop")
    }
}
