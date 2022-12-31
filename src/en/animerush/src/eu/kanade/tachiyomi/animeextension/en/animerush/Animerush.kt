package eu.kanade.tachiyomi.animeextension.en.animerush

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import android.text.Html
import androidx.annotation.RequiresApi
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
import kotlin.Exception

class Animerush : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animerush"

    override val baseUrl = "https://www.animerush.tv"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div#popular ul li a"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("img").attr("title")
        anime.thumbnail_url = baseUrl + element.select("img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val epElements = document.select("div.desc_box_mid div.episode_list")
        epElements.forEach {
            val episode = episodeFromElement(it)
            episodeList.add(episode)
        }
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.select("a.fixedLinkColor").attr("title").substringAfter("Episode ").toFloat()
        episode.name = element.select("a.fixedLinkColor").attr("title")
        episode.setUrlWithoutDomain(element.select("a.fixedLinkColor").attr("href"))
        return episode
    }

    // Video urls

    @RequiresApi(Build.VERSION_CODES.N)
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val episodes = document.select("div#episodes div.episode1")
        episodes.forEach {
            val quality = it.select("h3 a").text()
            val epurl = "https:" + it.select("h3 a").attr("href")
            val epdoc = client.newCall(GET(epurl)).execute().asJsoup()
            val urlenc = epdoc.select("div#embed_holder iframe").attr("src")
            val urldec = Html.fromHtml(urlenc, 1).toString()
            val emdoc = client.newCall(GET(urldec)).execute().asJsoup().toString()
            val script = emdoc.substringAfter("eval(function").substringBefore("</script>")
            val videoUrlstring = JsUnpacker("eval(function$script").unpack().toString()
            when {

                videoUrlstring.contains("player.src") -> {
                    val videoUrl = videoUrlstring.substringAfter("player.src(\"").substringBefore("\")")
                    val videoHeaders = Headers.headersOf("Referer", "https://www.mp4upload.com/", "Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                    val video = Video(videoUrl, quality, videoUrl, headers = videoHeaders)
                    videoList.add(video)
                }
                videoUrlstring.contains("MDCore.wurl") -> {
                    val videoUrl = "https:" + videoUrlstring.substringAfter("MDCore.wurl=\"").substringBefore("\";")
                    val videoHeaders = Headers.headersOf("Referer", "https://mixdrop.co/", "Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5", "origin", "https://mixdrop.co")
                    val video = Video(videoUrl, quality, videoUrl, headers = videoHeaders)
                    videoList.add(video)
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not Used")

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
        anime.setUrlWithoutDomain(element.select("a.highlightit, div.genre_title a").attr("href"))
        anime.title = element.select("a.highlightit h3, div.genre_title a").text()
        anime.thumbnail_url = "https:" + element.select("a.highlightit object, object").attr("data")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = "div#left-column div.search-page_in_box_mid_link"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/search.php?searchquery=$query")
        } else {
            val url = "$baseUrl/genres/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            GET(url.toString())
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.amin_week_box_up1 h1").text()
        anime.genre = document.select("div.cat_box_desc a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("div.cat_box_desc ").toString().substringAfter("Status:</h3>").substringBefore("<br>")) // span.Qlty
        anime.description = document.select("div.cat_box_desc div[align]").text()
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("On-Going", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
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
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Comedy", "Comedy"),
        Pair("Crime", "Crime"),
        Pair("Drama", "Drama"),
        Pair("Family", "Family"),
        Pair("Fantasy", "Fantasy"),
        Pair("History", "History"),
        Pair("Horror", "Horror"),
        Pair("Kids", "Kids"),
        Pair("Music", "Music"),
        Pair("Mystery", "Mystery"),
        Pair("Romance", "Romance"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Science Fiction", "Science Fiction"),
        Pair("Thriller", "Thriller"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {}
}
