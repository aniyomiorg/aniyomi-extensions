package eu.kanade.tachiyomi.animeextension.en.pactedanime

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
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class pactedanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "pactedanime"

    override val baseUrl = "https://pactedanime.com"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.content > div.items > article.item"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/trending-2/page/$page/")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("div h3 a").attr("href").toHttpUrl().encodedPath)
        anime.title = element.select("div h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("src")
        if (anime.thumbnail_url.toString().isEmpty()) {
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        }

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination span.current ~ a"

    // Episodes

    override fun episodeListSelector() = throw Exception("Not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        if (response.request.url.encodedPath.startsWith("/movies/")) {
            val episode = SEpisode.create()

            episode.name = document.select("div.data > h1").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.encodedPath)
            episodeList.add(episode)
        } else {
            var counter = 1
            for (season in document.select("div#seasons > div")) {
                for (ep in season.select("ul > li")) {
                    if (ep.childrenSize() > 0) {
                        val episode = SEpisode.create()

                        episode.name = "Season ${ep.select("div.numerando").text()} - ${ep.select("div.episodiotitle a").text()}"
                        episode.episode_number = counter.toFloat()
                        episode.setUrlWithoutDomain(ep.select("div.episodiotitle a").attr("href").toHttpUrl().encodedPath)
                        episodeList.add(episode)

                        counter++
                    }
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        for (video in document.select("div#playcontainer > div > div")) {
            if (!video.getElementsByTag("video").isEmpty()) {
                videoList.add(
                    Video(
                        video.select("source").attr("src"),
                        video.select("source").attr("label"),
                        video.select("source").attr("src")
                    )
                )
            }
        }

        return videoList.sort()
    }

    override fun videoListSelector() = throw Exception("Not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
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

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    // search

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = if (response.request.url.encodedPath.startsWith("/genre/")) {
            document.select(searchGenreAnimeSelector()).map { element ->
                searchGenreAnimeFromElement(element)
            }
        } else {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeSelector(): String = "div.search-page > div.result-item"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("div.details > div.title a").attr("href").toHttpUrl().encodedPath)
        anime.title = element.select("div.details > div.title a").text()
        anime.thumbnail_url = element.select("div.image img").attr("src")
        if (anime.thumbnail_url.toString().isEmpty()) {
            anime.thumbnail_url = element.select("div.image img").attr("data-src")
        }

        return anime
    }

    private fun searchGenreAnimeSelector(): String = "div.items > article.item"

    private fun searchGenreAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("div.data h3 a").attr("href").toHttpUrl().encodedPath)
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("src")
        if (anime.thumbnail_url.toString().isEmpty()) {
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        }

        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination span.current ~ a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            val url = "$baseUrl/genre/".toHttpUrl().newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.sheader > div.data > h1").text()
        anime.genre = document.select("div.sgeneros a").eachText().joinToString(separator = ", ")
        anime.description = document.selectFirst("div#info p").text()
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList())
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Genres", vals)

    private fun getGenreList() = arrayOf(
        Pair("Action & Adventure", "action-adventure"),
        Pair("Animation", "animation"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Drama", "drama"),
        Pair("Ecchi", "ecchi"),
        Pair("Family", "family"),
        Pair("Fantasy", "fantasy"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Romance", "romance"),
        Pair("Thriller", "thriller"),
        Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Pair("War & Politics", "war-politics"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
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
