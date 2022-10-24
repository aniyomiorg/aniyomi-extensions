package eu.kanade.tachiyomi.animeextension.en.animeowl

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.animeowl.extractors.GogoCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.ceil

@ExperimentalSerializationApi
class AnimeOwl : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeOwl"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://animeowl.net")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending?page=$page")

    override fun popularAnimeSelector(): String = "div#anime-list > div.recent-anime"

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.page-item > a[rel=next]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("div > a").attr("href"))
            thumbnail_url = element.select("div.img-container > a > img").attr("src")
            title = element.select("a.title-link").text()
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent-episode/all")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList
    ): Observable<AnimesPage> {
        val limit = 30
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = """{"limit":$limit,"page":${page - 1},"pageCount":0,"value":"$query","sort":4,"selected":{"type":[],"genre":[],"year":[],"country":[],"season":[],"status":[],"sort":[],"language":[]}}""".toRequestBody(mediaType)

        val response = client.newCall(POST("$baseUrl/api/advance-search", body = body, headers = headers)).execute()
        val result = json.decodeFromString<JsonObject>(response.body!!.string())

        val total = result["total"]!!.jsonPrimitive.int
        val nextPage = ceil(total.toFloat() / limit).toInt() > page
        val data = result["results"]!!.jsonArray
        val animes = data.map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain("/anime/${item.jsonObject["anime_slug"]!!.jsonPrimitive.content}/")
                thumbnail_url = "$baseUrl${item.jsonObject["image"]!!.jsonPrimitive.content}"
                title = item.jsonObject["anime_name"]!!.jsonPrimitive.content
            }
        }

        return Observable.just(AnimesPage(animes, nextPage))
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        throw Exception("Not Used")

    override fun searchAnimeSelector(): String = throw Exception("Not Used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("Not Used")

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("Not Used")

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h3.anime-name").text()
        anime.genre = document.select("div.genre > a").joinToString { it.text() }
        anime.description = document.select("div.anime-desc.desc-content").text()
        // No author info so use type of anime
        anime.author = document.select("div.type > a").text()
        anime.status = parseStatus(document.select("div.status > span").text())

        // add alternative name to anime description
        val altName = "Other name(s): "
        document.select("h4.anime-alternatives").text()?.let {
            if (it.isBlank().not()) {
                anime.description = when {
                    anime.description.isNullOrBlank() -> altName + it
                    else -> anime.description + "\n\n$altName" + it
                }
            }
        }
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeId = response.asJsoup().select("div#unq-anime-id").attr("animeId")
        val episodesJson = client.newCall(GET("$baseUrl/api/anime/$animeId/episodes")).execute().body!!.string()
        val episodes = json.decodeFromString<JsonObject>(episodesJson)
        val subList = episodes["sub"]!!.jsonArray
        val dubList = episodes["dub"]!!.jsonArray
        val subSlug = episodes["sub_slug"]!!.jsonPrimitive.content
        val dubSlug = episodes["dub_slug"]!!.jsonPrimitive.content
        return subList.map { item ->
            val dub = dubList.find {
                it.jsonObject["name"]!!.jsonPrimitive.content == item.jsonObject["name"]!!.jsonPrimitive.content
            }
            SEpisode.create().apply {
                url = "{\"Sub\": \"https://portablegaming.co/watch/$subSlug/${item.jsonObject["episode_index"]!!.jsonPrimitive.content}\"," +
                    if (dub != null) {
                        "\"Dub\": \"https://portablegaming.co/watch/$dubSlug/${dub.jsonObject["episode_index"]!!.jsonPrimitive.content}\"}"
                    } else { "\"Dub\": \"\"}" }
                episode_number = item.jsonObject["name"]!!.jsonPrimitive.float
                name = "Episode " + item.jsonObject["name"]!!.jsonPrimitive.content
            }
        }.reversed()
    }

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not Used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<JsonObject>(episode.url)
        val videoList = mutableListOf<Video>()
        urlJson.mapNotNull { (key, value) ->
            val link = value.jsonPrimitive.content
            if (link.isNotEmpty()) {
                // We won't need the interceptor if the jwt signing key is found
                // Look at fileInterceptor.files for the signed jwt string
                val fileInterceptor = FileRequestInterceptor()
                val owlClient = client.newBuilder().addInterceptor(fileInterceptor).build()
                val response = owlClient.newCall(GET(link)).execute().asJsoup()
                val sources = response.select("ul.list-server > li > button")

                sources.mapNotNull { source ->
                    if (source.text() == "No Ads") {
                        videoList.addAll(
                            extractOwlVideo(source.attr("data-source"), fileInterceptor.files, key)
                        )
                    } else {
                        videoList.addAll(
                            extractGogoVideo(source.attr("data-source"), key)
                        )
                    }
                }
            }
        }
        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================
    private fun extractOwlVideo(link: String, files: List<Pair<String, Headers>>, lang: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val response = client.newCall(GET(baseUrl + link)).execute().body!!.string()
        val serverJson = json.decodeFromString<JsonObject>(response)

        files.map { (url, headers) ->
            if (url.contains("service=2")) {
                videoList.add(Video(url, "Kaido - Deafult - $lang", url, headers = headers))

                val luffyUrl = url.replace("service=2", "service=1")
                videoList.add(Video(luffyUrl, "Luffy - Deafult - $lang", luffyUrl, headers = headers))
            } else {
                if (url.contains("service=1")) {
                    videoList.add(Video(url, "Luffy - Deafult - $lang", url, headers = headers))

                    val kaidoUrl = url.replace("service=1", "service=2")
                    videoList.add(Video(kaidoUrl, "Kaido - Deafult - $lang", kaidoUrl, headers = headers))
                } else {
                    val luffyUrl = "$url&service=1"
                    videoList.add(Video(luffyUrl, "Luffy - Deafult - $lang", luffyUrl, headers = headers))

                    val kaidoUrl = "$url&service=2"
                    videoList.add(Video(kaidoUrl, "Kaido - Deafult - $lang", kaidoUrl, headers = headers))
                }
            }
        }

        if ("vidstream" in serverJson.keys) {
            val zoroUrl = serverJson["vidstream"]!!.jsonPrimitive.content
            val zoroHeaders = mapOf(
                Pair("referer", "https://portablegaming.co/"),
                Pair("origin", "https://portablegaming.co"),
                Pair("host", zoroUrl.toHttpUrl().host),
                Pair("Accept-Language", "en-US,en;q=0.9"),
                Pair("User-Agent", " Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36")
            )
            val zoroResponse = Jsoup.connect(zoroUrl).headers(zoroHeaders).execute().body()

            zoroResponse.substringAfter("#EXT-X-STREAM-INF:")
                .split("#EXT-X-STREAM-INF:").map {
                    val quality = "Zoro: " + it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p"
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")
                    videoList.add(Video(videoUrl, quality, videoUrl))
                }
        }

        return videoList
    }

    private fun extractGogoVideo(url: String, lang: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = client.newCall(GET(url)).execute().asJsoup()

        // Vidstreaming:
        GogoCdnExtractor(network.client, json).videosFromUrl(url).map {
            videoList.add(
                Video(
                    it.url, it.quality + " $lang",
                    it.videoUrl, headers = it.headers
                )
            )
        }

        // Doodstream mirror:
        document.select("div#list-server-more > ul > li.linkserver:contains(Doodstream)")
            .firstOrNull()?.attr("data-video")
            ?.let { link ->
                DoodExtractor(client).videosFromUrl(link).map {
                    videoList.add(
                        Video(
                            it.url, it.quality + " $lang",
                            it.videoUrl, headers = it.headers
                        )
                    )
                }
            }

        // StreamSB mirror:
        document.select("div#list-server-more > ul > li.linkserver:contains(StreamSB)")
            .firstOrNull()?.attr("data-video")
            ?.let { link ->
                StreamSBExtractor(client).videosFromUrl(link, headers).map {
                    videoList.add(
                        Video(
                            it.url, it.quality + " $lang",
                            it.videoUrl, headers = it.headers
                        )
                    )
                }
            }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Luffy")
        val lang = preferences.getString("preferred_language", "Sub")

        val newList = mutableListOf<Video>()
        if (quality != null || lang != null) {
            val qualityList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality!!)) {
                    qualityList.add(preferred, video)
                    preferred++
                } else {
                    qualityList.add(video)
                }
            }
            preferred = 0
            for (video in qualityList) {
                if (video.quality.contains(lang!!)) {
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

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            // IT says Not yet aired for some animes even tho there is available videos,
            // so I choose ONGOING as it's a better fit than the other choices
            "Not yet aired" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("animeowl.net")
            entryValues = arrayOf("https://animeowl.net")
            setDefaultValue("https://animeowl.net")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Luffy", "Kaido", "Zoro: 720p", "Zoro: 1080p")
            entryValues = arrayOf("Luffy", "Kaido", "Zoro: 720p", "Zoro: 1080p")
            setDefaultValue("Luffy")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val videoLangPref = ListPreference(screen.context).apply {
            key = "preferred_language"
            title = "Preferred Language"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue("Sub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoLangPref)
    }
}
