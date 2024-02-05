package eu.kanade.tachiyomi.animeextension.de.movie4k

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animeextension.de.movie4k.extractors.StreamZExtractor
import eu.kanade.tachiyomi.animeextension.de.movie4k.extractors.VidozaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Movie4k : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Movie4k"

    override val baseUrl = "https://movie4k.stream"

    private val apiUrl = "https://api.movie4k.stream"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun popularAnimeRequest(page: Int): Request =
        GET("$apiUrl/data/browse/?lang=2&keyword=&year=&rating=&votes=&genre=&country=&cast=&directors=&type=movies&order_by=trending&page=$page", headers = Headers.headersOf("if-none-match", ""))

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val pager = jObject["pager"]!!.jsonObject
        val lastPage = pager.jsonObject["endPage"]!!.jsonPrimitive.int
        val page = pager.jsonObject["currentPage"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["movies"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["_id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$apiUrl/data/watch/?_id=$animeId")
            anime.thumbnail_url = "https://image.tmdb.org/t/p/w300" + (item.jsonObject["poster_path"]?.jsonPrimitive?.content ?: "")
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // episodes

    override fun episodeListRequest(anime: SAnime): Request {
        return GET(apiUrl + anime.url, headers = Headers.headersOf("if-none-match", ""))
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        return parseEpisodePage(responseString)
    }

    private fun parseEpisodePage(jsonLine: String?): List<SEpisode> {
        val jsonData = jsonLine ?: return mutableListOf()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val episodeList = mutableListOf<SEpisode>()
        if (jObject["tv"]!!.jsonPrimitive.int == 1) {
            val array = jObject["streams"]!!.jsonArray.distinctBy { it.jsonObject["e"] }.sortedBy { it.jsonObject["e"]!!.jsonPrimitive.int }
            for (item in array) {
                val episode = SEpisode.create()
                val id = jObject["_id"]!!.jsonPrimitive.content
                episode.episode_number = item.jsonObject["e"]!!.jsonPrimitive.float
                val epNumString = if (episode.episode_number % 1F == 0F) episode.episode_number.toInt().toString() else episode.episode_number.toString()
                val season = jObject["s"]!!.jsonPrimitive.content
                episode.name = "Staffel $season Folge $epNumString"
                episode.setUrlWithoutDomain("$apiUrl/data/watch/?_id=$id&e=$epNumString")
                episodeList.add(episode)
            }
        } else {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "Film"
                val id = jObject["_id"]!!.jsonPrimitive.content
                setUrlWithoutDomain("$apiUrl/data/watch/?_id=$id")
            }
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        val url = episode.url.replace("&e=${episode.episode_number.toInt()}", "")
        return GET(apiUrl + url, headers = Headers.headersOf("if-none-match", "", "e", episode.episode_number.toInt().toString()))
    }

    override fun videoListParse(response: Response): List<Video> {
        val limit = if (preferences.getBoolean("limit_qualities", true)) {
            15
        } else {
            null
        }
        return videosFromElement(response, limit)
    }

    private fun videosFromElement(response: Response, limit: Int?): List<Video> {
        val jsonData = response.body.string()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val array = jObject["streams"]!!.jsonArray
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("voe", "stape", "streamz", "vidoza"))
        if (jObject["tv"]!!.jsonPrimitive.int == 1) {
            for (item in array) {
                if (limit != null && videoList.size > limit) break
                if (item.jsonObject["e"]!!.jsonPrimitive.content == response.request.header("e").toString()) {
                    val link = item.jsonObject["stream"]!!.jsonPrimitive.content
                    when {
                        link.contains("//streamtape") && hosterSelection?.contains("stape") == true -> {
                            if (!link.contains("https:")) {
                                val url = "https:$link"
                                val quality = "Streamtape"
                                val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            } else {
                                val quality = "Streamtape"
                                val video = StreamTapeExtractor(client).videoFromUrl(link, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }
                        }

                        link.contains("//streamcrypt.net") || link.contains("//streamz") && hosterSelection?.contains("streamz") == true -> {
                            if (!link.contains("https:")) {
                                if (link.contains("//streamcrypt.net")) {
                                    val url = "https:$link"
                                    val zurl = client.newCall(GET(url)).execute().request.url.toString()
                                    val quality = "StreamZ"
                                    val video = StreamZExtractor(client).videoFromUrl(zurl, quality)
                                    if (video != null) {
                                        videoList.add(video)
                                    }
                                } else {
                                    val url = "https:$link"
                                    val quality = "StreamZ"
                                    val video = StreamZExtractor(client).videoFromUrl(url, quality)
                                    if (video != null) {
                                        videoList.add(video)
                                    }
                                }
                            } else {
                                if (link.contains("https://streamcrypt.net")) {
                                    val url = client.newCall(GET(link)).execute().request.url.toString()
                                    val quality = "StreamZ"
                                    val video = StreamZExtractor(client).videoFromUrl(url, quality)
                                    if (video != null) {
                                        videoList.add(video)
                                    }
                                } else {
                                    val quality = "StreamZ"
                                    val video = StreamZExtractor(client).videoFromUrl(link, quality)
                                    if (video != null) {
                                        videoList.add(video)
                                    }
                                }
                            }
                        }

                        link.contains("vidoza") && hosterSelection?.contains("vidoza") == true -> {
                            if (!link.contains("https:")) {
                                val url = "https:$link"
                                val quality = "Vidoza"
                                val video = VidozaExtractor(client).videoFromUrl(url, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            } else {
                                val quality = "Vidoza"
                                val video = VidozaExtractor(client).videoFromUrl(link, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }
                        }

                        link.contains("//voe.sx") || link.contains("//launchreliantcleaverriver") ||
                            link.contains("//fraudclatterflyingcar") ||
                            link.contains("//uptodatefinishconferenceroom") || link.contains("//realfinanceblogcenter") && hosterSelection?.contains("voe") == true -> {
                            videoList.addAll(VoeExtractor(client).videosFromUrl(if (link.contains("https:")) link else "https:$link"))
                        }
                    }
                }
            }
        } else {
            for (item in array) {
                if (limit != null && videoList.size > limit) break
                val link = item.jsonObject["stream"]!!.jsonPrimitive.content
                when {
                    link.contains("//streamtape") && hosterSelection?.contains("stape") == true -> {
                        if (!link.contains("https:")) {
                            val url = "https:$link"
                            val quality = "Streamtape"
                            val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        } else {
                            val quality = "Streamtape"
                            val video = StreamTapeExtractor(client).videoFromUrl(link, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        }
                    }

                    link.contains("//streamcrypt.net") || link.contains("https://streamz") && hosterSelection?.contains("streamz") == true -> {
                        if (!link.contains("https:")) {
                            if (link.contains("//streamcrypt.net")) {
                                val url = "https:$link"
                                val zurl = client.newCall(GET(url)).execute().request.url.toString()
                                val quality = "StreamZ"
                                val video = StreamZExtractor(client).videoFromUrl(zurl, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            } else {
                                val url = "https:$link"
                                val quality = "StreamZ"
                                val video = StreamZExtractor(client).videoFromUrl(url, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }
                        } else {
                            if (link.contains("https://streamcrypt.net")) {
                                val url = client.newCall(GET(link)).execute().request.url.toString()
                                val quality = "StreamZ"
                                val video = StreamZExtractor(client).videoFromUrl(url, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            } else {
                                val quality = "StreamZ"
                                val video = StreamZExtractor(client).videoFromUrl(link, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }
                        }
                    }

                    link.contains("vidoza") && hosterSelection?.contains("vidoza") == true -> {
                        if (!link.contains("https:")) {
                            val url = "https:$link"
                            val quality = "Vidoza"
                            val video = VidozaExtractor(client).videoFromUrl(url, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        } else {
                            val quality = "Vidoza"
                            val video = VidozaExtractor(client).videoFromUrl(link, quality)
                            if (video != null) {
                                videoList.add(video)
                            }
                        }
                    }

                    link.contains("//voe.sx") || link.contains("//launchreliantcleaverriver") ||
                        link.contains("//fraudclatterflyingcar") ||
                        link.contains("//uptodatefinishconferenceroom") || link.contains("//realfinanceblogcenter") && hosterSelection?.contains("voe") == true -> {
                        videoList.addAll(VoeExtractor(client).videosFromUrl(if (link.contains("https:")) link else "https:$link"))
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

    // Search

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$apiUrl/data/browse/?lang=2&keyword=$query&year=&rating=&votes=&genre=&country=&cast=&directors=&type=&order_by=&page=$page", headers = Headers.headersOf("if-none-match", ""))

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val pager = jObject["pager"]!!.jsonObject
        val lastPage = pager.jsonObject["endPage"]!!.jsonPrimitive.int
        val page = pager.jsonObject["currentPage"]!!.jsonPrimitive.int
        val hasNextPage = page < lastPage
        val array = jObject["movies"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["_id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$apiUrl/data/watch/?_id=$animeId")
            if (item.jsonObject["tv"]!!.jsonPrimitive.int == 1) {
                anime.thumbnail_url = "https://image.tmdb.org/t/p/w300" + (
                    item.jsonObject["poster_path_season"]?.jsonPrimitive?.content
                        ?: ""
                    )
            } else {
                anime.thumbnail_url = "https://image.tmdb.org/t/p/w300" + (
                    item.jsonObject["poster_path"]?.jsonPrimitive?.content
                        ?: ""
                    )
            }
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(apiUrl + anime.url, headers = Headers.headersOf("if-modified-since", ""))
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        return parseAnimeDetailsParseJson(responseString)
    }

    private fun parseAnimeDetailsParseJson(jsonLine: String?): SAnime {
        val anime = SAnime.create()
        val jsonData = jsonLine ?: return anime
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        anime.title = jObject.jsonObject["title"]!!.jsonPrimitive.content
        anime.description = jObject.jsonObject["storyline"]!!.jsonPrimitive.content
        return anime
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Streamtape", "Voe", "StreamZ", "Vidoza")
            entryValues = arrayOf("https://streamtape.com", "https://voe.sx", "https://streamz.ws", "https://vidoza.net")
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
            entries = arrayOf("Streamtape", "Voe", "StreamZ", "Vidoza")
            entryValues = arrayOf("stape", "voe", "streamz", "vidoza")
            setDefaultValue(setOf("stape", "voe", "streamz", "vidoza"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        val limitQualities = SwitchPreferenceCompat(screen.context).apply {
            key = "limit_qualities"
            title = "Anzahl der Videos auf 15 begrenzen"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
        screen.addPreference(limitQualities)
    }
}
