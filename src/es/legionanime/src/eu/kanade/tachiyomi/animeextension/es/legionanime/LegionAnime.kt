package eu.kanade.tachiyomi.animeextension.es.legionanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.legionanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animeextension.es.legionanime.extractors.YourUploadExtractor
import eu.kanade.tachiyomi.animeextension.es.legionanime.extractors.ZippyExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class LegionAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "LegionAnime"

    override val baseUrl = "https://legionanime.club/api"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val headers1 = headersBuilder().add("json", jsonString).add("User-Agent", "android l3gi0n4N1mE %E6%9C%AC%E7%89%A9").build()

    override fun animeDetailsParse(document: Document): SAnime {
        val jsonResponse = json.decodeFromString<JsonObject>(document.body().text())["response"]!!.jsonObject
        val anime = jsonResponse["anime"]!!.jsonObject
        val studioId = anime["studios"]!!.jsonPrimitive.content.split(",")
        val studio = studioId.map { id -> studiosMap.filter { it.value == id.toInt() }.keys.first() }
        return SAnime.create().apply {
            title = anime["name"]!!.jsonPrimitive.content
            description = anime["synopsis"]!!.jsonPrimitive.content
            genre = anime["genres"]!!.jsonPrimitive.content
            author = studio.joinToString { it.toString() }
            status = when (anime["status"]!!.jsonPrimitive.content) {
                "En emisión" -> SAnime.ONGOING
                "Finalizado" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun animeDetailsRequest(anime: SAnime): Request = episodeListRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonResponse = json.decodeFromString<JsonObject>(response.asJsoup().body().text())
        val episodes = jsonResponse["response"]!!.jsonObject["episodes"]!!.jsonArray

        return episodes.map {
            SEpisode.create().apply {
                name = "Episodio " + it.jsonObject["name"]!!.jsonPrimitive.content
                url = "$baseUrl/v2/episode_links/${it.jsonObject["id"]!!.jsonPrimitive.content}"
                date_upload = parseDate(it.jsonObject["release_date"]!!.jsonPrimitive.content)
            }
        }
    }

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers1)

    override fun latestUpdatesRequest(page: Int): Request {
        val body = FormBody.Builder().add("apyki", apyki).build()
        return POST(
            "$baseUrl/v2/directories?studio=0&not_genre=&year=&orderBy=2&language=&type=&duration=&search=&letter=0&limit=24&genre=&season=&page=${(page - 1) * 24}&status=",
            headers = headers1, body = body
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request {
        val body = FormBody.Builder().add("apyki", apyki).build()
        return POST(
            "$baseUrl/v2/directories?studio=0&not_genre=&year=&orderBy=4&language=&type=&duration=&search=&letter=0&limit=24&genre=&season=&page=${(page - 1) * 24}&status=",
            headers = headers1, body = body
        )
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<JsonObject>(response.asJsoup().body().text())
        try {
            val animeArray = responseJson["response"]!!.jsonArray
            return AnimesPage(
                animeArray.map {
                    val animeDetail = it.jsonObject
                    val animeId = animeDetail["id"]!!.jsonPrimitive.content
                    SAnime.create().apply {
                        title = animeDetail["nombre"]!!.jsonPrimitive.content
                        url = "$baseUrl/v1/episodes/$animeId"
                        thumbnail_url = aip.random() + animeDetail["img_url"]!!.jsonPrimitive.content
                    }
                },
                true
            )
        } catch (e: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val body = FormBody.Builder().add("apyki", apyki).build()

        val genreFilter = ((filters.find { it is TagFilter }) as? TagFilter)?.state ?: emptyList()
        val excludeGenreFilter = (filters.find { it is ExcludeTagFilter } as? ExcludeTagFilter)?.state ?: emptyList()
        val studioFilter = (filters.find { it is StudioFilter } as? StudioFilter)?.state ?: emptyList()
        val stateFilter = (filters.find { it is StateFilter } as? StateFilter) ?: StateFilter()

        val genre = try {
            if (genreFilter.isNotEmpty()) {
                genreFilter.filter { it.state }.map { genres[it.name] }.joinToString("%2C") { it.toString() }
            } else ""
        } catch (e: Exception) { "" }

        val excludeGenre = if (excludeGenreFilter.isNotEmpty()) {
            excludeGenreFilter.filter { it.state }.map { genres[it.name] }.joinToString("%2C") { it.toString() }
        } else ""

        val studio = if (studioFilter.isNotEmpty()) {
            studioFilter.filter { it.state }.map { studiosMap[it.name] }.joinToString("%2C") { it.toString() }
        } else 0

        val status = if (stateFilter.state != 0) stateFilter.toUriPart() else ""

        val url = "$baseUrl/v2/directories?studio=$studio&not_genre=$excludeGenre&year=&orderBy=4&language=&type=&duration=&search=$query&letter=0&limit=24&genre=$genre&season=&page=${(page - 1) * 24}&status=$status"

        return POST(
            url,
            headers = headers1, body = body
        )
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<JsonObject>(response.asJsoup().body().text())
        try {
            val animeArray = responseJson["response"]!!.jsonArray
            return AnimesPage(
                animeArray.map {
                    val animeDetail = it.jsonObject
                    val animeId = animeDetail["id"]!!.jsonPrimitive.content
                    SAnime.create().apply {
                        title = animeDetail["nombre"]!!.jsonPrimitive.content
                        url = "$baseUrl/v1/episodes/$animeId"
                        thumbnail_url = aip.random() + animeDetail["img_url"]!!.jsonPrimitive.content
                    }
                },
                false
            )
        } catch (e: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonResponse = json.decodeFromString<JsonObject>(response.asJsoup().body().text())
        val responseArray = jsonResponse["response"]!!.jsonObject
        val players = responseArray["players"]!!.jsonArray
        val videoList = mutableListOf<Video>()
        players.forEach {
            val server = it.jsonObject["option"]!!.jsonPrimitive.content
            val url = if (it.jsonObject["name"]!!.jsonPrimitive.content.startsWith("F-")) {
                it.jsonObject["name"]!!.jsonPrimitive.content.substringAfter("-")
            } else {
                it.jsonObject["name"]!!.jsonPrimitive.content.substringAfter("-").reversed()
            }
            try {
                when {
                    url.contains("streamtape") -> {
                        val video = StreamTapeExtractor(client).videoFromUrl(url, server)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    (url.contains("fembed") || url.contains("vanfem")) -> {
                        val newUrl = url.replace("fembed", "embedsito").replace("vanfem", "embedsito")
                        try {
                            videoList.addAll(FembedExtractor(client).videosFromUrl(newUrl, server))
                        } catch (_: Exception) {
                        }
                    }
                    url.contains("sb") -> {
                        val video = StreamSBExtractor(client).videosFromUrl(url, headers)
                        videoList.addAll(video)
                    }
                    url.contains("jkanime") -> {
                        videoList.add(JkanimeExtractor(client).getDesuFromUrl(url))
                    }
                    url.contains("/stream/amz.php?") -> {
                        try {
                            val video = amazonExtractor(url)
                            if (video.isNotBlank()) {
                                videoList.add(Video(video, server, video))
                            }
                        } catch (_: Exception) {
                        }
                    }
                    url.contains("yourupload") -> {
                        val headers = headers.newBuilder().add("referer", "https://www.yourupload.com/").build()
                        videoList.addAll(YourUploadExtractor(client).videoFromUrl(url, headers))
                    }
                    url.contains("zippyshare") -> {
                        val hostUrl = url.substringBefore("/v/")
                        val videoUrlD = ZippyExtractor().getVideoUrl(url, json)
                        val videoUrl = hostUrl + videoUrlD
                        videoList.add(Video(videoUrl, server, videoUrl))
                    }
                }
            } catch (_: Exception) {
                // ignore
            }
        }
        return videoList
    }

    private fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url.replace(".com", ".tv"))).execute().asJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)").data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")
        return try {
            if (client.newCall(GET(videoURl)).execute().code == 200) videoURl else ""
        } catch (_: Exception) {
            ""
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val body = FormBody.Builder().add("apyki", apyki).build()
        return POST(
            episode.url,
            headers1,
            body
        )
    }

    /* --FilterStuff-- */

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagFilter("Generos", checkboxesFrom(genres)),
        TagFilter("Ordernar Por", checkboxesFrom(orderby)),
        StateFilter(),
        StudioFilter("Estudio", checkboxesFrom(studiosMap)),
        ExcludeTagFilter("Excluir Genero", checkboxesFrom(genres)),
    )

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Emision", "1"),
            Pair("Finalizado", "2"),
            Pair("Proximamente", "3"),
        )
    )

    class TagCheckBox(tag: String) : AnimeFilter.CheckBox(tag, false)
    private fun checkboxesFrom(tagArray: Map<String, Int>): List<TagCheckBox> = tagArray.map { TagCheckBox(it.key) }
    class TagFilter(name: String, checkBoxes: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, checkBoxes)
    class StudioFilter(name: String, checkBoxes: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, checkBoxes)
    class ExcludeTagFilter(name: String, checkBoxes: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, checkBoxes)

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "FHD-EMBED Fembed:1080p", "FHD-EMBED Fembed:720p", "FHD-EMBED Fembed:480p", "FHD-EMBED Fembed:360p", "FHD-EMBED Fembed:240p", // Fembed
            "FHD-ALT Fembed:1080p", "FHD-ALT Fembed:720p", "FHD-ALT Fembed:480p", "FHD-ALT Fembed:360p", "FHD-ALT Fembed:240p", // Fembed-ALT
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", // Okru
            "StreamSB:360p", "StreamSB:480p", "StreamSB:720p", "StreamSB:1080p", // StreamSB
            "Xtreme S", "Nozomi", "Desu", "F1S-TAPE", "F1NIX" // video servers without resolution
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Desu")
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

    private fun List<Video>.sortIfContains(item: String): List<Video> {
        val newList = mutableListOf<Video>()
        for (video in this) {
            if (item in video.quality) {
                newList.add(0, video)
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "desu")!!
        return sortIfContains(quality)
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }
    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH)
        }
    }

    /* --Unused stuff-- */

    override fun popularAnimeSelector(): String = throw Exception("not used")

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun popularAnimeNextPageSelector(): String = throw Exception("not used")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoListSelector(): String = throw Exception("not used")

    override fun videoUrlParse(document: Document): String = throw Exception("not used")

    override fun searchAnimeSelector(): String = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun searchAnimeNextPageSelector(): String? = throw Exception("not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    override fun episodeListSelector(): String = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("not used")

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("not used")

    override fun latestUpdatesSelector(): String = throw Exception("not used")
}
