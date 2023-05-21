package eu.kanade.tachiyomi.animeextension.en.animeflix

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeFlix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFlix"

    override val baseUrl = "https://animeflix.net.in"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeSelector(): String = "div#page > div#content_box > article"

    override fun popularAnimeNextPageSelector(): String = "div.nav-links > span.current ~ a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img")!!.attr("src")
            title = element.selectFirst("header")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-release/page/$page/")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    // https://animeflix.org.in/download-demon-slayer-movie-infinity-train-movie-2020-japanese-with-esubs-hevc-720p-1080p/

    override fun searchAnimeParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val (request, isExact) = searchAnimeRequestExact(page, query, filters)
        return client.newCall(request)
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response, isExact)
            }
    }

    private fun searchAnimeParse(response: Response, isExact: Boolean): AnimesPage {
        val document = response.asJsoup()

        if (isExact) {
            val anime = SAnime.create()
            anime.title = document.selectFirst("div.single_post > header > h1")!!.text()
            anime.thumbnail_url = document.selectFirst("div.imdbwp img")!!.attr("src")
            anime.setUrlWithoutDomain(response.request.url.encodedPath)
            return AnimesPage(listOf(anime), false)
        }

        val animes = document.select(searchAnimeSelector()).map { element ->
            searchAnimeFromElement(element)
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.selectFirst(selector)
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    private fun searchAnimeRequestExact(page: Int, query: String, filters: AnimeFilterList): Pair<Request, Boolean> {
        val cleanQuery = query.replace(" ", "+").lowercase()

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subpageFilter = filterList.find { it is SubPageFilter } as SubPageFilter
        val urlFilter = filterList.find { it is URLFilter } as URLFilter

        return when {
            query.isNotBlank() -> Pair(GET("$baseUrl/page/$page/?s=$cleanQuery", headers = headers), false)
            genreFilter.state != 0 -> Pair(GET("$baseUrl/genre/${genreFilter.toUriPart()}/page/$page/", headers = headers), false)
            subpageFilter.state != 0 -> Pair(GET("$baseUrl/${subpageFilter.toUriPart()}/page/$page/", headers = headers), false)
            urlFilter.state.isNotEmpty() -> Pair(GET(urlFilter.state, headers = headers), true)
            else -> Pair(popularAnimeRequest(page), false)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        SubPageFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Get item url from webview"),
        URLFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Isekai", "isekai"),
            Pair("Drama", "drama"),
            Pair("Psychological", "psychological"),
            Pair("Ecchi", "ecchi"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Magic", "magic"),
            Pair("Slice Of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Comedy", "comedy"),
            Pair("Fantasy", "fantasy"),
            Pair("Horror", "horror"),
            Pair("Yaoi", "yaoi"),
        ),
    )

    private class SubPageFilter : UriPartFilter(
        "Sub-page",
        arrayOf(
            Pair("<select>", ""),
            Pair("Ongoing", "ongoing"),
            Pair("Latest Release", "latest-release"),
            Pair("Movies", "movies"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private class URLFilter : AnimeFilter.Text("Url")

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("div.single_post > header > h1")!!.text()
            val animeInfo = document.select("div.thecontent h3:contains(Anime Info) ~ ul li").joinToString("\n") { it.text() }
            description = document.select("div.thecontent h3:contains(Summary) ~ p:not(:has(*)):not(:empty)").joinToString("\n\n") { it.ownText() } + "\n\n$animeInfo"
        }
    }

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val document = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val serversList = mutableListOf<List<EpUrl>>()

        val driveList = mutableListOf<Pair<String, String>>()

        document.select("div.thecontent p:has(span:contains(Gdrive))").forEach {
            val qualityRegex = """(\d+)p""".toRegex()
            val quality = qualityRegex.find(it.previousElementSibling()!!.text())!!.groupValues[1]
            driveList.add(Pair(it.selectFirst("a")!!.attr("href"), quality))
        }

        // Load episodes
        driveList.forEach { drive ->
            val episodesDocument = client.newCall(GET(drive.first)).execute().asJsoup()
            serversList.add(
                episodesDocument.select("div.entry-content > h3 > a").map {
                    EpUrl(drive.second, it.attr("href"), it.text())
                },
            )
        }

        transpose(serversList).forEachIndexed { index, serverList ->
            episodeList.add(
                SEpisode.create().apply {
                    name = serverList.first().name
                    episode_number = (index + 1).toFloat()
                    setUrlWithoutDomain(
                        json.encodeToString(serverList),
                    )
                },
            )
        }

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not Used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videoList = mutableListOf<Video>()
        val failedMediaUrl = mutableListOf<Pair<String, String>>()
        val urls = json.decodeFromString<List<EpUrl>>(episode.url)

        val leechUrls = urls.map {
            val firstLeech = client.newCall(GET(it.url)).execute().asJsoup().selectFirst(
                "script:containsData(downlaod_button)",
            )!!.data().substringAfter("<a href=\"").substringBefore("\">")
            val link = "https://" + firstLeech.toHttpUrl().host + client.newCall(GET(firstLeech)).execute().body.string()
                .substringAfter("replace(\"").substringBefore("\"")
            EpUrl(it.quality, link, it.name)
        }

        videoList.addAll(
            leechUrls.parallelMap { url ->
                runCatching {
                    if (url.url.toHttpUrl().encodedPath == "/404") return@runCatching null
                    val (videos, mediaUrl) = extractVideo(url)
                    if (videos.isEmpty()) failedMediaUrl.add(Pair(mediaUrl, url.quality))
                    return@runCatching videos
                }.getOrNull()
            }
                .filterNotNull()
                .flatten(),
        )

        videoList.addAll(
            failedMediaUrl.mapNotNull { (url, quality) ->
                runCatching {
                    extractGDriveLink(url, quality)
                }.getOrNull()
            }.flatten(),
        )
        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

// ============================= Utilities ==============================

    // https://github.com/jmir1/aniyomi-extensions/blob/master/src/en/uhdmovies/src/eu/kanade/tachiyomi/animeextension/en/uhdmovies/UHDMovies.kt
    private fun extractVideo(epUrl: EpUrl): Pair<List<Video>, String> {
        val videoList = mutableListOf<Video>()

        val qualityRegex = """(\d+)p""".toRegex()
        val matchResult = qualityRegex.find(epUrl.name)
        val quality = if (matchResult == null) {
            epUrl.quality
        } else {
            matchResult.groupValues[1]
        }

        for (type in 1..3) {
            videoList.addAll(
                extractWorkerLinks(epUrl.url, quality, type),
            )
        }
        return Pair(videoList, epUrl.url)
    }

    private val sizeRegex = "\\[((?:.(?!\\[))+)][ ]*\$".toRegex(RegexOption.IGNORE_CASE)

    private fun extractWorkerLinks(mediaUrl: String, quality: String, type: Int): List<Video> {
        val reqLink = mediaUrl.replace("/file/", "/wfile/") + "?type=$type"
        val resp = client.newCall(GET(reqLink)).execute().asJsoup()
        val sizeMatch = sizeRegex.find(resp.select("div.card-header").text().trim())
        val size = sizeMatch?.groups?.get(1)?.value?.let { " - $it" } ?: ""
        return resp.select("div.card-body div.mb-4 > a").mapIndexed { index, linkElement ->
            val link = linkElement.attr("href")
            val decodedLink = if (link.contains("workers.dev")) {
                link
            } else {
                String(Base64.decode(link.substringAfter("download?url="), Base64.DEFAULT))
            }

            Video(
                url = decodedLink,
                quality = "$quality - CF $type Worker ${index + 1}$size",
                videoUrl = decodedLink,
            )
        }
    }

    private fun extractGDriveLink(mediaUrl: String, quality: String): List<Video> {
        val tokenClient = client.newBuilder().addInterceptor(TokenInterceptor()).build()
        val response = tokenClient.newCall(GET(mediaUrl)).execute().asJsoup()
        val gdBtn = response.selectFirst("div.card-body a.btn")!!
        val gdLink = gdBtn.attr("href")
        val sizeMatch = sizeRegex.find(gdBtn.text())
        val size = sizeMatch?.groups?.get(1)?.value?.let { " - $it" } ?: ""
        val gdResponse = client.newCall(GET(gdLink)).execute().asJsoup()
        val link = gdResponse.select("form#download-form")
        return if (link.isNullOrEmpty()) {
            listOf()
        } else {
            val realLink = link.attr("action")
            listOf(Video(realLink, "$quality - Gdrive$size", realLink))
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun <E> transpose(xs: List<List<E>>): List<List<E>> {
        // Helpers
        fun <E> List<E>.head(): E = this.first()
        fun <E> List<E>.tail(): List<E> = this.takeLast(this.size - 1)
        fun <E> E.append(xs: List<E>): List<E> = listOf(this).plus(xs)

        xs.filter { it.isNotEmpty() }.let { ys ->
            return when (ys.isNotEmpty()) {
                true -> ys.map { it.head() }.append(transpose(ys.map { it.tail() }))
                else -> emptyList()
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("2160p", "1080p", "720p", "480p")
            entryValues = arrayOf("2160", "1080", "720", "480")
            setDefaultValue("1080")
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

    @Serializable
    data class EpUrl(
        val quality: String,
        val url: String,
        val name: String,
    )

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
