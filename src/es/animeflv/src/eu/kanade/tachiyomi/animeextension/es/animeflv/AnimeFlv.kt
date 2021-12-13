package eu.kanade.tachiyomi.animeextension.es.animeflv

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.github.salomonbrys.kotson.get
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import java.net.URL

class AnimeFlv : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFLV"

    override val baseUrl = "https://www3.animeflv.net"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.Container ul.ListAnimes li article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/browse?page=$page")

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
                val json = JsonParser.parseString(data).asJsonObject
                val sub = json.get("SUB")
                if (!sub.isJsonNull) {
                    for (server in sub.asJsonArray) {
                        val url = server.asJsonObject.get("code").asString.replace("\\/", "/")
                        val quality = server.asJsonObject.get("title").asString
                        if (quality == "Stape") {
                            val videos = getStapeVideos(url)
                            videoList += videos
                        }
                        if (quality == "Okru") {
                            val videos = getOkruVideos(url)
                            videoList += videos
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

    // this code is trash but work
    private fun getStapeVideos(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val url5 = url.replace("https://streamtape.com/e/", "")
        val url6 = url5.replace("/", "")
        val document = client.newCall(GET("https://api.streamtape.com/file/dlticket?file=$url6&login=ef23317a52e4442dbdd3&key=mydvdBk717tb08Y")).execute().asJsoup()
        val test22 = document.body().text()
        val test23 = JsonParser.parseString(test22).asJsonObject
        val test24 = test23.get("result").get("ticket").toString().replace("\"", "")
        val test80 = test24
        val url80 = "https://api.streamtape.com/file/dl?file=$url6&ticket=$test24&captcha_response={captcha_response}".replace("={captcha_response}", "={captcha_response}")
        Log.i("stapeee", url80)
        val document2 = client.newCall(GET(url80)).execute().asJsoup()
        //I don't know how to make it wait 5 seconds, but this worked for some reason
        val jsjs = URL(url80).readText()
        val jsjs1 = URL(url80).readText()
        val jsjs2 = URL(url80).readText()
        val jsjs3 = URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        URL(url80).readText()
        val jsjs4 = URL(url80).readText()
        val test29 = document2.body().text()
        Log.i("stapeee", jsjs4)
        val test30 = JsonParser.parseString(jsjs4).asJsonObject
        val test31 = test30.get("result").get("url").toString().replace("\"", "")
        Log.i("stapeee", test31)
        videoList.add(Video(test31, "Stape", test31, null))
        return videoList
    }

    private fun getOkruVideos(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        Log.i("bruuh", document.select("div[data-options]").attr("data-options"))
        val videoList = mutableListOf<Video>()
        val videosString = document.select("div[data-options]").attr("data-options")
            .substringAfter("\\\"videos\\\":[{\\\"name\\\":\\\"")
            .substringBefore("]")
        videosString.split("{\\\"name\\\":\\\"").reversed().forEach {
            val videoUrl = it.substringAfter("url\\\":\\\"")
                .substringBefore("\\\"")
                .replace("\\\\u0026", "&")
            val quality = it.substringBefore("\\\"")
            if (videoUrl.startsWith("https://")) {
                videoList.add(Video(videoUrl, quality, videoUrl, null))
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "hd")
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

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("hd", "sd", "low", "lowest", "mobile")
            entryValues = arrayOf("hd", "sd", "low", "lowest", "mobile")
            setDefaultValue("hd")
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
