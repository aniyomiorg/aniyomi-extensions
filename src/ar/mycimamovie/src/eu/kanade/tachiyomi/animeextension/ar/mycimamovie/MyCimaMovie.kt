package eu.kanade.tachiyomi.animeextension.ar.mycimamovie

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
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class MyCimaMovie : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MY Cima Movies"

    override val baseUrl = "https://mycima.pw"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/افلام-كرتون/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a strong.hasyear").text()
        anime.thumbnail_url =
            element.select("a > span.BG--GridItem")
                .attr("data-lazy-style")
                .substringAfter("-image:url(")
                .substringBefore(");")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    // Episodes

    override fun episodeListSelector() = "div.Poster--Single-begin > a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.ownerDocument().select("div.Title--Content--Single-begin > h1").text() // "movie"
        return episode
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("data-lazy-src")
        val referer = response.request.url.encodedPath
        val newHeaderList = mutableMapOf(Pair("referer", baseUrl + referer))
        headers.forEach { newHeaderList[it.first] = it.second }
        val iframeResponse = client.newCall(GET(iframe, newHeaderList.toHeaders()))
            .execute().asJsoup()
        return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
    }

    override fun videoListSelector() = "body"

    private fun videosFromElement(element: Element): List<Video> {
        val videoList = mutableListOf<Video>()
        val script = element.select("script")
            .firstOrNull { it.data().contains("player.qualityselector({") }
        if (script != null) {
            val scriptV = element.select("script:containsData(source)")
            val data = element.data().substringAfter("sources: [").substringBefore("],")
            val sources = data.split("format: '").drop(1)
            val videoList = mutableListOf<Video>()
            for (source in sources) {
                val src = source.substringAfter("src: \"").substringBefore("\"")
                val quality = source.substringBefore("'") // .substringAfter("format: '")
                val video = Video(src, quality, src, null)
                videoList.add(video)
            }
            return videoList
        }
        val sourceTag = element.ownerDocument().select("source").firstOrNull()!!
        return listOf(Video(sourceTag.attr("src"), "Default", sourceTag.attr("src"), null))
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
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong.hasyear").text()
        anime.thumbnail_url = element.select("a > span.BG--GridItem").attr("data-lazy-style").substringAfter("-image:url(").substringBefore(");")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/$query/page/$page"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].name
                            val catUrl = "$baseUrl/$catQ/page/$page"
                            return GET(catUrl, headers)
                        }
                    }
                }
            }
            throw Exception("Choose a Filters")
        }
        return GET(url, headers)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.Title--Content--Single-begin > h1").text()
        anime.genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.AsideContext > div.StoryMovieContent, div.PostItemContent").text()
        anime.author = document.select("li:contains(شركات الإنتاج) > p > a").joinToString(", ") { it.text() }
        // add alternative name to anime description
        document.select("li:contains( بالعربي) > p, li:contains(معروف) > p").text()?.let {
            if (it.isEmpty().not()) {
                anime.description += when {
                    anime.description!!.isEmpty() -> "Alternative Name: $it"
                    else -> "\n\nAlternativ Name: $it"
                }
            }
        }
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong.hasyear").text()
        anime.thumbnail_url = element.select("a > span").attr("data-lazy-style").substringAfter("-image:url(").substringBefore(");")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/movies/recent/page/$page/")

    override fun latestUpdatesSelector(): String = "div.Grid--MycimaPosts div.GridItem div.Thumb--GridItem"

    // Filters

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)
    private data class CatUnit(val name: String)
    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر"),
        CatUnit("category/افلام-كرتون"),
        CatUnit("movies/best")
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
