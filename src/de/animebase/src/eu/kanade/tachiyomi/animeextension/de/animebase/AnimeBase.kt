package eu.kanade.tachiyomi.animeextension.de.animebase

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
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

class AnimeBase : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime-Base"

    override val baseUrl = "https://anime-base.net"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.table-responsive a"

    override fun popularAnimeRequest(page: Int): Request {
        val cookieInterceptor = client.newBuilder().addInterceptor(CookieInterceptor(baseUrl)).build()
        val headers = cookieInterceptor.newCall(GET(baseUrl)).execute().request.headers
        return GET("$baseUrl/favorites", headers = headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("div.thumbnail img").attr("src")
        anime.title = element.select("div.thumbnail div.caption h3").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episodeElement = document.select(
            "div.tab-content #gersub div.panel, div.tab-content #filme div.panel button[${
            if (document.select("div.tab-content #filme div.panel button[data-dubbed=\"0\"]").isNullOrEmpty()){
                "data-dubbed=\"1\""
            } else {
                "data-dubbed=\"0\""
            }
            }][data-hoster=\"1\"], div.tab-content #specials div.panel button[data-dubbed=\"0\"][data-hoster=\"1\"]"
        )
        episodeElement.forEach {
            val episode = episodeFromElement(it)
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val id = element.select("button[data-hoster=\"1\"]").attr("data-serieid")
        val epnum = element.select("button[data-hoster=\"1\"]").attr("data-folge")
        val host = element.select("button[data-hoster=\"1\"]").attr("data-hoster")
        if (element.attr("data-dubbed").contains("1")) {
            if (element.attr("data-special").contains("2")) {
                episode.episode_number = 1F
                episode.name = "Film $epnum"
                episode.setUrlWithoutDomain("/episode/$id/$epnum/1/$host/2")
            }
        } else {
            if (element.select("button[data-hoster=\"1\"]").attr("data-special").contains("2")) {
                episode.episode_number = 1F
                episode.name = "Film ${epnum.toInt() - 1}"
                episode.setUrlWithoutDomain("/episode/$id/$epnum/0/$host/2")
            } else {
                val season = element.select("button[data-hoster=\"1\"]").attr("data-embedcontainer")
                    .substringAfter("-").substringBefore("-")
                episode.name = "Staffel $season Folge $epnum : " + element.select("h3.panel-title").text()
                    .substringAfter(": ")
                    .replace("<span title=\"", "").replace("<span class=\"label label-danger\">Filler!</span>", "").replace("&nbsp;", "")
                episode.episode_number = element.select("button[data-hoster=\"1\"]").attr("data-folge").toFloat()
                episode.setUrlWithoutDomain("/episode/$id/$epnum/0/$host/0")
            }
            if (element.select("button[data-hoster=\"1\"]").attr("data-special").contains("1")) {
                episode.episode_number = 1F
                episode.name = "Special ${epnum.toInt() - 1}"
                episode.setUrlWithoutDomain("/episode/$id/$epnum/0/$host/1")
            }
        }
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        return videosFromElement(response)
    }

    private fun videosFromElement(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val epurl = response.request.url.toString()
        val epid = epurl.substringAfter("episode/").substringBefore("/")
        val epnum = epurl.substringAfter("$epid/").substringBefore("/")
        if (epurl.substringAfter("$epnum/").substringBefore("/").contains("0")) {
            val host = epurl.substringAfter("$epnum/0/").substringBefore("/")
            try {
                if (!epurl.substringAfter("$host/").contains("2")) {
                    try {
                        val subdoc = client.newCall(GET("$baseUrl/episode/$epid/$epnum/0/$host/0")).execute().asJsoup()
                        val sublink = subdoc.toString()
                            .substringAfter("url=").substringBefore("\"")
                        val domain = sublink.substringAfter("https://").substringBefore("/")
                        val id = sublink.substringAfter("https://$domain/").substringBefore(".html")
                        val url = "https://$domain/e/$id.html"
                        val videos = StreamSBExtractor(client).videosFromUrl(url, headers, suffix = "SUB")
                        videoList.addAll(videos)
                    } catch (e: Exception) {
                    }
                    try {
                        val dubdoc = client.newCall(GET("$baseUrl/episode/$epid/$epnum/1/$host/0")).execute().asJsoup()
                        val dublink = dubdoc.toString()
                            .substringAfter("url=").substringBefore("\"")
                        val dubdomain = dublink.substringAfter("https://").substringBefore("/")
                        val dubid = dublink.substringAfter("https://$dubdomain/").substringBefore(".html")
                        val duburl = "https://$dubdomain/e/$dubid.html"
                        val dubvideos = StreamSBExtractor(client).videosFromUrl(duburl, headers, suffix = "DUB")
                        videoList.addAll(dubvideos)
                    } catch (e: Exception) {
                    }
                } else {
                    try {
                        val subdoc = client.newCall(GET("$baseUrl/episode/$epid/$epnum/0/$host/2")).execute().asJsoup()
                        val sublink = subdoc.toString()
                            .substringAfter("url=").substringBefore("\"")
                        val domain = sublink.substringAfter("https://").substringBefore("/")
                        val id = sublink.substringAfter("https://$domain/").substringBefore(".html")
                        val url = "https://$domain/e/$id.html"
                        val videos = StreamSBExtractor(client).videosFromUrl(url, headers, suffix = "SUB")
                        videoList.addAll(videos)
                        val dubdoc = client.newCall(GET("$baseUrl/episode/$epid/${epnum.toInt() - 1}/1/$host/2")).execute().asJsoup()
                        val dublink = dubdoc.toString()
                            .substringAfter("url=").substringBefore("\"")
                        val dubdomain = dublink.substringAfter("https://").substringBefore("/")
                        val dubid = dublink.substringAfter("https://$dubdomain/").substringBefore(".html")
                        val duburl = "https://$dubdomain/e/$dubid.html"
                        val dubvideos = StreamSBExtractor(client).videosFromUrl(duburl, headers, suffix = "DUB")
                        videoList.addAll(dubvideos)
                    } catch (e: Exception) {
                    }
                }
                if (epurl.substringAfter("$host/").contains("1")) {
                    try {
                        val subdoc = client.newCall(GET("$baseUrl/episode/$epid/$epnum/0/$host/1")).execute().asJsoup()
                        val sublink = subdoc.toString()
                            .substringAfter("url=").substringBefore("\"")
                        val domain = sublink.substringAfter("https://").substringBefore("/")
                        val id = sublink.substringAfter("https://$domain/").substringBefore(".html")
                        val url = "https://$domain/e/$id.html"
                        val videos = StreamSBExtractor(client).videosFromUrl(url, headers, suffix = "SUB")
                        videoList.addAll(videos)
                        val dubdoc = client.newCall(GET("$baseUrl/episode/$epid/${epnum.toInt() - 1}/1/$host/1")).execute().asJsoup()
                        val dublink = dubdoc.toString()
                            .substringAfter("url=").substringBefore("\"")
                        val dubdomain = dublink.substringAfter("https://").substringBefore("/")
                        val dubid = dublink.substringAfter("https://$dubdomain/").substringBefore(".html")
                        val duburl = "https://$dubdomain/e/$dubid.html"
                        val dubvideos = StreamSBExtractor(client).videosFromUrl(duburl, headers, suffix = "DUB")
                        videoList.addAll(dubvideos)
                    } catch (e: Exception) {
                    }
                }
            } catch (e: Exception) {
                throw Exception("To many Requests")
            }
        } else {
            val host = epurl.substringAfter("$epnum/1/").substringBefore("/")
            if (epurl.substringAfter("$host/").contains("2")) {
                val dubdoc = client.newCall(GET("$baseUrl/episode/$epid/${epnum.toInt() - 1}/1/$host/2")).execute().asJsoup()
                val dublink = dubdoc.toString()
                    .substringAfter("url=").substringBefore("\"")
                val dubdomain = dublink.substringAfter("https://").substringBefore("/")
                val dubid = dublink.substringAfter("https://$dubdomain/").substringBefore(".html")
                val duburl = "https://$dubdomain/e/$dubid.html"
                val dubvideos = StreamSBExtractor(client).videosFromUrl(duburl, headers, suffix = "DUB")
                videoList.addAll(dubvideos)
            } else {
                val dubdoc = client.newCall(GET("$baseUrl/episode/$epid/${epnum.toInt() - 1}/1/$host/1")).execute().asJsoup()
                val dublink = dubdoc.toString()
                    .substringAfter("url=").substringBefore("\"")
                val dubdomain = dublink.substringAfter("https://").substringBefore("/")
                val dubid = dublink.substringAfter("https://$dubdomain/").substringBefore(".html")
                val duburl = "https://$dubdomain/e/$dubid.html"
                val dubvideos = StreamSBExtractor(client).videosFromUrl(duburl, headers, suffix = "DUB")
                videoList.addAll(dubvideos)
            }
        }
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_sub", null)
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
        if (!element.text().contains("PainterCrowe")) {
            anime.setUrlWithoutDomain(element.attr("href"))
            anime.thumbnail_url = element.select("div.thumbnail img").attr("src")
            anime.title = element.select("div.caption h3").text()
        } else {
            throw Exception("Keine Ergebnisse gefunden")
        }
        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = "div.col-lg-9.col-md-8 div.box-body a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cookieInterceptor = client.newBuilder().addInterceptor(CookieInterceptor(baseUrl)).build()
        val headers = cookieInterceptor.newCall(GET(baseUrl)).execute().request.headers
        val token = client.newCall(GET("$baseUrl/searching", headers = headers)).execute().asJsoup()
            .select("div.box-body form input[name=\"_token\"]").attr("value")
        return POST("$baseUrl/searching", headers = headers, body = "_token=$token&_token=$token&name_serie=$query&jahr=".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.box-body.box-profile center img").attr("src")
        anime.title = document.select("section.content-header small").text()
        anime.genre = document.select("div.box-body p a span").joinToString(", ") { it.text() }
        anime.description = document.select("div.box-body p.text-muted[style=\"text-align: justify;\"]").toString()
            .substringAfter(";\">").substringBefore("<br")
        anime.status = parseStatus(document.select("div.box-body span.label.label-info").text())
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Laufend", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Standardmäßig Sub oder Dub?"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("SUB", "DUB")
            setDefaultValue("SUB")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(subPref)
    }
}
