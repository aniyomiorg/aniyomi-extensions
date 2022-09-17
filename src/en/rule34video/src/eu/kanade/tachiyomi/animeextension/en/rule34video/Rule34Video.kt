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

    override val baseUrl = "https://rule34video.com/"

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

        val videoLinks = document.select("div.video_tools div:nth-child(3) div a.tag_item")
            .map {
                it.attr("href") + it.text()
                    .replace("MP4", "")
            }

        val videoList = mutableListOf<Video>()
        for (video in videoLinks) {
            videoList.add(Video(video.split(" ")[0], video.split(" ")[1], video.split(" ")[0]))
        }

        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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

    // Search

    private var cat = false

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sortedBy = filters.find { it is OrderFilter } as OrderFilter
        val catBy = filters.find { it is CategoryBy } as CategoryBy
        val newSort = when (sortedBy.toUriPart()) {
            "latest-updates" -> "post_date"

            "most-popular" -> "video_viewed"

            "top-rated" -> "rating"
            else -> ""
        }
        val tagFilter = try {
            verifyTag = true
            (filters.find { it is TagFilter } as TagFilter).state
        } catch (e: Exception) {
            verifyTag = false
            ""
        }

        tagDocument = if (tagFilter.isNotBlank()) client.newCall(GET("$baseUrl/search_ajax.php?tag=$tagFilter", headers)).execute().asJsoup() else Document("")

        val tagSearch = try {
            filters.find { it is TagSearch } as TagSearch
        } catch (e: Exception) {
            TagSearch(arrayOf()).apply { state = 0 }
        }

        return when {
            query.isNotEmpty() -> {
                GET("$baseUrl/search/$query/?flag1=${catBy.toUriPart()}&sort_by=$newSort&from_videos=$page", headers) // with search
            }
            tagSearch.state != 0 -> GET("$baseUrl/search/?tag_ids=all,${tagSearch.toUriPart()}&sort_by=$newSort&from_videos=$page") // with tag search
            sortedBy.state != 0 || catBy.state != 0 -> {
                GET("$baseUrl/search/?flag1=${catBy.toUriPart()}&sort_by=${sortedBy.toUriPart()}&from_videos=$page", headers) // with sort and category
            }
            else -> {
                GET("$baseUrl/latest-updates/$page/", headers) // without search
            }
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
            "Duration : ${document.select("#tab_video_info div.info.row div:nth-child(3) span").text()}\n"
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
            entryValues = arrayOf("2160p", "1080p", "720p", "480p", "360p")
            setDefaultValue("1080p")
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

    // Filters
    private var verifyTag = false
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
        AnimeFilter.Header("Tags Search (Experimental)"),
        AnimeFilter.Header("Click in \"filter\" to make te search and click in \"reset\" to see the results."),
        TagFilter(),
        if (verifyTag) TagSearch(tagsResults(tagDocument)) else AnimeFilter.Separator(),
    )

    private class TagFilter : AnimeFilter.Text("Tag", "")

    private class TagSearch(results: Array<Pair<String, String>>) : UriPartFilter(
        "Category Filter",
        results
    )

    private class CategoryBy : UriPartFilter(
        "Category Filter",
        arrayOf(
            Pair("All", ""),
            Pair("Futa", "15"),
            Pair("Gay", "192"),
        )
    )

    private class OrderFilter : UriPartFilter(
        "Order",
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
