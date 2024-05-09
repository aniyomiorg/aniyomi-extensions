package eu.kanade.tachiyomi.animeextension.hi.yomovies

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors.MinoplresExtractor
import eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors.MovembedExtractor
import eu.kanade.tachiyomi.animeextension.hi.yomovies.extractors.SpeedostreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
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
import java.lang.Exception

class YoMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "YoMovies"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://yomovies.town"

    override val lang = "hi"

    override val supportsLatest = false

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/most-favorites/".addPage(page), headers)

    override fun popularAnimeSelector(): String = "div.movies-list > div.ml-item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href"))
        thumbnail_url = element.selectFirst("img[data-original]")?.attr("abs:data-original") ?: ""
        title = element.selectFirst("div.qtip-title")!!.text()
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active + li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val firstSelected = filterList.firstOrNull {
            it.state != 0
        }?.let { it as UriPartFilter }

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query".addPage(page), headers)
            firstSelected != null -> GET("$baseUrl${firstSelected.toUriPart()}".addPage(page), headers)
            else -> throw Exception("Either search or")
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val infoDiv = document.selectFirst("div.mvi-content")!!

        return SAnime.create().apply {
            description = infoDiv.selectFirst("p.f-desc")?.text()
            genre = infoDiv.select("div.mvici-left > p:contains(Genre:) a").joinToString(", ") { it.text() }
            author = infoDiv.select("div.mvici-left > p:contains(Studio:) a").joinToString(", ") { it.text() }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val seasonList = document.select("div#seasons > div.tvseason")
        // For movies
        if (seasonList.size == 0) {
            episodeList.add(
                SEpisode.create().apply {
                    setUrlWithoutDomain(response.request.url.toString())
                    name = "Movie"
                    episode_number = 1F
                },
            )
        } else {
            seasonList.forEach { season ->
                val seasonText = season.selectFirst("div.les-title")!!.text().trim()

                season.select(episodeListSelector()).forEachIndexed { index, ep ->
                    val epNumber = ep.text().trim().substringAfter("pisode ")

                    episodeList.add(
                        SEpisode.create().apply {
                            setUrlWithoutDomain(ep.attr("abs:href"))
                            name = "$seasonText Ep. $epNumber"
                            episode_number = epNumber.toFloatOrNull() ?: (index + 1).toFloat()
                        },
                    )
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector() = "div.les-content > a"

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val videoList = document.select("div[id*=tab]:has(div.movieplay > iframe)")
            .parallelCatchingFlatMapBlocking { server ->
                val iframe = server.selectFirst("div.movieplay > iframe")!!
                val name = document.selectFirst("ul.idTabs > li:has(a[href=#${server.id()}]) div.les-title")
                    ?.text()
                    ?.let { "[$it] - " }
                    .orEmpty()

                extractVideosFromIframe(iframe.attr("abs:src"), name)
            }

        return videoList.sort()
    }

    private fun extractVideosFromIframe(iframeUrl: String, name: String): List<Video> {
        return when {
            iframeUrl.contains("speedostream") -> {
                SpeedostreamExtractor(client, headers).videosFromUrl(iframeUrl, "$baseUrl/", prefix = name)
            }

            // Prepending the server name probably isn't needed for this,
            // since it doesn't do different episodes for different servers from what I can tell
            iframeUrl.contains("movembed.cc") -> {
                MovembedExtractor(client, headers).videosFromUrl(iframeUrl)
            }

            iframeUrl.contains("minoplres") -> {
                MinoplresExtractor(client, headers).videosFromUrl(iframeUrl, name)
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun String.addPage(page: Int): String {
        return if (page == 1) {
            this
        } else {
            this.toHttpUrl().newBuilder()
                .addPathSegment("page")
                .addPathSegment(page.toString())
                .toString()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Note: Only one selection at a time works, and it ignores text search"),
        AnimeFilter.Separator(),
        BollywoodFilter(),
        DualAudioFilter(),
        HollywoodFilter(),
        EnglishSeriesFilter(),
        HindiSeriesFilter(),
        GenreFilter(),
        ExtraMoviesFilter(),
        EroticFilter(),
        HotSeriesFilter(),
    )

    // Note for filters, clicking on the "category selector" yields its own page

    // $("ul.top-menu > li:has(a:contains(Bollywood)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class BollywoodFilter : UriPartFilter(
        "Bollywood",
        arrayOf(
            Pair("<select>", ""),
            Pair("Bollywood", "/genre/bollywood/"),
            Pair("Trending", "/genre/top-rated/"),
            Pair("Bollywood (2024)", "/account/?ptype=post&tax_category%5B%5D=bollywood&tax_release-year=2024&wpas=1"),
            Pair("Bollywood (2023)", "/account/?ptype=post&tax_category%5B%5D=bollywood&tax_release-year=2023&wpas=1"),
            Pair("Bollywood (2022)", "/account/?ptype=post&tax_category%5B%5D=bollywood&tax_release-year=2022&wpas=1"),
            Pair("Bollywood (2021)", "/account/?ptype=post&tax_category%5B%5D=bollywood&tax_release-year=2021&wpas=1"),
        ),
    )

    // $("ul.top-menu > li:has(a:contains(Dual audio)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class DualAudioFilter : UriPartFilter(
        "Dual Audio",
        arrayOf(
            Pair("<select>", ""),
            Pair("Dual Audio", "/genre/dual-audio/"),
            Pair("Hollywood Dubbed", "/account/?ptype=post&tax_category%5B%5D=dual-audio&wpas=1"),
            Pair("South Dubbed", "/account/?ptype=post&tax_category%5B%5D=dual-audio&tax_category%5B%5D=south-special&wpas=1"),
        ),
    )

    // $("ul.top-menu > li:has(a:contains(Hollywood)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class HollywoodFilter : UriPartFilter(
        "Hollywood",
        arrayOf(
            Pair("<select>", ""),
            Pair("Hollywood", "/genre/hollywood/"),
            Pair("Hollywood (2024)", "/account/?ptype=post&tax_category%5B%5D=hollywood&tax_release-year=2024&wpas=1"),
            Pair("Hollywood (2023)", "/account/?ptype=post&tax_category%5B%5D=hollywood&tax_release-year=2023&wpas=1"),
            Pair("Hollywood (2022)", "/account/?ptype=post&tax_category%5B%5D=hollywood&tax_release-year=2022&wpas=1"),
            Pair("Hollywood (2021)", "/account/?ptype=post&tax_category%5B%5D=hollywood&tax_release-year=2021&wpas=1"),
        ),
    )

    private class EnglishSeriesFilter : UriPartFilter(
        "English Series",
        arrayOf(
            Pair("<select>", ""),
            Pair("English Series", "/series/"),
        ),
    )

    // $("ul.top-menu > li:has(a:contains(Hindi Series)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class HindiSeriesFilter : UriPartFilter(
        "Hindi Series",
        arrayOf(
            Pair("<select>", ""),
            Pair("Hindi Series", "/genre/web-series/"),
            Pair("Netflix", "/director/netflix/"),
            Pair("Amazon", "/director/amazon-prime/"),
            Pair("Altbalaji", "/director/altbalaji/"),
            Pair("Zee5", "/director/zee5/"),
            Pair("Voot", "/director/voot-originals/"),
            Pair("Mx Player", "/director/mx-player/"),
            Pair("Hotstar", "/director/hotstar/"),
            Pair("Viu", "/director/viu-originals/"),
            Pair("Sony Liv", "/director/sonyliv-original/"),
        ),
    )

    // $("ul.top-menu > li:has(a:contains(Genre)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class GenreFilter : UriPartFilter(
        "Genre",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "/genre/action/"),
            Pair("Adventure", "/genre/adventure/"),
            Pair("Animation", "/genre/animation/"),
            Pair("Biography", "/genre/biography/"),
            Pair("Comedy", "/genre/comedy/"),
            Pair("Crime", "/genre/crime/"),
            Pair("Drama", "/genre/drama/"),
            Pair("Music", "/genre/music/"),
            Pair("Mystery", "/genre/mystery/"),
            Pair("Family", "/genre/family/"),
            Pair("Fantasy", "/genre/fantasy/"),
            Pair("Horror", "/genre/horror/"),
            Pair("History", "/genre/history/"),
            Pair("Romance", "/genre/romantic/"),
            Pair("Science Fiction", "/genre/science-fiction/"),
            Pair("Thriller", "/genre/thriller/"),
            Pair("War", "/genre/war/"),
        ),
    )

    // $("ul.top-menu > li:has(a:contains(ExtraMovies)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class ExtraMoviesFilter : UriPartFilter(
        "ExtraMovies",
        arrayOf(
            Pair("<select>", ""),
            Pair("ExtraMovies", "/genre/south-special/"),
            Pair("Bengali", "/genre/bengali/"),
            Pair("Marathi", "/genre/marathi/"),
            Pair("Gujarati", "/genre/gujarati/"),
            Pair("Punjabi", "/genre/punjabi/"),
            Pair("Tamil", "/genre/tamil/"),
            Pair("Telugu", "/genre/telugu/"),
            Pair("Malayalam", "/genre/malayalam/"),
            Pair("Kannada", "/genre/kannada/"),
            Pair("Pakistani", "/genre/pakistani/"),
        ),
    )

    private class EroticFilter : UriPartFilter(
        "Erotic",
        arrayOf(
            Pair("<select>", ""),
            Pair("Erotic", "/genre/erotic-movies/"),
        ),
    )

    // $("ul.top-menu > li:has(a:contains(Hot Series)) ul > li").map((i,el) => `Pair("${$(el).text().trim()}", "${$(el).find("a").first().attr('href').trim().replace('https://yomovies.baby', '')}")`).get().join(',\n')
    // on /
    private class HotSeriesFilter : UriPartFilter(
        "Hot Series",
        arrayOf(
            Pair("<select>", ""),
            Pair("Hot Series", "/genre/tv-shows/"),
            Pair("Uncut", "/?s=uncut"),
            Pair("Fliz Movies", "/director/fliz-movies/"),
            Pair("Nuefliks", "/director/nuefliks-exclusive/"),
            Pair("Hotshots", "/director/hotshots/"),
            Pair("Ullu Originals", "/?s=ullu"),
            Pair("Kooku", "/director/kooku-originals/"),
            Pair("Gupchup", "/director/gupchup-exclusive/"),
            Pair("Feneomovies", "/director/feneomovies/"),
            Pair("Cinemadosti", "/director/cinemadosti/"),
            Pair("Primeflix", "/director/primeflix/"),
            Pair("Gemplex", "/director/gemplex/"),
            Pair("Rabbit", "/director/rabbit-original/"),
            Pair("HotMasti", "/director/hotmasti-originals/"),
            Pair("BoomMovies", "/director/boommovies-original/"),
            Pair("CliffMovies", "/director/cliff-movies/"),
            Pair("MastiPrime", "/director/masti-prime-originals/"),
            Pair("Ek Night Show", "/director/ek-night-show/"),
            Pair("Flixsksmovies", "/director/flixsksmovies/"),
            Pair("Lootlo", "/director/lootlo-original/"),
            Pair("Hootzy", "/director/hootzy-channel/"),
            Pair("Balloons", "/director/balloons-originals/"),
            Pair("Big Movie Zoo", "/director/big-movie-zoo-originals/"),
            Pair("Bambooflix", "/director/bambooflix/"),
            Pair("Piliflix", "/director/piliflix-originals/"),
            Pair("11upmovies", "/director/11upmovies-originals/"),
            Pair("Eightshots", "/director/eightshots-originals/"),
            Pair("I-Entertainment", "/director/i-entertainment-exclusive/"),
            Pair("Hotprime", "/director/hotprime-originals/"),
            Pair("BananaPrime", "/director/banana-prime/"),
            Pair("HotHitFilms", "/director/hothitfilms/"),
            Pair("Chikooflix", "/director/chikooflix-originals/"),
            Pair("Glamheart", "/?s=glamheart"),
            Pair("Worldprime", "/director/worldprime-originals/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
