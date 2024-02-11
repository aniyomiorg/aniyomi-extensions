package eu.kanade.tachiyomi.animeextension.id.samehadaku

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Samehadaku : ConfigurableAnimeSource, AnimeHttpSource() {
    override val name: String = "Samehadaku"
    override val baseUrl: String = "https://samehadaku.show"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

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
        val params = SamehadakuFilters.getSearchParameters(filters)
        return GET("$baseUrl/daftar-anime-2/page/$page/?s=$query${params.filter}", headers)
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
        return doc.select("#server > ul > li > div")
            .parallelMapNotNullBlocking {
                runCatching { getEmbedLinks(url, it) }.getOrNull()
            }
            .parallelCatchingFlatMapBlocking {
                getVideosFromEmbed(it.first, it.second)
            }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(compareByDescending { it.quality.contains(quality) })
    }

    private fun String?.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }
            .getOrNull() ?: 0L
    }

    private fun Element.getInfo(info: String, cut: Boolean = true): String {
        return selectFirst("span:has(b:contains($info))")!!.text()
            .let {
                when {
                    cut -> it.substringAfter(" ")
                    else -> it
                }.trim()
            }
    }

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

    private fun parseStatus(status: String?): Int {
        return when (status?.trim()?.lowercase()) {
            "completed" -> SAnime.COMPLETED
            "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
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
                val link = it.body.string().substringAfter("src=\"").substringBefore("\"")
                val server = element.selectFirst("span")!!.text()
                Pair(server, link)
            }
    }

    private fun getVideosFromEmbed(server: String, link: String): List<Video> {
        return when {
            "wibufile" in link -> {
                client.newCall(GET(link)).execute().use {
                    if (!it.isSuccessful) return emptyList()
                    val videoUrl = it.body.string().substringAfter("cast(\"").substringBefore("\"")
                    listOf(Video(videoUrl, server, videoUrl, headers))
                }
            }

            "krakenfiles" in link -> {
                client.newCall(GET(link)).execute().let {
                    val doc = it.asJsoup()
                    val getUrl = doc.selectFirst("source")!!.attr("src")
                    val videoUrl = "https:${getUrl.replace("&amp;", "&")}"
                    listOf(Video(videoUrl, server, videoUrl, headers))
                }
            }

            "blogger" in link -> {
                client.newCall(GET(link)).execute().let {
                    val videoUrl =
                        it.body.string().substringAfter("play_url\":\"").substringBefore("\"")
                    listOf(Video(videoUrl, server, videoUrl, headers))
                }
            }

            else -> emptyList()
        }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            summary = "%s"
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("d MMMM yyyy", Locale("id", "ID"))
        }

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
    }
}
