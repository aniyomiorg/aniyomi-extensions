package eu.kanade.tachiyomi.animeextension.en.kawaiifu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Kawaiifu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Kawaiifu"

    override val baseUrl = "https://kawaiifu.com"
    private val streamUrl = "https://domdom.stream"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "ul.list-film li"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/category/tv-series/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        element.select("a.mv-namevn").attr("href").toHttpUrlOrNull()?.let {
            anime.setUrlWithoutDomain(
                it.encodedPath
            )
        }
        anime.title = element.select("a.mv-namevn").text()
        anime.thumbnail_url = element.select("a img").attr("src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi a.nextpostslink"

    // Episodes

    override fun episodeListSelector() = "div#1 ul.list-ep li"

    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$streamUrl/anime${anime.url}".removeSuffix(".html"))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select(episodeListSelector()).map { episodeList.add(episodeFromElement(it)) }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()

        episode.episode_number = """(?:Ep )?(\d+)""".toRegex().find(element.select("a").text())?.groups?.get(1)?.value?.toFloat()
            ?: 0F
        episode.name = element.select("a").text()
        episode.setUrlWithoutDomain(element.select("a").attr("href").substringAfter("anime/"))

        return episode
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        return GET("$streamUrl/anime/${episode.url}")
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val serverList = document.select("div#server_ep div").toList()
        val serverName = serverList[0].select("h4").text()
        document.select(videoListSelector()).map {
            videoList.add(
                Video(
                    it.select("source").attr("src"),
                    "${it.select("source").attr("data-quality")}p ($serverName)",
                    it.select("source").attr("src"),
                )
            )
        }

        val activeIndex = serverList[0].select("ul li").indexOfFirst {
            it.select("a").hasClass("active")
        }

        // Loop over rest of sources
        for (server in serverList.subList(1, serverList.size)) {
            val serverUrl = server.select("ul li").toList()[activeIndex].select("a").attr("href")
            val document = client.newCall(GET(serverUrl)).execute().asJsoup()
            val serverName = server.select("h4").text()

            document.select(videoListSelector()).map {
                videoList.add(
                    Video(
                        it.select("source").attr("src"),
                        "${it.select("source").attr("data-quality")}p ($serverName)",
                        it.select("source").attr("src"),
                    )
                )
            }
        }

        return videoList.sort()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)

        val newList = mutableListOf<Video>()
        if (quality != null) {
            var preferred = 0
            for (video in this) {
                if (quality.contains(video.quality)) {
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

    override fun videoListSelector() = "div#video_box div.player video"

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        element.select("a.thumb").attr("href").toHttpUrlOrNull()?.let {
            anime.setUrlWithoutDomain(
                it.encodedPath
            )
        }
        anime.title = element.select("div.info h4 a:last-child").text()
        anime.thumbnail_url = element.select("a img").attr("src")

        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination-content a.next"

    override fun searchAnimeSelector(): String = "div.today-update div.item"

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = KawaiifuFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: KawaiifuFilters.FilterSearchParams): Request {
        return if (query.isEmpty()) {
            GET("$baseUrl/search-movie/page/$page?keyword=&cat-get=${filters.category}${filters.tags}")
        } else {
            GET("$baseUrl/search-movie/page/$page?keyword=$query&cat-get=${filters.category}${filters.tags}")
        }
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET("$streamUrl/anime${anime.url.removeSuffix(".html")}")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()

        anime.genre = document.select("div.sub-desc h3 ~ a").eachText().joinToString(separator = ", ")
        anime.title = document.select("div.desc h2.title").text()
        anime.description = document.select("div.wrap-desc div.sub-desc p").filter {
            it.select("a").isEmpty() && it.select("iframe").isEmpty()
        }.joinToString(separator = "\n\n") { t -> t.text() }

        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Filters

    override fun getFilterList(): AnimeFilterList = KawaiifuFilters.filterList

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
