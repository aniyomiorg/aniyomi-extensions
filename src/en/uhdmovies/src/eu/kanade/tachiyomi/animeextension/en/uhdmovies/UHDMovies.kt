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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class UHDMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "UHD Movies"

    override val baseUrl by lazy {
        val url = preferences.getString(PREF_DOMAIN_KEY, PREF_DEFAULT_DOMAIN)!!
        runBlocking {
            withContext(Dispatchers.Default) {
                client.newBuilder()
                    .followRedirects(false)
                    .build()
                    .newCall(GET("$url/")).execute().use { resp ->
                        when (resp.code) {
                            301 -> {
                                (resp.headers["location"]?.substringBeforeLast("/") ?: url).also {
                                    preferences.edit().putString(PREF_DOMAIN_KEY, it).apply()
                                }
                            }
                            else -> url
                        }
                    }
            }
        }
    }

    override val lang = "en"

    override val supportsLatest = false

    override val client = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeSelector(): String = "div#content  div.gridlove-posts > div.layout-masonry"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.select("div.entry-image > a").attr("abs:href"))
            thumbnail_url = element.select("div.entry-image > a > img").attr("abs:src")
            title = element.select("div.entry-image > a").attr("title")
                .replace("Download", "").trim()
        }
    }

    override fun popularAnimeNextPageSelector(): String =
        "div#content  > nav.gridlove-pagination > a.next"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not Used")

    override fun latestUpdatesSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not Used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not Used")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val cleanQuery = query.replace(" ", "+").lowercase()
        return GET("$baseUrl/page/$page/?s=$cleanQuery")
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            initialized = true
            title = document.selectFirst(".entry-title")?.text()
                ?.replace("Download", "", true)?.trim() ?: "Movie"
            status = SAnime.COMPLETED
            description = document.selectFirst("pre:contains(plot)")?.text()
        }
    }

    // ============================== Episodes ==============================
    private fun Regex.firstValue(text: String) =
        find(text)?.groupValues?.get(1)?.let { Pair(text, it) }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.use { it.asJsoup() }
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

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not Used")

    // ============================ Video Links =============================
    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val urlJson = json.decodeFromString<EpLinks>(episode.url)
        val failedMediaUrl = mutableListOf<Pair<String, String>>()
        val videoList = mutableListOf<Video>()
        videoList.addAll(
            urlJson.urls.parallelMap { url ->
                runCatching {
                    val (videos, mediaUrl) = extractVideo(url)
                    if (videos.isEmpty() && mediaUrl.isNotBlank()) failedMediaUrl.add(Pair(mediaUrl, url.quality))
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

        if (videoList.isEmpty()) throw Exception("No working links found")

        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================
    private fun extractVideo(epUrl: EpUrl): Pair<List<Video>, String> {
        val noRedirectClient = client.newBuilder().followRedirects(false).build()
        val mediaResponse = if (epUrl.url.contains("?id=")) {
            val postLink = epUrl.url.substringBefore("?id=").substringAfter("/?")
            val initailUrl = epUrl.url.substringAfter("/?http").let {
                if (it.startsWith("http")) {
                    it
                } else {
                    "http$it"
                }
            }
            val initialResp = noRedirectClient.newCall(GET(initailUrl)).execute().asJsoup()
            val (tokenUrl, tokenCookie) = if (initialResp.selectFirst("form#landing input[name=_wp_http_c]") != null) {
                val formData = FormBody.Builder().add("_wp_http_c", epUrl.url.substringAfter("?id=")).build()
                val response = client.newCall(POST(postLink, body = formData)).execute().body.string()
                val (longC, catC, _) = getCookiesDetail(response)
                val cookieHeader = Headers.headersOf("Cookie", "$longC; $catC")
                val parsedSoup = Jsoup.parse(response)
                val link = parsedSoup.selectFirst("center > a")!!.attr("href")

                val response2 = client.newCall(GET(link, cookieHeader)).execute().body.string()
                val (longC2, _, postC) = getCookiesDetail(response2)
                val cookieHeader2 = Headers.headersOf("Cookie", "$catC; $longC2; $postC")
                val parsedSoup2 = Jsoup.parse(response2)
                val link2 = parsedSoup2.selectFirst("center > a")!!.attr("href")
                val tokenResp = client.newCall(GET(link2, cookieHeader2)).execute().body.string()
                val goToken = tokenResp.substringAfter("?go=").substringBefore("\"")
                val tokenUrl = "$postLink?go=$goToken"
                val newLongC = "$goToken=" + longC2.substringAfter("=")
                val tokenCookie = Headers.headersOf("Cookie", "$catC; rdst_post=; $newLongC")
                Pair(tokenUrl, tokenCookie)
            } else {
                val secondResp = initialResp.getNextResp().asJsoup()
                val thirdResp = secondResp.getNextResp().body.string()
                val goToken = thirdResp.substringAfter("?go=").substringBefore("\"")
                val tokenUrl = "$postLink?go=$goToken"
                val cookie = secondResp.selectFirst("form#landing input[name=_wp_http2]")?.attr("value")
                val tokenCookie = Headers.headersOf("Cookie", "$goToken=$cookie")
                Pair(tokenUrl, tokenCookie)
            }

            val tokenResponse = noRedirectClient.newCall(GET(tokenUrl, tokenCookie)).execute().asJsoup()
            val redirectUrl = tokenResponse.select("meta[http-equiv=refresh]").attr("content")
                .substringAfter("url=").substringBefore("\"")
            noRedirectClient.newCall(GET(redirectUrl)).execute()
        } else if (epUrl.url.contains("r?key=")) {
            client.newCall(GET(epUrl.url)).execute()
        } else { throw Exception("Something went wrong") }

        val path = mediaResponse.body.string().substringAfter("replace(\"").substringBefore("\"")
        if (path == "/404") return Pair(emptyList(), "")
        val mediaUrl = "https://" + mediaResponse.request.url.host + path
        val videoList = mutableListOf<Video>()

        for (type in 1..3) {
            videoList.addAll(
                extractWorkerLinks(mediaUrl, epUrl.quality, type),
            )
        }
        return Pair(videoList, mediaUrl)
    }

    private fun Document.getNextResp(): Response {
        val form = this.selectFirst("form#landing") ?: throw Exception("Failed to find form")
        val postLink = form.attr("action")
        val formData = FormBody.Builder().let { fd ->
            form.select("input").map {
                fd.add(it.attr("name"), it.attr("value"))
            }
            fd.build()
        }

        return client.newCall(POST(postLink, body = formData)).execute()
    }

    private fun getCookiesDetail(page: String): Triple<String, String, String> {
        val cat = "rdst_cat"
        val post = "rdst_post"
        val longC = page.substringAfter(".setTime")
            .substringAfter("document.cookie = \"")
            .substringBefore("\"")
            .substringBefore(";")
        val catC = if (page.contains("$cat=")) {
            page.substringAfterLast("$cat=")
                .substringBefore(";").let {
                    "$cat=$it"
                }
        } else { "" }

        val postC = if (page.contains("$post=")) {
            page.substringAfterLast("$post=")
                .substringBefore(";").let {
                    "$post=$it"
                }
        } else { "" }

        return Triple(longC, catC, postC)
    }

    private val sizeRegex = "\\[((?:.(?!\\[))+)] *\$".toRegex(RegexOption.IGNORE_CASE)

    private fun extractWorkerLinks(mediaUrl: String, quality: String, type: Int): List<Video> {
        val reqLink = mediaUrl.replace("/file/", "/wfile/") + "?type=$type"
        val resp = client.newCall(GET(reqLink)).execute().asJsoup()
        val sizeMatch = sizeRegex.find(resp.select("div.card-header").text().trim())
        val size = sizeMatch?.groups?.get(1)?.value?.let { " - $it" } ?: ""
        return try {
            resp.select("div.card-body div.mb-4 > a").mapIndexed { index, linkElement ->
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
        } catch (_: Exception) {
            emptyList()
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
        return if (link.isEmpty()) {
            listOf()
        } else {
            val realLink = link.attr("action")
            listOf(Video(realLink, "$quality - Gdrive$size", realLink))
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val ascSort = preferences.getString("preferred_size_sort", "asc")!! == "asc"

        val comparator = compareByDescending<Video> { it.quality.contains(quality) }.let { cmp ->
            if (ascSort) {
                cmp.thenBy { it.quality.fixQuality() }
            } else {
                cmp.thenByDescending { it.quality.fixQuality() }
            }
        }
        return this.sortedWith(comparator)
    }

    private fun String.fixQuality(): Float {
        val size = this.substringAfterLast("-").trim()
        return if (size.contains("GB", true)) {
            size.replace("GB", "", true)
                .toFloat() * 1000
        } else {
            size.replace("MB", "", true)
                .toFloat()
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
        val sizeSortPref = ListPreference(screen.context).apply {
            key = "preferred_size_sort"
            title = "Preferred Size Sort"
            entries = arrayOf("Ascending", "Descending")
            entryValues = arrayOf("asc", "dec")
            setDefaultValue("asc")
            summary = """%s
                |Sort order to be used after the videos are sorted by their quality.
            """.trimMargin()

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val domainPref = EditTextPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Currently used domain"
            dialogTitle = title
            setDefaultValue(PREF_DEFAULT_DOMAIN)
            val tempText = preferences.getString(key, PREF_DEFAULT_DOMAIN)
            summary = """$tempText
                |For any change to be applied App restart is required.
            """.trimMargin()

            setOnPreferenceChangeListener { _, newValue ->
                val newValueString = newValue as String
                preferences.edit().putString(key, newValueString.trim()).commit().also {
                    summary = """$newValueString
                        |For any change to be applied App restart is required.
                    """.trimMargin()
                }
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(sizeSortPref)
        screen.addPreference(domainPref)
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

    private fun EpLinks.toJson(): String {
        return json.encodeToString(this)
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    companion object {
        const val PREF_DOMAIN_KEY = "pref_domain_new"
        const val PREF_DEFAULT_DOMAIN = "https://uhdmovies.zip"
    }
}
