package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.app.Application
import android.content.SharedPreferences
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Rule34Video : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Rule34Video"

    override val baseUrl = "https://rule34video.com"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Videos

    override fun popularAnimeSelector(): String = "div.item.thumb"

    override fun popularAnimeRequest(page: Int): Request =

        GET("$baseUrl/latest-updates/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.th").attr("href"))
        anime.title = element.select("a.th div.thumb_title").text()
        anime.thumbnail_url = element.select("a.th div.img img").attr("data-original")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.item.pager.next a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val episode = SEpisode.create().apply {
            name = "Video"
            date_upload = System.currentTimeMillis()
        }
        episode.setUrlWithoutDomain(response.request.url.toString())
        episodes.add(episode)

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("div.video_tools div:nth-child(3) div a.tag_item")
            .map { element ->
                val url = element.attr("href")
                val quality = element.text().substringAfter(" ")
                Video(url, quality, url)
            }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p") ?: return this
        return this.sortedWith(compareByDescending { it.quality == quality })
    }

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val orderFilter = filters.find { it is OrderFilter } as OrderFilter
        val categoryFilter = filters.find { it is CategoryBy } as CategoryBy
        val sortType = when (orderFilter.toUriPart()) {
            "latest-updates" -> "post_date"
            "most-popular" -> "video_viewed"
            "top-rated" -> "rating"
            else -> ""
        }

        val tagFilter: String = if (filters.find { it is TagFilter } is TagFilter) {
            (filters.find { it is TagFilter } as TagFilter).state
        } else {
            ""
        }

        val url = "$baseUrl/search_ajax.php?tag=${tagFilter.ifBlank { "." }}"
        val response = client.newCall(GET(url, headers)).execute()
        tagDocument = response.asJsoup()

        val tagSearch = try {
            filters.find { it is TagSearch } as TagSearch
        } catch (e: Exception) {
            TagSearch(arrayOf()).apply { state = 0 }
        }

        return if (query.isNotEmpty()) {
            GET("$baseUrl/search/$query/?flag1=${categoryFilter.toUriPart()}&sort_by=$sortType&from_videos=$page&tag_ids=all%2C${tagSearch.toUriPart()}")
        } else {
            GET("$baseUrl/search/?flag1=${categoryFilter.toUriPart()}&sort_by=$sortType&from_videos=$page&tag_ids=all%2C${tagSearch.toUriPart()}")
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = "div.item.pager.next a"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.title_video").text()
        anime.author = document.select("#tab_video_info div:nth-child(3) div div:nth-child(2) a").joinToString { it.text() }
        anime.description = document.select("#tab_video_info div:nth-child(2) div em").text() +
            "\n\nViews : ${document.select("#tab_video_info div.info.row div:nth-child(2) span").text().replace((" "), ",")}\n" +
            "Duration : ${document.select("#tab_video_info div.info.row div:nth-child(3) span").text()}\n" +
            "Quality : ${document.select("div.video_tools div:nth-child(3) div a.tag_item").joinToString { it.text().substringAfter(" ") }}\n"
        anime.genre = document.select("div.video_tools div:nth-child(4) div a").joinToString {
            if (it.text() != "+ | Suggest") it.text() else ""
        }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("2160p", "1080p", "720p", "480p", "360p")
            entryValues = entries
            setDefaultValue("1080p")
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putString(key, newValue as String).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // Filters
    private var tagDocument = Document("")

    private fun tagsResults(document: Document): Array<Pair<String, String>> {
        val tagList = mutableListOf(Pair("<Select>", ""))
        tagList.addAll(
            document.select("div.item").map {
                val tagValue = it.select("input").attr("value")
                val tagName = it.select("label").text()
                Pair(tagName, tagValue)
            }
        )
        return tagList.toTypedArray()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        OrderFilter(),
        CategoryBy(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Entered a \"tag\", click on \"filter\" then Click \"reset\" to load tags."),
        TagFilter(),
        TagSearch(tagsResults(tagDocument))
    )

    private class TagFilter : AnimeFilter.Text("Click \"reset\" without any text to load all A-Z tags.", "")

    private class TagSearch(results: Array<Pair<String, String>>) : UriPartFilter(
        "Tag Filter ",
        results
    )

    private class CategoryBy : UriPartFilter(
        "Category Filter ",
        arrayOf(
            Pair("All", ""),
            Pair("Futa", "15"),
            Pair("Gay", "192"),
        )
    )

    private class OrderFilter : UriPartFilter(
        "Sort By ",
        arrayOf(
            Pair("Latest", "latest-updates"),
            Pair("Most Viewed", "most-popular"),
            Pair("Top Rated", "top-rated"),
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
