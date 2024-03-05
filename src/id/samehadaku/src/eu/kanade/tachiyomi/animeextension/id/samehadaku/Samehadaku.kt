package eu.kanade.tachiyomi.animeextension.id.samehadaku

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapNotNullBlocking
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Samehadaku : ConfigurableAnimeSource, AnimeHttpSource() {
    private val mainBaseUrl: String = "https://samehadaku.show"

    override val name: String = "Samehadaku"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true
    override val baseUrl: String by lazy { getPrefBaseUrl() }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/daftar-anime-2/page/$page/?order=popular")

    override fun popularAnimeParse(response: Response): AnimesPage =
        getAnimeParse(response.asJsoup(), "div.relat > article")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/daftar-anime-2/page/$page/?order=latest")

    override fun latestUpdatesParse(response: Response): AnimesPage =
        getAnimeParse(response.asJsoup(), "div.relat > article")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val params = SamehadakuFilters.getSearchParameters(filters)
            GET("$baseUrl/daftar-anime-2/page/$page/?${params.filter}")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val doc = response.asJsoup()
        val searchSelector = "main.site-main.relat > article"

        return if (doc.selectFirst(searchSelector) != null) {
            getAnimeParse(doc, searchSelector)
        } else {
            getAnimeParse(doc, "div.relat > article")
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = SamehadakuFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val doc = response.asJsoup()
        val detail = doc.selectFirst("div.infox > div.spe")!!

        return SAnime.create().apply {
            author = detail.getInfo("Studio")
            status = parseStatus(detail.getInfo("Status"))
            title = doc.selectFirst("h3.anim-detail")!!.text().split("Detail Anime ")[1]
            thumbnail_url =
                doc.selectFirst("div.infoanime.widget_senction > div.thumb > img")!!
                    .attr("src")
            description =
                doc.selectFirst("div.entry-content.entry-content-single > p")!!.text()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()

        return doc.select("div.lstepsiode > ul > li")
            .map {
                val episode = it.selectFirst("span.eps > a")!!
                SEpisode.create().apply {
                    setUrlWithoutDomain(episode.attr("href"))
                    episode_number = episode.text().trim().toFloatOrNull() ?: 1F
                    name = it.selectFirst("span.lchx > a")!!.text()
                    date_upload = it.selectFirst("span.date")!!.text().toDate()
                }
            }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val parseUrl = response.request.url.toUrl()
        val url = "${parseUrl.protocol}://${parseUrl.host}"
        if (!getPrefBaseUrl().contains(url)) putPrefBaseUrl(url)

        return doc.select("#server > ul > li > div")
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(url, it) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first, it.second)
            }
    }

    // ============================= Utilities ==============================

    private fun getAnimeParse(document: Document, query: String): AnimesPage {
        val animes = document.select(query).map {
            SAnime.create().apply {
                setUrlWithoutDomain(it.selectFirst("div > a")!!.attr("href"))
                title = it.selectFirst("div.title > h2")!!.text()
                thumbnail_url = it.selectFirst("div.content-thumb > img")!!.attr("src")
            }
        }

        val hasNextPage = try {
            val pagination = document.selectFirst("div.pagination")!!
            val totalPage = pagination.selectFirst("span:nth-child(1)")!!.text().split(" ").last()
            val currentPage = pagination.selectFirst("span.page-numbers.current")!!.text()
            currentPage.toInt() < totalPage.toInt()
        } catch (_: Exception) {
            false
        }

        return AnimesPage(animes, hasNextPage)
    }

    private fun getEmbedLinks(url: String, element: Element): Pair<String, String> {
        val form = FormBody.Builder().apply {
            add("action", "player_ajax")
            add("post", element.attr("data-post"))
            add("nume", element.attr("data-nume"))
            add("type", element.attr("data-type"))
        }.build()

        return client.newCall(POST("$url/wp-admin/admin-ajax.php", body = form))
            .execute()
            .let {
                val body = it.body.string()
                val quality = element.selectFirst("span")!!.text()
                val link = body.substringAfter("src=\"").substringBefore("\"")
                Pair(quality, link)
            }
    }

    private fun getVideosFromEmbed(quality: String, link: String): List<Video> {
        return when {
            "wibufile" in link -> {
                val videoQuality = when {
                    "480" in quality -> "Wibufile 480p"
                    "720" in quality -> "Wibufile 720p"
                    "1080" in quality -> "Wibufile 1080p"
                    else -> "Unknown Resolution"
                }

                if (".mp4" in link.lowercase()) {
                    return listOf(Video(link, videoQuality, link, headers))
                }

                client.newCall(GET(link)).execute().use {
                    if (!it.isSuccessful) return emptyList()

                    val body = it.body.string()
                    val json = JSONObject(body.substringAfter("source = ").substringBefore(";"))
                    val sources = json.getJSONArray("sources")
                    val videoList = mutableListOf<Video>()
                    for (i in 0 until sources.length()) {
                        val stream = sources.getJSONObject(i)
                        val videoUrl = stream.getString("src")
                        videoList.add(Video(videoUrl, videoQuality, videoUrl, headers))
                    }
                    videoList
                }
            }

            "krakenfiles" in link -> {
                client.newCall(GET(link)).execute().let {
                    val doc = it.asJsoup()
                    val getUrl = doc.selectFirst("source")!!.attr("src")
                    val videoUrl = "https:${getUrl.replace("&amp;", "&")}"
                    listOf(Video(videoUrl, quality, videoUrl, headers))
                }
            }

            "blogger" in link -> {
                client.newCall(GET(link)).execute().body.string().let {
                    val json = JSONObject(it.substringAfter("= ").substringBefore("<"))
                    val streams = json.getJSONArray("streams")
                    val videoList = mutableListOf<Video>()
                    for (i in 0 until streams.length()) {
                        val stream = streams.getJSONObject(i)
                        val videoUrl = stream.getString("play_url")
                        val videoQuality = when (stream.getString("format_id")) {
                            "18" -> "Google 360p"
                            "22" -> "Google 720p"
                            else -> "Unknown Resolution"
                        }
                        videoList.add(Video(videoUrl, videoQuality, videoUrl, headers))
                    }
                    videoList
                }
            }

            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> =
        sortedWith(compareByDescending { it.quality.contains(getPrefQuality()) })

    private fun String?.toDate(): Long =
        runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }.getOrNull() ?: 0L

    private fun Element.getInfo(info: String, cut: Boolean = true): String =
        selectFirst("span:has(b:contains($info))")!!.text()
            .let {
                when {
                    cut -> it.substringAfter(" ")
                    else -> it
                }.trim()
            }

    private fun parseStatus(status: String?): Int =
        when (status?.trim()?.lowercase()) {
            "completed" -> SAnime.COMPLETED
            "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_BASEURL_KEY
            title = PREF_BASEURL_TITLE
            dialogTitle = PREF_BASEURL_TITLE
            summary = getPrefBaseUrl()

            setDefaultValue(getPrefBaseUrl())
            setOnPreferenceChangeListener { _, newValue ->
                val changed = newValue as String
                summary = changed
                Toast.makeText(screen.context, RESTART_ANIYOMI, Toast.LENGTH_LONG).show()
                putPrefBaseUrl(changed)
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            summary = "%s"

            setDefaultValue(getPrefQuality())
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                putPrefQuality(entry)
            }
        }.also(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String =
        preferences.getString(PREF_BASEURL_KEY, mainBaseUrl)!!

    private fun putPrefBaseUrl(newValue: String): Boolean =
        preferences.edit().putString(PREF_BASEURL_KEY, newValue).commit()

    private fun getPrefQuality(): String =
        preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

    private fun putPrefQuality(newValue: String): Boolean =
        preferences.edit().putString(PREF_QUALITY_KEY, newValue).commit()

    companion object {
        private const val PREF_BASEURL_KEY = "baseurlkey_v${BuildConfig.VERSION_NAME}"
        private const val PREF_BASEURL_TITLE = "Override BaseUrl"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred Quality"
        private const val PREF_QUALITY_DEFAULT = "480p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")

        private const val RESTART_ANIYOMI = "Restart Aniyomi to apply new setting."

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
        }
    }
}
