package eu.kanade.tachiyomi.animeextension.de.aniking

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.aniking.extractors.StreamZExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
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
import kotlin.Exception

class Aniking : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Aniking"

    override val baseUrl = "https://aniking.cc"

    override val lang = "de"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.item-container div.item"

    override fun popularAnimeRequest(page: Int): Request {
        val interceptor = client.newBuilder().addInterceptor(CloudflareInterceptor()).build()
        val headers = interceptor.newCall(GET(baseUrl)).execute().request.headers
        return GET("$baseUrl/page/$page/?order=rating", headers = headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val postid = element.attr("id").replace("post-", "")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a img").attr("data-src")
        anime.title = element.select("a h2").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "footer"

    // episodes

    override fun episodeListRequest(anime: SAnime): Request {
        val interceptor = client.newBuilder().addInterceptor(CloudflareInterceptor()).build()
        val headers = interceptor.newCall(
            GET(
                "$baseUrl${anime.url}",
                headers =
                Headers.headersOf("user-agent", "Mozilla/5.0 (Linux; Android 12; SM-T870 Build/SP2A.220305.013; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/106.0.5249.126 Safari/537.36")
            )
        )
            .execute().request.headers
        return GET("$baseUrl${anime.url}", headers = headers)
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        if (document.select("#movie-js-extra").isNullOrEmpty()) {
            val episodeElement = document.select("script[id=\"tv-js-after\"]")
            val episodeString = episodeElement.toString()
                .substringAfter("var streaming = {").substringBefore("}; var").split(",")
            episodeString.forEach {
                val episode = episodeFromString(it)
                episodeList.add(episode)
            }
        } else {
            val episode = SEpisode.create()
            episode.name = document.select("h1.entry-title").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(document.select("meta[property=\"og:url\"]").attr("content"))
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not Used")

    private fun episodeFromString(string: String): SEpisode {
        val episode = SEpisode.create()
        val season = string.substringAfter("\"s").substringBefore("_")
        val ep = string.substringAfter("_").substringBefore("\":")
        episode.episode_number = ep.toFloat()
        episode.name = "Staffel $season Folge $ep"
        episode.url = (string.replace("\\", "").replace("\"", "").replace("s${season}_$ep:", ""))
        return episode
    }

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        if (!episode.url.contains("https://")) {
            return GET("$baseUrl${episode.url}")
        } else {
            return GET(episode.url.replace(baseUrl, ""))
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val url = response.request.url.toString()
        return videosFromElement(url)
    }

    private fun videosFromElement(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("dood", "stape", "streamz", "streamsb"))
        if (!url.contains(baseUrl)) {
            when {
                url.contains("https://dood") && hosterSelection?.contains("dood") == true -> {
                    val quality = "Doodstream"
                    val video = try {
                        DoodExtractor(client).videoFromUrl(url, quality, redirect = false)
                    } catch (e: Exception) {
                        null
                    }
                    if (video != null) {
                        videoList.add(video)
                    }
                }

                url.contains("https://streamtape") && hosterSelection?.contains("stape") == true -> {
                    val quality = "Streamtape"
                    val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }

                url.contains("https://streamz") && hosterSelection?.contains("streamz") == true -> {
                    val quality = "StreamZ"
                    val video = StreamZExtractor(client).videoFromUrl(url, quality)
                    if (video != null) {
                        videoList.add(video)
                    }
                }

                url.contains("https://viewsb.com") || url.contains("https://watchsb.com") && hosterSelection?.contains("streamsb") == true -> {
                    val video = try {
                        StreamSBExtractor(client).videosFromUrl(url, headers)
                    } catch (e: Exception) {
                        null
                    }
                    if (video != null) {
                        videoList.addAll(video)
                    }
                }
            }
        } else {
            val document = client.newCall(GET(url)).execute().asJsoup()
            val elements = document.select("div.multi a")
            elements.forEach {
                when {
                    it.attr("href").contains("https://dood") && hosterSelection?.contains("dood") == true -> {
                        val quality = "Doodstream"
                        val video = try {
                            DoodExtractor(client).videoFromUrl(it.attr("href"), quality, redirect = false)
                        } catch (e: Exception) {
                            null
                        }
                        if (video != null) {
                            videoList.add(video)
                        }
                    }

                    it.attr("href").contains("https://streamtape") && hosterSelection?.contains("stape") == true -> {
                        val quality = "Streamtape"
                        val video = StreamTapeExtractor(client).videoFromUrl(it.attr("href"), quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }

                    it.attr("href").contains("https://streamz") || it.attr("href").contains("https://streamcrypt.net") && hosterSelection?.contains("streamz") == true -> {
                        if (it.attr("href").contains("https://streamcrypt.net")) {
                            val zurl = client.newCall(GET(it.attr("href"))).execute().request.url.toString()
                            val quality = "StreamZ"
                            val video = StreamZExtractor(client).videoFromUrl(zurl, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        } else {
                            val quality = "StreamZ"
                            val video = StreamZExtractor(client).videoFromUrl(it.attr("href"), quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        }
                    }

                    it.attr("href").contains("https://viewsb.com") || url.contains("https://watchsb.com") && hosterSelection?.contains("streamsb") == true -> {
                        val video = try {
                            StreamSBExtractor(client).videosFromUrl(it.attr("href"), headers)
                        } catch (e: Exception) {
                            null
                        }
                        if (video != null) {
                            videoList.addAll(video)
                        }
                    }
                }
            }
        }
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
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a img").attr("data-src")
        anime.title = element.select("a h2").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "footer"

    override fun searchAnimeSelector(): String = "div.item-container div.item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (client.newCall(GET("$baseUrl/page/$page/?s=$query")).execute().code == 404) {
            throw Exception("Ignorieren")
        } else {
            return GET("$baseUrl/page/$page/?s=$query")
        }
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        val interceptor = client.newBuilder().addInterceptor(CloudflareInterceptor()).build()
        val headers = interceptor.newCall(
            GET(
                "$baseUrl${anime.url}",
                headers =
                Headers.headersOf("user-agent", "Mozilla/5.0 (Linux; Android 12; SM-T870 Build/SP2A.220305.013; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/106.0.5249.126 Safari/537.36")
            )
        )
            .execute().request.headers
        return GET("$baseUrl${anime.url}", headers = headers)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.tv-poster img").attr("src")
        anime.title = document.select("h1.entry-title").text()
        anime.genre = document.select("span[itemprop=genre] a").joinToString(", ") { it.text() }
        anime.description = document.select("p.movie-description").toString()
            .substringAfter("trama\">").substringBefore("<br>")
        anime.author = document.select("div.name a").joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("span.stato").text())
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Returning Series", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
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
            entries = arrayOf("Streamtape", "Doodstream", "StreamZ", "StreamSB")
            entryValues = arrayOf("https://streamz.ws", "https://dood", "https://voe.sx", "https://viewsb.com")
            setDefaultValue("https://streamtape.com")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Hoster auswählen"
            entries = arrayOf("Streamtape", "Doodstream", "StreamZ", "StreamSB")
            entryValues = arrayOf("stape", "dood", "streamz", "streamsb")
            setDefaultValue(setOf("stape", "dood", "streamz", "streamsb"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
