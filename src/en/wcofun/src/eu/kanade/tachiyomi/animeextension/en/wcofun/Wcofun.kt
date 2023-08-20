package eu.kanade.tachiyomi.animeextension.en.wcofun

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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Wcofun : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Wcofun"

    override val baseUrl = "https://www.wcofun.org"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#sidebar_right2 ul.items li"

    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers = headers)

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

    @Serializable
    data class VideoResponseDto(
        val server: String,
        @SerialName("enc")
        val sd: String,
        val hd: String,
        val fhd: String,
    ) {
        val videos by lazy {
            listOfNotNull(
                sd.takeIf(String::isNotBlank)?.let { Pair("SD", it) },
                hd.takeIf(String::isNotBlank)?.let { Pair("HD", it) },
                fhd.takeIf(String::isNotBlank)?.let { Pair("FHD", it) },
            ).map { Pair(it.first, "$server/getvid?evid=" + it.second) }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val iframeLink = document.selectFirst("div.pcat-jwplayer iframe")!!.attr("src")

        val iframeDomain = "https://" + iframeLink.toHttpUrl().host

        val playerHtml = client.newCall(GET(iframeLink, headers)).execute()
            .use { it.body.string() }

        val getVideoLink = playerHtml.substringAfter("\$.getJSON(\"").substringBefore("\"")

        val requestUrl = iframeDomain + getVideoLink
        val requestHeaders = headersBuilder()
            .add("x-requested-with", "XMLHttpRequest")
            .set("Referer", requestUrl)
            .build()

        val videoData = client.newCall(GET(requestUrl, requestHeaders)).execute()
            .parseAs<VideoResponseDto>()

        return videoData.videos.map { Video(it.second, it.first, it.second) }
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
        anime.title = document.selectFirst("div.video-title a")!!.text()
        anime.description = document.select("div#sidebar_cat p")?.first()?.text()
        anime.thumbnail_url = "https:${document.selectFirst("div#sidebar_cat img")!!.attr("src")}"
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

    // ============================= Utilities ==============================
    private inline fun <reified T> Response.parseAs(): T {
        return use { it.body.string() }.let(json::decodeFromString)
    }
}
