package eu.kanade.tachiyomi.animeextension.en.kissanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.kissanime.extractors.VodstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class KissAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "kissanime.com.ru"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/AnimeListOnline/Trending?page=$page")

    override fun popularAnimeSelector(): String = "div.listing > div.item_movies_in_cat"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.selectFirst("div.title_in_cat_container > a")!!.text()
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination > ul > li.current ~ li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/AnimeListOnline/LatestUpdate?page=$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val params = KissAnimeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .awaitSuccess()
            .use(::searchAnimeParse)
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: KissAnimeFilters.FilterSearchParams): Request {
        return when {
            filters.subpage.isNotBlank() -> GET("$baseUrl/${filters.subpage}/?page=$page")
            filters.schedule.isNotBlank() -> GET("$baseUrl/Schedule#${filters.schedule}")
            else -> GET("$baseUrl/AdvanceSearch/?name=$query&status=${filters.status}&genre=${filters.genre}&page=$page", headers = headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.encodedPath.startsWith("/Schedule")) {
            val document = response.asJsoup()
            val name = response.request.url.encodedFragment!!

            val animeList = document.select("div.barContent > div.schedule_container > div.schedule_item:has(div.schedule_block_title:contains($name)) div.schedule_row > div.schedule_block").map {
                SAnime.create().apply {
                    title = it.selectFirst("h2 > a > span.jtitle")!!.text()
                    thumbnail_url = it.selectFirst("img")!!.attr("src")
                    setUrlWithoutDomain(it.selectFirst("a")!!.attr("href"))
                }
            }

            AnimesPage(animeList, false)
        } else {
            super.searchAnimeParse(response)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = KissAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val rating = document.selectFirst("div.Votes > div.Prct > div[data-percent]")?.let { "\n\nUser rating: ${it.attr("data-percent")}%" } ?: ""

        return SAnime.create().apply {
            title = document.selectFirst("div.barContent > div.full > h2")!!.text()
            thumbnail_url = document.selectFirst("div.cover_anime img")!!.attr("src")
            status = parseStatus(document.selectFirst("div.full > div.static_single > p:has(span:contains(Status))")!!.ownText())
            description = (document.selectFirst("div.full > div.summary > p")?.text() ?: "") + rating
            genre = document.select("div.full > p.info:has(span:contains(Genre)) > a").joinToString(", ") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div.listing > div:not([class])"

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        name = element.selectFirst("a")!!.text()
        episode_number = element.selectFirst("a")!!.text().substringAfter("Episode ").toFloatOrNull() ?: 0F
        date_upload = parseDate(element.selectFirst("div:not(:has(a))")!!.text())
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
    }

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val serverList = mutableListOf<Server>()

        // GET VIDEO HOSTERS
        val episodeId = (baseUrl + episode.url).toHttpUrl().queryParameter("id")!!

        var document = client.newCall(
            GET(baseUrl + episode.url, headers = headers),
        ).await().asJsoup()
        var newDocument = document

        for (server in document.select("select#selectServer > option")) {
            val url = baseUrl + server.attr("value")

            if (!server.hasAttr("selected")) {
                newDocument = client.newCall(
                    GET(url, headers = headers),
                ).await().asJsoup()
            }

            val ctk = newDocument.selectFirst("script:containsData(ctk)")!!.data().substringAfter("var ctk = '").substringBefore("';")

            val getIframeHeaders = Headers.headersOf(
                "Accept", "application/json, text/javascript, */*; q=0.01",
                "Alt-Used", baseUrl.toHttpUrl().host,
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
                "Host", baseUrl.toHttpUrl().host,
                "Origin", baseUrl,
                "Referer", url,
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                "X-Requested-With", "XMLHttpRequest",
            )

            val getIframeBody = "episode_id=$episodeId&ctk=$ctk".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val serverName = url.toHttpUrl().queryParameter("s")!!

            val iframe = json.decodeFromString<IframeResponse>(
                client.newCall(
                    POST("$baseUrl/ajax/anime/load_episodes_v2?s=$serverName", body = getIframeBody, headers = getIframeHeaders),
                ).await().body.string(),
            )
            var iframeUrl = Jsoup.parse(iframe.value).selectFirst("iframe")!!.attr("src")

            val password = if (iframe.value.contains("password: ")) {
                iframe.value.substringAfter("password: ").substringBefore(" <button")
            } else {
                null
            }

            if (!iframeUrl.startsWith("http")) iframeUrl = "https:$iframeUrl"
            serverList.add(Server(server.text(), iframeUrl, password))
        }

        // GET VIDEO URLS
        val videoList = serverList.parallelCatchingFlatMap { server ->
            val url = server.url

            when {
                url.contains("yourupload") -> {
                    YourUploadExtractor(client).videoFromUrl(url, headers = headers, name = server.name)
                }
                url.contains("mp4upload") -> {
                    Mp4uploadExtractor(client).videosFromUrl(url, headers, "(${server.name}) ")
                }
                url.contains("embed.vodstream.xyz") -> {
                    val referer = "$baseUrl/"
                    VodstreamExtractor(client).getVideosFromUrl(url, referer = referer, prefix = "${server.name} - ")
                }
                url.contains("dailymotion") -> {
                    DailymotionExtractor(client, headers).videosFromUrl(url, "${server.name} - ", baseUrl, server.password)
                }
                else -> null
            }.orEmpty()
        }

        return videoList.sort()
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    data class Server(
        val name: String,
        val url: String,
        val password: String? = null,
    )

    @Serializable
    data class IframeResponse(
        val value: String,
    )

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString.trim()) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MM/dd/yyyy", Locale.ENGLISH)
        }

        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_DEFAULT = "https://kissanime.com.ru"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("kissanime.com.ru", "kissanime.co", "kissanime.sx", "kissanime.org.ru")
            entryValues = arrayOf("https://kissanime.com.ru", "https://kissanime.co", "https://kissanime.sx", "https://kissanime.org.ru")
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
