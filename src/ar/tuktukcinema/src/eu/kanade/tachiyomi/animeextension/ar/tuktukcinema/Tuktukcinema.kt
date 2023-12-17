package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.BuildConfig
import eu.kanade.tachiyomi.animeextension.ar.tuktukcinema.extractors.UpStreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Tuktukcinema : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "توك توك سينما"

    override val baseUrl by lazy {
        getPrefHostUrl(preferences)
    }

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================ popular ===============================

    override fun popularAnimeSelector(): String = "div.Block--Item, div.Small--Box"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("a").attr("title"), true).trim()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    private fun titleEdit(title: String, details: Boolean = false): String {
        return if (Regex("(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s").containsMatchIn(title)) {
            val titleGroup = Regex("(?:فيلم|عرض)\\s(.*\\s[0-9]+)\\s(.+?)\\s").find(title)
            val movieName = titleGroup!!.groupValues[1]
            val type = titleGroup.groupValues[2]
            movieName + if (details) " ($type)" else ""
        } else if (Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)").containsMatchIn(title)) {
            val titleGroup = Regex("(?:مسلسل|برنامج|انمي)\\s(.+)\\sالحلقة\\s(\\d+)").find(title)
            val seriesName = titleGroup!!.groupValues[1]
            val epNum = titleGroup.groupValues[2]
            if (details) {
                "$seriesName (ep:$epNum)"
            } else if (seriesName.contains("الموسم")) {
                seriesName.split("الموسم")[0].trim()
            } else {
                seriesName
            }
        } else {
            title
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    // ============================ episodes ===============================

    private fun seasonsNextPageSelector() = "section.allseasonss div.Block--Item"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodeNew(url: String, title: String) {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(url)
            episode.name = title
            episodes.add(episode)
        }
        fun addEpisodes(response: Response) {
            val document = response.asJsoup()
            val url = response.request.url.toString()
            if (document.select(seasonsNextPageSelector()).isNullOrEmpty()) {
                addEpisodeNew(url, "مشاهدة")
            } else {
                document.select(seasonsNextPageSelector()).reversed().forEach { season ->
                    val seasonNum = season.select("h3").text()
                    (
                        if (seasonNum == document.selectFirst("div#mpbreadcrumbs a span:contains(الموسم)")!!.text()) {
                            document
                        } else {
                            client.newCall(GET(season.selectFirst("a")!!.attr("href"))).execute().asJsoup()
                        }
                        )
                        .select("section.allepcont a").forEach { episode ->
                            addEpisodeNew(
                                episode.attr("href") + "watch/",
                                seasonNum + " : الحلقة " + episode.select("div.epnum").text().filter { it.isDigit() },
                            )
                        }
                }
            }
        }
        addEpisodes(response)
        return episodes
    }

    override fun episodeListSelector() = "link[rel=canonical]"

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    // ============================ video links ============================
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListRequest(episode: SEpisode): Request {
        val refererHeaders = headers.newBuilder().apply {
            add("Referer", "$baseUrl/")
        }.build()

        return GET("$baseUrl/${episode.url}", headers = refererHeaders)
    }

    override fun videoListSelector() = "div.watch--servers--list ul li.server--item"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).parallelMap {
            runCatching { extractVideos(it.attr("data-link")) }.getOrElse { emptyList() }
        }.flatten()
    }
    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun extractVideos(url: String): List<Video> {
        return when {
            url.contains("egtpgrvh") -> {
                val videoList = mutableListOf<Video>()
                val request = client.newCall(GET(url, headers)).execute().asJsoup()
                val data = request.selectFirst("script:containsData(m3u8)")!!.data()
                val masterUrl = data.substringAfter("sources: [{").substringAfter("file:\"").substringBefore("\"}")
                playlistUtils.extractFromHls(masterUrl)
            }
            url.contains("ok") -> {
                OkruExtractor(client).videosFromUrl(url)
            }
            VIDBOM_REGEX.containsMatchIn(url) -> {
                val finalUrl = VIDBOM_REGEX.find(url)!!.groupValues[0]
                VidBomExtractor(client).videosFromUrl("https://www.$finalUrl")
            }
            DOOD_REGEX.containsMatchIn(url) -> {
                val finalUrl = DOOD_REGEX.find(url)!!.groupValues[0]
                DoodExtractor(client).videoFromUrl("https://www.$finalUrl", "Dood mirror", false)?.let(::listOf)
            }
            url.contains("uqload") -> {
                UqloadExtractor(client).videosFromUrl(url, "mirror")
            }
            url.contains("tape") -> {
                StreamTapeExtractor(client).videoFromUrl(url)?.let(::listOf)
            }
            url.contains("upstream", ignoreCase = true) -> {
                UpStreamExtractor(client).videoFromUrl(url.replace("//", "//www."))
            }
            else -> null
        } ?: emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    // ============================ search ============================

    override fun searchAnimeSelector(): String = "div.Block--Item"

    override fun searchAnimeNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/?s=$query&page=$page"
        } else {
            val url = baseUrl
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/$catQ/?page=$page/"
                            return GET(catUrl, headers)
                        }
                    }
                    else -> {}
                }
            }
            return GET(url, headers)
        }
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("h3").text(), true).trim()
        anime.thumbnail_url = element.select("img").attr(if (element.ownerDocument()!!.location().contains("?s="))"src" else "data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    // ============================ details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.genre = document.select("div.catssection li a").joinToString(", ") { it.text() }
        anime.title = titleEdit(document.select("h1.post-title").text()).trim()
        anime.author = document.select("ul.RightTaxContent li:contains(دولة) a").text()
        anime.description = document.select("div.story").text().trim()
        anime.status = SAnime.COMPLETED
        anime.thumbnail_url = document.select("div.left div.image img").attr("src")
        return anime
    }

    // ============================ latest ============================

    override fun latestUpdatesSelector(): String = "div.Block--Item"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("a").attr("title"), true).trim()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    // ============================ filters ============================

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)
    private data class CatUnit(val name: String, val query: String)
    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("كل الافلام", "category/movies-33/"),
        CatUnit("افلام اجنبى", "category/movies-33/افلام-اجنبي/"),
        CatUnit("افلام انمى", "category/anime-6/افلام-انمي/"),
        CatUnit("افلام تركيه", "category/movies-33/افلام-تركي/"),
        CatUnit("افلام اسيويه", "category/movies-33/افلام-اسيوي/"),
        CatUnit("افلام هنديه", "category/movies-33/افلام-هندى/"),
        CatUnit("كل المسسلسلات", "category/series-9/"),
        CatUnit("مسلسلات اجنبى", "category/series-9/مسلسلات-اجنبي/"),
        CatUnit("مسلسلات انمى", "category/anime-6/انمي-مترجم/"),
        CatUnit("مسلسلات تركى", "category/series-9/مسلسلات-تركي/"),
        CatUnit("مسلسلات اسيوى", "category/series-9/مسلسلات-أسيوي/"),
        CatUnit("مسلسلات هندى", "category/series-9/مسلسلات-هندي/"),
    )

    // preferred quality settings
    private fun getPrefHostUrl(preferences: SharedPreferences): String = preferences.getString(
        "default_domain_v${BuildConfig.VERSION_CODE}",
        "https://w38.tuktukcinema1.buzz/",
    )!!.trim()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val defaultDomain = EditTextPreference(screen.context).apply {
            key = "default_domain"
            title = "Enter default domain"
            summary = getPrefHostUrl(preferences)
            this.setDefaultValue(getPrefHostUrl(preferences))
            dialogTitle = "Default domain"
            dialogMessage = "You can change the site domain from here"

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString("default_domain", newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "DoodStream", "Uqload")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "Dood", "Uqload")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(defaultDomain)
        screen.addPreference(videoQualityPref)
    }
    companion object {
        private val VIDBOM_REGEX = Regex("(?:v[aie]d[bp][aoe]?m|myvii?d|govad|segavid|v[aei]{1,2}dshar[er]?)\\.(?:com|net|org|xyz)(?::\\d+)?/(?:embed[/-])?([A-Za-z0-9]+).html")
        private val DOOD_REGEX = Regex("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|ds|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)")
    }
}
