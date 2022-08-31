package eu.kanade.tachiyomi.animeextension.all.twitch

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Twitch : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Twitch"

    override val baseUrl = "https://twitchtracker.com" // bruh idk if exists a better page to get the details from the streamer

    override val lang = "all"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "tbody tr"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/channels/ranking?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create().apply {
            setUrlWithoutDomain(
                element.select("td a").attr("href")
            )
            title = element.select("td a").text()
            thumbnail_url = element.select("td a img").attr("src")
        }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination.pagination-simple li a:contains(Next)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        return if (document.select("li.list-group-item span.label.label-danger").text().contains("streaming now")) {
            mutableListOf(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Streaming Now (Refresh to update state)"
                    episode_number = 1F
                }
            )
        } else {
            mutableListOf(
                SEpisode.create().apply {
                    url = ""
                    name = "Offline (Refresh to update state)"
                    episode_number = 0F
                }
            )
        }
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        if (response.request.url.toString() == "$baseUrl/") {
            val amogusVideo = "https://cdn.discordapp.com/attachments/909242748283006996/1014362630456090654/amogus.mp4"
            return listOf(Video(amogusVideo, "Refresh Anime to Update State (Offline)", amogusVideo))
        }
        val videos = mutableListOf<Video>()
        if (response.request.url.toString().isNotBlank()) {

            val channelName = response.request.url.toString().substringAfterLast("/")
            val twitchUrl = "https://www.twitch.tv/$channelName"
            val apiUrl = "https://pwn.sh/tools/streamapi.py?url=$twitchUrl"
            val jsonResponse = client.newCall(GET(apiUrl)).execute().body!!.string()
            val json = json.decodeFromString<ApiResponse>(jsonResponse)
            videos.add(Video("${json.urls!!.audio_only}", "Audio Only", "${json.urls!!.audio_only}"))
            videos.add(Video("${json.urls!!.veryLow}", "Very Low", "${json.urls!!.veryLow}"))
            videos.add(Video("${json.urls!!.low}", "Low", "${json.urls!!.low}"))
            videos.add(Video("${json.urls!!.sd}", "SD", "${json.urls!!.sd}"))
            videos.add(Video("${json.urls!!.hd}", "HD", "${json.urls!!.hd}"))
            videos.add(Video("${json.urls!!.fhd}", "FHD", "${json.urls!!.fhd}"))
        }

        return videos.filter { it.url != "null" }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val liveNowFilter = try { (filters.find { it is LiveNowFilter } as LiveNowFilter).state } catch (e: Exception) { false }
        val langFilter = try { (filters.find { it is LanguageFilter } as LanguageFilter) } catch (e: Exception) { LanguageFilter().apply { state = 0 } }

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?q=$query")
            liveNowFilter && langFilter.state != 0 -> GET("$baseUrl/channels/live/${langFilter.toUriPart()}?page=$page")
            liveNowFilter -> GET("$baseUrl/channels/live?page=$page")
            langFilter.state != 0 -> GET("$baseUrl/channels/ranking/${langFilter.toUriPart()}?page=$page")
            else -> GET("$baseUrl/channels/ranking?page=$page")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()
        return if (url.contains("/channels/")) {
            val nextPageElement = document.select("ul.pagination.pagination-simple li a:contains(Next)").first().text()
            val nextPage = !nextPageElement.isNullOrBlank()
            AnimesPage(document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }, nextPage)
        } else {
            AnimesPage(document.select(searchAnimeSelector()).map { searchAnimeFromElement(it) }, false)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        thumbnail_url = element.select("td.image-cell img").attr("src")
        title = element.select("td a.item-title").text()
        url = popularAnimeFromElement(element).url
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "table.tops.table.table-condensed.table-hover tbody tr"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create().apply {
            title = document.select("div#app-conception div#app-title").text()
            genre = document.select("ul.list-group.text-center li.list-group-item div div a.label.label-soft").text()
            status = SAnime.ONGOING
            description = document.select("div.row div.col-md-4.text-center").text()
        }
        return anime
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/channels/live?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        LiveNowFilter(),
        LanguageFilter()
    )

    private class LiveNowFilter : AnimeFilter.CheckBox("Just show Live Now", false)

    private class LanguageFilter : UriPartFilter(
        "Languages",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("English", "english"),
            Pair("Spanish", "spanish"),
            Pair("Portuguese", "portuguese"),
            Pair("French", "french"),
            Pair("German", "german"),
            Pair("Italian", "italian"),
            Pair("Russian", "russian"),
            Pair("Japanese", "japanese"),
            Pair("Korean", "korean"),
            Pair("Chinese", "chinese"),
            Pair("Italian", "italian")
        )
    )
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Amazon")
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred video quality"
            entries = arrayOf("Audio Only", "160p", "360p", "480p", "720p60FPS", "1080p60FPS")
            entryValues = arrayOf("Audio Only", "Very Low", "Low", "SD", "HD", "FHD")
            setDefaultValue("720p60FPS")
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

    @Serializable
    data class ApiResponse(
        var success: Boolean? = null,
        var urls: Urls? = Urls()
    )

    @Serializable
    data class Urls(
        var audio_only: String? = null,
        @SerialName("160p") var veryLow: String? = null,
        @SerialName("360p") var low: String? = null,
        @SerialName("480p") var sd: String? = null,
        @SerialName("720p60") var hd: String? = null,
        @SerialName("1080p60") var fhd: String? = null
    )
}
