package eu.kanade.tachiyomi.animeextension.ar.animeblkom

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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimeBlkom : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "أنمي بالكوم"

    override val baseUrl = "https://animeblkom.net"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://animeblkom.net")
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.contents div.content div.content-inner div.poster a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + element.select("img").attr("data-original")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("img").attr("alt").removePrefix(" poster")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        if (document.selectFirst(episodeListSelector()) == null) {
            return oneEpisodeParse(document)
        }
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    private fun oneEpisodeParse(document: Document): List<SEpisode> {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(document.location())
        episode.episode_number = 1F
        episode.name = document.selectFirst("div.name.col-xs-12 span h1").text()
        return listOf(episode)
    }

    override fun episodeListSelector() = "ul.episodes-links li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("span:nth-child(3)").text())
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        // episode.episode_number = element.select("span:nth-child(3)").text().replace(" - ", "").toFloat()
        episode.name = element.select("span:nth-child(3)").text() + " :" + element.select("span:nth-child(1)").text()

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe").attr("src")
        val referer = response.request.url.encodedPath
        val newHeaders = Headers.headersOf("referer", baseUrl + referer)
        val iframeResponse = client.newCall(GET(iframe, newHeaders))
            .execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        return Video(element.attr("src").replace("watch", "download"), element.attr("res") + "p", element.attr("src").replace("watch", "download"))
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

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = baseUrl + element.select("img").first().attr("data-original")
        anime.title = element.select("img").attr("alt").replace(" poster", "")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "div.contents div.content div.content-inner div.poster a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search?query=$query&page=$page"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is TypeList -> {
                        if (filter.state > 0) {
                            val GenreN = getTypeList()[filter.state].query
                            val genreUrl = "$baseUrl/$GenreN?page=$page".toHttpUrlOrNull()!!.newBuilder()
                            return GET(genreUrl.toString(), headers)
                        }
                    }
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.select("div.poster img").attr("data-original")
        anime.title = document.select("div.name span h1").text()
        anime.genre = document.select("p.genres a").joinToString(", ") { it.text() }
        anime.description = document.select("div.story p, div.story").text()
        anime.author = document.select("div:contains(الاستديو) span > a").text()
        document.select("span.info")?.text()?.also { statusText ->
            when {
                statusText.contains("مستمر", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }
        anime.artist = document.select("div:contains(المخرج) > span.info").text()
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filter

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeList(typesName),
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("نوع الأنمي", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        Type("قائمة الأنمي", "anime-list"),
        Type(" قائمة المسلسلات ", "series-list"),
        Type(" قائمة الأفلام ", "movie-list"),
        Type(" قائمة الأوفا ", "ova-list"),
        Type(" قائمة الأونا ", "ona-list"),
        Type(" قائمة الحلقات خاصة ", "special-list")
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
