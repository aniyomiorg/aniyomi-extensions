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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class FASELHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "فاصل اعلاني"

    override val baseUrl = "https://www.faselhd.club"

    override val lang = "ar"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div#postList div.col-xl-2 a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        // anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    // Episodes
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
            if (document.select(episodeListSelector()).isNullOrEmpty())
                document.select("div.shortLink").map { episodes.add(episodeExtract(it)) }
            else {
                document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
                document.select(seasonsNextPageSelector(seasonNumber)).firstOrNull()?.let {
                    seasonNumber++
                    addEpisodes(
                        client.newCall(
                            GET(
                                "$baseUrl/?p=" + it.select("div.seasonDiv")
                                    .attr("data-href"),
                                headers
                            )
                        ).execute().asJsoup()
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
        episode.name = element.ownerDocument().select("div.seasonDiv.active > div.title").text() + " : " + element.text()
        episode.episode_number = element.text().replace("الحلقة ", "").toFloat()
        return episode
    }

    // Video urls

    override fun videoListSelector() = throw UnsupportedOperationException("Not used.")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val getSources = "master.m3u8"
        val referer = Headers.headersOf("Referer", "$baseUrl/")
        val iframe = document.selectFirst("iframe").attr("src").substringBefore("&img")
        val webViewIncpec = client.newBuilder().addInterceptor(GetSourcesInterceptor(getSources, client)).build()
        val lol = webViewIncpec.newCall(GET(iframe, referer)).execute().body!!.string()
        val videoList = mutableListOf<Video>()
        lol.substringAfter("#EXT-X-STREAM-INF:").split("#EXT-X-STREAM-INF:").forEach {
            val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",") + "p"
            val videoUrl = it.substringAfter("\n").substringBefore("\n").replace("https", "http")
            videoList.add(Video(videoUrl, quality, videoUrl, headers = referer))
        }
        return videoList
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

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // search

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
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page?s=$query", headers)
        } else {
            val url = "$baseUrl/".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is GenreFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            url.addPathSegment("page")
            url.addPathSegment("$page")
            GET(url.toString(), headers)
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.title.h1").text()
        anime.genre = document.select("span:contains(تصنيف) > a, span:contains(مستوى) > a").joinToString(", ") { it.text() }
        // anime.thumbnail_url = document.select("div.posterImg img.poster").attr("src")

        val cover = document.select("div.posterImg img.poster").attr("src")
        anime.thumbnail_url = if (cover.isNullOrEmpty()) {
            document.select("div.col-xl-2 > div.seasonDiv:nth-child(1) > img").attr("data-src")
        } else {
            cover
        }
        anime.description = document.select("div.singleDesc p").text()
        anime.status = parseStatus(document.select("span:contains(حالة)").text().replace("حالة ", "").replace("المسلسل : ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "مستمر" -> SAnime.ONGOING
            // "Completed" -> SAnime.COMPLETED
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li a.page-link:contains(›)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.select("div.imgdiv-class img").attr("alt")
        anime.thumbnail_url = element.select("div.imgdiv-class img").attr("data-src")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/most_recent/page/$page")

    override fun latestUpdatesSelector(): String = "div#postList div.col-xl-2 a"

    // Filters

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList())
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("تصنيف المسلسلات", vals)

    private fun getGenreList() = arrayOf(
        Pair("افلام انمي", "anime-movies"),
        Pair("جميع الافلام", "all-movies"),
        Pair("جوائز الأوسكار لهذا العام⭐", "oscars-winners"),
        Pair("افلام اجنبي", "movies"),
        Pair("افلام مدبلجة", "dubbed-movies"),
        Pair("افلام هندي", "hindi"),
        Pair("افلام اسيوي", "asian-movies"),
        Pair("الاعلي مشاهدة", "movies_top_views"),
        Pair("الافلام الاعلي تقييما IMDB", "movies_top_imdb"),
        Pair("مسلسلات الأنمي", "anime"),
        Pair("جميع المسلسلات", "series"),
        Pair("الاعلي مشاهدة", "series_top_views"),
        Pair("الاعلي تقييما IMDB", "series_top_imdb"),
        Pair("المسلسلات القصيرة", "short_series"),
        Pair("المسلسلات الاسيوية", "asian-series"),
        Pair("المسلسلات الاسيوية الاعلي مشاهدة", "asian_top_views"),
        Pair("الانمي الاعلي مشاهدة", "anime_top_views")
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
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
