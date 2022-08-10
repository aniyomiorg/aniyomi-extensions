package eu.kanade.tachiyomi.animeextension.ar.arabseed

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class ArabSeed : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "عرب سيد"

    override val baseUrl = "https://m.arabseed.sbs"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime
    override fun popularAnimeSelector(): String = "ul.Blocks-UL div.MovieBlock a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/?offset=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.BlockName > h4").text()
        anime.thumbnail_url = element.select("div.Poster img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    // Episodes
    override fun episodeListSelector() = "div.WatchButtons > a[href~=watch]"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.ownerDocument().select("div.InfoPartOne a h1.Title").text().replace(" مترجم", "").replace("فيلم ", "")
        return episode
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "div.containerServers ul li" // ul#playeroptionsul

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val location = element.ownerDocument().location()
            val videoHeaders = headers.newBuilder()
                .set("Referer", "https://m.arabseed.sbs/")
                .set("X-Requested-With", "XMLHttpRequest")
                .build()
            // Headers.headersOf("Referer", location)
            val dataPost = element.attr("data-post")
            val dataServer = element.attr("data-server")
            val dataQu = element.attr("data-qu")
            val pageData = FormBody.Builder()
                .add("post_id", dataPost)
                .add("server", dataServer)
                .add("qu", dataQu)
                .build()
            val ajax1 = "https://m.arabseed.sbs/wp-content/themes/Elshaikh2021/Ajaxat/Single/Server.php"
            val ajax = client.newCall(POST(ajax1, videoHeaders, pageData)).execute().asJsoup()
            val embedUrl = ajax.select("iframe").attr("src")
            when {
                embedUrl.contains("seeeed") -> {
                    val iframeResponse = client.newCall(GET(embedUrl)).execute().asJsoup()
                    val videoUrl = iframeResponse.select("source").attr("src")
                    val video = Video(embedUrl, dataQu + "p", videoUrl.replace("https", "http"))
                    videoList.add(video)
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.BlockName h4").text()
        anime.thumbnail_url = element.select("img").first().attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "ul.Blocks-UL div.MovieBlock a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/find/?find=$query&offset=$page"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is TypeList -> {
                        if (filter.state > 0) {
                            val TypeN = getTypeList()[filter.state].query
                            val typeUrl = "$baseUrl/category/$TypeN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(typeUrl.toString(), headers)
                        }
                    }
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.Poster img").first().attr("data-src")
        anime.title = document.select("div.InfoPartOne a h1.Title").text().replace(" مترجم", "").replace("فيلم ", "")
        anime.genre = document.select("div.MetaTermsInfo  > li:contains(النوع) > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.StoryLine p").text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Filter

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        TypeList(typesName),
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("نوع الفلم", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        Type("أختر", ""),
        Type("افلام عربي", "arabic-movies-5/"),
        Type("افلام اجنبى", "foreign-movies3/"),
        Type("افلام اسيوية", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%b3%d9%8a%d9%88%d9%8a%d8%a9/"),
        Type("افلام هندى", "indian-movies/"),
        Type("افلام تركية", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%aa%d8%b1%d9%83%d9%8a%d8%a9/"),
        Type("افلام انيميشن", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%8a%d9%85%d9%8a%d8%b4%d9%86/"),
        Type("افلام كلاسيكيه", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%83%d9%84%d8%a7%d8%b3%d9%8a%d9%83%d9%8a%d9%87/"),
        Type("افلام مدبلجة", "%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/"),
        Type("افلام Netfilx", "netfilx/افلام-netfilx/")

    )

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("سيرفر عرب سيد - 720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(qualityPref)
    }
}
