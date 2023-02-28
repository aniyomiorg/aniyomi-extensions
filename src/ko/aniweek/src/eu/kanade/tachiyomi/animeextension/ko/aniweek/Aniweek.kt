package eu.kanade.tachiyomi.animeextension.ko.aniweek

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class Aniweek : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Aniweek"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://aniweek.com")!! }

    override val lang = "ko"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DateFormatter by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/bbs/board.php?bo_table=ing")

    override fun popularAnimeSelector(): String = "div.list-board > div.list-body > div.list-row"

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active ~ li:not(.disabled):matches(.)"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val thumbnailUrl = element.selectFirst("img")!!.attr("src")
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            thumbnail_url = if (thumbnailUrl.startsWith("..")) {
                baseUrl + thumbnailUrl.substringAfter("..")
            } else {
                thumbnailUrl
            }
            title = element.selectFirst("div.post-title")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not Used")

    override fun latestUpdatesSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not Used")

    // =============================== Search ===============================

    // 원피스

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val airingFilter = filterList.find { it is AiringFilter } as AiringFilter
        val yearFilter = filterList.find { it is YearFilter } as YearFilter
        val otherFilter = filterList.find { it is OtherFilter } as OtherFilter

        val pageString = if (page == 1) "" else "&page=$page"
        return when {
            query.isNotBlank() -> GET("$baseUrl/bbs/search.php?sfl=wr_subject&stx=$query&sop=and&gr_id=&srows=24&onetable=&page=$page", headers = headers)
            airingFilter.state != 0 -> GET("$baseUrl/bbs/board.php?${airingFilter.toUriPart()}$pageString", headers = headers)
            yearFilter.state != 0 -> GET("$baseUrl/bbs/board.php?${yearFilter.toUriPart()}$pageString", headers = headers)
            otherFilter.state != 0 -> GET("$baseUrl/bbs/board.php?${otherFilter.toUriPart()}$pageString", headers = headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== FILTERS ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("텍스트 검색은 필터를 무시합니다"),
        AiringFilter(),
        YearFilter(),
        OtherFilter(),
    )

    private class AiringFilter : UriPartFilter(
        "방영중",
        arrayOf(
            Pair("<선택하다>", ""),
            Pair("전체", "bo_table=ing"),
            Pair("일요일", "bo_table=ing&sca=일요일"),
            Pair("월요일", "bo_table=ing&sca=월요일"),
            Pair("화요일", "bo_table=ing&sca=화요일"),
            Pair("수요일", "bo_table=ing&sca=수요일"),
            Pair("목요일", "bo_table=ing&sca=목요일"),
            Pair("금요일", "bo_table=ing&sca=금요일"),
            Pair("토요일", "bo_table=ing&sca=토요일"),
            Pair("기타", "bo_table=ing&sca=기타"),
        ),
    )

    private class YearFilter : UriPartFilter(
        "종영",
        arrayOf(
            Pair("<선택하다>", ""),
            Pair("전체", "bo_table=fin"),
            Pair("2023", "bo_table=fin&sca=2023"),
            Pair("2022", "bo_table=fin&sca=2022"),
            Pair("2021", "bo_table=fin&sca=2021"),
            Pair("2020", "bo_table=fin&sca=2020"),
            Pair("2019", "bo_table=fin&sca=2019"),
            Pair("2018", "bo_table=fin&sca=2018"),
            Pair("2017", "bo_table=fin&sca=2017"),
            Pair("2016", "bo_table=fin&sca=2016"),
            Pair("2015", "bo_table=fin&sca=2015"),
            Pair("2014", "bo_table=fin&sca=2014"),
            Pair("2013", "bo_table=fin&sca=2013"),
            Pair("2012", "bo_table=fin&sca=2012"),
            Pair("2011", "bo_table=fin&sca=2011"),
            Pair("기타", "bo_table=fin&sca=기타"),
        ),
    )

    private class OtherFilter : UriPartFilter(
        "다른",
        arrayOf(
            Pair("<선택하다>", ""),
            Pair("극장판", "bo_table=theater"),
            Pair("전체", "bo_table=s"),
            Pair("방영중", "bo_table=s&sca=방영중"),
            Pair("종영", "bo_table=s&sca=종영"),
            Pair("극장판", "bo_table=s&sca=극장판"),
            Pair("기타", "bo_table=s&sca=기타"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val thumbnailUrl = document.selectFirst("div.view-info > div.image img")!!.attr("src")
        return SAnime.create().apply {
            title = document.selectFirst("div.view-title")!!.text()
            thumbnail_url = if (thumbnailUrl.startsWith("..")) {
                baseUrl + thumbnailUrl.substringAfter("..")
            } else {
                thumbnailUrl
            }
            status = SAnime.UNKNOWN
            description = document.select("div.view-info > div.list > p").joinToString("\n") { row ->
                row.select("span").joinToString(": ") { it.text() }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String = "div.serial-list > ul.list-body > li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = element.selectFirst("div.wr-num")?.let { it.text()?.toFloatOrNull() ?: 1F } ?: 1F
        episode.name = element.selectFirst("a")!!.text()
        episode.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        episode.date_upload = element.selectFirst("div.wr-date")?.let { parseDate(it.text()) } ?: 0L

        return episode
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val iframeUrl = document.selectFirst("iframe")!!.attr("src")

        val iframeHeaders = Headers.headersOf(
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Host", iframeUrl.toHttpUrl().host,
            "Referer", "$baseUrl/",
            "Sec-Fetch-Dest", "iframe",
            "Sec-Fetch-Mode", "navigate",
            "Sec-Fetch-Site", "cross-site",
            "Upgrade-Insecure-Requests", "1",
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
        )
        var iframeResponse = client.newCall(
            GET(iframeUrl, headers = iframeHeaders),
        ).execute()

        val subtitleList = mutableListOf<Track>()
        val scriptElement = iframeResponse.asJsoup().selectFirst("script:containsData(playerjsSubtitle)")
        if (scriptElement != null) {
            val string = scriptElement.data().substringAfter("var playerjsSubtitle = \"").substringBefore("\"")
            if (string.isNotEmpty()) {
                try {
                    subtitleList.add(
                        Track(
                            "https:" + string.substringAfter("https:"),
                            string.substringBefore("https:"),
                        ),
                    )
                } catch (a: Exception) { }
            }
        }

        val cookiePref = preferences.getString("cookie", null)
        val cookieValue = if (cookiePref == null) {
            val value = iframeResponse.headers.first { it.first == "set-cookie" }.second
            preferences.edit().putString("cookie", value).apply()
            value
        } else {
            cookiePref
        }

        val hash = if (iframeUrl.contains("/video/")) {
            iframeUrl.substringAfter("/video/")
        } else {
            iframeUrl.substringAfter("data=")
        }

        val postHeaders = Headers.headersOf(
            "Accept", "*/*",
            "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
            "Origin", "https://${iframeUrl.toHttpUrl().host}",
            "Referer", iframeUrl,
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            "X-Requested-With", "XMLHttpRequest",
            "Cookie", cookieValue.substringBefore(";"),
        )

        val postBody = "hash=$hash&r=${java.net.URLEncoder.encode("$baseUrl/", "utf-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val postResponse = client.newCall(
            POST("https://${iframeUrl.toHttpUrl().host}/player/index.php?data=$hash&do=getVideo", body = postBody, headers = postHeaders),
        ).execute()

        val parsed = json.decodeFromString<IframeResponse>(postResponse.body.string())

        if (parsed.hls) {
            val playlistHeaders = Headers.headersOf(
                "Accept", "*/*",
                "Cookie", cookieValue.substringBefore(";"),
                "Host", iframeUrl.toHttpUrl().host,
                "Referer", iframeUrl,
                "Sec-Fetch-Dest", "empty",
                "Sec-Fetch-Mode", "cors",
                "Sec-Fetch-Site", "same-origin",
                "TE", "trailers",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            )

            val masterPlaylist = client.newCall(
                GET(parsed.videoSource, headers = playlistHeaders),
            ).execute().body.string()

            val videoHeaders = Headers.headersOf(
                "Accept", "*/*",
                "Origin", baseUrl,
                "Referer", "$baseUrl/",
                "Sec-Fetch-Dest", "empty",
                "Sec-Fetch-Mode", "cors",
                "Sec-Fetch-Site", "same-origin",
                "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
            )

            masterPlaylist.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:")
                .forEach {
                    val quality = it.substringAfter("RESOLUTION=").substringAfter("x")
                        .substringBefore(",").substringBefore("\n") + "p"
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")

                    try {
                        videoList.add(Video(videoUrl, quality, videoUrl, headers = videoHeaders, subtitleTracks = subtitleList))
                    } catch (a: Exception) {
                        videoList.add(Video(videoUrl, quality, videoUrl, headers = videoHeaders))
                    }
                }
        }

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.substringBefore("p").toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    @Serializable
    data class IframeResponse(
        val hls: Boolean,
        val videoSource: String,
    )

    private fun parseDate(dateStr: String): Long {
        return runCatching { DateFormatter.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("aniweek.com")
            entryValues = arrayOf("https://aniweek.com")
            setDefaultValue("https://aniweek.com")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
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

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }
}
