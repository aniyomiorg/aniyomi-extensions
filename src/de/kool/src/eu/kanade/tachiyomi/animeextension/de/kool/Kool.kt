package eu.kanade.tachiyomi.animeextension.de.kool

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.movie4k.extractors.VidozaExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Kool : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Kool"

    override val baseUrl = "https://www.kool.to"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private fun mxhub(): String {
        val mhubjson = client.newCall(
            POST(
                "https://www.dezor.net/api/app/ping",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "accept", "application/json"),
                body =
                """
                    {
                          "reason": "ping",
                          "locale": "de",
                          "theme": "dark",
                          "metadata": {
                            "device": {
                              "type": "Tablet",
                              "brand": "google",
                              "model": "Pixel 5",
                              "name": "Pixel 5",
                              "uniqueId": "17623a364c1eab4b"
                            },
                            "os": {
                              "name": "android",
                              "version": "12",
                              "abis": [
                                "x86_64",
                                "arm64-v8a",
                                "x86",
                                "armeabi-v7a",
                                "armeabi"
                              ],
                              "host": "2e977b6bc000001"
                            },
                            "app": {
                              "platform": "android",
                              "version": "1.1.2",
                              "buildId": "97245000",
                              "engine": "hbc85",
                              "signatures": [
                                "43c308d52a6d51a07092ecd410963f26baae6a0e47d57fd718663a55e3d2d5e4"
                              ],
                              "installer": "com.android.vending"
                            },
                            "version": {
                              "package": "net.dezor.browser",
                              "binary": "1.1.2",
                              "js": "1.1.2"
                            }
                          },
                          "appFocusTime": 120169,
                          "playDuration": 0,
                          "devMode": true,
                          "hasMhub": true,
                          "castConnected": false,
                          "package": "net.dezor.browser",
                          "version": "1.1.2",
                          "process": "app",
                          "firstAppStart": 1677833802384,
                          "lastAppStart": 1677833802384,
                          "ipLocation": {
                            "ip": "0.0.0.0",
                            "country": "DE",
                            "city": "Berlin"
                          },
                          "adblockEnabled": false,
                          "proxy": {
                            "supported": true,
                            "enabled": false
                          }
                    }
                """.toRequestBody("application/json".toMediaType()),
            ),
        ).execute().body.string()
        val jsonData = json.decodeFromString<JsonObject>(mhubjson)
        return jsonData["mhub"]!!.jsonPrimitive.content
    }

    override fun popularAnimeRequest(page: Int): Request {
        val mhub = mxhub()
        val tpage = page - 1
        return POST(
            "$baseUrl/kool/mediahubmx-catalog.json",
            headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
            body =
            """
             {
                  "language": "de",
                  "region": "DE",
                  "catalogId": "tmdb.movie",
                  "id": "movie/popular",
                  "adult": false,
                  "search": "",
                  "sort": "popularity",
                  "filter": {},
                  "cursor":
                  ${
                when (tpage) {
                    0 -> {
                        "null"
                    }
                    1 -> {
                        8
                    }
                    else -> {
                        tpage * 8 - (tpage - 1)
                    }
                }
            },
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val popularcursor = jObject.jsonObject["nextCursor"]?.jsonPrimitive?.content.toString()
        val hasNextPage: Boolean = !popularcursor.contains("null")
        val array = jObject["items"]!!.jsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["name"]!!.jsonPrimitive.content
            val animeId = item.jsonObject["ids"]!!.jsonObject["tmdb_id"]!!.jsonPrimitive.content
            val type = item.jsonObject["type"]!!.jsonPrimitive.content
            if (type == "iptv") {
                val url = item.jsonObject["url"]!!.jsonPrimitive.content
                anime.setUrlWithoutDomain(url)
            } else {
                anime.setUrlWithoutDomain("$baseUrl/data/watch/?_id=$animeId&type=$type")
            }

            anime.thumbnail_url = item.jsonObject["images"]!!.jsonObject["poster"]?.jsonPrimitive?.content ?: item.jsonObject["images"]!!.jsonObject["backdrop"]?.jsonPrimitive?.content
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    // episodes

    override fun episodeListRequest(anime: SAnime): Request {
        val mhub = mxhub()
        if (anime.url.substringAfter("&type=") == "movie") {
            return POST(
                "$baseUrl/kool-cluster/mediahubmx-source.json?id=${anime.url.substringAfter("?_id=").substringBefore("&type")}&name=${anime.title}&type=movie",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "type": "${anime.url.substringAfter("&type=")}",
                  "ids": {
                    "tmdb_id": "${anime.url.substringAfter("?_id=").substringBefore("&type")}"
                  },
                  "name": "${anime.title}",
                  "episode": {},
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        } else if (anime.url.substringAfter("&type=") == "series") {
            return POST(
                "$baseUrl/kool/mediahubmx-item.json?id=${anime.url.substringAfter("?_id=").substringBefore("&type")}&name=${anime.title}&type=series",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "type": "${anime.url.substringAfter("&type=")}",
                  "ids": {
                    "tmdb_id": "${anime.url.substringAfter("?_id=").substringBefore("&type")}"
                  },
                  "name": "${anime.title}",
                  "episode": {},
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        } else {
            return POST(
                "$baseUrl/kool-cluster/mediahubmx-resolve.json?url=${anime.url}&type=tv",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "url": "$baseUrl${anime.url}",
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseString = response.body.string()
        val url = response.request.url.toString()
        val type = url.substringAfter("&type=")
        if (type == "movie") {
            return parseMoviePage(url)
        } else if (type == "series") {
            return parseEpisodePage(responseString, url)
        } else {
            return parseTvPage(url)
        }
    }

    private fun parseMoviePage(url: String): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = "Film"
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(url)
        episodeList.add(episode)
        return episodeList.reversed()
    }

    private fun parseEpisodePage(jsonLine: String?, url: String): List<SEpisode> {
        val jsonData = jsonLine ?: return mutableListOf()
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val episodeList = mutableListOf<SEpisode>()
        val array = jObject["episodes"]!!.jsonArray
        for (item in array) {
            val episode = SEpisode.create()
            val id = item.jsonObject["ids"]!!.jsonObject["tmdb_episode_id"]!!.jsonPrimitive.content
            episode.name = "Staffel ${item.jsonObject["season"]!!.jsonPrimitive.int} " +
                "Folge ${item.jsonObject["episode"]!!.jsonPrimitive.int} : " + item.jsonObject["name"]!!.jsonPrimitive.content
            episode.episode_number = item.jsonObject["episode"]!!.jsonPrimitive.float
            episode.setUrlWithoutDomain("$url&epid=$id&season=${item.jsonObject["season"]!!.jsonPrimitive.int}&ep=${item.jsonObject["episode"]!!.jsonPrimitive.int}&epname=${item.jsonObject["name"]!!.jsonPrimitive.content}")
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseTvPage(url: String): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = "TV"
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(url)
        episodeList.add(episode)
        return episodeList.reversed()
    }

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        val mhub = mxhub()
        val id = episode.url.substringAfter("?id=").substringBefore("&name=")
        val name = java.net.URLDecoder.decode(episode.url.substringAfter("&name=").substringBefore("&type="), "utf-8")
        val type = episode.url.substringAfter("&type=").substringBefore("&epid")
        if (type == "movie") {
            return POST(
                "$baseUrl/kool-cluster/mediahubmx-source.json",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "type": "$type",
                  "ids": {
                    "tmdb_id": "$id"
                  },
                  "name": "$name",
                  "episode": {},
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        } else if (type == "series") {
            return POST(
                "$baseUrl/kool-cluster/mediahubmx-source.json",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "type": "$type",
                  "ids": {
                    "tmdb_id": "$id"
                  },
                  "name": "$name",
                  "episode": {
                    "name": "${episode.url.substringAfter("&epname=")}",
                    "ids": {
                        "tmdb_episode_id": "${episode.url.substringAfter("&epid=").substringBefore("&season=")}"
                    },
                    "season": ${episode.url.substringAfter("&season=").substringBefore("&ep=")},
                    "episode": ${episode.url.substringAfter("&ep=").substringBefore("&epname=")}
                  },
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        } else {
            return POST(
                "$baseUrl/kool-cluster/mediahubmx-resolve.json",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "url": "$baseUrl${episode.url.substringAfter("?url=").substringBefore("&type=")}",
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        return videosFromElement(response)
    }

    private fun videosFromElement(response: Response): List<Video> {
        val jsonData = response.body.string()
        val array = json.decodeFromString<JsonArray>(jsonData)
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("voe", "stape", "vidoza", "clip", "fmoon"))
        for (item in array) {
            when {
                item.jsonObject["url"]!!.jsonPrimitive.content.contains("https://voe") ||
                    item.jsonObject["url"]!!.jsonPrimitive.content.contains("scatch176duplicities") && hosterSelection?.contains("voe") == true -> {
                    val videoUrl = item.jsonObject["url"]!!.jsonPrimitive.content
                    videoList.addAll(VoeExtractor(client).videosFromUrl(videoUrl))
                }
                item.jsonObject["url"]!!.jsonPrimitive.content.contains("https://clipboard") && hosterSelection?.contains("clip") == true -> {
                    val videoUrl = item.jsonObject["url"]!!.jsonPrimitive.content
                    val video = Video(videoUrl, "Clipboard", videoUrl)
                    videoList.add(video)
                }
                item.jsonObject["url"]!!.jsonPrimitive.content.contains("https://streamtape") && hosterSelection?.contains("stape") == true -> {
                    val videoUrl = item.jsonObject["url"]!!.jsonPrimitive.content
                    val video = StreamTapeExtractor(client).videoFromUrl(videoUrl)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                item.jsonObject["url"]!!.jsonPrimitive.content.contains("https://vidoza") && hosterSelection?.contains("vidoza") == true -> {
                    val videoUrl = item.jsonObject["url"]!!.jsonPrimitive.content
                    val video = VidozaExtractor(client).videoFromUrl(videoUrl, "Vidoza")
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                item.jsonObject["url"]!!.jsonPrimitive.content.contains("https://filemoon.sx") && hosterSelection?.contains("fmoon") == true -> {
                    val videoUrl = item.jsonObject["url"]!!.jsonPrimitive.content
                    videoList.addAll(FilemoonExtractor(client).videosFromUrl(videoUrl))
                }
                response.request.url.toString().contains("kool-cluster/mediahubmx-resolve.json") -> {
                    val videoUrl = item.jsonObject["url"]!!.jsonPrimitive.content
                    val video = Video(videoUrl, "TV", videoUrl)
                    videoList.add(video)
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

    private var allquery: String = ""

    private var tpage: Int = 0

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val mhub = mxhub()
        allquery = query
        tpage = page - 1
        var search: String? = null
        filters.forEach { filter ->
            when (filter) {
                is SearchFilter -> {
                    Log.i("searchAnimeRequest", filter.toUriPart())
                    search = filter.toUriPart()
                }
                else -> {}
            }
        }
        when {
            search?.contains("filme") == true -> {
                return POST(
                    "$baseUrl/kool/mediahubmx-catalog.json",
                    headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                    body =
                    """
             {
                  "language": "de",
                  "region": "DE",
                  "catalogId": "tmdb.movie",
                  "id": "tmdb.movie",
                  "adult": false,
                  "search": "$query",
                  "sort": "",
                  "filter": {},
                  "cursor": ${
                        if (tpage == 0) {
                            "null"
                        } else if (tpage == 1) {
                            8
                        } else {
                            tpage * 8 - (tpage - 1)
                        }
                    },
                  "clientVersion": "1.1.3"
            }
            """.toRequestBody("application/json".toMediaType()),
                )
            }
            search?.contains("serien") == true -> {
                return POST(
                    "$baseUrl/kool/mediahubmx-catalog.json",
                    headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                    body =
                    """
             {
                  "language": "de",
                  "region": "DE",
                  "catalogId": "tmdb.series",
                  "id": "tmdb.series",
                  "adult": false,
                  "search": "$query",
                  "sort": "",
                  "filter": {},
                  "cursor": ${
                        if (tpage == 0) {
                            "null"
                        } else if (tpage == 1){
                            8
                        } else {
                            tpage * 8 - (tpage - 1)
                        }
                    },
                  "clientVersion": "1.1.3"
            }
            """.toRequestBody("application/json".toMediaType()),
                )
            }
            search?.contains("tv") == true -> {
                return POST(
                    "$baseUrl/kool-cluster/mediahubmx-catalog.json",
                    headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                    body =
                    """
             {
                  "language": "de",
                  "region": "DE",
                  "catalogId": "kool-iptv",
                  "id": "kool-iptv",
                  "adult": false,
                  "search": "$query",
                  "sort": "",
                  "filter": {},
                  "cursor": ${
                        if (tpage == 0) {
                            "null"
                        } else if (tpage == 1){
                            8
                        } else {
                            tpage * 8 - (tpage - 1)
                        }
                    },
                  "clientVersion": "1.1.3"
            }
            """.toRequestBody("application/json".toMediaType()),
                )
            }
            else -> {
                return POST(
                    "$baseUrl/kool/mediahubmx-catalog.json",
                    headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                    body =
                    """
                            {
                              "language": "de",
                              "region": "DE",
                              "catalogId": "tmdb.movie",
                              "id": "tmdb.movie",
                              "adult": false,
                              "search": "$query",
                              "sort": "",
                              "filter": {},
                              "cursor": ${
                        if (tpage == 0) {
                            "null"
                        } else if (tpage == 1) {
                            8
                        } else {
                            tpage * 8 - (tpage - 1)
                        }
                    },
                              "clientVersion": "1.1.3"
                            }
                     """.toRequestBody("application/json".toMediaType()),
                )
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val moviejson = response.body.string()
        val url = response.request.url.toString()
        return parseSearchAnimeJson(moviejson, url)
    }

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            AnimeFilter.Header("Wähle Suche aus!"),
            AnimeFilter.Separator(),
            SearchFilter(getSearchList()),
        )
    }

    private class SearchFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Suche", vals)

    private fun getSearchList() = arrayOf(
        Pair("Filme", "filme"),
        Pair("Serien", "serien"),
        Pair("TV", "tv"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // private var animeListS = mutableListOf<SAnime>()

    private fun parseSearchAnimeJson(movieJson: String?, url: String): AnimesPage {
        // Define the batch size for processing JSON items
        val bATCHSIZE = 50

        val animeList = mutableListOf<SAnime>()
        val movieJsonData = movieJson ?: return AnimesPage(emptyList(), false)
        val movieJObject = json.decodeFromString<JsonObject>(movieJsonData)
        val movieArray = movieJObject["items"]?.jsonArray ?: return AnimesPage(emptyList(), false)
        val searchMovieCursor = movieJObject.jsonObject["nextCursor"]?.jsonPrimitive?.content.orEmpty()

        var hasNextPage = !searchMovieCursor.contains("null")

        for (item in movieArray) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
            val idsObject = item.jsonObject["ids"]?.jsonObject
            val animeId = idsObject?.get("urlId")?.jsonPrimitive?.content ?: idsObject?.get("tmdb_id")?.jsonPrimitive?.content
            val type = item.jsonObject["type"]?.jsonPrimitive?.content.orEmpty()
            when {
                type == "iptv" -> {
                    anime.setUrlWithoutDomain(item.jsonObject["url"]?.jsonPrimitive?.content.orEmpty())
                }
                else -> {
                    anime.url = item.jsonObject["url"]?.jsonPrimitive?.content ?: "$baseUrl/data/watch/?_id=$animeId&type=$type"
                }
            }
            if (!url.contains("kool-cluster")) {
                anime.thumbnail_url = item.jsonObject["images"]?.jsonObject?.let { images ->
                    images["poster"]?.jsonPrimitive?.content ?: images["backdrop"]?.jsonPrimitive?.content
                }
            }
            animeList.add(anime)

            // If the list size reaches a certain limit, return a batch of results to prevent crashes
            if (animeList.size >= bATCHSIZE) {
                val animeListS = animeList.filterIndexed { index, _ -> index in 1..50 }
                return AnimesPage(animeListS.takeIf { it.isNotEmpty() } ?: animeList, hasNextPage)
            }
        }

        // If the entire JSON response has been processed, return the remaining results
        val animeListS = animeList.filterIndexed { index, _ -> index in 1..50 }
        return AnimesPage(animeListS.takeIf { it.isNotEmpty() } ?: animeList, hasNextPage)
    }

    // Details

    override fun animeDetailsRequest(anime: SAnime): Request {
        val mhub = mxhub()
        val type = anime.url.substringAfter("&type=")
        if (type == "movie" || type == "series") {
            return POST(
                "$baseUrl/kool/mediahubmx-item.json",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "type": "${anime.url.substringAfter("&type=")}",
                  "ids": {
                    "tmdb_id": "${anime.url.substringAfter("?_id=").substringBefore("&type=movie")}"
                  },
                  "name": "${anime.title}",
                  "episode": {},
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        } else {
            return POST(
                "$baseUrl/kool-cluster/mediahubmx-resolve.json",
                headers = Headers.headersOf("content-type", "application/json; charset=utf-8", "mediahubmx-signature", mhub, "user-agent", "MediaHubMX/2"),
                body =
                """
             {
                  "language": "de",
                  "region": "DE",
                  "url": "$baseUrl${anime.url}",
                  "clientVersion": "1.1.3"
             }
            """.toRequestBody("application/json".toMediaType()),
            )
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val responseString = response.body.string()
        val url = response.request.url.toString()
        return parseAnimeDetailsParseJson(responseString, url)
    }

    private fun parseAnimeDetailsParseJson(jsonLine: String?, url: String): SAnime {
        val anime = SAnime.create()
        if (url.contains("mediahubmx-item.json")) {
            val jsonData = jsonLine ?: return anime
            val jObject = json.decodeFromString<JsonObject>(jsonData)
            anime.title = jObject.jsonObject["name"]!!.jsonPrimitive.content
            anime.description = jObject.jsonObject["description"]?.jsonPrimitive?.content
            anime.thumbnail_url = jObject.jsonObject["images"]!!.jsonObject["poster"]?.jsonPrimitive?.content
                ?: jObject.jsonObject["images"]!!.jsonObject["backdrop"]?.jsonPrimitive?.content
            return anime
        } else {
            val jsonData = jsonLine ?: return anime
            val jArray = json.decodeFromString<JsonArray>(jsonData)
            for (item in jArray) {
                anime.title = item.jsonObject["name"]!!.jsonPrimitive.content
                anime.description = item.jsonObject["name"]!!.jsonPrimitive.content
            }
            return anime
        }
    }

    // Latest

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Streamtape", "Voe", "Vidoza", "Clipboard", "Filemoon")
            entryValues = arrayOf("https://streamtape.com", "https://voe.sx", "https://vidoza.net", "https://clipboard.cc", "https://filemoon.sx")
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
            entries = arrayOf("Streamtape", "Voe", "Vidoza", "Clipboard", "Filemoon")
            entryValues = arrayOf("stape", "voe", "vidoza", "clip", "fmoon")
            setDefaultValue(setOf("stape", "voe", "vidoza", "clip", "fmoon"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
