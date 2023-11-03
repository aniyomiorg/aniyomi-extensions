package eu.kanade.tachiyomi.animeextension.id.samehadaku

import android.app.Application
import android.content.SharedPreferences
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
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Samehadaku : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val name: String = "Samehadaku"
    override val baseUrl: String = "https://samehadaku.rent"
    override val lang: String = "id"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/daftar-anime-2/page/$page/?order=popular")

    override fun popularAnimeSelector(): String = "div.relat > article"

    override fun popularAnimeFromElement(element: Element): SAnime =
        getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "div.pagination > a.arrow_pag"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/daftar-anime-2/page/$page/?order=latest")

    override fun latestUpdatesSelector(): String = "div.relat > article"

    override fun latestUpdatesFromElement(element: Element): SAnime =
        getAnimeFromAnimeElement(element)

    override fun latestUpdatesNextPageSelector(): String = "div.pagination > a.arrow_pag"

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/page/$page/?s=$query")

    override fun searchAnimeSelector(): String = "main.site-main.relat > article"

    override fun searchAnimeFromElement(element: Element): SAnime =
        getAnimeFromAnimeElement(element)

    override fun searchAnimeNextPageSelector(): String = "div.pagination > a.arrow_pag"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val detail = document.selectFirst("div.infox > div.spe")!!
        return SAnime.create().apply {
            author = detail.getInfo("Studio")
            status = parseStatus(detail.getInfo("Status"))
            title = document.selectFirst("h3.anim-detail")!!.text().split("Detail Anime ")[1]
            thumbnail_url =
                document.selectFirst("div.infoanime.widget_senction > div.thumb > img")!!
                    .attr("src")
            description =
                document.selectFirst("div.entry-content.entry-content-single > p")!!.text()
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector(): String =
        "div.whites.lsteps.widget_senction > div.lstepsiode.listeps > ul > li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = element.selectFirst("span.eps > a")!!.text()
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            episode_number = episode.toFloatOrNull() ?: 1F
            name = element.selectFirst("span.lchx > a")!!.text()
            date_upload = element.selectFirst("span.date")!!.text().toDate()
        }
    }

    // ============================ Video Links =============================

    override fun videoListSelector(): String = "#server > ul > li > div"

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        return doc.select(videoListSelector())
            .parallelMapNotNull {
                runCatching { getEmbedLinks(it) }.getOrNull()
            }
            .parallelMapNotNull {
                runCatching { getVideosFromEmbed(it.first, it.second) }.getOrNull()
            }.flatten()
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.quality.contains(quality) },
        )
    }

    private fun String?.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(this?.trim() ?: "")?.time }
            .getOrNull() ?: 0L
    }

    private fun Element.getInfo(info: String, cut: Boolean = true): String? {
        return selectFirst("span:has(b:contains($info))")!!.text()
            .let {
                when {
                    cut -> it.substringAfter(" ")
                    else -> it
                }.trim()
            }
    }

    private inline fun <A, B> Iterable<A>.parallelMapNotNull(crossinline f: suspend (A) -> B?): List<B> {
        return runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll().filterNotNull()
        }
    }

    private fun getAnimeFromAnimeElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("div > a")!!.attr("href"))
            title = element.selectFirst("div.title > h2")!!.text()
            thumbnail_url =
                element.selectFirst("div.content-thumb > img")!!.attr("src")
        }
    }

    private fun parseStatus(status: String?): Int {
        return when (status?.trim()?.lowercase()) {
            "completed" -> SAnime.COMPLETED
            "ongoing" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun getEmbedLinks(element: Element): Pair<String, String> {
        val form = FormBody.Builder().apply {
            add("action", "player_ajax")
            add("post", element.attr("data-post"))
            add("nume", element.attr("data-nume"))
            add("type", element.attr("data-type"))
        }.build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", body = form))
            .execute()
            .use {
                val server = element.selectFirst("span")!!.text()
                val link = it.body.string().substringAfter("src=\"").substringBefore("\"")
                return@use Pair(server, link)
            }
    }

    private fun getVideosFromEmbed(server: String, link: String): List<Video> {
        return when {
            "wibufile" in link -> {
                client.newCall(GET(link)).execute().use {
                    val videoUrl = it.body.string().substringAfter("cast(\"").substringBefore("\"")
                    listOf(Video(videoUrl, server, videoUrl, headers))
                }
            }

//            "krakenfiles" in link -> {
//                client.newCall(GET(link)).execute().use {
//                    val doc = it.asJsoup()
//                    val videoUrl = doc.selectFirst("#my-video > source")!!.attr("src")
//                    listOf(Video(videoUrl, server, videoUrl, headers))
//                }
//            }

            "blogger" in link -> {
                client.newCall(GET(link)).execute().use {
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
