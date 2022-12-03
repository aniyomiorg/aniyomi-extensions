package eu.kanade.tachiyomi.animeextension.en.wcofun

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.net.URI

class Wcofun : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Wcofun"

    override val baseUrl = "https://www.wcofun.net"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#sidebar_right2 ul.items li"

    override fun popularAnimeRequest(page: Int): Request {
        val interceptor = client.newBuilder().addInterceptor(RedirectInterceptor()).build()
        val headers = interceptor.newCall(GET(baseUrl)).execute().request.headers
        return GET(baseUrl, headers = headers)
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = "https:" + element.select("div.img a img").attr("src")
        anime.setUrlWithoutDomain(element.select("div.img a").attr("href"))
        anime.title = element.select("div.recent-release-episodes a").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    override fun episodeListSelector() = "div.cat-eps a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        val epName = element.ownText()
        val season = epName.substringAfter("Season ")
        val ep = epName.substringAfter("Episode ")
        val seasonNo = try {
            season.substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) {
            0.toFloat()
        }
        val epNo = try {
            ep.substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) {
            0.toFloat()
        }
        var episodeName = if (ep == epName) epName else "Episode $ep"
        episodeName = if (season == epName) episodeName else "Season $season"
        episode.episode_number = epNo + (seasonNo * 100)
        episode.name = episodeName
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val scriptData = document.select("script:containsData( = \"\"; var )").first().data()

        val numberRegex = """(?<=\.replace\(/\\D/g,''\)\) - )\d+""".toRegex()
        val subtractionNumber = numberRegex.find(scriptData)!!.value.toInt()

        val htmlRegex = """(?<=\["|, ").+?(?=")""".toRegex()
        val html = htmlRegex.findAll(scriptData).map {
            val decoded = String(Base64.decode(it.value, Base64.DEFAULT))
            val number = decoded.replace("""\D""".toRegex(), "").toInt()
            (number - subtractionNumber).toChar()
        }.joinToString("")

        val iframeLink = Jsoup.parse(html).select("div.pcat-jwplayer iframe")
            .attr("src")

        val iframeDomain = "https://" + URI(iframeLink).host

        val playerHtml = client.newCall(
            GET(
                url = iframeLink,
                headers = Headers.headersOf("Referer", document.location())
            )
        ).execute().body!!.string()

        val getVideoLink = playerHtml.substringAfter("\$.getJSON(\"").substringBefore("\"")

        val head = Headers.Builder()
        head.add("x-requested-with", "XMLHttpRequest")
        head.add("Referer", (iframeDomain + getVideoLink))

        val videoJson = json.decodeFromString<JsonObject>(
            client.newCall(
                GET(
                    url = (iframeDomain + getVideoLink),
                    headers = head.build()
                )
            ).execute().body!!.string()
        )

        val server = videoJson["server"]!!.jsonPrimitive.content
        val hd = videoJson["hd"]?.jsonPrimitive?.content
        val sd = videoJson["enc"]?.jsonPrimitive?.content
        val fhd = videoJson["fhd"]?.jsonPrimitive?.content
        val videoList = mutableListOf<Video>()
        hd?.let {
            if (it.isNotEmpty()) {
                val videoUrl = "$server/getvid?evid=$it"
                videoList.add(Video(videoUrl, "HD", videoUrl))
            }
        }
        sd?.let {
            if (it.isNotEmpty()) {
                val videoUrl = "$server/getvid?evid=$it"
                videoList.add(Video(videoUrl, "SD", videoUrl))
            }
        }

        fhd?.let {
            if (it.isNotEmpty()) {
                val videoUrl = "$server/getvid?evid=$it"
                videoList.add(Video(videoUrl, "FHD", videoUrl))
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "HD")
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

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("alt")

        return anime
    }

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = "div#sidebar_right2 li div.img a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val formBody = FormBody.Builder()
            .add("catara", query)
            .add("konuara", "series")
            .build()
        return POST("$baseUrl/search", headers, body = formBody)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.video-title a").first().text()
        anime.description = document.select("div#sidebar_cat p")?.first()?.text()
        anime.thumbnail_url = "https:${document.select("div#sidebar_cat img").first().attr("src")}"
        anime.genre = document.select("div#sidebar_cat > a").joinToString { it.text() }
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("HD", "SD")
            entryValues = arrayOf("HD", "SD")
            setDefaultValue("HD")
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
