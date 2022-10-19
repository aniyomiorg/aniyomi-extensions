package eu.kanade.tachiyomi.animeextension.ar.tuktukcinema

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.tuktukcinema.extractors.MoshahdaExtractor
import eu.kanade.tachiyomi.animeextension.ar.tuktukcinema.extractors.UQLoadExtractor
import eu.kanade.tachiyomi.animeextension.ar.tuktukcinema.extractors.VidBomExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Tuktukcinema : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "تك تك سينما"

    override val baseUrl = "https://w.tuktukcinema.net"

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
        return if (Regex("فيلم (.*?) مترجم").containsMatchIn(title))
            Regex("فيلم (.*?) مترجم").find(title)!!.groupValues[1] + " (فيلم)" // افلام اجنبيه مترجمه
        else if (Regex("فيلم (.*?) مدبلج").containsMatchIn(title))
            Regex("فيلم (.*?) مدبلج").find(title)!!.groupValues[1] + " (مدبلج)(فيلم)" // افلام اجنبيه مدبلجه
        else if (Regex("فيلم ([^a-zA-Z]+) ([0-9]+)").containsMatchIn(title)) // افلام عربى
            Regex("فيلم ([^a-zA-Z]+) ([0-9]+)").find(title)!!.groupValues[1] + " (فيلم)"
        else if (title.contains("مسلسل")) {
            if (title.contains("الموسم") and details) {
                val newTitle = Regex("مسلسل (.*?) الموسم (.*?) الحلقة ([0-9]+)").find(title)
                return "${newTitle!!.groupValues[1]} (م.${newTitle.groupValues[2]})(${newTitle.groupValues[3]}ح)"
            } else if (title.contains("الحلقة") and details) {
                val newTitle = Regex("مسلسل (.*?) الحلقة ([0-9]+)").find(title)
                return "${newTitle!!.groupValues[1]} (${newTitle.groupValues[2]}ح)"
            } else Regex(if (title.contains("الموسم")) "مسلسل (.*?) الموسم" else "مسلسل (.*?) الحلقة").find(title)!!.groupValues[1] + " (مسلسل)"
        } else if (title.contains("انمي"))
            return Regex(if (title.contains("الموسم"))"انمي (.*?) الموسم" else "انمي (.*?) الحلقة").find(title)!!.groupValues[1] + " (انمى)"
        else if (title.contains("برنامج"))
            Regex(if (title.contains("الموسم"))"برنامج (.*?) الموسم" else "برنامج (.*?) الحلقة").find(title)!!.groupValues[1] + " (برنامج)"
        else
            title
    }

    override fun popularAnimeNextPageSelector(): String = "div.paginate ul.page-numbers li.active + li a"

    // ============================ episodes ===============================

    private fun seasonsNextPageSelector() = "div.seasons--toggler ul li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodeNew(url: String, type: String, title: String = "") {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(url)
            if (type == "movie")
                episode.name = "مشاهدة"
            else
                episode.name = title

            episodes.add(episode)
        }
        fun addEpisodes(response: Response) {
            val document = response.asJsoup()
            val url = response.request.url.toString()
            if (document.select("div.episodes--side--list").isNullOrEmpty()) {
                // Movies
                addEpisodeNew(url, "movie")
            } else {
                // Series
                // look for what is wrong
                document.select(seasonsNextPageSelector()).forEach { season ->
                    val seasonNum = season.text()
                    if (season.hasClass("active")) {
                        // get episodes from page
                        document.select("div.episodes--list--side a").forEach { ep ->
                            addEpisodeNew(
                                ep.attr("href"),
                                "series",
                                seasonNum + " " + ep.text()
                            )
                        }
                    } else {
                        // send request to get episodes
                        val seasonData = season.attr("data-season")
                        val postId = season.attr("data-id")
                        val refererHeaders = Headers.headersOf("referer", response.request.url.toString(), "x-requested-with", "XMLHttpRequest")
                        val requestBody = FormBody.Builder().add("season", seasonData).add("post_id", postId).build()
                        val getEpisodes = client.newCall(POST("$baseUrl/wp-content/themes/Elshaikh/Inc/Ajax/Single/Episodes.php", refererHeaders, requestBody)).execute().asJsoup()
                        getEpisodes.select("li a").forEach { ep ->
                            addEpisodeNew(
                                ep.attr("href"),
                                "series",
                                seasonNum + " " + ep.text()
                            )
                        }
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

    override fun videoListSelector() = "div.watch--servers--list ul li.server--item"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        document.select(videoListSelector()).forEach { server ->
            val link = server.attr("data-link")
            if (link.contains("moshahda")) {
                val refererHeaders = Headers.headersOf("referer", response.request.url.toString())
                val videosFromURL = MoshahdaExtractor(client).videosFromUrl(link, refererHeaders)
                videos.addAll(videosFromURL)
            } else if (link.contains("ok")) {
                val videosFromURL = OkruExtractor(client).videosFromUrl(link)
                videos.addAll(videosFromURL)
            } else if (server.text().contains("vidbom", ignoreCase = true) or server.text().contains("vidshare", ignoreCase = true)) {
                val videosFromURL = VidBomExtractor(client).videosFromUrl(link)
                videos.addAll(videosFromURL)
            } else if (server.text().contains("dood", ignoreCase = true)) {
                val videosFromURL = DoodExtractor(client).videoFromUrl(link)
                if (videosFromURL != null) videos.add(videosFromURL)
            } else if (server.text().contains("uqload", ignoreCase = true)) {
                val videosFromURL = UQLoadExtractor(client).videoFromUrl(link, "Uqload mirror")
                if (videosFromURL != null) videos.add(videosFromURL)
            }
        }
        return videos
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

    override fun searchAnimeNextPageSelector(): String = "div.paginate ul.page-numbers li.next a"

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
        anime.title = titleEdit(element.select("a").attr("title"), true).trim()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href") + "watch/")
        return anime
    }

    // ============================ details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val info = client.newCall(GET(document.select(episodeListSelector()).attr("href").replace("watch/", ""))).execute().asJsoup()
        anime.genre = info.select("div.catssection li a").joinToString(", ") { it.text() }
        anime.title = titleEdit(info.select("h1.post-title").text()).trim()
        anime.author = info.select("ul.RightTaxContent li:contains(دولة) a").text()
        anime.description = info.select("div.story").text().trim()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // ============================ latest ============================

    override fun latestUpdatesSelector(): String = "div.Block--Item"

    override fun latestUpdatesNextPageSelector(): String = "div.paginate ul.page-numbers li.next a"

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
        CatUnit("مسلسلات اجنبى", "sercat/مسلسلات-اجنبي/"),
        CatUnit("جديد مسلسلات اجنبى", "category/series-9/مسلسلات-اجنبي/"),
        CatUnit("مسلسلات انمى", "sercat/قائمة-الانمي/"),
        CatUnit("جديد مسلسلات انمى", "category/anime-6/انمي-مترجم/"),
        CatUnit("مسلسلات تركى", "sercat/مسلسلات-تركي/"),
        CatUnit("جديد مسلسلات تركى", "category/series-9/مسلسلات-تركي/"),
        CatUnit("مسلسلات اسيوى", "sercat/مسلسلات-أسيوي/"),
        CatUnit("جديد مسلسلات اسيوى", "category/series-9/مسلسلات-أسيوي/"),
        CatUnit("مسلسلات هندى", "sercat/مسلسلات-هندي/"),
        CatUnit("جديد مسلسلات هندى", "category/series-9/مسلسلات-هندي/")
    )

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
        screen.addPreference(videoQualityPref)
    }
}
