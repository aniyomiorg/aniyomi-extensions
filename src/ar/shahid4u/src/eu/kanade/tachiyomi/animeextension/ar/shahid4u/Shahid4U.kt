package eu.kanade.tachiyomi.animeextension.ar.shahid4u

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors.UQLoadExtractor
import eu.kanade.tachiyomi.animeextension.ar.shahid4u.extractors.VidBomExtractor
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

class Shahid4U : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "شاهد فور يو"

    override val baseUrl = "https://shahed4u.team"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.glide-slides div.media-block"

    override fun popularAnimeRequest(page: Int): Request = GET(if (page == 1)" $baseUrl/home2/" else baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("h3").text()).trim()
        anime.thumbnail_url = element.select("a.image img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a.fullClick").attr("href") + "watch/")
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

    // episodes

    private fun seasonsNextPageSelector() = "div.allseasonstab ul li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodeNew(url: String, type: String, title: String = "") {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(url)
            if (type == "assembly")
                episode.name = title.replace("فيلم", "").trim()
            else if (type == "movie")
                episode.name = "مشاهدة"
            else
                episode.name = title

            episodes.add(episode)
        }
        fun addEpisodes(response: Response) {
            val document = response.asJsoup()
            val url = response.request.url.toString()
            if (url.contains("assemblies")) {
                for (movie in document.select(searchAnimeSelector())) {
                    addEpisodeNew(
                        movie.select("a.fullClick").attr("href") + "watch/",
                        "assembly",
                        movie.select("h3").text()
                    )
                }
                return
            }
            if (document.select("div.seasons--episodes").isNullOrEmpty()) {
                // Movies
                addEpisodeNew(url, "movie")
            } else {
                // Series
                // look for what is wrong
                for (season in document.select(seasonsNextPageSelector())) {
                    val seasonNum = season.text()
                    if (season.hasClass("active")) {
                        // get episodes from page
                        for (episode in document.select("ul.episodes-list li a")) {
                            addEpisodeNew(
                                episode.attr("href"),
                                "series",
                                seasonNum + " " + episode.text()
                            )
                        }
                    } else {
                        // send request to get episodes
                        val seasonData = season.attr("data-id")
                        val refererHeaders = Headers.headersOf("referer", response.request.url.toString(), "x-requested-with", "XMLHttpRequest")
                        val requestBody = FormBody.Builder().add("season", seasonData).build()
                        val getEpisodes = client.newCall(POST("$baseUrl/wp-content/themes/Shahid4u-WP_HOME/Ajaxat/Single/Episodes.php", refererHeaders, requestBody)).execute().asJsoup()
                        for (episode in getEpisodes.select("li a")) {
                            addEpisodeNew(
                                episode.attr("href"),
                                "series",
                                seasonNum + " " + episode.text()
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

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        val servers = document.select(videoListSelector())
        fun getUrl(v_id: String, i: String): String {
            val refererHeaders = Headers.headersOf(
                "referer", response.request.url.toString(),
                "x-requested-with", "XMLHttpRequest",
                "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
                "user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36 Edg/105.0.1343.42"
            )
            val requestBody = FormBody.Builder().add("id", v_id).add("i", i).build()
            val iframe = client.newCall(
                POST(
                    "$baseUrl/wp-content/themes/Shahid4u-WP_HOME/Ajaxat/Single/Server.php",
                    refererHeaders,
                    requestBody
                )
            ).execute().asJsoup()
            return iframe.select("iframe").attr("src")
        }
        for (server in servers) {
            if (server.hasClass("active")) {
                // special server
                val videosFromURL = videosFromElement(client.newCall(GET(document.select("input[name=fserver]").`val`(), headers)).execute().asJsoup())
                videos.addAll(videosFromURL)
            } else if (server.text().contains("ok")) {
                val videosFromURL = OkruExtractor(client).videosFromUrl(getUrl(server.attr("data-id"), server.attr("data-i")))
                videos.addAll(videosFromURL)
            } else if (server.text().contains("vidbom", ignoreCase = true) or server.text().contains("Vidshare", ignoreCase = true)) {
                val videosFromURL = VidBomExtractor(client).videosFromUrl(getUrl(server.attr("data-id"), server.attr("data-i")))
                videos.addAll(videosFromURL)
            } else if (server.text().contains("dood", ignoreCase = true)) {
                val videosFromURL = DoodExtractor(client).videoFromUrl(getUrl(server.attr("data-id"), server.attr("data-i")))
                if (videosFromURL != null) videos.add(videosFromURL)
            } else if (server.text().contains("uqload", ignoreCase = true)) {
                val videosFromURL = UQLoadExtractor(client).videoFromUrl(getUrl(server.attr("data-id"), server.attr("data-i")), "Uqload: 720p")
                if (videosFromURL != null) videos.add(videosFromURL)
            }
        }
        return videos
    }

    override fun videoListSelector() = "ul.servers-list li.server--item"

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        return try {
            val scriptSelect = document.select("script:containsData(eval)").first().data()
            val serverPrefix =
                scriptSelect.substringAfter("|net|cdn|amzn|").substringBefore("|rewind|icon|")
            val sourceServer = "https://$serverPrefix.e-amzn-cdn.net"
            val qualities = scriptSelect.substringAfter("|image|").substringBefore("|sources|")
                .replace("||", "|").split("|")
            qualities.forEachIndexed { i, q ->
                if (i % 2 == 0) {
                    val id = qualities[i + 1]
                    val src = "$sourceServer/$id/v.mp4"
                    val video = Video(src, "Main: $q", src)
                    videoList.add(video)
                }
            }
            videoList
        } catch (e: Exception) {
            videoList
        }
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

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        var link = element.select("a.fullClick").attr("href")
        anime.title = titleEdit(element.select("h3").text(), details = true).trim()
        if (link.contains("assemblies"))
            anime.thumbnail_url = element.select("a.image img").attr("data-src")
        else
            anime.thumbnail_url = element.select("a.image img.imgInit").attr("data-image")
        if (!link.contains("assemblies")) link += "watch/"
        anime.setUrlWithoutDomain(link)
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.paginate ul.page-numbers li.active + li a"

    override fun searchAnimeSelector(): String = "div.MediaGrid div.media-block"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            val url = "$baseUrl/home2/page/$page"
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/$catQ/page/$page/"
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

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // div.CoverSingle div.CoverSingleContent
        anime.genre = document.select("div.SingleDetails li:contains(النوع) a").joinToString(", ") { it.text() }
        anime.title = titleEdit(document.select("meta[property=og:title]").attr("content")).trim()
        anime.author = document.select("div.SingleDetails li:contains(دولة) a").text()
        anime.description = document.select("div.ServersEmbeds section.story").text().replace(document.select("meta[property=og:title]").attr("content"), "").replace(":", "").trim()
        anime.status = SAnime.COMPLETED
        if (anime.title.contains("سلسلة")) anime.thumbnail_url = document.select("img.imgInit").first().attr("data-image")
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "div.paginate ul.page-numbers li.active + li a"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = titleEdit(element.select("h3").text()).trim()
        anime.thumbnail_url = element.select("a.image img").attr("data-image")
        anime.setUrlWithoutDomain(element.select("a.fullClick").attr("href") + "watch/")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/home2/page/$page/")

    override fun latestUpdatesSelector(): String = "div.MediaGrid div.media-block"

    // Filters

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
        CatUnit("افلام اجنبى", "category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a-3/"),
        CatUnit("افلام انمى", "category/افلام-انمي/"),
        CatUnit("افلام تركيه", "category/افلام-تركية/"),
        CatUnit("افلام اسيويه", "category/افلام-اسيوية/"),
        CatUnit("افلام هنديه", "category/افلام-هندي-1/"),
        CatUnit("سلاسل افلام", "assemblies/"),
        CatUnit("مسلسلات اجنبى", "category/مسلسلات-اجنبي-1/"),
        CatUnit("مسلسلات انمى", "category/مسلسلات-انمي-4/"),
        CatUnit("مسلسلات تركى", "category/مسلسلات-تركي-3/"),
        CatUnit("مسلسلات اسيوى", "category/مسلسلات-اسيوي/"),
        CatUnit("مسلسلات هندى", "category/مسلسلات-هندية/")
    )

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
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
