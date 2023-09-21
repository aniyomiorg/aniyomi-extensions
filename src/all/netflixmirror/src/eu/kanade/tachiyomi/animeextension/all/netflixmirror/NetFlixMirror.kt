package eu.kanade.tachiyomi.animeextension.all.netflixmirror

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto.DetailsDto
import eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto.EpisodeUrl
import eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto.EpisodesDto
import eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto.SearchDto
import eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto.SeasonEpisodesDto
import eu.kanade.tachiyomi.animeextension.all.netflixmirror.dto.VideoList
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.select.Elements
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.min

class NetFlixMirror : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "NetFlix Mirror"

    override val baseUrl = "https://m.netflixmirror.com"

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client = network.cloudflareClient.newBuilder()
        .addNetworkInterceptor(
            CookieInterceptor(baseUrl.toHttpUrl().host, "hd", "on"),
        )
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val xhrHeaders by lazy {
        headersBuilder()
            .add("X-Requested-With", "XMLHttpRequest")
            .build()
    }

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val playListUtils by lazy {
        PlaylistUtils(client, headers)
    }

    private lateinit var pageElements: Elements

    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> {
        return if (page == 1) {
            super.fetchPopularAnime(page)
        } else {
            Observable.just(paginatedAnimePageParse(page))
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/home", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        pageElements = response.asJsoup().select("article > a.post-data")

        return paginatedAnimePageParse(1)
    }

    private fun paginatedAnimePageParse(page: Int): AnimesPage {
        val end = min(page * 20, pageElements.size)
        val entries = pageElements.subList((page - 1) * 20, end).map {
            SAnime.create().apply {
                title = "" // no title here
                url = it.attr("data-post")
                thumbnail_url = it.selectFirst("img")?.attr("abs:data-src")
            }
        }

        return AnimesPage(entries, end < pageElements.size)
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        return if (query.isNotEmpty()) {
            super.fetchSearchAnime(page, query, filters)
        } else {
            if (page == 1) {
                val pageFilter = filters.filterIsInstance<PageFilter>().firstOrNull()?.selected ?: "/home"
                val request = GET(baseUrl + pageFilter, headers)

                client.newCall(request)
                    .asObservableSuccess()
                    .map(::popularAnimeParse)
            } else {
                Observable.just(paginatedAnimePageParse(page))
            }
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("s", query.trim())
            addQueryParameter("t", System.currentTimeMillis().toString())
        }.build().toString()

        return GET(url, xhrHeaders)
    }

    override fun getFilterList() = getFilters()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val result = response.parseAs<SearchDto>()

        val entries = result.searchResult?.map {
            SAnime.create().apply {
                url = it.id
                title = it.title
                thumbnail_url = idToThumbnailUrl(it.id)
            }
        } ?: emptyList()

        return AnimesPage(entries, false)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val url = "$baseUrl/post.php?id=${anime.url}&t=${System.currentTimeMillis()}"

        return GET(url, xhrHeaders)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val result = response.parseAs<DetailsDto>()
        val id = response.request.url.queryParameter("id")!!

        return SAnime.create().apply {
            title = result.title
            url = id
            thumbnail_url = idToThumbnailUrl(id)
            genre = "${result.genre}, ${result.cast}"
            author = result.creator
            artist = result.director
            description = result.desc
            if (!result.lang.isNullOrEmpty()) {
                description += "\n\nAvailable Language(s): ${result.lang.joinToString { it.language }}"
            }
            status = result.status
        }
    }

    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val result = response.parseAs<EpisodesDto>()
        val id = response.request.url.queryParameter("id")!!

        if (result.episodes?.firstOrNull() == null) {
            return SEpisode.create().apply {
                name = "Movie"
                url = EpisodeUrl(id, result.title).let(json::encodeToString)
            }.let(::listOf)
        }

        val episodes = result.episodes.mapNotNull {
            if (it == null) return@mapNotNull null

            it.toSEpisode(result.title)
        }.toMutableList()

        result.season?.reversed()?.drop(1)?.forEach { season ->
            val seasonRequest = GET("$baseUrl/episodes.php?s=${season.id}&series=$id&t=${System.currentTimeMillis()}", xhrHeaders)
            val seasonResponse = client.newCall(seasonRequest).execute().parseAs<SeasonEpisodesDto>()

            episodes.addAll(
                index = 0,
                elements = seasonResponse.episodes?.map {
                    it.toSEpisode(result.title)
                } ?: emptyList(),
            )
        }

        return episodes.reversed()
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodeUrl = episode.url.parseAs<EpisodeUrl>()

        val url = "$baseUrl/playlist.php".toHttpUrl().newBuilder().apply {
            addQueryParameter("id", episodeUrl.id)
            addQueryParameter("t", episodeUrl.title)
            addQueryParameter("tm", System.currentTimeMillis().toString())
        }.build().toString()

        return GET(url, xhrHeaders)
    }

    override fun videoListParse(response: Response): List<Video> {
        val result = response.parseAs<VideoList>()

        val masterPlayList = result
            .firstOrNull()
            ?.sources
            ?.firstOrNull()
            ?.file
            ?.let { baseUrl + it }
            ?.toHttpUrlOrNull()
            ?.newBuilder()
            ?.removeAllQueryParameters("q")
            ?.build()
            ?.toString()
            ?: return emptyList()

        return playListUtils.extractFromHls(masterPlayList)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY
            title = PREF_QUALITY_TITLE
            entries = arrayOf("720p", "480p", "360p")
            entryValues = arrayOf("720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    private inline fun <reified T> String.parseAs(): T =
        json.decodeFromString(this)

    private inline fun <reified T> Response.parseAs(): T =
        use { it.body.string() }.parseAs()

    companion object {
        private const val PREF_QUALITY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720"

        private fun idToThumbnailUrl(id: String) = "https://img.netflixmirror.com/poster/v/$id.jpg"
    }

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException("Not used")
}
