package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
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

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient

    override fun animeDetailsRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfterLast("?anime_id=")
        val session = getSession(anime.title, animeId)
        return GET("$baseUrl/anime/$session?anime_id=$animeId")
    }

    private fun getSession(title: String, animeId: String): String {
        return runBlocking {
            withContext(Dispatchers.IO) {
                client.newCall(GET("$baseUrl/api?m=search&q=$title"))
                    .execute().body!!.string()
            }
        }.substringAfter("\"id\":$animeId")
            .substringAfter("\"session\":\"").substringBefore("\"")
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val jsoup = response.asJsoup()
        val anime = SAnime.create()
        val animeId = response.request.url.toString().substringAfterLast("?anime_id=")
        anime.setUrlWithoutDomain("$baseUrl/anime/?anime_id=$animeId")
        anime.title = jsoup.selectFirst("div.title-wrapper > h1 > span").text()
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
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val data = jObject["data"] ?: return AnimesPage(emptyList(), false)
        val array = data.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.int
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
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val lastPage = jObject["last_page"]!!.jsonPrimitive.int
        val page = jObject["current_page"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["data"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["anime_title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["anime_id"]!!.jsonPrimitive.int
            anime.setUrlWithoutDomain("$baseUrl/anime/?anime_id=$animeId")
            anime.artist = item.jsonObject["fansub"]!!.jsonPrimitive.content
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
        val session = getSession(anime.title, animeId)
        return GET("$baseUrl/api?m=release&id=$session&sort=episode_desc&page=1")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return recursivePages(response)
    }

    private fun parseEpisodePage(jsonLine: String?): MutableList<SEpisode> {
        val jsonData = jsonLine ?: return mutableListOf()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val array = jObject["data"]!!.jsonArray
        val episodeList = mutableListOf<SEpisode>()
        for (item in array) {
            val itemO = item.jsonObject
            val episode = SEpisode.create()
            episode.date_upload = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .parse(itemO["created_at"]!!.jsonPrimitive.content)!!.time
            val animeId = itemO["anime_id"]!!.jsonPrimitive.int
            val session = itemO["session"]!!.jsonPrimitive.content
            episode.setUrlWithoutDomain("$baseUrl/api?m=links&id=$animeId&session=$session&p=kwik")
            val epNum = itemO["episode"]!!.jsonPrimitive.float
            episode.episode_number = epNum
            val epNumString = if (epNum % 1F == 0F) epNum.toInt().toString() else epNum.toString()
            episode.name = "Episode $epNumString"
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun recursivePages(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        val jObject = json.decodeFromString<JsonObject>(responseString)
        val lastPage = jObject["last_page"]!!.jsonPrimitive.int
        val page = jObject["current_page"]!!.jsonPrimitive.int
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
        val array = json.decodeFromString<JsonObject>(response.body!!.string())
            .jsonObject["data"]!!.jsonArray
        val videos = mutableListOf<Video>()
        for (item in array) {
            val quality = item.jsonObject.keys.first()
            val paheWinLink = item.jsonObject[quality]!!.jsonObject["kwik_pahewin"]!!.jsonPrimitive.content
            val kwikLink = item.jsonObject[quality]!!.jsonObject["kwik"]!!.jsonPrimitive.content
            val audio = item.jsonObject[quality]!!.jsonObject["audio"]!!
            val qualityString = if (audio is JsonNull) "${quality}p" else "${quality}p (" + audio.jsonPrimitive.content + " audio)"
            videos.add(getVideo(paheWinLink, kwikLink, qualityString))
        }
        return videos
    }

    private fun getVideo(paheUrl: String, kwikUrl: String, quality: String): Video {
        return if (preferences.getBoolean("preferred_link_type", false)) {
            val videoUrl = KwikExtractor(client).getHlsStreamUrl(kwikUrl, referer = baseUrl)
            Video(
                videoUrl, quality, videoUrl,
                headers = Headers.headersOf("referer", "https://kwik.cx")
            )
        } else {
            val videoUrl = KwikExtractor(client).getStreamUrlFromKwik(paheUrl)
            Video(videoUrl, quality, videoUrl)
        }
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
        val linkPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_link_type"
            title = "Use HLS links"
            summary = """Enable this if you are having Cloudflare issues.
                |Note that this will break the ability to seek inside of the video unless the episode is downloaded in advance.""".trimMargin()
            setDefaultValue(false)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(domainPref)
        screen.addPreference(subPref)
        screen.addPreference(linkPref)
    }
}
