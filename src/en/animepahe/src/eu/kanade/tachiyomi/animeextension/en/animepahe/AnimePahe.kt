package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class AnimePahe : ConfigurableAnimeSource, AnimeHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "AnimePahe"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://animepahe.com")!! }

    override val lang = "en"

    // Create bypass object
    private val ddgbypass = DdosGuardBypass("https://animepahe.com/")

    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder {
        try {
            // Bypass...
            // Only required once. Then you can browse any page on the domain.
            if (!ddgbypass.isBypassed) {
                ddgbypass.bypass()
            }
            // Set Cookie header
            if (ddgbypass.isBypassed) {
                return super.headersBuilder().add("cookie", ddgbypass.cookiesAsString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return super.headersBuilder()
    }

    override val client: OkHttpClient = network.cloudflareClient

    override fun animeDetailsRequest(anime: SAnime): Request {
        val responseString = runBlocking {
            withContext(Dispatchers.IO) {
                client.newCall(GET("$baseUrl/api?m=search&q=${anime.title}"))
                    .execute().body!!.string()
            }
        }
        val animeId = anime.url.substringAfterLast("?anime_id=")
        val session = responseString.substringAfter("\"id\":$animeId")
            .substringAfter("\"session\":\"").substringBefore("\"")
        return GET("$baseUrl/anime/$session?anime_id=$animeId")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val jsoup = response.asJsoup()
        val anime = SAnime.create()
        val animeId = response.request.url.toString().substringAfterLast("?anime_id=")
        anime.setUrlWithoutDomain("$baseUrl/anime/?anime_id=$animeId")
        anime.title = jsoup.selectFirst("div.title-wrapper h1").text()
        anime.author = jsoup.select("div.col-sm-4.anime-info p:contains(Studio:)")
            .firstOrNull()?.text()?.replace("Studio: ", "")
        anime.status = parseStatus(jsoup.selectFirst("div.col-sm-4.anime-info p:contains(Status:) a").text())
        anime.thumbnail_url = jsoup.selectFirst("div.anime-poster a").attr("href")
        anime.genre = jsoup.select("div.anime-genre ul li").joinToString { it.text() }
        val synonyms = jsoup.select("div.col-sm-4.anime-info p:contains(Synonyms:)")
            .firstOrNull()?.text()
        anime.description = jsoup.select("div.anime-summary").text() +
            if (synonyms.isNullOrEmpty()) "" else "\n\n$synonyms"
        return anime
    }

    override fun latestUpdatesRequest(page: Int) = throw Exception("not supported")

    override fun latestUpdatesParse(response: Response) = throw Exception("not supported")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api?m=search&l=8&q=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }

    private fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val data = jObject.get("data") ?: return AnimesPage(emptyList(), false)
        val array = data.asJsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.asJsonObject.get("title").asString
            val animeId = item.asJsonObject.get("id").asInt
            anime.setUrlWithoutDomain("$baseUrl/anime/?anime_id=$animeId")
            animeList.add(anime)
        }
        return AnimesPage(animeList, false)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api?m=airing&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val lastPage = jObject.get("last_page").asInt
        val page = jObject.get("current_page").asInt
        val hasNextPage = page < lastPage
        val array = jObject.get("data").asJsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.asJsonObject.get("anime_title").asString
            val animeId = item.asJsonObject.get("anime_id").asInt
            anime.setUrlWithoutDomain("$baseUrl/anime/?anime_id=$animeId")
            anime.artist = item.asJsonObject.get("fansub").asString
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfterLast("?anime_id=")
        return GET("$baseUrl/api?m=release&id=$animeId&sort=episode_desc&page=1")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return recursivePages(response)
    }

    private fun parseEpisodePage(jsonLine: String?): MutableList<SEpisode> {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val array = jObject.get("data").asJsonArray
        val episodeList = mutableListOf<SEpisode>()
        for (item in array) {
            val itemO = item.asJsonObject
            val episode = SEpisode.create()
            episode.date_upload = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .parse(itemO.get("created_at").asString)!!.time
            val animeId = itemO.get("anime_id").asInt
            val session = itemO.get("session").asString
            episode.setUrlWithoutDomain("$baseUrl/api?m=links&id=$animeId&session=$session&p=kwik")
            val epNum = itemO.get("episode").asInt
            episode.episode_number = epNum.toFloat()
            episode.name = "Episode $epNum"
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun recursivePages(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val lastPage = jObject.get("last_page").asInt
        val page = jObject.get("current_page").asInt
        val hasNextPage = page < lastPage
        val returnList = parseEpisodePage(responseString)
        if (hasNextPage) {
            val nextPage = nextPageRequest(response.request.url.toString(), page + 1)
            returnList += recursivePages(nextPage)
        }
        return returnList
    }

    private fun nextPageRequest(url: String, page: Int): Response {
        val request = GET(url.substringBeforeLast("&page=") + "&page=$page")
        return client.newCall(request).execute()
    }

    override fun videoListParse(response: Response): List<Video> {
        val array = JsonParser.parseString(response.body!!.string())
            .asJsonObject.get("data").asJsonArray
        val videos = mutableListOf<Video>()
        for (item in array) {
            val quality = item.asJsonObject.keySet().first()
            val adflyLink = item.asJsonObject.get(quality)
                .asJsonObject.get("kwik_adfly").asString
            val audio = item.asJsonObject.get(quality).asJsonObject.get("audio")
            val qualityString = if (audio is JsonNull) "${quality}p" else "${quality}p (" + audio.asString + " audio)"
            videos.add(getVideo(adflyLink, qualityString))
        }
        return videos
    }

    private fun getVideo(adflyUrl: String, quality: String): Video {
        val videoUrl = KwikExtractor(client).getStreamUrlFromKwik(adflyUrl)
        return Video(videoUrl, quality, videoUrl, null)
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString("preferred_sub", "jpn")!!
        val quality = preferences.getString("preferred_quality", "1080")!!
        val qualityList = mutableListOf<Video>()
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this.reversed()) {
            if (video.quality.contains(quality)) {
                qualityList.add(preferred, video)
                preferred++
            } else {
                qualityList.add(video)
            }
        }
        preferred = 0
        for (video in qualityList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "360p")
            entryValues = arrayOf("1080", "720", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("animepahe.com", "animepahe.ru", "animepahe.org")
            entryValues = arrayOf("https://animepahe.com", "https://animepahe.ru", "https://animepahe.org")
            setDefaultValue("https://animepahe.com")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Prefer subs or dubs?"
            entries = arrayOf("sub", "dub")
            entryValues = arrayOf("jpn", "eng")
            setDefaultValue("jpn")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(domainPref)
        screen.addPreference(subPref)
    }
}
