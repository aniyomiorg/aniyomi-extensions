package eu.kanade.tachiyomi.animeextension.es.animeflv

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animeflv.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.animeflv.extractors.OkruExtractor
import eu.kanade.tachiyomi.animeextension.es.animeflv.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animeextension.es.animeflv.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class AnimeFlv : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFLV"

    override val baseUrl = "https://www3.animeflv.net"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?order=rating&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            baseUrl + element.select("div.Description a.Button")
                .attr("href")
        )
        anime.title = element.select("a h3").text()
        anime.thumbnail_url = try {
            element.select("a div.Image figure img").attr("src")
        } catch (e: Exception) {
            element.select("a div.Image figure img").attr("data-cfsrc")
        }
        anime.description =
            element.select("div.Description p:eq(2)").text().removeSurrounding("\"")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=next]:not(li.disabled)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        jsoup.select("script").forEach { script ->
            if (script.data().contains("var episodes = [")) {
                val data = script.data().substringAfter("var episodes = [").substringBefore("];")
                val animeId = response.request.url.pathSegments.last()
                data.split("],").forEach {
                    val epNum = it.removePrefix("[").substringBefore(",")
                    val episode = SEpisode.create().apply {
                        episode_number = epNum.toFloat()
                        name = "Episodio $epNum"
                        url = "/ver/$animeId-$epNum"
                        date_upload = System.currentTimeMillis()
                    }
                    episodes.add(episode)
                }
            }
        }
        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("var videos = {")) {
                val data = script.data().substringAfter("var videos = ").substringBefore(";")
                val jsonObject = json.decodeFromString<JsonObject>(data)
                val sub = jsonObject["SUB"]!!
                val lat = jsonObject["LAT"]
                Log.i("bruh", " a $lat")
                if (sub !is JsonNull) {
                    for (server in sub.jsonArray) {
                        val url = server.jsonObject["code"]!!.jsonPrimitive.content.replace("\\/", "/")
                        val quality = server.jsonObject["title"]!!.jsonPrimitive.content
                        if (quality == "SB") {
                            val headers = headers.newBuilder()
                                .set("Referer", url)
                                .set("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                                .set("Accept-Language", "en-US,en;q=0.5")
                                .set("watchsb", "streamsb")
                                .build()
                            val videos = StreamSBExtractor(client).videosFromUrl(url, headers)
                            videoList.addAll(videos)
                        }
                        if (quality == "Fembed") {
                            val videos = FembedExtractor().videosFromUrl(url)
                            videoList.addAll(videos)
                        }
                        if (quality == "Stape") {
                            val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        }
                        if (quality == "Okru") {
                            val videos = OkruExtractor(client).videosFromUrl(url)
                            videoList.addAll(videos)
                        }
                    }
                }
                if (lat !is JsonNull) {

                    if (lat != null) {
                        for (server in lat.jsonArray) {
                            val url = server.jsonObject["code"]!!.jsonPrimitive.content.replace("\\/", "/")
                            val quality = server.jsonObject["title"]!!.jsonPrimitive.content

                            if (quality == "Fembed") {
                                val videos = FembedExtractor().videosFromUrl(url, "DUB: ")
                                videoList.addAll(videos)
                            }

                            if (quality == "Okru") {
                                val videos = OkruExtractor(client).videosFromUrl(url, "DUB: ")
                                videoList.addAll(videos)
                            }
                        }
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Stape")
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/browse?q=$query&page=$page")

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + "/" + document.selectFirst("div.AnimeCover div.Image figure img").attr("src")
        anime.title = document.selectFirst("div.Ficha.fchlt div.Container h1.Title").text()
        anime.description = document.selectFirst("div.Description").text().removeSurrounding("\"")
        anime.genre = document.select("nav.Nvgnrs a").joinToString { it.text() }
        anime.status = parseStatus(document.select("span.fa-tv").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En emision") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/browse?order=added&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Fembed:480p", "Fembed:720p", "Stape", "hd", "sd", "low", "lowest", "mobile")
            entryValues = arrayOf("Fembed:480p", "Fembed:720p", "Stape", "hd", "sd", "low", "lowest", "mobile")
            setDefaultValue("Fembed:720p")
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
