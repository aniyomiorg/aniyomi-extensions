package eu.kanade.tachiyomi.animeextension.ru.animevost

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ru.animevost.dto.AnimeDetailsDto
import eu.kanade.tachiyomi.animeextension.ru.animevost.dto.Data
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import kotlin.math.roundToInt

class AnimevostSource(override val name: String, override val baseUrl: String, private val baseApiUrl: String) :
    ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    private enum class SortBy(val by: String) {
        RATING("rating"),
        DATE("date"),
        NEWS_READ("news_read"),
        COMM_NUM("comm_num"),
        TITLE("title"),
    }

    private enum class SortDirection(val direction: String) {
        ASC("asc"),
        DESC("desc"),
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val lang = "ru"

    override val supportsLatest = true

    private val animeSelector = "div.shortstoryContent"

    private val nextPageSelector = "td.block_4 span:not(.nav_ext) + a"

    private fun animeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("table div > a").attr("href"))
        anime.thumbnail_url = baseUrl + element.select("table div > a img").attr("src")
        anime.title = element.select("table div > a img").attr("alt")
        return anime
    }

    private fun animeRequest(page: Int, sortBy: SortBy, sortDirection: SortDirection = SortDirection.DESC, genre: String = "all"): Request {
        val headers: Headers =
            Headers.headersOf("Content-Type", "application/x-www-form-urlencoded", "charset", "UTF-8")
        val url = baseUrl.toHttpUrlOrNull()!!.newBuilder()

        var body = FormBody.Builder()
            .add("dlenewssortby", sortBy.by)
            .add("dledirection", sortDirection.direction)

        body = if (genre != "all") {
            url.addPathSegment("zhanr")
            url.addPathSegment(genre)
            body.add("set_new_sort", "dle_sort_cat")
                .add("set_direction_sort", "dle_direction_cat")
        } else {
            body.add("set_new_sort", "dle_sort_main")
                .add("set_direction_sort", "dle_direction_main")
        }

        url.addPathSegment("page")
        url.addPathSegment("$page")

        return POST(url.toString(), headers, body.build())
    }

    private fun parseAnimeIdFromUrl(url: String): String = url.split("/").last().split("-").first()

    // Anime details

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        val animeId = parseAnimeIdFromUrl(anime.url)

        return client.newCall(GET("$baseApiUrl/animevost/api/v0.2/GetInfo/$animeId"))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val animeData = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string()).data?.first()
        val anime = SAnime.create().apply {
            title = animeData?.title!!

            if (animeData.preview != null) {
                thumbnail_url = "$baseUrl/" + animeData.preview
            }

            author = animeData.director
            description = formatDescription(animeData)

            if (animeData.timer != null) {
                status = if (animeData.timer > 0) SAnime.ONGOING else SAnime.COMPLETED
            }

            genre = animeData.genre
        }

        return anime
    }

    private fun formatDescription(animeData: Data): String {
        var description = ""

        if (animeData.year != null) {
            description += "Год: ${animeData.year}\n"
        }

        if (animeData.rating != null && animeData.votes != null) {
            val rating = (animeData.rating.toDouble() / animeData.votes.toDouble()).roundToInt()

            description += "Рейтинг: ${"★".repeat(rating)}${"☆".repeat(Math.max(5 - rating, 0))} (Голосов: ${animeData.votes})\n"
        }

        if (animeData.type != null) {
            description += "Тип: ${animeData.type}\n"
        }

        if (description.isNotEmpty()) {
            description += "\n"
        }

        description += animeData.description?.replace("<br />", "")

        return description
    }

    override fun animeDetailsParse(document: Document) = throw Exception("not used")

    // Episode

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = parseAnimeIdFromUrl(anime.url)

        return GET("$baseApiUrl/animevost/api/v0.2/GetInfo/$animeId")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeData = json.decodeFromString(AnimeDetailsDto.serializer(), response.body!!.string()).data?.first()

        val episodeList = mutableListOf<SEpisode>()

        if (animeData?.series != null) {
            val series = Json.parseToJsonElement(animeData.series.replace("'", "\"")).jsonObject.toMap()

            series.entries.forEachIndexed { index, entry ->
                episodeList.add(
                    SEpisode.create().apply {
                        val id = entry.value.toString().replace("\"", "")
                        name = entry.key
                        episode_number = index.toFloat()
                        date_upload = System.currentTimeMillis()
                        url = "/frame5.php?play=$id&old=1"
                    }
                )
            }
        }

        return episodeList.reversed()
    }

    // Latest

    override fun latestUpdatesFromElement(element: Element) = animeFromElement(element)

    override fun latestUpdatesNextPageSelector() = nextPageSelector

    override fun latestUpdatesRequest(page: Int) = animeRequest(page, SortBy.DATE)

    override fun latestUpdatesSelector() = animeSelector

    // Popular Anime

    override fun popularAnimeFromElement(element: Element) = animeFromElement(element)

    override fun popularAnimeNextPageSelector() = nextPageSelector

    override fun popularAnimeRequest(page: Int) = animeRequest(page, SortBy.RATING)

    override fun popularAnimeSelector() = animeSelector

    // Search

    override fun searchAnimeFromElement(element: Element) = animeFromElement(element)

    override fun searchAnimeNextPageSelector() = nextPageSelector

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            val searchStart = if (page <= 1) 0 else page
            val resultFrom = (page - 1) * 10 + 1
            val headers: Headers =
                Headers.headersOf("Content-Type", "application/x-www-form-urlencoded", "charset", "UTF-8")
            val body = FormBody.Builder()
                .add("do", "search")
                .add("subaction", "search")
                .add("search_start", searchStart.toString())
                .add("full_search", "0")
                .add("result_from", resultFrom.toString())
                .add("story", query)
                .build()

            POST("$baseUrl/index.php?do=search", headers, body)
        } else {
            var sortBy = SortBy.DATE
            var sortDirection = SortDirection.DESC
            var genre = "all"

            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> {
                        genre = filter.toString()
                    }
                    is SortFilter -> {
                        if (filter.state != null) {
                            sortBy = sortableList[filter.state!!.index].second

                            sortDirection = if (filter.state!!.ascending) SortDirection.ASC else SortDirection.DESC
                        }
                    }
                }
            }

            animeRequest(page, sortBy, sortDirection, genre)
        }
    }

    override fun searchAnimeSelector() = animeSelector

    // Video

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = response.asJsoup()

        val videoData = document.html().substringAfter("file\":\"").substringBefore("\",").split(",")

        videoData.forEach {
            val linkData = it.replace("[", "").split("]")
            val quality = linkData.first()
            val url = linkData.last().split(" or").first()
            videoList.add(Video(url, quality, url, null))
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

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Не работают при текстовом поиске!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList()),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
    )

    private class GenreFilter(genres: Array<Pair<String, String>>) : UriPartFilter("Жанр", genres)

    private fun getGenreList() = arrayOf(
        Pair("Все", "all"),
        Pair("Боевые искусства", "boyevyye-iskusstva"),
        Pair("Война", "voyna"),
        Pair("Драма", "drama"),
        Pair("Детектив", "detektiv"),
        Pair("История", "istoriya"),
        Pair("Комедия", "komediya"),
        Pair("Мистика", "mistika"),
        Pair("Меха", "mekha"),
        Pair("Махо-сёдзё", "makho-sedze"),
        Pair("Музыкальный", "muzykalnyy"),
        Pair("Повседневность", "povsednevnost"),
        Pair("Приключения", "priklyucheniya"),
        Pair("Пародия", "parodiya"),
        Pair("Романтика", "romantika"),
        Pair("Сёнэн", "senen"),
        Pair("Сёдзё", "sedze"),
        Pair("Спорт", "sport"),
        Pair("Сказка", "skazka"),
        Pair("Сёдзё-ай", "sedze-ay"),
        Pair("Сёнэн-ай", "senen-ay"),
        Pair("Самураи", "samurai"),
        Pair("Триллер", "triller"),
        Pair("Ужасы", "uzhasy"),
        Pair("Фантастика", "fantastika"),
        Pair("Фэнтези", "fentezi"),
        Pair("Школа", "shkola"),
        Pair("Этти", "etti"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        override fun toString() = vals[state].second
    }

    private val sortableList = listOf(
        Pair("Дате", SortBy.DATE),
        Pair("Популярности", SortBy.RATING),
        Pair("Посещаемости", SortBy.NEWS_READ),
        Pair("Комментариям", SortBy.COMM_NUM),
        Pair("Алфавиту", SortBy.TITLE),
    )

    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Сортировать по", sortables, Selection(0, false))

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("720p", "480p")
            entryValues = arrayOf("720", "480")
            setDefaultValue("480")
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
