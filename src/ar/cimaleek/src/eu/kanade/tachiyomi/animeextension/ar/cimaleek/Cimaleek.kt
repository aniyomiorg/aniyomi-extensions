package eu.kanade.tachiyomi.animeextension.ar.cimaleek

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.cimaleek.interceptor.WebViewResolver
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Cimaleek : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "سيما ليك"

    override val baseUrl = "https://m.cimaleek.to"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val webViewResolver by lazy { WebViewResolver(headers) }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    // ============================== Popular ===============================
    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("div.data .title").text()
        anime.thumbnail_url = element.select("img").attr("data-src")
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination div.pagination-num i#nextpagination"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending/page/$page/", headers)

    override fun popularAnimeSelector(): String = "div.film_list-wrap div.item"

    // ============================== Episodes ==============================
    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val url = response.request.url.toString()
        if (url.contains("movies")) {
            val episode = SEpisode.create().apply {
                name = "مشاهدة"
                setUrlWithoutDomain("$url/watch/")
            }
            episodes.add(episode)
        } else {
            document.select(seasonListSelector()).parallelCatchingFlatMapBlocking { sElement ->
                val seasonNum = sElement.select("span.se-a").text()
                val seasonUrl = sElement.attr("href")
                val seasonPage = client.newCall(GET(seasonUrl, headers)).execute().asJsoup()
                seasonPage.select(episodeListSelector()).map { eElement ->
                    val episodeNum = eElement.select("span.serie").text().substringAfter("(").substringBefore(")")
                    val episodeUrl = eElement.attr("href")
                    val finalNum = ("$seasonNum.$episodeNum").toFloat()
                    val episodeTitle = "الموسم ${seasonNum.toInt()} الحلقة ${episodeNum.toInt()}"
                    val episode = SEpisode.create().apply {
                        name = episodeTitle
                        episode_number = finalNum
                        setUrlWithoutDomain("$episodeUrl/watch/")
                    }
                    episodes.add(episode)
                }
            }
        }
        return episodes.sortedBy { it.episode_number }.reversed()
    }

    override fun episodeListSelector(): String = "div.season-a ul.episodios li.episodesList a"

    private fun seasonListSelector(): String = "div.season-a ul.seas-list li.sealist a"

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.ani_detail-stage div.film-poster img").attr("src")
        anime.title = document.select("div.anisc-more-info div.item:contains(الاسم) span:nth-child(3)").text()
        anime.author = document.select("div.anisc-more-info div.item:contains(البلد) span:nth-child(3)").text()
        anime.genre = document.select("div.anisc-detail div.item-list a").joinToString(", ") { it.text() }
        anime.description = document.select("div.anisc-detail div.film-description div.text").text()
        anime.status = if (document.select("div.anisc-detail div.item-list").text().contains("افلام")) SAnime.COMPLETED else SAnime.UNKNOWN
        return anime
    }

    // ============================ Video Links =============================
    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListSelector(): String = "div#servers-content div.server-item div"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(dtAjax)")!!.data()
        val version = script.substringAfter("ver\":\"").substringBefore("\"")
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking {
            extractVideos(it, version)
        }
    }

    private fun generateRandomString(): String {
        val characters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val result = StringBuilder(16)
        for (i in 0 until 16) {
            val randomIndex = (Math.random() * characters.length).toInt()
            result.append(characters[randomIndex])
        }
        return result.toString()
    }

    private fun extractVideos(element: Element, version: String): List<Video> {
        val videoUrl = "$baseUrl/wp-json/lalaplayer/v2/".toHttpUrl().newBuilder()
        videoUrl.addQueryParameter("p", element.attr("data-post"))
        videoUrl.addQueryParameter("t", element.attr("data-type"))
        videoUrl.addQueryParameter("n", element.attr("data-nume"))
        videoUrl.addQueryParameter("ver", version)
        videoUrl.addQueryParameter("rand", generateRandomString())
        val videoFrame = client.newCall(GET(videoUrl.toString(), headers)).execute().body.string()
        val embedUrl = videoFrame.substringAfter("embed_url\":\"").substringBefore("\"")
        val referer = headers.newBuilder().add("Referer", "$baseUrl/").build()
        val webViewResult = webViewResolver.getUrl(embedUrl, referer)
        return when {
            ".mp4" in webViewResult.url -> {
                Video(webViewResult.url, element.text(), webViewResult.url, headers = referer).let(::listOf)
            }
            ".m3u8" in webViewResult.url -> {
                val subtitleList = if (webViewResult.subtitle.isNotBlank()) Track(webViewResult.subtitle, "Arabic").let(::listOf) else emptyList()
                playlistUtils.extractFromHls(webViewResult.url, videoNameGen = { "${element.text()}: $it" }, subtitleList = subtitleList)
            }
            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = baseUrl.toHttpUrl().newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment("category")
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (categoryFilter.state != 0) {
                url.addPathSegment("genre")
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("من فضلك اختر قسم او نوع")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            if (categoryFilter.state != 0) {
                url.addQueryParameter("type", categoryFilter.toUriPart())
            }
            GET(url.toString(), headers)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    // ============================ Filters =============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("هذا القسم يعمل لو كان البحث فارع"),
        SectionFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("الفلتره تعمل فقط لو كان اقسام الموقع على 'اختر'"),
        CategoryFilter(),
        GenreFilter(),
    )
    private class SectionFilter : PairFilter(
        "اقسام الموقع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام اجنبي", "aflam-online"),
            Pair("افلام نتفليكس", "netflix-movies"),
            Pair("افلام هندي", "indian-movies"),
            Pair("افلام اسيوي", "asian-aflam"),
            Pair("افلام كرتون", "cartoon-movies"),
            Pair("افلام انمي", "anime-movies"),
            Pair("مسلسلات اجنبي", "english-series"),
            Pair("مسلسلات نتفليكس", "netflix-series"),
            Pair("مسلسلات اسيوي", "asian-series"),
            Pair("مسلسلات كرتون", "anime-series"),
            Pair("مسلسلات انمي", "netflix-anime"),
        ),
    )
    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies"),
            Pair("مسلسلات", "series"),
        ),
    )
    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "Action", "Adventure", "Animation", "Western", "Documentary", "Fantasy", "Science-fiction", "Romance", "Comedy", "Family", "Drama", "Thriller", "Crime", "Horror",
        ).sortedArray(),
    )

    open class SingleFilter(displayName: String, private val vals: Array<String>) :
        AnimeFilter.Select<String>(displayName, vals) {
        fun toUriPart() = vals[state]
    }
    open class PairFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =============================== Latest ===============================
    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/recent/page/$page/", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    // =============================== Settings ===============================
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
