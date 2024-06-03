package eu.kanade.tachiyomi.animeextension.ar.mycima

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MyCima : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MY Cima"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://wecima.show"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ==============================
    override fun popularAnimeSelector(): String =
        "div.Grid--WecimaPosts div.GridItem div.Thumb--GridItem"

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/seriestv/top/?page_number=$page", headers)

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

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.Episodes--Seasons--Episodes a"

    private fun seasonsNextPageSelector(seasonNumber: Int) =
        "div.List--Seasons--Episodes > a:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        var seasonNumber = 1
        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                if (!document.select("mycima singlerelated.hasdivider ${popularAnimeSelector()}")
                        .isNullOrEmpty()
                ) {
                    document.select("mycima singlerelated.hasdivider ${popularAnimeSelector()}")
                        .map { episodes.add(newEpisodeFromElement(it, "mSeries")) }
                } else {
                    episodes.add(
                        newEpisodeFromElement(
                            document.selectFirst("div.Poster--Single-begin > a")!!,
                            "movie",
                        ),
                    )
                }
            } else {
                document.select(episodeListSelector())
                    .map { episodes.add(newEpisodeFromElement(it)) }
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
        val epNum = element.text().let(::getNumberFromEpsString)
        episode.setUrlWithoutDomain(
            if (type == "mSeries") element.select("a").attr("href") else element.attr("abs:href"),
        )
        if (type == "series") {
            episode.episode_number = when {
                epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
                else -> 1F
            }
        }
        episode.name = when (type) {
            "movie" -> "مشاهدة"
            "mSeries" -> element.select("a").attr("title")
            else -> element.ownerDocument()!!.select("div.List--Seasons--Episodes a.selected")
                .text() + element.text()
        }
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode =
        throw UnsupportedOperationException()

    private fun getNumberFromEpsString(epsStr: String): String = epsStr.filter { it.isDigit() }

    // ============================== Video Links ==============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector())
            .parallelCatchingFlatMapBlocking(::extractVideos)
    }

    private val vidBomExtractor by lazy { VidBomExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }

    private fun extractVideos(element: Element): List<Video> {
        val iframeUrl = element.selectFirst("btn")!!.absUrl("data-url")
        val newHeader = headers.newBuilder().add("referer", "$baseUrl/").build()
        val iframeTxt = element.text().lowercase()
        return when {
            element.hasClass("MyCimaServer") -> {
                videosFromElement(iframeUrl, newHeader)
            }

            "govid" in iframeTxt || "vidbom" in iframeTxt || "vidshare" in iframeTxt -> {
                vidBomExtractor.videosFromUrl(iframeUrl, newHeader)
            }

            "dood" in iframeTxt -> {
                doodExtractor.videosFromUrl(iframeUrl)
            }

            "ok.ru" in iframeTxt -> {
                okruExtractor.videosFromUrl(iframeUrl)
            }

            "uqload" in iframeTxt -> {
                uqloadExtractor.videosFromUrl(iframeUrl)
            }

            else -> null
        } ?: emptyList()
    }

    override fun videoListSelector() = "ul.WatchServersList li"

    private fun videosFromElement(url: String, newHeaders: Headers): List<Video> {
        val document = client.newCall(GET(url, newHeaders)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        val script = document.selectFirst("script:containsData(player.qualityselector({)")
        if (script != null) {
            val data = script.data().substringAfter("sources: [").substringBefore("],")
            val sources = data.split("format: '").drop(1)
            for (source in sources) {
                val src = source.substringAfter("src: \"").substringBefore("\"")
                    .replace("https://localhost", baseUrl)
                val quality = source.substringBefore("'") // .substringAfter("format: '")
                val video = Video(src, quality, src, newHeaders)
                videoList.add(video)
            }
            return videoList
        }
        val sourceTag = document.select("source").firstOrNull()!!
        val trueUrl = sourceTag.absUrl("src").replace("https://localhost", baseUrl)
        return Video(trueUrl, "Default", trueUrl, newHeaders).let(::listOf)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Search ==============================
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val url = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            url.addPathSegment("search/$query")
            url.addPathSegment(categoryFilter.toUriPart() + page)
        } else {
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
                url.addPathSegment("page/$page")
            } else {
                url.addPathSegment("genre/" + genreFilter.toUriPart())
                url.addPathSegment(categoryFilter.toUriPart() + page)
            }
        }
        return GET(url.toString(), headers)
    }

    // ============================== Details ==============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = when {
            document.selectFirst("li:contains(المسلسل) p") != null -> {
                document.select("li:contains(المسلسل) p").text()
            }

            else -> {
                document.select("div.Title--Content--Single-begin > h1").text()
                    .substringBefore(" (")
            }
        }
        anime.genre = document.select("li:contains(التصنيف) > p > a, li:contains(النوع) > p > a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("div.AsideContext > div.StoryMovieContent").text()
        anime.author =
            document.select("li:contains(شركات الإنتاج) > p > a").joinToString(", ") { it.text() }
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

    // ============================== Latest ==============================
    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime =
        popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page", headers)

    // ============================== Filters ==============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("هذا القسم يعمل لو كان البحث فارع"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("النوع يستخدم فى البحث و التصنيف"),
        CategoryFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("التصنيف يعمل لو كان اقسام الموقع على 'اختر' فقط"),
        GenreFilter(),
    )

    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("فيلم", "page/"),
            Pair("مسلسل", "list/series/?page_number="),
            Pair("انمى", "list/anime/?page_number="),
            Pair("برنامج", "list/tv/?page_number="),
        ),
    )

    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", ""),
            Pair("جميع الافلام", "movies"),
            Pair("افلام اجنبى", "category/أفلام/10-movies-english-افلام-اجنبي"),
            Pair("افلام عربى", "category/أفلام/افلام-عربي-arabic-movies"),
            Pair("افلام هندى", "category/أفلام/افلام-هندي-indian-movies"),
            Pair("افلام تركى", "category/أفلام/افلام-تركى-turkish-films"),
            Pair("افلام وثائقية", "category/أفلام/افلام-وثائقية-documentary-films"),
            Pair("افلام انمي", "category/افلام-كرتون"),
            Pair(
                "سلاسل افلام",
                "category/أفلام/10-movies-english-افلام-اجنبي/سلاسل-الافلام-الكاملة-full-pack",
            ),
            Pair("مسلسلات", "seriestv"),
            Pair("مسلسلات اجنبى", "category/مسلسلات/5-series-english-مسلسلات-اجنبي"),
            Pair("مسلسلات عربى", "category/مسلسلات/5-series-english-مسلسلات-اجنبي"),
            Pair("مسلسلات هندى", "category/مسلسلات/9-series-indian-مسلسلات-هندية"),
            Pair("مسلسلات اسيوى", "category/مسلسلات/مسلسلات-اسيوية"),
            Pair("مسلسلات تركى", "category/مسلسلات/8-مسلسلات-تركية-turkish-series"),
            Pair("مسلسلات وثائقية", "category/مسلسلات/مسلسلات-وثائقية-documentary-series"),
            Pair("مسلسلات انمي", "category/مسلسلات-كرتون"),
            Pair("NETFLIX", "production/netflix"),
            Pair("WARNER BROS", "production/warner-bros"),
            Pair("LIONSGATE", "production/lionsgate"),
            Pair("DISNEY", "production/walt-disney-pictures"),
            Pair("COLUMBIA", "production/columbia-pictures"),
        ),
    )

    private class GenreFilter : PairFilter(
        "التصنيف",
        arrayOf(
            Pair("اكشن", "اكشن-action"),
            Pair("مغامرات", "مغامرات-adventure"),
            Pair("خيال علمى", "خيال-علمى-science-fiction"),
            Pair("فانتازيا", "فانتازيا-fantasy"),
            Pair("كوميديا", "كوميديا-comedy"),
            Pair("دراما", "دراما-drama"),
            Pair("جريمة", "جريمة-crime"),
            Pair("اثارة", "اثارة-thriller"),
            Pair("رعب", "رعب-horror"),
            Pair("سيرة ذاتية", "سيرة-ذاتية-biography"),
            Pair("كرتون", "كرتون"),
            Pair("انيميشين", "انيميشين-anime"),
        ),
    )

    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ============================== Settings ==============================
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
