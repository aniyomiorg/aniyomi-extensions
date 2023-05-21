package eu.kanade.tachiyomi.animeextension.ar.animeblkom

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.animeblkom.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimeBlkom : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "أنمي بالكوم"

    override val baseUrl = "https://animeblkom.net"

    override val lang = "ar"

    override val supportsLatest = false

    override val client = network.cloudflareClient

    override fun headersBuilder() = super.headersBuilder().add("Referer", baseUrl)

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.contents div.poster > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes-list/?sort_by=rate&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            val img = element.selectFirst("img")!!
            thumbnail_url = img.attr("data-original")
            title = img.attr("alt").removeSuffix(" poster")
            setUrlWithoutDomain(element.attr("href"))
        }
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li.page-item a[rel=next]"

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        if (document.selectFirst(episodeListSelector()) == null) {
            return oneEpisodeParse(document)
        }
        return document.select(episodeListSelector()).map(::episodeFromElement).reversed()
    }

    private fun oneEpisodeParse(document: Document): List<SEpisode> {
        return SEpisode.create().apply {
            setUrlWithoutDomain(document.location())
            episode_number = 1F
            name = document.selectFirst("div.name.col-xs-12 span h1")!!.text()
        }.let(::listOf)
    }

    override fun episodeListSelector() = "ul.episodes-links li a"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))

            val eptitle = element.selectFirst("span:nth-child(3)")!!.text()
            val epNum = eptitle.filter { it.isDigit() }
            episode_number = when {
                (epNum.isNotEmpty()) -> epNum.toFloatOrNull() ?: 1F
                else -> 1F
            }
            name = eptitle + " :" + element.selectFirst("span:nth-child(1)")!!.text()
        }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("span.server a").mapNotNull {
            val url = it.attr("data-src").replace("http://", "https://")
            when {
                "new.vid4up" in url -> {
                    val urlResponse = client.newCall(GET(url, headers))
                        .execute().asJsoup()
                    urlResponse.select(videoListSelector()).map(::videoFromElement)
                }
                "ok.ru" in url -> OkruExtractor(client).videosFromUrl(url)
                "mp4upload" in url -> Mp4uploadExtractor(client).videosFromUrl(url, headers)
                else -> null
            }
        }.flatten()
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        val videoUrl = element.attr("src")
        return Video(videoUrl, "Blkbom - " + element.attr("label"), videoUrl, headers = headers)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search?query=$query&page=$page"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is TypeList -> {
                        if (filter.state > 0) {
                            val genreN = getTypeList()[filter.state].query
                            val genreUrl = "$baseUrl/$genreN?page=$page".toHttpUrlOrNull()!!.newBuilder()
                            return GET(genreUrl.toString(), headers)
                        }
                    }
                    else -> {}
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            thumbnail_url = document.selectFirst("div.poster img")!!.attr("data-original")
            title = document.selectFirst("div.name span h1")!!.text()
            genre = document.select("p.genres a").joinToString { it.text() }
            description = document.selectFirst("div.story p, div.story")?.text()
            author = document.selectFirst("div:contains(الاستديو) span > a")?.text()
            status = document.selectFirst("div.info-table div:contains(حالة الأنمي) span.info")?.text()?.let {
                when {
                    it.contains("مستمر") -> SAnime.ONGOING
                    it.contains("مكتمل") -> SAnime.COMPLETED
                    else -> null
                }
            } ?: SAnime.UNKNOWN
            artist = document.selectFirst("div:contains(المخرج) > span.info")?.text()
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // ============================== Filters ===============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeList(typesName),
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("نوع الأنمي", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        Type("قائمة الأنمي", "anime-list"),
        Type(" قائمة المسلسلات ", "series-list"),
        Type(" قائمة الأفلام ", "movie-list"),
        Type(" قائمة الأوفا ", "ova-list"),
        Type(" قائمة الأونا ", "ona-list"),
        Type(" قائمة الحلقات خاصة ", "special-list"),
    )

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
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
        }
        screen.addPreference(videoQualityPref)
    }

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
    }
}
