package eu.kanade.tachiyomi.animeextension.en.allmovies

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AllMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AllMovies"

    override val baseUrl = "https://allmoviesforyou.net"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.TPost > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("h2.Title").text()
        anime.thumbnail_url = "https:" + element.select("div.Image figure img").attr("data-src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nav-links a:last-child"

    // Episodes

    override fun episodeListSelector() = "link[rel=canonical]"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain("allmoviesforyou.net" + element.attr("href"))
        Log.i("episodddd", episode.url)
        episode.name = element.ownerDocument().select("div.TPMvCn h1.Title").text()
        return episode
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        Log.i("iframe1", document.select("iframe[data-src^=\"https://allmovies\"]").attr("data-src"))
        val iframe1 = client.newCall(GET(document.select("iframe[data-src^=\"https://allmovies\"]").attr("data-src"))).execute().asJsoup()
        val iframe = iframe1.select("iframe").attr("data-src") // [data-src^="https://stream"]
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "script:containsData(sources)"

    private fun videosFromElement(element: Element): List<Video> {
        val data = element.data().substringAfter("sources: [").substringBefore("],")
        val sources = data.split("src: \"").drop(1)
        val videoList = mutableListOf<Video>()
        for (source in sources) {
            val masterUrl = source.substringBefore("\"")
            val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
            val videoList = mutableListOf<Video>()
            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
                Log.i("bruhqual", quality)
                val videoUrl = it.substringAfter("\n").substringBefore("\n")
                Log.i("bruhvid", videoUrl)
                videoList.add(Video(videoUrl, quality, videoUrl, null))
            }
            return videoList
        }
        return videoList
    }

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

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("article a").attr("href"))
        anime.title = element.select("h2.Title").text()
        anime.thumbnail_url = "https:" + element.select("div.Image figure img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.nav-links a:last-child"

    override fun searchAnimeSelector(): String = "ul.MovieList li"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/category/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {

                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
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
        anime.title = document.select("h1.Title").text()
        anime.genre = document.select("p.Genre a").joinToString(", ") { it.text() }
        document.select("div.Info > span.Qlty").text().let {
            if (it.contains("ON AIR")) anime.status = SAnime.ONGOING else anime.status = SAnime.COMPLETED
        }
        anime.description = document.select("div.Description p:first-child").text()
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList())
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("تصنيف المسلسلات", vals)

    private fun getGenreList() = arrayOf(
        Pair("Action & Adventure", "action-adventure"),
        Pair("Adventure", "aventure"),
        Pair("Animation", "animation"),
        Pair("Comedy", "comedy"),
        Pair("Crime", "crime"),
        Pair("Disney", "disney"),
        Pair("Drama", "drama"),
        Pair("Family", "family"),
        Pair("Fantasy", "fantasy"),
        Pair("History", "fistory"),
        Pair("Horror", "horror"),
        Pair("Kids", "kids"),
        Pair("Music", "music"),
        Pair("Mystery", "mystery"),
        Pair("Reality", "reality"),
        Pair("Romance", "romance"),
        Pair("Sci-Fi & Fantasy", "sci-fi-fantasy"),
        Pair("Science Fiction", "science-fiction"),
        Pair("Thriller", "thriller"),
        Pair("War", "war"),
        Pair("War & Politics", "war-politics"),
        Pair("Western", "western")
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
