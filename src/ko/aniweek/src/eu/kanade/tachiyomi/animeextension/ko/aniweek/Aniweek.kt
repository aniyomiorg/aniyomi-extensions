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
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
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

    override val baseUrl = "https://aniweek.com"

    override val lang = "ko"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/bbs/board.php?bo_table=ing")

    override fun popularAnimeSelector(): String = "div.list-board > div.list-body > div.list-row"

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

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active ~ li:not(.disabled):matches(.)"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

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

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

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
            Pair("2024", "bo_table=fin&sca=2024"),
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

    override fun episodeFromElement(element: Element): SEpisode = SEpisode.create().apply {
        episode_number = element.selectFirst("div.wr-num")?.let { it.text()?.toFloatOrNull() ?: 1F } ?: 1F
        name = element.selectFirst("a")!!.text()
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        date_upload = element.selectFirst("div.wr-date")?.let { parseDate(it.text()) } ?: 0L
    }

    // ============================ Video Links =============================

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val form = document.selectFirst("form.tt") ?: error("Failed to generate form")
        val postUrl = form.attr("action")

        val postBody = FormBody.Builder().apply {
            form.select("input[type=hidden][name][value]").forEach {
                add(it.attr("name"), it.attr("value"))
            }
        }.build()

        val postHeaders = headers.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Content-Type", "application/x-www-form-urlencoded")
            add("Host", postUrl.toHttpUrl().host)
            add("Origin", baseUrl)
            add("Referer", "$baseUrl/")
        }.build()

        val newDocument = client.newCall(
            POST(postUrl, body = postBody, headers = postHeaders),
        ).execute().asJsoup()

        val iframeUrl = newDocument.selectFirst("iframe")?.attr("src") ?: error("Failed to extract iframe")

        val iframeHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", iframeUrl.toHttpUrl().host)
            .add("Referer", "$baseUrl/")
            .add("Sec-Fetch-Dest", "iframe")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-Site", "cross-site")
            .add("Upgrade-Insecure-Requests", "1")
            .build()

        val iframeResponse = client.newCall(
            GET(iframeUrl, headers = iframeHeaders),
        ).execute()

        val subtitleList = mutableListOf<Track>()
        val scriptElement = iframeResponse.asJsoup().selectFirst("script:containsData(playerjsSubtitle)")
        if (scriptElement != null) {
            val string = scriptElement.data().substringAfter("var playerjsSubtitle = \"").substringBefore("\"")
            if (string.isNotEmpty()) {
                subtitleList.add(
                    Track(
                        "https:" + string.substringAfter("https:"),
                        string.substringBefore("https:"),
                    ),
                )
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

        val videoPostHeaders = headers.newBuilder().apply {
            add("Accept", "*/*")
            add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            add("Origin", "https://${iframeUrl.toHttpUrl().host}")
            add("Referer", iframeUrl)
            add("X-Requested-With", "XMLHttpRequest")
            add("Cookie", cookieValue.substringBefore(";"))
        }.build()

        val videoPostBody = "hash=$hash&r=${java.net.URLEncoder.encode("$baseUrl/", "utf-8")}"
            .toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val postResponse = client.newCall(
            POST("https://${iframeUrl.toHttpUrl().host}/player/index.php?data=$hash&do=getVideo", body = videoPostBody, headers = videoPostHeaders),
        ).execute().body.string()

        val parsed = json.decodeFromString<IframeResponse>(postResponse)

        return if (parsed.hls) {
            val masterHeaders = headers.newBuilder().apply {
                add("Accept", "*/*")
                add("Cookie", cookieValue.substringBefore(";"))
                add("Host", iframeUrl.toHttpUrl().host)
                add("Referer", iframeUrl)
                add("Sec-Fetch-Dest", "empty")
                add("Sec-Fetch-Mode", "cors")
                add("Sec-Fetch-Site", "same-origin")
                add("TE", "trailers")
            }.build()

            fun genVideoHeaders(baseHeaders: Headers, referer: String, videoUrl: String): Headers {
                return baseHeaders.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Origin", "https://${iframeUrl.toHttpUrl().host}")
                    add("Referer", "https://${iframeUrl.toHttpUrl().host}/")
                }.build()
            }

            playlistUtils.extractFromHls(
                parsed.videoSource,
                masterHeadersGen = { _, _ -> masterHeaders },
                videoHeadersGen = ::genVideoHeaders,
            )
        } else {
            emptyList()
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

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
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.KOREAN)
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
