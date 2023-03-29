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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import kotlin.collections.ArrayList

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

    override fun popularAnimeParse(response: Response): AnimesPage {
        return parseAnime(response)
    }

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/anime?sort=vwk-d&page=$page", headers = headers)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnime(response)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?sort=rel-d&page=$page")

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = MarinMoeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: MarinMoeFilters.FilterSearchParams): Request {
        var url = "$baseUrl/anime".toHttpUrlOrNull()!!.newBuilder()
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

        return GET(url, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnime(response)
    }

    override fun getFilterList(): AnimeFilterList = MarinMoeFilters.filterList

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        val dataPage = document.select("div#app").attr("data-page").replace("&quot;", "\"")
        val details = json.decodeFromString<AnimeDetails>(dataPage).props.anime

        anime.thumbnail_url = details.cover
        anime.title = details.title
        anime.genre = details.genre_list.joinToString(", ") { it.name }
        anime.author = details.production_list.joinToString(", ") { it.name }
        anime.status = parseStatus(details.status.name)

        var description = Jsoup.parse(
            details.description.replace("<br />", "br2n"),
        ).text().replace("br2n", "\n") + "\n"
        description += "\nContent Rating: ${details.content_rating.name}"
        description += "\nRelease Date: ${details.release_date}"
        description += "\nType: ${details.type.name}"
        description += "\nSource: ${details.source_list.joinToString(separator = ", ") { it.name }}"
        anime.description = description

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val dataPage = document.select("div#app").attr("data-page").replace("&quot;", "\"")

        val dataJson = json.decodeFromString<AnimeDetails>(dataPage)
        val includeSpecial = preferences.getBoolean("preferred_special", true)

        dataJson.props.episode_list.data.forEach {
            val episode = SEpisode.create()

            episode.name = "Episode ${it.slug} ${it.title}"
            episode.episode_number = it.sort
            episode.url = "${response.request.url}/${it.slug}"

            val parsedDate = parseDate(it.release_date)
            if (parsedDate.time != -1L) episode.date_upload = parsedDate.time

            if (includeSpecial || it.type != 2) episodes.add(episode)
        }

        var next = dataJson.props.episode_list.links.next

        while (next != null) {
            val nextDocument = client.newCall(GET(next, headers = headers)).execute().asJsoup()
            val nextDataPage = nextDocument.select("div#app").attr("data-page").replace("&quot;", "\"")
            val nextDataJson = json.decodeFromString<AnimeDetails>(nextDataPage)

            nextDataJson.props.episode_list.data.forEach {
                val episode = SEpisode.create()

                episode.name = "Episode ${it.slug} ${it.title}"
                episode.episode_number = it.sort
                episode.url = "${response.request.url}/${it.slug}"

                val parsedDate = parseDate(it.release_date)
                if (parsedDate.time != -1L) episode.date_upload = parsedDate.time

                episodes.add(episode)
            }

            next = nextDataJson.props.episode_list.links.next
        }

        return episodes.sortedBy { it.episode_number }.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers = headers)
    }

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
        val prefGroup = preferences.getString("preferred_group", "site_default")!!
        val quality = preferences.getString("preferred_quality", "1080")!!
        val subOrDub = preferences.getString("preferred_sub", "sub")!!

        return pList.sortedWith(
            compareBy(
                { it.first.quality.lowercase().contains(subOrDub) },
                { it.first.quality.contains(quality) },
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
        val knownPatterns: MutableList<SimpleDateFormat> = ArrayList()
        knownPatterns.add(SimpleDateFormat("dd'th of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'nd of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'st of 'MMM, yyyy"))
        knownPatterns.add(SimpleDateFormat("dd'rd of 'MMM, yyyy"))

        for (pattern in knownPatterns) {
            try {
                // Take a try
                return Date(pattern.parse(date)!!.time)
            } catch (e: Throwable) {
                // Loop on
            }
        }
        return Date(-1L)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val groupPref = ListPreference(screen.context).apply {
            key = "preferred_group"
            title = "Preferred group"
            entries = MarinMoeConstants.groupEntries
            entryValues = MarinMoeConstants.groupEntryValues
            setDefaultValue("site_default")
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
            entries = arrayOf("Subs", "Dubs")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue("sub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val specialPref = SwitchPreferenceCompat(screen.context).apply {
            key = "preferred_special"
            title = "Include Special Episodes"
            setDefaultValue(true)

            setOnPreferenceChangeListener { _, newValue ->
                val new = newValue as Boolean
                preferences.edit().putBoolean(key, new).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(groupPref)
        screen.addPreference(subPref)
        screen.addPreference(specialPref)
    }
}
