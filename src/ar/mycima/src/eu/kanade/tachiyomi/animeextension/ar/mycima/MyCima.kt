package eu.kanade.tachiyomi.animeextension.ar.mycima

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.mycima.extractors.GoVadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class MyCima : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MY Cima"

    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== popular ==============================

    override fun popularAnimeSelector(): String = "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/seriestv/top/?page_number=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a").attr("title")
        anime.thumbnail_url =
            element.select("a > span.BG--GridItem")
                .attr("data-lazy-style")
                .substringAfter("-image:url(")
                .substringBefore(");")
        return anime
    }

    // ============================== episodes ==============================

    override fun episodeListSelector() = "div.Episodes--Seasons--Episodes a"

    private fun seasonsNextPageSelector(seasonNumber: Int) = "div.List--Seasons--Episodes > a:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        var seasonNumber = 1
        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                if (!document.select("mycima singlerelated.hasdivider ${popularAnimeSelector()}").isNullOrEmpty()) {
                    document.select("mycima singlerelated.hasdivider ${popularAnimeSelector()}").map { episodes.add(newEpisodeFromElement(it, "mSeries")) }
                } else {
                    episodes.add(newEpisodeFromElement(document.selectFirst("div.Poster--Single-begin > a")!!, "movie"))
                }
            } else {
                document.select(episodeListSelector()).map { episodes.add(newEpisodeFromElement(it)) }
                document.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                    seasonNumber++
                    addEpisodes(
                        client.newCall(GET(it.attr("abs:href"), headers)).execute().asJsoup(),
                    )
                }
            }
        }
        addEpisodes(response.asJsoup())
        return episodes
    }

    private fun newEpisodeFromElement(element: Element, type: String = "series"): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.text())
        episode.setUrlWithoutDomain(if (type == "mSeries") element.select("a").attr("href") else element.attr("abs:href"))
        if (type == "series") {
            episode.episode_number = when {
                epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
                else -> 1F
            }
        }
        episode.name = when (type) {
            "movie" -> "مشاهدة"
            "mSeries" -> element.select("a").attr("title")
            else -> element.ownerDocument()!!.select("div.List--Seasons--Episodes a.selected").text() + element.text()
        }
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not used")

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // ============================== video urls ==============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("ul.WatchServersList li btn").parallelCatchingFlatMapBlocking {
            val frameURL = it.attr("data-url")
            if (it.parent()?.hasClass("MyCimaServer") == true) {
                val referer = response.request.url.encodedPath
                val newHeader = headers.newBuilder().add("referer", baseUrl + referer).build()
                val iframeResponse = client.newCall(GET(frameURL, newHeader)).execute().asJsoup()
                videosFromElement(iframeResponse.selectFirst(videoListSelector())!!)
            } else {
                extractVideos(frameURL)
            }
        }
    }

    private fun extractVideos(url: String): List<Video> {
        return when {
            GOVAD_REGEX.containsMatchIn(url) -> {
                val finalUrl = GOVAD_REGEX.find(url)!!.groupValues[0]
                val urlHost = GOVAD_REGEX.find(url)!!.groupValues[1]
                GoVadExtractor(client).videosFromUrl("https://www.$finalUrl.html", urlHost)
            }
            UQLOAD_REGEX.containsMatchIn(url) -> {
                val finalUrl = UQLOAD_REGEX.find(url)!!.groupValues[0]
                UqloadExtractor(client).videosFromUrl("https://www.$finalUrl.html")
            }
            else -> null
        } ?: emptyList()
    }

    override fun videoListSelector() = "body"

    private fun videosFromElement(element: Element): List<Video> {
        val videoList = mutableListOf<Video>()
        val script = element.select("script")
            .firstOrNull { it.data().contains("player.qualityselector({") }
        if (script != null) {
            val data = element.data().substringAfter("sources: [").substringBefore("],")
            val sources = data.split("format: '").drop(1)
            for (source in sources) {
                val src = source.substringAfter("src: \"").substringBefore("\"")
                val quality = source.substringBefore("'") // .substringAfter("format: '")
                val video = Video(src, quality, src)
                videoList.add(video)
            }
            return videoList
        }
        val sourceTag = element.ownerDocument()!!.select("source").firstOrNull()!!
        return listOf(Video(sourceTag.attr("src"), "Default", sourceTag.attr("src")))
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // ============================== search ==============================

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong").text()
        anime.thumbnail_url = element.select("a > span.BG--GridItem").attr("data-lazy-style").substringAfter("-image:url(").substringBefore(");")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is SearchCategoryList -> {
                        val catQ = getSearchCategoryList()[filter.state].query
                        val catUrl = "$baseUrl/search/$query/" + if (catQ == "page/" && page == 1) "" else "$catQ$page"
                        return GET(catUrl, headers)
                    }
                    else -> {}
                }
            }
        } else {
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
            throw Exception("Choose a Filters")
        }
        return GET(baseUrl, headers)
    }

    // ============================== details ==============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = when {
            document.selectFirst("li:contains(المسلسل) p") != null -> {
                document.select("li:contains(المسلسل) p").text()
            }
            else -> {
                document.select("div.Title--Content--Single-begin > h1").text().substringBefore(" (")
            }
        }
        anime.genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.AsideContext > div.StoryMovieContent").text()
        anime.author = document.select("li:contains(شركات الإنتاج) > p > a").joinToString(", ") { it.text() }
        // add alternative name to anime description
        document.select("li:contains( بالعربي) > p, li:contains(معروف) > p").text().let {
            if (it.isEmpty().not()) {
                anime.description += when {
                    anime.description!!.isEmpty() -> "Alternative Name: $it"
                    else -> "\n\nAlternativ Name: $it"
                }
            }
        }
        return anime
    }

    // ============================== latest ==============================

    override fun latestUpdatesSelector(): String = "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun latestUpdatesNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("a > strong").text()
        anime.thumbnail_url = element.select("a > span").attr("data-lazy-style").substringAfter("-image:url(").substringBefore(");")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page")

    // ============================== filters ==============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("فلترات البحث"),
        SearchCategoryList(searchCategoryNames),
        AnimeFilter.Separator(),
        AnimeFilter.Header("اقسام الموقع (تعمل فقط اذا كان البحث فارغ)"),
        CategoryList(categoryNames),
    )

    private class SearchCategoryList(categories: Array<String>) : AnimeFilter.Select<String>("بحث عن", categories)
    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("اختر قسم", categories)
    private data class CatUnit(val name: String, val query: String)
    private val searchCategoryNames = getSearchCategoryList().map {
        it.name
    }.toTypedArray()
    private val categoryNames = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getSearchCategoryList() = listOf(
        CatUnit("فيلم", "page/"),
        CatUnit("مسلسل", "list/series/?page_number="),
        CatUnit("انمى", "list/anime/?page_number="),
        CatUnit("برنامج", "list/tv/?page_number="),
    )
    private fun getCategoryList() = listOf(
        CatUnit("اختر", ""),
        CatUnit("جميع الافلام", "category/أفلام/"),
        CatUnit("افلام اجنبى", "category/أفلام/10-movies-english-افلام-اجنبي"),
        CatUnit("افلام عربى", "category/أفلام/افلام-عربي-arabic-movies"),
        CatUnit("افلام هندى", "category/أفلام/افلام-هندي-indian-movies"),
        CatUnit("افلام تركى", "category/أفلام/افلام-تركى-turkish-films"),
        CatUnit("افلام وثائقية", "category/أفلام/افلام-وثائقية-documentary-films"),
        CatUnit("افلام انمي", "category/افلام-كرتون"),
        CatUnit("سلاسل افلام", "category/أفلام/10-movies-english-افلام-اجنبي/سلاسل-الافلام-الكاملة-full-pack"),
        CatUnit("مسلسلات", "category/مسلسلات"),
        CatUnit("مسلسلات اجنبى", "category/مسلسلات/5-series-english-مسلسلات-اجنبي"),
        CatUnit("مسلسلات عربى", "category/مسلسلات/5-series-english-مسلسلات-اجنبي"),
        CatUnit("مسلسلات هندى", "category/مسلسلات/9-series-indian-مسلسلات-هندية"),
        CatUnit("مسلسلات اسيوى", "category/مسلسلات/مسلسلات-اسيوية"),
        CatUnit("مسلسلات تركى", "category/مسلسلات/8-مسلسلات-تركية-turkish-series"),
        CatUnit("مسلسلات وثائقية", "category/مسلسلات/مسلسلات-وثائقية-documentary-series"),
        CatUnit("مسلسلات انمي", "category/مسلسلات-كرتون"),
        CatUnit("NETFLIX", "production/netflix"),
        CatUnit("WARNER BROS", "production/warner-bros"),
        CatUnit("LIONSGATE", "production/lionsgate"),
        CatUnit("DISNEY", "production/walt-disney-pictures"),
        CatUnit("COLUMBIA", "production/columbia-pictures"),
    )

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = androidx.preference.EditTextPreference(screen.context).apply {
            key = PREF_BASE_URL_KEY
            title = PREF_BASE_URL_TITLE
            summary = getPrefBaseUrl()
            this.setDefaultValue(PREF_BASE_URL_DEFAULT)
            dialogTitle = PREF_BASE_URL_DIALOG_TITLE
            dialogMessage = PREF_BASE_URL_DIALOG_MESSAGE

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(PREF_BASE_URL_KEY, newValue as String).commit()
                    Toast.makeText(screen.context, "Restart Aniyomi to apply changes", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES.map { it.replace("p", "") }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(baseUrlPref)
        screen.addPreference(videoQualityPref)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(PREF_BASE_URL_KEY, PREF_BASE_URL_DEFAULT)!!

    // ============================= Utilities ===================================
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")

        private const val PREF_BASE_URL_DEFAULT = "https://cdn3.wecima.watch"
        private const val PREF_BASE_URL_KEY = "default_domain"
        private const val PREF_BASE_URL_TITLE = "Enter default domain"
        private const val PREF_BASE_URL_DIALOG_TITLE = "Default domain"
        private const val PREF_BASE_URL_DIALOG_MESSAGE = "You can change the site domain from here"

        private val GOVAD_REGEX = Regex("(v[aie]d[bp][aoe]?m|myvii?d|govad|segavid|v[aei]{1,2}dshar[er]?)\\.(?:com|net|org|xyz)(?::\\d+)?/(?:embed[/-])?([A-Za-z0-9]+)")
        private val UQLOAD_REGEX = Regex("(uqload\\.[ic]om?)/(?:embed-)?([0-9a-zA-Z]+)")
    }
}
