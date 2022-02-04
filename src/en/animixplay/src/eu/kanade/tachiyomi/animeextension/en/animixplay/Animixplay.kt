package eu.kanade.tachiyomi.animeextension.en.animixplay

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animixplay.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.en.animixplay.extractors.StreamSbExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.util.Locale

@ExperimentalSerializationApi
class Animixplay : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animixplay"

    override val baseUrl = "https://animixplay.to"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.img a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?tab=popular")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val scriptData = document.select("script:containsData(var nowtime)").toString()
        val envSeason = scriptData.substringAfter("var envSeason = '").substringBefore("';")
        val envYear = scriptData.substringAfter("var envYear = ").substringBefore(";")
        val animeJson = json.decodeFromString<JsonObject>(
            client.newCall(
                GET(
                    url = "https://animixplay.to/assets/season/$envYear/${
                    envSeason.toLowerCase(
                        Locale.ROOT
                    )
                    }.json",
                    headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().body!!.string()
        )
        val animeList = animeJson["anime"]!!.jsonObject
        val animes = animeList.map { element ->
            popularAnimeFromElement(element.value.jsonObject)
        }

        return AnimesPage(animes, false)
    }

    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")

    private fun popularAnimeFromElement(animeJson: JsonObject): SAnime {
        val anime = SAnime.create()
        val url = "https://animixplay.to/assets/mal/${
        animeJson["mal_id"]!!.jsonPrimitive.content
        }.json"
        anime.setUrlWithoutDomain(url)
        anime.thumbnail_url = animeJson["image_url"]!!.jsonPrimitive.content
        anime.title = animeJson["title"]!!.jsonPrimitive.content
        return anime
    }

    override fun popularAnimeNextPageSelector(): String =
        "ul.pagination-list li:last-child:not(.selected)"

    override fun episodeListSelector() = "div#epslistplace"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val animeJson = json.decodeFromString<JsonObject>(document.select("body").text())
        val malId = animeJson["mal_id"]!!.jsonPrimitive.int

        return episodesRequest(malId, document)
    }

    private fun episodesRequest(malId: Int, document: Document): List<SEpisode> {
        // POST data
        val body = FormBody.Builder()
            .add("recomended", malId.toString())
            .build()
        val animeServersJson = json.decodeFromString<JsonObject>(
            client.newCall(
                POST(
                    "https://animixplay.to/api/search",
                    body = body,
                    headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().body!!.string()
        )
        val urlEndpoint = animeServersJson["data"]!!.jsonArray[0].jsonObject["items"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
        val episodesResponse = client.newCall(
            GET(
                baseUrl + urlEndpoint,
            )
        ).execute().asJsoup()
        val episodeListJson = json.decodeFromString<JsonObject>(episodesResponse.select("div#epslistplace").text())
        val episodeAvailable = episodeListJson["eptotal"]!!.jsonPrimitive.int
        val episodeList = mutableListOf<SEpisode>()

        for (i in 0 until episodeAvailable) {
            episodeList.add(episodeFromJsonElement(baseUrl + urlEndpoint, i))
        }
        return episodeList
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun episodeFromJsonElement(url: String, number: Int): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain("$url/ep$number")
        episode.episode_number = number.toFloat()
        episode.name = "Episode ${number + 1}"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val episodeListJson = json.decodeFromString<JsonObject>(document.select("div#epslistplace").text())
        val epNo = response.request.url.toString().substringAfter("/ep")
//        val serverUrl = "https:" + episodeListJson[epNo]!!.jsonPrimitive.content
        val serverUrl = "https://streamsb.net/e/g13hrgae001c.html"

//        val gogoVideos = GogoCdnExtractor(client, json).videosFromUrl(serverUrl)
        val gogoVideos = StreamSbExtractor(client).videosFromUrl(serverUrl)
        return gogoVideos.ifEmpty {
            DoodExtractor(client).videosFromUrl(serverUrl)
        }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
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
        anime.thumbnail_url = element.select("img").first().attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String =
        "ul.pagination-list li:last-child:not(.selected)"

    override fun searchAnimeSelector(): String = "div.img a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search.html?keyword=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}?page=$page")
            else -> GET("$baseUrl/popular.html?page=$page")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val animeJson = json.decodeFromString<JsonObject>(document.select("body").text())
        anime.title = animeJson["title_english"]!!.jsonPrimitive.content
        anime.genre =
            animeJson["genres"]!!.jsonArray.joinToString { it.jsonObject["name"]!!.jsonPrimitive.content }
//        anime.genre = document.select("p.type:eq(5) a").joinToString("") { it.text() }
        anime.description = animeJson["synopsis"]!!.jsonPrimitive.content
        anime.status = parseStatus(animeJson["status"]!!.jsonPrimitive.content)
        anime.author =
            animeJson["studios"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content
        // add alternative name to anime description
//        val altName = "Other name(s): "
//        document.select("p.type:eq(8)").firstOrNull()?.ownText()?.let {
//            if (it.isBlank().not()) {
//                anime.description = when {
//                    anime.description.isNullOrBlank() -> altName + it
//                    else -> anime.description + "\n\n$altName" + it
//                }
//            }
//        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String =
        "ul.pagination-list li:last-child:not(.selected)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val style = element.select("div.thumbnail-popular").attr("style")
        anime.thumbnail_url = style.substringAfter("background: url('").substringBefore("');")
        anime.title = element.attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET(
            "https://ajax.gogo-load.com/ajax/page-recent-release-ongoing.html?page=$page&type=1",
            headers
        )

    override fun latestUpdatesSelector(): String = "div.added_series_body.popular li a:has(div)"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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

    // Filters
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Cars", "cars"),
            Pair("Comedy", "comedy"),
            Pair("Crime", "crime"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mature", "mature"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
