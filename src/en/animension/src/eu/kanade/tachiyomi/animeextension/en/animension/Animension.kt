package eu.kanade.tachiyomi.animeextension.en.animension

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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Animension() : ConfigurableAnimeSource, AnimeHttpSource() {
    override val lang = "en"

    override val name = "Animension"

    override val baseUrl = "https://animension.to/"

    override val supportsLatest = true

    private val apiUrl = "https://animension.to/public-api"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular
    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiUrl/search.php?dub=0&sort=popular-week&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<JsonArray>(response.body.string())
        val animes = responseJson.map { anime ->
            val data = anime.jsonArray

            SAnime.create().apply {
                title = data[0].jsonPrimitive.content
                url = data[1].jsonPrimitive.content
                thumbnail_url = data[2].jsonPrimitive.content
            }
        }
        val hasNextPage = responseJson.size >= 25

        return AnimesPage(animes, hasNextPage)
    }

    // Episode
    override fun episodeListRequest(anime: SAnime): Request {
        return GET("$apiUrl/episodes.php?id=${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseJson = json.decodeFromString<JsonArray>(response.body.string())
        val episodes = responseJson.map { episode ->
            val data = episode.jsonArray

            SEpisode.create().apply {
                name = "Episode ${data[2]}"
                url = data[1].jsonPrimitive.content
                episode_number = data[2].jsonPrimitive.float
                date_upload = data[3].jsonPrimitive.long.toMilli()
            }
        }
        return episodes
    }

    // Video urls
    override fun videoListRequest(episode: SEpisode): Request =
        GET("$apiUrl/episode.php?id=${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val responseJson = json.decodeFromString<JsonArray>(response.body.string())
        val videos = json.decodeFromString<JsonObject>(responseJson[3].jsonPrimitive.content)
        val videoList = mutableListOf<Video>()

        for (key in videos.keys.toList()) {
            val url = videos[key]!!.jsonPrimitive.content
            when {
                url.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
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

    override fun videoUrlParse(response: Response) = throw UnsupportedOperationException()

    // Anime details
    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.thumb img").attr("src")
        anime.title = document.select("h1.entry-title").text()
        anime.description = document.select("div.desc").text()
        anime.genre = document.select("div.genxed span a").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.spe span:contains(Status)").text().substringAfter("Status: "))
        return anime
    }

    // Latest
    override fun latestUpdatesRequest(page: Int): Request =
        GET("$apiUrl/index.php?page=$page&mode=sub")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<JsonArray>(response.body.string())
        val animes = responseJson.map { anime ->
            val data = anime.jsonArray
            SAnime.create().apply {
                title = data[0].jsonPrimitive.content
                url = data[1].jsonPrimitive.content
                thumbnail_url = data[4].jsonPrimitive.content
            }
        }
        val hasNextPage = responseJson.size >= 25

        return AnimesPage(animes, hasNextPage)
    }

    // Search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$apiUrl/search.php?search_text=$query&page=$page", headers)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<JsonArray>(response.body.string())
        val animes = responseJson.map { anime ->
            val data = anime.jsonArray

            SAnime.create().apply {
                title = data[0].jsonPrimitive.content
                url = data[1].jsonPrimitive.content
                thumbnail_url = data[2].jsonPrimitive.content
            }
        }
        val hasNextPage = responseJson.size >= 25

        return AnimesPage(animes, hasNextPage)
    }

    // Utilities
    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Finished" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun Long.toMilli(): Long = this * 1000

    // Preferences
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Doodstream")
            entryValues = arrayOf("1080", "720", "480", "360", "Doodstream")
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
