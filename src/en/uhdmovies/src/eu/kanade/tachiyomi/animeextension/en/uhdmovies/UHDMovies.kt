package eu.kanade.tachiyomi.animeextension.en.uhdmovies

import android.app.Application
import android.util.Base64
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class UHDMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "UHD Movies"

    override val baseUrl by lazy {
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!
    }

    private val currentBaseUrl by lazy {
        runCatching {
            runBlocking {
                withContext(Dispatchers.Default) {
                    client.newBuilder()
                        .followRedirects(false)
                        .build()
                        .newCall(GET("$baseUrl/")).await().use { resp ->
                            when (resp.code) {
                                301 -> {
                                    (resp.headers["location"]?.substringBeforeLast("/") ?: baseUrl).also {
                                        preferences.edit().putString(PREF_DOMAIN_KEY, it).apply()
                                    }
                                }
                                else -> baseUrl
                            }
                        }
                }
            }
        }.getOrDefault(baseUrl)
    }

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$currentBaseUrl/page/$page/")

    override fun popularAnimeSelector(): String = "div#content  div.gridlove-posts > div.layout-masonry"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("div.entry-image > a").attr("abs:href"))
        thumbnail_url = element.select("div.entry-image > a > img").attr("abs:src")
        title = element.select("div.entry-image > a").attr("title")
            .replace("Download", "").trim()
    }

    override fun popularAnimeNextPageSelector(): String =
        "div#content  > nav.gridlove-pagination > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()
        return GET("$currentBaseUrl/page/$page/?s=$cleanQuery")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        initialized = true
        title = document.selectFirst(".entry-title")?.text()
            ?.replace("Download", "", true)?.trim() ?: "Movie"
        status = SAnime.COMPLETED
        description = document.selectFirst("pre:contains(plot)")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = GET(currentBaseUrl + anime.url, headers)

    private fun Regex.firstValue(text: String) =
        find(text)?.groupValues?.get(1)?.let { Pair(text, it) }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val episodeElements = doc.select(episodeListSelector())
            .asSequence()

        val qualityRegex = "\\d{3,4}p".toRegex(RegexOption.IGNORE_CASE)
        val seasonRegex = "[ .]?S(?:eason)?[ .]?(\\d{1,2})[ .]?".toRegex(RegexOption.IGNORE_CASE)
        val seasonTitleRegex = "[ .\\[(]?S(?:eason)?[ .]?(\\d{1,2})[ .\\])]?".toRegex(RegexOption.IGNORE_CASE)
        val partRegex = "Part ?(\\d{1,2})".toRegex(RegexOption.IGNORE_CASE)

        val isSerie = doc.selectFirst(episodeListSelector())?.text().orEmpty().run {
            contains("Episode", true) ||
                contains("Zip", true) ||
                contains("Pack", true)
        }

        val episodeList = episodeElements.map { row ->
            val prevP = row.previousElementSibling()!!.text()
            val qualityMatch = qualityRegex.find(prevP)
            val quality = qualityMatch?.value ?: let {
                val qualityMatchOwn = qualityRegex.find(row.text())
                qualityMatchOwn?.value ?: "HD"
            }

            val defaultName = if (isSerie) {
                val (source, seasonNumber) = seasonRegex.firstValue(prevP) ?: run {
                    val prevPre = row.previousElementSiblings().prev("pre,div.mks_separator").first()
                        ?.text()
                        .orEmpty()
                    seasonRegex.firstValue(prevPre)
                } ?: run {
                    val title = doc.selectFirst("h1.entry-title")?.text().orEmpty()
                    seasonTitleRegex.firstValue(title)
                } ?: "" to "1"

                val part = partRegex.find(source)?.groupValues?.get(1)
                    ?.let { " Pt $it" }
                    .orEmpty()

                "Season ${seasonNumber.toIntOrNull() ?: 1 }$part"
            } else {
                row.previousElementSiblings().let { prevElem ->
                    (prevElem.prev("h1,h2,h3,pre:not(:contains(plot))").first()?.text() ?: "Movie - $quality")
                        .replace("Download", "", true).trim().let {
                            if (it.contains("Collection", true)) {
                                row.previousElementSibling()!!.ownText()
                            } else {
                                it
                            }
                        }
                }
            }

            row.select("a").asSequence()
                .filter { el -> el.classNames().none { it.endsWith("-zip") } }
                .mapIndexedNotNull { index, linkElement ->
                    val episode = linkElement.text()
                        .replace("Episode", "", true)
                        .trim()
                        .toIntOrNull() ?: index + 1

                    val url = linkElement.attr("href").takeUnless(String::isBlank)
                        ?: return@mapIndexedNotNull null

                    Triple(
                        Pair(defaultName, episode),
                        url,
                        quality,
                    )
                }
        }.flatten().groupBy { it.first }.values.mapIndexed { index, items ->
            val (itemName, episodeNum) = items.first().first

            SEpisode.create().apply {
                url = EpLinks(
                    urls = items.map { triple ->
                        EpUrl(url = triple.second, quality = triple.third)
                    },
                ).toJson()

                name = if (isSerie) "$itemName Ep $episodeNum" else itemName

                episode_number = if (isSerie) episodeNum.toFloat() else (index + 1).toFloat()
            }
        }

        if (episodeList.isEmpty()) throw Exception("Only Zip Pack Available")
        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = "p:has(a[href*=?sid=],a[href*=r?key=]):has(a[class*=maxbutton])[style*=center]"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val urlJson = json.decodeFromString<EpLinks>(episode.url)

        val videoList = urlJson.urls.parallelCatchingFlatMap { eplink ->
            val quality = eplink.quality
            val url = getMediaUrl(eplink) ?: return@parallelCatchingFlatMap emptyList()
            val videos = extractVideo(url, quality)
            when {
                videos.isEmpty() -> {
                    extractGDriveLink(url, quality).ifEmpty {
                        getDirectLink(url, "instant", "/mfile/")?.let {
                            listOf(Video(it, "$quality - GDrive Instant link", it))
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
    private val redirectBypasser by lazy { RedirectorBypasser(client, headers) }

    private fun getMediaUrl(epUrl: EpUrl): String? {
        val url = epUrl.url
        val mediaResponse = if (url.contains("?sid=")) {
            /* redirector bs */
            val finalUrl = redirectBypasser.bypass(url) ?: return null
            client.newCall(GET(finalUrl)).execute()
        } else if (url.contains("r?key=")) {
            /* everything under control */
            client.newCall(GET(url)).execute()
        } else { return null }

        val path = mediaResponse.body.string().substringAfter("replace(\"").substringBefore("\"")

        if (path == "/404") return null

        return "https://" + mediaResponse.request.url.host + path
    }

    private fun extractVideo(url: String, quality: String): List<Video> {
        return (1..3).toList().flatMap { type ->
            extractWorkerLinks(url, quality, type)
        }
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
                quality = "$quality - CF $type Worker ${index + 1}$size",
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
        val ascSort = preferences.getString(PREF_SIZE_SORT_KEY, PREF_SIZE_SORT_DEFAULT)!! == "asc"

        val comparator = compareByDescending<Video> { it.quality.contains(quality) }.let { cmp ->
            if (ascSort) {
                cmp.thenBy { it.quality.fixQuality() }
            } else {
                cmp.thenByDescending { it.quality.fixQuality() }
            }
        }
        return sortedWith(comparator)
    }

    private fun String.fixQuality(): Float {
        val size = substringAfterLast("-").trim()
        return if (size.contains("GB", true)) {
            size.replace("GB", "", true)
                .toFloatOrNull()?.let { it * 1000 } ?: 1F
        } else {
            size.replace("MB", "", true)
                .toFloatOrNull() ?: 1F
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
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
            key = PREF_SIZE_SORT_KEY
            title = PREF_SIZE_SORT_TITLE
            entries = PREF_SIZE_SORT_ENTRIES
            entryValues = PREF_SIZE_SORT_VALUES
            setDefaultValue(PREF_SIZE_SORT_DEFAULT)
            summary = PREF_SIZE_SORT_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            dialogTitle = PREF_DOMAIN_DIALOG_TITLE
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = getDomainPrefSummary()

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).ifEmpty { PREF_DOMAIN_DEFAULT }
                    preferences.edit().putString(key, value).commit().also {
                        summary = getDomainPrefSummary()
                    }
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }

    @Serializable
    data class EpLinks(
        val urls: List<EpUrl>,
    )

    @Serializable
    data class EpUrl(
        val quality: String,
        val url: String,
    )

    @Serializable
    data class DriveLeechDirect(val url: String? = null)

    private fun EpLinks.toJson(): String {
        return json.encodeToString(this)
    }

    private fun getDomainPrefSummary(): String =
        preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!!.let {
            """$it
                |For any change to be applied App restart is required.
            """.trimMargin()
        }

    companion object {
        private val SIZE_REGEX = "\\[((?:.(?!\\[))+)][ ]*\\$".toRegex(RegexOption.IGNORE_CASE)

        private const val PREF_DOMAIN_KEY = "pref_domain_new"
        private const val PREF_DOMAIN_TITLE = "Currently used domain"
        private const val PREF_DOMAIN_DEFAULT = "https://uhdmovies.vip"
        private const val PREF_DOMAIN_DIALOG_TITLE = PREF_DOMAIN_TITLE

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Prefferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080p"
        private val PREF_QUALITY_ENTRIES = arrayOf("2160p", "1080p", "720p", "480p")

        private const val PREF_SIZE_SORT_KEY = "preferred_size_sort"
        private const val PREF_SIZE_SORT_TITLE = "Preferred Size Sort"
        private const val PREF_SIZE_SORT_DEFAULT = "asc"
        private val PREF_SIZE_SORT_SUMMARY = """%s
            |Sort order to be used after the videos are sorted by their quality.
        """.trimMargin()
        private val PREF_SIZE_SORT_ENTRIES = arrayOf("Ascending", "Descending")
        private val PREF_SIZE_SORT_VALUES = arrayOf("asc", "desc")
    }
}
