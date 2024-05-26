package eu.kanade.tachiyomi.animeextension.ar.faselhd

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
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل اعلاني"

    override val baseUrl = "https://www.faselhd.pro"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val webViewResolver by lazy { WebViewResolver() }

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page", headers)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.epAll a"

    private fun seasonsNextPageSelector(seasonNumber: Int) = "div#seasonList div.col-xl-2:nth-child($seasonNumber)" // "div.List--Seasons--Episodes > a:nth-child($seasonNumber)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        var seasonNumber = 1
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.select("span#liskSh").text())
            episode.name = "مشاهدة"
            return episode
        }
        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                document.select("div.shortLink").map { episodes.add(episodeExtract(it)) }
            } else {
                document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
                document.selectFirst(seasonsNextPageSelector(seasonNumber))?.let {
                    seasonNumber++
                    addEpisodes(
                        client.newCall(
                            GET(
                                "$baseUrl/?p=" + it.select("div.seasonDiv")
                                    .attr("onclick").substringAfterLast("=").substringBeforeLast("'"),
                                headers,
                            ),
                        ).execute().asJsoup(),
                    )
                }
            }
        }

        addEpisodes(response.asJsoup())
        return episodes.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = element.ownerDocument()!!.select("div.seasonDiv.active > div.title").text() + " : " + element.text()
        episode.episode_number = element.text().replace("الحلقة ", "").toFloat()
        return episode
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.selectFirst("iframe")!!.attr("src").substringBefore("&img")
        val webViewResult = webViewResolver.getUrl(iframe, headers)
        return if (webViewResult.isNotBlank()) playlistUtils.extractFromHls(webViewResult) else emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img, img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img, img").attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun searchAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val sectionFilter = filterList.find { it is SectionFilter } as SectionFilter
        val categoryFilter = filterList.find { it is CategoryFilter } as CategoryFilter
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            if (sectionFilter.state != 0) {
                url.addPathSegment(sectionFilter.toUriPart())
            } else if (categoryFilter.state != 0) {
                url.addPathSegment(categoryFilter.toUriPart())
                url.addPathSegment(genreFilter.toUriPart().lowercase())
            } else {
                throw Exception("من فضلك اختر قسم او نوع")
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("meta[itemprop=name]").attr("content")
        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a").joinToString(", ") { it.text() }
        // anime.thumbnail_url = document.select("div.posterImg img.poster").attr("src")

        val cover = document.select("div.posterImg img.poster").attr("src")
        anime.thumbnail_url = if (cover.isNullOrEmpty()) {
            document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
        } else {
            cover
        }
        anime.description = document.select("div.singleDesc").text()
        anime.status = parseStatus(document.select("span:contains(حالة)").text().replace("حالة ", "").replace("المسلسل : ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "مستمر" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page", headers)

    override fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"

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
            Pair("جميع الافلام", "all-movies"),
            Pair("افلام اجنبي", "movies"),
            Pair("افلام مدبلجة", "dubbed-movies"),
            Pair("افلام هندي", "hindi"),
            Pair("افلام اسيوي", "asian-movies"),
            Pair("افلام انمي", "anime-movies"),
            Pair("الافلام الاعلي تصويتا", "movies_top_votes"),
            Pair("الافلام الاعلي مشاهدة", "movies_top_views"),
            Pair("الافلام الاعلي تقييما IMDB", "movies_top_imdb"),
            Pair("جميع المسلسلات", "series"),
            Pair("مسلسلات الأنمي", "anime"),
            Pair("المسلسلات الاعلي تقييما IMDB", "series_top_imdb"),
            Pair("المسلسلات القصيرة", "short_series"),
            Pair("المسلسلات الاسيوية", "asian-series"),
            Pair("المسلسلات الاعلي مشاهدة", "series_top_views"),
            Pair("المسلسلات الاسيوية الاعلي مشاهدة", "asian_top_views"),
            Pair("الانمي الاعلي مشاهدة", "anime_top_views"),
            Pair("البرامج التليفزيونية", "tvshows"),
            Pair("البرامج التليفزيونية الاعلي مشاهدة", "tvshows_top_views"),
        ),
    )
    private class CategoryFilter : PairFilter(
        "النوع",
        arrayOf(
            Pair("اختر", "none"),
            Pair("افلام", "movies-cats"),
            Pair("مسلسلات", "series_genres"),
            Pair("انمى", "anime-cats"),
        ),
    )
    private class GenreFilter : SingleFilter(
        "التصنيف",
        arrayOf(
            "Action", "Adventure", "Animation", "Western", "Sport", "Short", "Documentary", "Fantasy", "Sci-fi", "Romance", "Comedy", "Family", "Drama", "Thriller", "Crime", "Horror", "Biography",
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

    // preferred quality settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
