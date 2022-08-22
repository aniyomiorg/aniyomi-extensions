package eu.kanade.tachiyomi.animeextension.ar.movizland

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
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Movizland : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "موفيزلاند"

    override val baseUrl = "https://new.movizland.cyou/"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.BoxOfficeOtherSide div.BlocksUI div.BlockItem"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("a div.BlockImageItem img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a div.BlockImageItem img").attr("alt").replace("فيلم", "Movie").replace("مسلسل", "Series").replace("[^A-Za-z ]".toRegex(), "").trim()
        if (anime.title.contains("Movie"))
            anime.title = anime.title.replace("Movie ", "") + " (Movie)"
        else
            anime.title = anime.title.replace("Series ", "") + " (Series)"
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination div.navigation ul li.active + li a"

    // episodes
    private fun seasonsNextPageSelector() = "div.BlockItem a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisode(document: Document) {
            document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
        }
        fun addEpisodes(document: Document) {
            // Add all season in search
            if (!document.select("div.SeriesSingle div.container h2:contains(موفيز لاند) a").isNullOrEmpty()) {
                val seriesLink = document.select("div.SeriesSingle div.container h2:contains(موفيز لاند) a")
                addEpisodes(client.newCall(GET(seriesLink.attr("href"), headers)).execute().asJsoup())
                return
            }
            if (document.select("link[rel=canonical]").attr("href").contains("series")) {
                // Series
                for (season in document.select(seasonsNextPageSelector())) {
                    season.let {
                        val link = it.attr("href")
                        // More than 1 season
                        if (link.contains("series")) {
                            val seasonHTML = client.newCall(GET(link, headers)).execute().asJsoup()
                            for (episode in seasonHTML.select(seasonsNextPageSelector())) {
                                episode.run {
                                    addEpisode(client.newCall(GET(this.attr("href"), headers)).execute().asJsoup())
                                }
                            }
                        } else {
                            // 1 season only
                            addEpisode(client.newCall(GET(it.attr("href"), headers)).execute().asJsoup())
                        }
                    }
                }
            } else {
                // Movies
                addEpisode(document)
            }
        }
        addEpisodes(response.asJsoup())
        return episodes.reversed()
    }

    override fun episodeListSelector() = "link[rel=canonical]"

    override fun episodeFromElement(element: Element): SEpisode {
        // movie
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.ownerDocument().select("meta[property=og:title]").attr("content").replace("مسلسل", "").replace("انمي", "").replace("فيلم", "").replace("مشاهدة", "").replace("وتحميل", "").replace("مترجمة", "").replace("مترجم", "").replace("[A-Za-z:?]".toRegex(), "").trim()

        return episode
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.select("code#EmbedScmain").select("iframe").attr("data-srcout")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "body"

    private fun videosFromElement(element: Element): List<Video> {
        val videoList = mutableListOf<Video>()
        val qualityMap = mapOf("l" to "240p", "n" to "360p", "h" to "480p", "x" to "720p", "o" to "1080p")
        val script = element.select("script[type='text/javascript']")
            .firstOrNull { it.data().contains("jwplayer(\"vplayer\").setup({") }
        val data = element.data().substringAfter(", file: \"").substringBefore("\"}],")
        val sources = data.split(",l,n,h,x,o,.urlset/master")
        val qualities = listOf("l", "n", "h", "x", "o")
        for (q in qualities) {
            val src = sources[0] + q + "/index-v1-a1" + sources[1]
            val video = qualityMap[q]?.let { Video(src, it, src) }
            if (video != null) {
                videoList.add(video)
            }
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

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("a div.BlockImageItem img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a div.BlockImageItem div.BlockTitle").text().replace("فيلم", "Movie").replace("مسلسل", "Series").replace("[^A-Za-z ]".toRegex(), "").trim()
        if (anime.title.contains("Movie"))
            anime.title = anime.title.replace("Movie ", "") + " (Movie)"
        else
            anime.title = anime.title.replace("Series ", "") + " (Series)"
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination div.navigation ul li.active + li a"

    override fun searchAnimeSelector(): String = "div.BlocksInner div.BlocksUI div.BlockItem, div.BoxOfficeOtherSide div.BlocksUI div.BlockItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            val url = "$baseUrl/page/$page"
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/category/$catQ/page/$page/"
                            return GET(catUrl, headers)
                        }
                    }
                }
            }
            return GET(url, headers)
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // div.CoverSingle div.CoverSingleContent
        anime.genre = document.select("div.SingleDetails li:contains(النوع) a").joinToString(", ") { it.text() }
        anime.title = document.select("meta[property=og:title]").attr("content").replace("فيلم", "Movie").replace("مسلسل", "Series").replace("[^A-Za-z ]".toRegex(), "").trim()
        if (anime.title.contains("Movie"))
            anime.title = anime.title.replace("Movie ", "") + " (Movie)"
        else
            anime.title = anime.title.replace("Series ", "") + " (Series)"
        anime.author = document.select("div.SingleDetails li:contains(دولة) a").text()
        anime.description = document.select("div.ServersEmbeds section.story").text().replace(document.select("meta[property=og:title]").attr("content"), "").replace(":", "").trim()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)
    private data class CatUnit(val name: String, val query: String)
    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("All Movies", "movies"),
        CatUnit("English Movies", "movies/foreign"),
        CatUnit("Netflix Movies", "movies/netflix"),
        CatUnit("Animation Movies", "movies/anime"),
        CatUnit("Asian Movies", "movies/asia"),
        CatUnit("Indian Movies", "movies/india"),
        CatUnit("Arabic Movies", "movies/arab"),
        CatUnit("Turkish Movies", "movies/turkey"),
        CatUnit("All Series", "series"),
        CatUnit("English Series", "series/foreign-series"),
        CatUnit("Netflix Series", "series/netflix-series"),
        CatUnit("Animation Series", "series/anime-series"),
        CatUnit("Turkish Series", "series/turkish-series"),
        CatUnit("Arabic Series", "series/arab-series")
    )

    // preferred quality settings

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
