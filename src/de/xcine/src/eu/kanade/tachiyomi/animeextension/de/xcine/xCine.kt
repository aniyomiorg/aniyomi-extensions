package eu.kanade.tachiyomi.animeextension.de.xcine

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.xcine.extractors.HDFilmExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class xCine : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "xCine"

    override val baseUrl = "https://xcine.me"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add("cookie", "PHPSESSID=2d88418b469e522a75dd4c8327c3a936; SERVERID=s2; _ga=GA1.2.826139063.1658618592; _gid=GA1.2.162365688.1658618592; _gat_gtag_UA_144665518_1=1; __gads=ID=4fe53672dd9aeab8-22d4864165d40078:T=1658618593:RT=1658618593:S=ALNI_MYrmBvhl86smHeaNwW8I5UMS2ZOlA; _pop=1; dom3ic8zudi28v8lr6fgphwffqoz0j6c=3f6f53a9-7930-4d7a-87b1-c67125d128e0%3A2%3A1; m5a4xojbcp2nx3gptmm633qal3gzmadn=hopefullyapricot.com; _TqHT6=_0xc53")

    override fun popularAnimeSelector(): String = "div.group-film-small a"

    override fun popularAnimeRequest(page: Int): Request = POST(
        "$baseUrl/filme1?page=$page&sort=view_total&sort_type=desc", body = "load=full-page".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
        headers = headersBuilder().build()
    )

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val urlpart = element.select("div.poster-film-small").toString()
            .substringAfter("data-src=\"https://cdn.xcine.me/img/").substringBefore("\"")
        anime.thumbnail_url = "https://cdn.xcine.me/img/$urlpart"
        anime.title = element.attr("title").replace(" stream", "")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = ".pag-next a[title=Next]"

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        if (document.select("#breadcrumbDiv a[title=\"Serien stream\"]").attr("href").contains("/serien1")) {
            val seriesLink = document.select("link[rel=canonical]").attr("abs:href")
            val seasonHtml = client.newCall(GET("$seriesLink/folge-1", headers = Headers.headersOf("Referer", document.location(), headersBuilder().build().toString())))
                .execute().asJsoup()
            val episodeElement = seasonHtml.select("ul.list-inline.list-film li")
            episodeElement.forEach {
                val episode = parseEpisodesFromSeries(it)
                episodeList.addAll(episode)
            }
        } else {
            val episode = SEpisode.create()
            episode.name = document.select("h1.title-film-detail-1").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val episodeElements = element.select("a")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.text().substringAfter("-").toFloat()
        episode.name = element.attr("title").replace("stream", "")
        episode.setUrlWithoutDomain(element.attr("href"))
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val videos = HDFilmExtractor(client).videosFromUrl(document)
        videoList.addAll(videos)
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        if (hoster != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(hoster)) {
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

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val urlpart = element.select("div.poster-film-small").toString()
            .substringAfter("data-src=\"https://cdn.xcine.me/img/").substringBefore("\"")
        anime.thumbnail_url = "https://cdn.xcine.me/img/$urlpart"
        anime.title = element.attr("title").replace(" stream", "")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = ".pag-next a[title=Next]"

    override fun searchAnimeSelector(): String = "div.group-film-small a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return POST(
            "$baseUrl/search?page=$page&key=$query", body = "load=full-page".toRequestBody("application/x-www-form-urlencoded; charset=UTF-8".toMediaType()),
            headers = headersBuilder().build())
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = anime.thumbnail_url
        anime.title = document.select("h1.title-film-detail-1").text()
        anime.genre = document.select("ul[class=infomation-film]").joinToString(", ") {
            it.toString()
                .substringAfter("Genre:").replace("<span>", "").substringBefore("</span>")
        }
        anime.description = document.select("p[class=content-film]").text()
        anime.author = document.select("ul[class=infomation-film]").joinToString(", ") {
            it.toString()
                .substringAfter("Regisseur:").replace("<span>", "").substringBefore("</span>")
        }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("2k", "1080p", "720p", "480p", "360p")
            entryValues = arrayOf("2", "1080", "720", "480", "360")
            setDefaultValue("2")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(hosterPref)
    }
}
