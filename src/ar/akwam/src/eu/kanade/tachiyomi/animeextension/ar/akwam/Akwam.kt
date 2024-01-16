package eu.kanade.tachiyomi.animeextension.ar.akwam

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
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Akwam : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "أكوام"

    override val baseUrl = "https://akw-cdn1.link"

    override val lang = "ar"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.entry-box-1 div.entry-image a.box"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("picture img").attr("data-src")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("picture img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    // episodes
    override fun episodeListSelector() = "div.bg-primary2 h2 a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun addEpisodes(document: Document) {
            if (document.select(episodeListSelector()).isNullOrEmpty()) {
                // add movie
                document.select("input#reportInputUrl").map { episodes.add(episodeFromElement(it)) }
            } else {
                document.select(episodeListSelector()).map { episodes.add(episodesFromElement(it)) }
            }
        }
        addEpisodes(response.asJsoup())
        return episodes
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("value"))
        episode.name = "مشاهدة"
        return episode
    }

    private fun episodesFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.text())
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.text()
        episode.episode_number = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = "https://akw-cdn1.link/watch" + document.select("a.link-show").attr("href").substringAfter("watch") + "/" + document.ownerDocument()!!.select("input#page_id").attr("value")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        return Video(element.attr("src").replace("https", "http"), element.attr("size") + "p", element.attr("src").replace("https", "http"))
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

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("picture img").attr("data-src")
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("picture img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li.page-item a[rel=next]"

    override fun searchAnimeSelector(): String = "div.widget div.widget-body div.col-lg-auto div.entry-box div.entry-image a.box"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            val url = "$baseUrl/search?q=$query&page=$page".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is SectionFilter -> url.addQueryParameter("section", filter.toUriPart())
                    is RatingFilter -> url.addQueryParameter("rating", filter.toUriPart())
                    is FormatFilter -> url.addQueryParameter("formats", filter.toUriPart())
                    is QualityFilter -> url.addQueryParameter("quality", filter.toUriPart())
                    else -> {}
                }
            }
            url.toString()
        } else {
            val url = "$baseUrl/search?page=$page".toHttpUrlOrNull()!!.newBuilder()
            var type = "movies"
            filters.forEach { filter ->
                when (filter) {
                    is TypeFilter -> type = filter.toUriPart().toString()
                    is SectionSFilter -> url.addQueryParameter("section", filter.toUriPart())
                    is CategorySFilter -> url.addQueryParameter("category", filter.toUriPart())
                    is RatingSFilter -> url.addQueryParameter("rating", filter.toUriPart())
                    // is LanguageSFilter -> url.addQueryParameter("quality", filter.toUriPart())
                    else -> {}
                }
            }
            url.toString().replace("search", type)
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        // anime.thumbnail_url = document.select("div.container div div a picture > img.img-fluid").attr("data-src")
        anime.title = document.select("picture > img.img-fluid").attr("alt")
        anime.genre = document.select("div.font-size-16.d-flex.align-items-center.mt-3 a.badge, span.badge-info, span:contains(جودة الفيلم), span:contains(انتاج)").joinToString(", ") { it.text().replace("جودة الفيلم : ", "") }
        anime.author = document.select("span:contains(انتاج)").text().replace("انتاج : ", "")
        anime.description = document.select("div.widget:contains(قصة )").text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("فلترات البحث"),
        AnimeFilter.Separator(),
        SectionFilter(getSectionFilter()),
        RatingFilter(getRatingFilter()),
        FormatFilter(getFormatFilter()),
        QualityFilter(getQualityFilter()),
        AnimeFilter.Header("تصفح الموقع (تعمل فقط لو كان البحث فارغ)"),
        AnimeFilter.Separator(),
        TypeFilter(getTypeFilter()),
        SectionSFilter(getSectionSFilter()),
        CategorySFilter(getCategorySFilter()),
        RatingSFilter(getRatingSFilter()),
    )

    private class SectionFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("الأقسام", vals)
    private class RatingFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("التقيم", vals)
    private class FormatFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("الجودة", vals)
    private class QualityFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("الدقة", vals)
    private class TypeFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("النوع", vals)
    private class SectionSFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("القسم", vals)
    private class CategorySFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("التصنيف", vals)
    private class RatingSFilter(vals: Array<Pair<String?, String>>) : UriPartFilter("التقييم", vals)
    private fun getTypeFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("movies", "افلام"),
        Pair("series", "مسلسلات"),
    )
    private fun getSectionFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "الكل"),
        Pair("movie", "افلام"),
        Pair("series", "مسلسلات"),
        Pair("show", "تلفزيون"),
    )

    private fun getRatingFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "التقييم"),
        Pair("1", "1+"),
        Pair("2", "2+"),
        Pair("3", "3+"),
        Pair("4", "4+"),
        Pair("5", "5+"),
        Pair("6", "6+"),
        Pair("7", "7+"),
        Pair("8", "8+"),
        Pair("9", "9+"),
    )

    private fun getFormatFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "الكل"),
        Pair("BluRay", "BluRay"),
        Pair("WebRip", "WebRip"),
        Pair("BRRIP", "BRRIP"),
        Pair("DVDrip", "DVDrip"),
        Pair("DVDSCR", "DVDSCR"),
        Pair("HD", "HD"),
        Pair("HDTS", "HDTS"),
        Pair("HDTV", "HDTV"),
        Pair("CAM", "CAM"),
        Pair("WEB-DL", "WEB-DL"),
        Pair("HDTC", "HDTC"),
        Pair("BDRIP", "BDRIP"),
        Pair("HDRIP", "HDRIP"),
        Pair("HC+HDRIP", "HC HDRIP"),
    )

    private fun getQualityFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "الدقة"),
        Pair("240p", "240p"),
        Pair("360p", "360p"),
        Pair("480p", "480p"),
        Pair("720p", "720p"),
        Pair("1080p", "1080p"),
        Pair("3D", "3D"),
        Pair("4K", "4K"),
    )

    private fun getSectionSFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "القسم"),
        Pair("29", "عربي"),
        Pair("30", "اجنبي"),
        Pair("31", "هندي"),
        Pair("32", "تركي"),
        Pair("33", "اسيوي"),
    )

    private fun getCategorySFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "التصنيف"),
        Pair("87", "رمضان"),
        Pair("30", "انمي"),
        Pair("18", "اكشن"),
        Pair("71", "مدبلج"),
        Pair("72", "NETFLIX"),
        Pair("20", "كوميدي"),
        Pair("35", "اثارة"),
        Pair("34", "غموض"),
        Pair("33", "عائلي"),
        Pair("88", "اطفال"),
        Pair("25", "حربي"),
        Pair("32", "رياضي"),
        Pair("89", "قصير"),
        Pair("43", "فانتازيا"),
        Pair("24", "خيال علمي"),
        Pair("31", "موسيقى"),
        Pair("29", "سيرة ذاتية"),
        Pair("28", "وثائقي"),
        Pair("27", "رومانسي"),
        Pair("26", "تاريخي"),
        Pair("23", "دراما"),
        Pair("22", "رعب"),
        Pair("21", "جريمة"),
        Pair("19", "مغامرة"),
        Pair("91", "غربي"),
    )

    private fun getRatingSFilter(): Array<Pair<String?, String>> = arrayOf(
        Pair("0", "التقييم"),
        Pair("1", "1+"),
        Pair("2", "2+"),
        Pair("3", "3+"),
        Pair("4", "4+"),
        Pair("5", "5+"),
        Pair("6", "6+"),
        Pair("7", "7+"),
        Pair("8", "8+"),
        Pair("9", "9+"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String?, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUriPart() = vals[state].first
    }

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
