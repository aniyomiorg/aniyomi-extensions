package eu.kanade.tachiyomi.animeextension.es.legionanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.legionanime.extractors.JkanimeExtractor
import eu.kanade.tachiyomi.animeextension.es.legionanime.extractors.MediaFireExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
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

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val headers1 = headersBuilder().add("json", JSON_STRING).add("User-Agent", "android l3gi0n4N1mE %E6%9C%AC%E7%89%A9").build()

    override fun animeDetailsRequest(anime: SAnime): Request = episodeListRequest(anime)

    override fun animeDetailsParse(document: Document): SAnime {
        val jsonResponse = json.decodeFromString<JsonObject>(document.body().text())["response"]!!.jsonObject
        val anime = jsonResponse["anime"]!!.jsonObject
        val studioId = anime["studios"]!!.jsonPrimitive.content.split(",")
        val studio = try { studioId.map { id -> STUDIOS_MAP.filter { it.value == id.toInt() }.keys.first() } } catch (e: Exception) { emptyList() }
        val malid = anime["mal_id"]!!.jsonPrimitive.content
        var thumb: String? = null

        try {
            val jikanResponse = client.newCall(GET("https://api.jikan.moe/v4/anime/$malid")).execute().asJsoup().body().text()
            val jikanJson = json.decodeFromString<JsonObject>(jikanResponse)
            val pictures = jikanJson["data"]!!.jsonObject["images"]!!.jsonObject["jpg"]!!.jsonObject
            thumb = pictures["large_image_url"]!!.jsonPrimitive.content
        } catch (_: Exception) {
            // ignore
        }

        return SAnime.create().apply {
            title = anime["name"]!!.jsonPrimitive.content
            if (thumb != null) {
                thumbnail_url = thumb
            }
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

    override fun episodeListRequest(anime: SAnime): Request = GET(anime.url, headers1)
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

    override fun latestUpdatesRequest(page: Int): Request {
        val body = FormBody.Builder().add("apyki", API_KEY).build()
        return POST(
            "$baseUrl/v2/directories?studio=0&not_genre=&year=&orderBy=2&language=&type=&duration=&search=&letter=0&limit=24&genre=&season=&page=${(page - 1) * 24}&status=",
            headers = headers1,
            body = body,
        )
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    override fun popularAnimeRequest(page: Int): Request {
        val body = FormBody.Builder().add("apyki", API_KEY).build()
        return POST(
            "$baseUrl/v2/directories?studio=0&not_genre=&year=&orderBy=4&language=&type=&duration=&search=&letter=0&limit=24&genre=&season=&page=${(page - 1) * 24}&status=",
            headers = headers1,
            body = body,
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
                        thumbnail_url = AIP.random() + animeDetail["img_url"]!!.jsonPrimitive.content
                    }
                },
                true,
            )
        } catch (e: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val requestBody = FormBody.Builder().add("apyki", API_KEY).build()

        val genreFilter = filters.getTagFilter()?.state ?: emptyList()
        val excludeGenreFilter = filters.getExcludeTagFilter()?.state ?: emptyList()
        val studioFilter = filters.getStudioFilter()?.state ?: emptyList()
        val stateFilter = filters.getStateFilter() ?: StateFilter()
        val orderByFilter = filters.getOrderByFilter() ?: OrderByFilter()

        val genre = genreFilter.filter { it.state }
            .map { GENRES[it.name] }
            .joinToString("%2C") { it.toString() }
            .takeIf { it.isNotEmpty() } ?: ""

        val excludeGenre = excludeGenreFilter.filter { it.state }
            .map { GENRES[it.name] }
            .joinToString("%2C") { it.toString() }
            .takeIf { it.isNotEmpty() } ?: ""

        val studio = studioFilter.filter { it.state }
            .map { STUDIOS_MAP[it.name] }
            .joinToString("%2C") { it.toString() }
            .takeIf { it.isNotEmpty() } ?: "0"

        val status = stateFilter.toUriPart()
        val orderBy = orderByFilter.toUriPart()

        val url = buildAnimeSearchUrl(query, page, genre, orderBy, excludeGenre, studio, status)

        return POST(
            url,
            headers = headers1,
            body = requestBody,
        )
    }

    private fun AnimeFilterList.getTagFilter() = find { it is TagFilter } as? TagFilter
    private fun AnimeFilterList.getExcludeTagFilter() = find { it is ExcludeTagFilter } as? ExcludeTagFilter
    private fun AnimeFilterList.getStudioFilter() = find { it is StudioFilter } as? StudioFilter
    private fun AnimeFilterList.getStateFilter() = find { it is StateFilter } as? StateFilter
    private fun AnimeFilterList.getOrderByFilter() = find { it is OrderByFilter } as? OrderByFilter

    private fun buildAnimeSearchUrl(
        query: String,
        page: Int,
        genre: String?,
        orderBy: String?,
        excludeGenre: String?,
        studio: String,
        status: String?,
    ): String {
        val itemsPerPage = 24
        return "$baseUrl/v2/directories?" +
            "studio=$studio&" +
            "not_genre=$excludeGenre&" +
            "year=&" +
            "orderBy=$orderBy&" +
            "language=&" +
            "type=&" +
            "duration=&" +
            "search=$query&" +
            "letter=0&" +
            "limit=$itemsPerPage&" +
            "genre=$genre&" +
            "season=&" +
            "page=${(page - 1) * itemsPerPage}&" +
            "status=$status"
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
                        thumbnail_url = AIP.random() + animeDetail["img_url"]!!.jsonPrimitive.content
                    }
                },
                false,
            )
        } catch (e: Exception) {
            return AnimesPage(emptyList(), false)
        }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val body = FormBody.Builder().add("apyki", API_KEY).build()
        return POST(
            episode.url,
            headers1,
            body,
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val jsonResponse = json.decodeFromString<JsonObject>(response.asJsoup().body().text())
        val responseArray = jsonResponse["response"]!!.jsonObject
        val players = responseArray["players"]!!.jsonArray
        val videoList = mutableListOf<Video>()
        players.forEach {
            val server = it.jsonObject["option"]!!.jsonPrimitive.content
            val preUrl = it.jsonObject["name"]!!.jsonPrimitive.content

            val url = if (preUrl.startsWith("F-")) {
                preUrl.substringAfter("-")
            } else {
                preUrl.substringAfter("-").reversed()
            }
            videoList.addAll(parseExtractors(url, server))
        }

        return videoList.filter { it.url.contains("http") }
    }

    private fun parseExtractors(url: String, server: String): List<Video> {
        return when {
            url.contains("streamwish") -> StreamWishExtractor(client, headers).videosFromUrl(url, prefix = "StreamWish")
            url.contains("mediafire") -> {
                val video = MediaFireExtractor(client).getVideoFromUrl(url, server)
                if (video != null) {
                    listOf(video)
                } else {
                    emptyList()
                }
            }
            url.contains("streamtape") -> {
                val video = StreamTapeExtractor(client).videoFromUrl(url, server)
                if (video != null) {
                    listOf(video)
                } else {
                    emptyList()
                }
            }
            url.contains("jkanime") -> {
                val video = JkanimeExtractor(client).getDesuFromUrl(url)
                if (video != null) {
                    listOf(video)
                } else {
                    emptyList()
                }
            }
            url.contains("/stream/amz.php?") -> {
                try {
                    val video = JkanimeExtractor(client).amazonExtractor(url)
                    if (video.isNotBlank()) {
                        listOf(Video(video, server, video))
                    } else {
                        emptyList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            url.contains("yourupload") -> {
                YourUploadExtractor(client).videoFromUrl(url, headers)
            }
            url.contains("mp4upload") -> {
                Mp4uploadExtractor(client).videosFromUrl(url, headers)
            }
            url.contains("dood") -> {
                try {
                    val video = DoodExtractor(client).videoFromUrl(url)
                    if (video != null) {
                        listOf(video)
                    } else {
                        emptyList()
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            url.contains("ok.ru") -> {
                OkruExtractor(client).videosFromUrl(url)
            }
            url.contains("flvvideo") && (url.endsWith(".m3u8") || url.endsWith(".mp4")) -> {
                if (url.contains("http")) {
                    listOf(Video(url, "VideoFLV", url))
                } else {
                    emptyList()
                }
            }
            url.contains("cdnlat4animecen") && (url.endsWith(".class") || url.endsWith(".m3u8") || url.endsWith(".mp4")) -> {
                if (url.contains("http")) {
                    listOf(Video(url, "AnimeCen", url))
                } else {
                    emptyList()
                }
            }
            url.contains("uqload") -> {
                UqloadExtractor(client).videosFromUrl(url)
            }
            else -> emptyList()
        }
    }

    /* --FilterStuff-- */

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagFilter("Generos", checkboxesFrom(GENRES)),
        OrderByFilter(),
        StateFilter(),
        StudioFilter("Estudio", checkboxesFrom(STUDIOS_MAP)),
        ExcludeTagFilter("Excluir Genero", checkboxesFrom(GENRES)),
    )

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Emision", "1"),
            Pair("Finalizado", "2"),
            Pair("Proximamente", "3"),
        ),
    )

    private class OrderByFilter : UriPartFilter(
        "Ordenar Por",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Fecha (Menor a Mayor)", "0"),
            Pair("Recientemente vistos por otros", "1"),
            Pair("Fecha (Mayor a Menor)", "2"),
            Pair("A-Z", "3"),
            Pair("Más Visitado", "4"),
            Pair("Z-A", "5"),
            Pair("Mejor Calificación", "6"),
            Pair("Peor Calificación", "7"),
            Pair("Últimos Agregados en app", "8"),
            Pair("Primeros Agregados en app", "9"),
        ),
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
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", // Okru
            "Xtreme S", "Nozomi", "Desu", "F1S-TAPE", "F1NIX", // video servers without resolution
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

    override fun popularAnimeSelector(): String = throw UnsupportedOperationException()

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()
}
