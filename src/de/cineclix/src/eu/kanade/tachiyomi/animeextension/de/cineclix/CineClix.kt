package eu.kanade.tachiyomi.animeextension.de.cineclix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.cineclix.extractors.StreamVidExtractor
import eu.kanade.tachiyomi.animeextension.de.cineclix.extractors.SuperVideoExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class CineClix : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "CineClix"

    override val baseUrl = "https://cineclix.de"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun popularAnimeRequest(page: Int): Request = GET(
        "$baseUrl/api/v1/channel/64?returnContentOnly=true&restriction=&order=rating:desc&paginate=simple&perPage=50&query=&page=$page",
        headers = Headers.headersOf("referer", "$baseUrl/movies?order=rating%3Adesc"),
    )

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val jO = jObject.jsonObject["pagination"]!!.jsonObject
        val nextPage = jO.jsonObject["next_page"]!!.jsonPrimitive.int
        // .substringAfter("page=").toInt()
        val page = jO.jsonObject["current_page"]!!.jsonPrimitive.int
        val hasNextPage = page < nextPage
        val array = jO["data"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["name"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/api/v1/titles/$animeId?load=images,genres,productionCountries,keywords,videos,primaryVideo,seasons,compactCredits")
            anime.thumbnail_url = item.jsonObject["poster"]?.jsonPrimitive?.content ?: item.jsonObject["backdrop"]?.jsonPrimitive?.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // episodes

    override fun episodeListRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers = Headers.headersOf("referer", baseUrl))

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val url = response.request.url.toString()
        return parseEpisodeAnimeJson(responseString, url)
    }

    private fun parseEpisodeAnimeJson(jsonLine: String?, url: String): List<SEpisode> {
        val jsonData = jsonLine ?: return emptyList()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val episodeList = mutableListOf<SEpisode>()
        val mId = jObject.jsonObject["title"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val season = jObject.jsonObject["seasons"]?.jsonObject
        if (season != null) {
            val dataArray = season.jsonObject["data"]!!.jsonArray
            val next = season.jsonObject["next_page"]?.jsonPrimitive?.content
            if (next != null) {
                val seNextJsonData = client.newCall(GET("$baseUrl/api/v1/titles/$mId/seasons?perPage=8&query=&page=$next", headers = Headers.headersOf("referer", baseUrl))).execute().body.string()
                val seNextJObject = json.decodeFromString<JsonObject>(seNextJsonData)
                val seasonNext = seNextJObject.jsonObject["pagination"]!!.jsonObject
                val dataNextArray = seasonNext.jsonObject["data"]!!.jsonArray
                val dataAllArray = dataArray.plus(dataNextArray)
                for (item in dataAllArray) {
                    val id = item.jsonObject["title_id"]!!.jsonPrimitive.content
                    val num = item.jsonObject["number"]!!.jsonPrimitive.content
                    val seUrl = "$baseUrl/api/v1/titles/$id/seasons/$num?load=episodes,primaryVideo"
                    val seJsonData = client.newCall(GET(seUrl, headers = Headers.headersOf("referer", baseUrl))).execute().body.string()
                    val seJObject = json.decodeFromString<JsonObject>(seJsonData)
                    val epObject = seJObject.jsonObject["episodes"]!!.jsonObject
                    val epDataArray = epObject.jsonObject["data"]!!.jsonArray.reversed()
                    for (epItem in epDataArray) {
                        val episode = SEpisode.create()
                        val seNum = epItem.jsonObject["season_number"]!!.jsonPrimitive.content
                        val epNum = epItem.jsonObject["episode_number"]!!.jsonPrimitive.content
                        episode.name = "Staffel $seNum Folge $epNum : " + epItem.jsonObject["name"]!!.jsonPrimitive.content
                        episode.episode_number = epNum.toFloat()
                        val epId = epItem.jsonObject["title_id"]!!.jsonPrimitive.content
                        episode.setUrlWithoutDomain("$baseUrl/api/v1/titles/$epId/seasons/$seNum/episodes/$epNum?load=videos,compactCredits,primaryVideo")
                        episodeList.add(episode)
                    }
                }
            } else {
                for (item in dataArray) {
                    val id = item.jsonObject["title_id"]!!.jsonPrimitive.content
                    val num = item.jsonObject["number"]!!.jsonPrimitive.content
                    val seUrl = "$baseUrl/api/v1/titles/$id/seasons/$num?load=episodes,primaryVideo"
                    val seJsonData = client.newCall(GET(seUrl, headers = Headers.headersOf("referer", baseUrl))).execute().body.string()
                    val seJObject = json.decodeFromString<JsonObject>(seJsonData)
                    val epObject = seJObject.jsonObject["episodes"]!!.jsonObject
                    val epDataArray = epObject.jsonObject["data"]!!.jsonArray.reversed()
                    for (epItem in epDataArray) {
                        val episode = SEpisode.create()
                        val seNum = epItem.jsonObject["season_number"]!!.jsonPrimitive.content
                        val epNum = epItem.jsonObject["episode_number"]!!.jsonPrimitive.content
                        episode.name = "Staffel $seNum Folge $epNum : " + epItem.jsonObject["name"]!!.jsonPrimitive.content
                        episode.episode_number = epNum.toFloat()
                        val epId = epItem.jsonObject["title_id"]!!.jsonPrimitive.content
                        episode.setUrlWithoutDomain("$baseUrl/api/v1/titles/$epId/seasons/$seNum/episodes/$epNum?load=videos,compactCredits,primaryVideo")
                        episodeList.add(episode)
                    }
                }
            }
        } else {
            val episode = SEpisode.create()
            episode.episode_number = 1F
            episode.name = "Film"
            episode.setUrlWithoutDomain(url)
            episodeList.add(episode)
        }
        return episodeList
    }

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(baseUrl + episode.url, headers = Headers.headersOf("referer", baseUrl))
    }

    override fun videoListParse(response: Response): List<Video> {
        val responseString = response.body.string()
        val url = response.request.url.toString()
        return videosFromJson(responseString, url)
    }

    private fun videosFromJson(jsonLine: String?, url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("stape", "supv", "mix", "svid", "dood", "voe"))
        val jsonData = jsonLine ?: return emptyList()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        if (url.contains("episodes")) {
            val epObject = jObject.jsonObject["episode"]!!.jsonObject
            val videoArray = epObject.jsonObject["videos"]!!.jsonArray
            for (item in videoArray) {
                val host = item.jsonObject["name"]!!.jsonPrimitive.content
                val eUrl = item.jsonObject["src"]!!.jsonPrimitive.content
                when {
                    host.contains("streamtape") && hosterSelection?.contains("stape") == true -> {
                        val quality = "Streamtape"
                        val video = StreamTapeExtractor(client).videoFromUrl(eUrl, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    host.contains("supervideo") && hosterSelection?.contains("supv") == true -> {
                        val video = SuperVideoExtractor(client).videosFromUrl(eUrl)
                        videoList.addAll(video)
                    }
                    host.contains("mixdrop") && hosterSelection?.contains("mix") == true -> {
                        val video = MixDropExtractor(client).videoFromUrl(eUrl)
                        videoList.addAll(video)
                    }
                    host.contains("streamvid") && hosterSelection?.contains("svid") == true -> {
                        val video = StreamVidExtractor(client).videosFromUrl(eUrl)
                        videoList.addAll(video)
                    }
                    host.contains("DoodStream") && hosterSelection?.contains("dood") == true -> {
                        val video = DoodExtractor(client).videoFromUrl(eUrl)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    host.contains("VOE.SX") && hosterSelection?.contains("voe") == true -> {
                        videoList.addAll(VoeExtractor(client).videosFromUrl(eUrl))
                    }
                }
            }
        } else {
            val titleObject = jObject.jsonObject["title"]!!.jsonObject
            val videoArray = titleObject.jsonObject["videos"]!!.jsonArray
            for (item in videoArray) {
                val host = item.jsonObject["name"]!!.jsonPrimitive.content
                val fUrl = item.jsonObject["src"]!!.jsonPrimitive.content
                when {
                    host.contains("streamtape") && hosterSelection?.contains("stape") == true -> {
                        val quality = "Streamtape"
                        val video = StreamTapeExtractor(client).videoFromUrl(fUrl, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    host.contains("supervideo") && hosterSelection?.contains("supv") == true -> {
                        val video = SuperVideoExtractor(client).videosFromUrl(fUrl)
                        videoList.addAll(video)
                    }
                    host.contains("mixdrop") && hosterSelection?.contains("mix") == true -> {
                        val video = MixDropExtractor(client).videoFromUrl(fUrl)
                        videoList.addAll(video)
                    }
                    host.contains("streamvid") && hosterSelection?.contains("svid") == true -> {
                        val video = StreamVidExtractor(client).videosFromUrl(fUrl)
                        videoList.addAll(video)
                    }
                    host.contains("DoodStream") && hosterSelection?.contains("dood") == true -> {
                        val video = DoodExtractor(client).videoFromUrl(fUrl)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    host.contains("VOE.SX") && hosterSelection?.contains("voe") == true -> {
                        videoList.addAll(VoeExtractor(client).videosFromUrl(fUrl))
                    }
                }
            }
        }
        return videoList
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET(
        "$baseUrl/api/v1/search/$query?query=$query",
        headers = Headers.headersOf("referer", "$baseUrl/search/$query"),
    )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val array = jObject["results"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["name"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["id"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("$baseUrl/api/v1/titles/$animeId?load=images,genres,productionCountries,keywords,videos,primaryVideo,seasons,compactCredits")
            anime.thumbnail_url = item.jsonObject["poster"]?.jsonPrimitive?.content ?: item.jsonObject["backdrop"]?.jsonPrimitive?.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage = false)
    }
    // Details

    override fun animeDetailsRequest(anime: SAnime): Request = GET(baseUrl + anime.url, headers = Headers.headersOf("referer", baseUrl))

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        return parseAnimeDetailsParseJson(responseString)
    }

    private fun parseAnimeDetailsParseJson(jsonLine: String?): SAnime {
        val anime = SAnime.create()
        val jsonData = jsonLine ?: return anime
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val jO = jObject.jsonObject["title"]!!.jsonObject
        anime.title = jO.jsonObject["name"]!!.jsonPrimitive.content
        anime.description = jO.jsonObject["description"]!!.jsonPrimitive.content
        val genArray = jO.jsonObject["genres"]!!.jsonArray
        val genres = mutableListOf<String>()
        for (item in genArray) {
            val genre = item.jsonObject["display_name"]!!.jsonPrimitive.content
            genres.add(genre)
        }
        anime.genre = genres.joinToString { it }
        anime.thumbnail_url = jO.jsonObject["poster"]?.jsonPrimitive?.content ?: jO.jsonObject["backdrop"]?.jsonPrimitive?.content
        return anime
    }

    // Latest

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Streamtape", "SuperVideo", "MixDrop", "StreamVid", "DoodStream", "Voe")
            entryValues = arrayOf("https://streamtape", "https://supervideo", "https://mixdrop", "https://streamvid", "https://dood", "https://voe")
            setDefaultValue("https://streamtape")
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
            entries = arrayOf("Streamtape", "SuperVideo", "MixDrop", "StreamVid", "DoodStream", "Voe")
            entryValues = arrayOf("stape", "supv", "mix", "svid", "dood", "voe")
            setDefaultValue(setOf("stape", "supv", "mix", "svid", "dood", "voe"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
