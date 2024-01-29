package eu.kanade.tachiyomi.animeextension.en.animeflix

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeFlix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFlix"

    override val baseUrl = "https://animeflix.mobi"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/page/$page/")

    override fun popularAnimeSelector() = "div#content_box > div.post-cards > article"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        // prevent base64 images
        thumbnail_url = element.selectFirst("img")!!.run {
            attr("data-pagespeed-high-res-src").ifEmpty { attr("src") }
        }
        title = element.selectFirst("header")!!.text()
    }

    override fun popularAnimeNextPageSelector() = "div.nav-links > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/latest-release/page/$page/")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()

        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subpageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$cleanQuery", headers = headers)
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/page/$page/", headers = headers)
            subpageFilter.state != 0 -> GET("$baseUrl/${subpageFilter.toUriPart()}/page/$page/", headers = headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        SubPageFilter(),
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

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst("div.single_post > header > h1")!!.text()
        thumbnail_url = document.selectFirst("img.imdbwp__img")?.attr("src")

        val infosDiv = document.selectFirst("div.thecontent h3:contains(Anime Info) ~ ul")!!
        status = when (infosDiv.getInfo("Status").toString()) {
            "Completed" -> SAnime.COMPLETED
            "Currently Airing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
        artist = infosDiv.getInfo("Studios")
        author = infosDiv.getInfo("Producers")
        genre = infosDiv.getInfo("Genres")
        val animeInfo = infosDiv.select("li").joinToString("\n") { it.text() }
        description = document.select("div.thecontent h3:contains(Summary) ~ p:not(:has(*)):not(:empty)")
            .joinToString("\n\n") { it.ownText() } + "\n\n$animeInfo"
    }

    private fun Element.getInfo(info: String) =
        selectFirst("li:contains($info)")?.ownText()?.trim()

    // ============================== Episodes ==============================
    val seasonRegex by lazy { Regex("""season (\d+)""", RegexOption.IGNORE_CASE) }
    val qualityRegex by lazy { """(\d+)p""".toRegex() }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.newCall(GET(baseUrl + anime.url)).execute()
            .asJsoup()

        val seasonList = document.select("div.inline > h3:contains(Season),div.thecontent > h3:contains(Season)")

        val episodeList = if (seasonList.distinctBy { seasonRegex.find(it.text())!!.groupValues[1] }.size > 1) {
            val seasonsLinks = document.select("div.thecontent p:has(span:contains(Gdrive))").groupBy {
                seasonRegex.find(it.previousElementSibling()!!.text())!!.groupValues[1]
            }

            seasonsLinks.flatMap { (seasonNumber, season) ->
                val serverListSeason = season.map {
                    val previousText = it.previousElementSibling()!!.text()
                    val quality = qualityRegex.find(previousText)?.groupValues?.get(1) ?: "Unknown quality"

                    val url = it.selectFirst("a")!!.attr("href")
                    val episodesDocument = client.newCall(GET(url)).execute()
                        .asJsoup()
                    episodesDocument.select("div.entry-content > h3 > a").map {
                        EpUrl(quality, it.attr("href"), "Season $seasonNumber ${it.text()}")
                    }
                }

                transposeEpisodes(serverListSeason)
            }
        } else {
            val driveList = document.select("div.thecontent p:has(span:contains(Gdrive))").map {
                val quality = qualityRegex.find(it.previousElementSibling()!!.text())?.groupValues?.get(1) ?: "Unknown quality"
                Pair(it.selectFirst("a")!!.attr("href"), quality)
            }

            // Load episodes
            val serversList = driveList.map { drive ->
                val episodesDocument = client.newCall(GET(drive.first)).execute()
                    .asJsoup()
                episodesDocument.select("div.entry-content > h3 > a").map {
                    EpUrl(drive.second, it.attr("href"), it.text())
                }
            }

            transposeEpisodes(serversList)
        }

        return episodeList.reversed()
    }

    private fun transposeEpisodes(serversList: List<List<EpUrl>>) =
        transpose(serversList).mapIndexed { index, serverList ->
            SEpisode.create().apply {
                name = serverList.first().name
                episode_number = (index + 1).toFloat()
                setUrlWithoutDomain(json.encodeToString(serverList))
            }
        }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urls = json.decodeFromString<List<EpUrl>>(episode.url)

        val leechUrls = urls.map {
            val firstLeech = client.newCall(GET(it.url)).execute()
                .asJsoup()
                .selectFirst("script:containsData(downlaod_button)")!!
                .data()
                .substringAfter("<a href=\"")
                .substringBefore("\">")

            val path = client.newCall(GET(firstLeech)).execute()
                .body.string()
                .substringAfter("replace(\"")
                .substringBefore("\"")

            val link = "https://" + firstLeech.toHttpUrl().host + path
            EpUrl(it.quality, link, it.name)
        }

        val videoList = leechUrls.parallelCatchingFlatMap { url ->
            if (url.url.toHttpUrl().encodedPath == "/404") return@parallelCatchingFlatMap emptyList()
            val (videos, mediaUrl) = extractVideo(url)
            when {
                videos.isEmpty() -> {
                    extractGDriveLink(mediaUrl, url.quality).ifEmpty {
                        getDirectLink(mediaUrl, "instant", "/mfile/")?.let {
                            listOf(Video(it, "${url.quality}p - GDrive Instant link", it))
                        } ?: emptyList()
                    }
                }
                else -> videos
            }
        }

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    // https://github.com/aniyomiorg/aniyomi-extensions/blob/master/src/en/uhdmovies/src/eu/kanade/tachiyomi/animeextension/en/uhdmovies/UHDMovies.kt
    private fun extractVideo(epUrl: EpUrl): Pair<List<Video>, String> {
        val matchResult = qualityRegex.find(epUrl.name)
        val quality = matchResult?.groupValues?.get(1) ?: epUrl.quality

        return (1..3).toList().flatMap { type ->
            extractWorkerLinks(epUrl.url, quality, type)
        }.let { Pair(it, epUrl.url) }
    }

    private fun extractWorkerLinks(mediaUrl: String, quality: String, type: Int): List<Video> {
        val reqLink = mediaUrl.replace("/file/", "/wfile/") + "?type=$type"
        val resp = client.newCall(GET(reqLink)).execute().asJsoup()
        val sizeMatch = SIZE_REGEX.find(resp.select("div.card-header").text().trim())
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
                quality = "${quality}p - CF $type Worker ${index + 1}$size",
                videoUrl = decodedLink,
            )
        }
    }

    private fun getDirectLink(url: String, action: String = "direct", newPath: String = "/file/"): String? {
        val doc = client.newCall(GET(url, headers)).execute().asJsoup()
        val script = doc.selectFirst("script:containsData(async function taskaction)")
            ?.data()
            ?: return url

        val key = script.substringAfter("key\", \"").substringBefore('"')
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", action)
            .addFormDataPart("key", key)
            .addFormDataPart("action_token", "")
            .build()

        val headers = headersBuilder().set("x-token", url.toHttpUrl().host).build()

        val req = client.newCall(POST(url.replace("/file/", newPath), headers, form)).execute()
        return runCatching {
            json.decodeFromString<DriveLeechDirect>(req.body.string()).url
        }.getOrNull()
    }

    private fun extractGDriveLink(mediaUrl: String, quality: String): List<Video> {
        val neoUrl = getDirectLink(mediaUrl) ?: mediaUrl
        val response = client.newCall(GET(neoUrl)).execute().asJsoup()
        val gdBtn = response.selectFirst("div.card-body a.btn")!!
        val gdLink = gdBtn.attr("href")
        val sizeMatch = SIZE_REGEX.find(gdBtn.text())
        val size = sizeMatch?.groups?.get(1)?.value?.let { " - $it" } ?: ""
        val gdResponse = client.newCall(GET(gdLink)).execute().asJsoup()
        val link = gdResponse.select("form#download-form")
        return if (link.isNullOrEmpty()) {
            emptyList()
        } else {
            val realLink = link.attr("action")
            listOf(Video(realLink, "$quality - Gdrive$size", realLink))
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
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

    @Serializable
    data class EpUrl(
        val quality: String,
        val url: String,
        val name: String,
    )

    @Serializable
    data class DriveLeechDirect(val url: String? = null)

    companion object {
        private val SIZE_REGEX = "\\[((?:.(?!\\[))+)][ ]*\$".toRegex(RegexOption.IGNORE_CASE)

        private const val PREF_QUALITY_KEY = "pref_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
