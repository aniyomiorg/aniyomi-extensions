package eu.kanade.tachiyomi.animeextension.en.marinmoe

import android.annotation.SuppressLint
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Date

class MarinMoe : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "marin.moe"

    override val baseUrl = "https://marin.moe"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val ddgInterceptor = DdosGuardInterceptor(network.client)

    override val client: OkHttpClient = network.client
        .newBuilder()
        .addInterceptor(ddgInterceptor)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime?sort=vwk-d&page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnime(response)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?sort=rel-d&page=$page", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnime(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = MarinMoeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: MarinMoeFilters.FilterSearchParams): Request {
        var url = "$baseUrl/anime".toHttpUrl().newBuilder()
            .addQueryParameter("sort", filters.sort)
            .addQueryParameter("search", query)
            .addQueryParameter("page", page.toString())
            .build().toString()

        if (filters.type.isNotBlank()) url += "&${filters.type}"
        if (filters.status.isNotBlank()) url += "&${filters.status}"
        if (filters.contentRating.isNotBlank()) url += "&${filters.contentRating}"
        if (filters.genre.isNotBlank()) url += "&${filters.genre}"
        if (filters.source.isNotBlank()) url += "&${filters.source}"
        if (filters.group.isNotBlank()) url += "&filter[group][0][id]=${filters.group}&filter[group][0][opr]=include"
        if (filters.studio.isNotBlank()) url += "&filter[production][0][id]=${filters.studio}&filter[production][0][opr]=include"

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnime(response)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = MarinMoeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        val dataPage = document.select("div#app").attr("data-page").replace("&quot;", "\"")
        val details = json.decodeFromString<AnimeDetails>(dataPage).props.anime

        return SAnime.create().apply {
            title = details.title
            thumbnail_url = details.cover
            genre = details.genre_list.joinToString(", ") { it.name }
            author = details.production_list.joinToString(", ") { it.name }
            status = parseStatus(details.status.name)
            description = buildString {
                append(
                    Jsoup.parse(
                        details.description.replace("<br />", "br2n"),
                    ).text().replace("br2n", "\n"),
                )
                append("\n\nContent Rating: ${details.content_rating.name}")
                append("\nRelease Date: ${details.release_date}")
                append("\nType: ${details.type.name}")
                append("\nSource: ${details.source_list.joinToString(separator = ", ") { it.name }}")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val dataPage = document.select("div#app").attr("data-page").replace("&quot;", "\"")

        val dataJson = json.decodeFromString<AnimeDetails>(dataPage)
        val includeSpecial = preferences.getBoolean(PREF_SPECIAL_KEY, PREF_SPECIAL_DEFAULT)

        dataJson.props.episode_list.data.forEach {
            val episode = SEpisode.create().apply {
                name = "Episode ${it.slug} ${it.title}"
                episode_number = it.sort
                url = "${response.request.url}/${it.slug}"
                val parsedDate = parseDate(it.release_date)
                if (parsedDate.time != -1L) date_upload = parsedDate.time
            }

            if (includeSpecial || it.type != 2) {
                episodes.add(episode)
            }
        }

        var next = dataJson.props.episode_list.links.next

        while (next != null) {
            val nextDocument = client.newCall(GET(next, headers = headers)).execute().asJsoup()
            val nextDataPage = nextDocument.select("div#app").attr("data-page").replace("&quot;", "\"")
            val nextDataJson = json.decodeFromString<AnimeDetails>(nextDataPage)

            nextDataJson.props.episode_list.data.forEach {
                val episode = SEpisode.create().apply {
                    name = "Episode ${it.slug} ${it.title}"
                    episode_number = it.sort
                    url = "${response.request.url}/${it.slug}"
                    val parsedDate = parseDate(it.release_date)
                    if (parsedDate.time != -1L) date_upload = parsedDate.time
                }

                episodes.add(episode)
            }

            next = nextDataJson.props.episode_list.links.next
        }

        return episodes.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = GET(episode.url, headers)

    override fun videoListParse(response: Response): List<Video> {
        var newHeaders = headers.newBuilder()
        val ddosCookies = client.cookieJar.loadForRequest(response.request.url).filter {
            // Don't need these
            it.name !in arrayOf("__ddgid_", "__ddgmark_")
        }.joinToString(";") { "${it.name}=${it.value}" }

        newHeaders.add(
            "X-XSRF-TOKEN",
            ddosCookies.substringAfter("XSRF-TOKEN=").substringBefore(";").replace("%3D", "="),
        )

        newHeaders.add("Cookie", ddosCookies)

        val videoHeaders = newHeaders.build().newBuilder()
            .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .add("Referer", response.request.url.toString())
            .add("Accept-Language", "en-US,en;q=0.5")

        newHeaders.add("Origin", baseUrl)
            .add("Content-Type", "application/json")
            .add("Referer", response.request.url.toString())
            .add("Accept", "text/html, application/xhtml+xml")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("X-Requested-With", "XMLHttpRequest")

        val videoList = mutableListOf<Pair<Video, Float>>()
        val document = response.asJsoup()
        val dataPage = document.select("div#app").attr("data-page").replace("&quot;", "\"")

        val videos = json.decodeFromString<EpisodeData>(dataPage).props.video_list.data

        for (video in videos) {
            val dataStr = """{"video":"${video.slug}"}"""

            newHeaders.add("Content-Length", dataStr.length.toString())

            val videoJson = client.newCall(
                POST(response.request.url.toString(), body = dataStr.toRequestBody("application/json".toMediaType()), headers = newHeaders.build()),
            ).execute().asJsoup().select("div#app").attr("data-page").replace("&quot;", "\"")

            val src = json.decodeFromString<EpisodeResponse>(videoJson).props.video.data

            for (link in src.mirror) {
                videoList.add(
                    Pair(
                        Video(
                            response.request.url.toString(),
                            "${src.title} ${link.resolution} (${if (src.audio.code == "jp") "Sub" else "Dub"} - ${src.source.name})",
                            link.code.file,
                            headers = videoHeaders.build(),
                        ),
                        src.sort,
                    ),
                )
            }
        }

        return prioritySort(videoList)
    }

    // ============================= Utilities ==============================

    private fun prioritySort(pList: List<Pair<Video, Float>>): List<Video> {
        val prefGroup = preferences.getString(PREF_GROUP_KEY, PREF_GROUP_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val subOrDub = preferences.getString(PREF_SUBS_KEY, PREF_SUBS_DEFAULT)!!

        return pList.sortedWith(
            compareBy(
                { it.first.quality.lowercase().contains(subOrDub) },
                { it.first.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.first.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
                { if (prefGroup == "site_default") -it.second else it.first.quality.contains(prefGroup) },
            ),
        ).reversed().map { t -> t.first }
    }

    private fun parseAnime(response: Response): AnimesPage {
        val document = response.asJsoup()
        val dataPage = document.select("div#app").attr("data-page").replace("&quot;", "\"")

        val dataJson = json.decodeFromString<ResponseData>(dataPage)

        val animes = dataJson.props.anime_list.data.map { ani ->
            SAnime.create().apply {
                title = ani.title
                thumbnail_url = ani.cover
                url = "/anime/${ani.slug}"
            }
        }

        val hasNextPage = dataJson.props.anime_list.meta.current_page < dataJson.props.anime_list.meta.last_page

        return AnimesPage(animes, hasNextPage)
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun parseDate(date: String): Date {
        val patterns = arrayOf(
            "dd'th of 'MMM, yyyy",
            "dd'nd of 'MMM, yyyy",
            "dd'st of 'MMM, yyyy",
            "dd'rd of 'MMM, yyyy",
        )

        for (pattern in patterns) {
            try {
                // Take a try
                return Date(SimpleDateFormat(pattern).parse(date)!!.time)
            } catch (e: Throwable) {
                // Loop on
            }
        }
        return Date(-1L)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private val PREF_QUALITY_ENTRY_VALUES = arrayOf("1080", "720", "480", "360")
        private val PREF_QUALITY_ENTRIES = PREF_QUALITY_ENTRY_VALUES.map { "${it}p" }.toTypedArray()
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_GROUP_KEY = "preferred_group"
        private const val PREF_GROUP_DEFAULT = "site_default"

        private const val PREF_SUBS_KEY = "preferred_sub"
        private val PREF_SUBS_ENTRY_VALUES = arrayOf("sub", "dub")
        private val PREF_SUBS_ENTRIES = arrayOf("Subs", "Dubs")
        private const val PREF_SUBS_DEFAULT = "sub"

        private const val PREF_SPECIAL_KEY = "preferred_special"
        private const val PREF_SPECIAL_DEFAULT = true
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_GROUP_KEY
            title = "Preferred group"
            entries = MarinMoeConstants.GROUP_ENTRIES
            entryValues = MarinMoeConstants.GROUP_ENTRY_VALUES
            setDefaultValue(PREF_GROUP_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SUBS_KEY
            title = "Prefer subs or dubs?"
            entries = PREF_SUBS_ENTRIES
            entryValues = PREF_SUBS_ENTRY_VALUES
            setDefaultValue(PREF_SUBS_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SPECIAL_KEY
            title = "Include Special Episodes"
            setDefaultValue(PREF_SPECIAL_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }.also(screen::addPreference)
    }
}
