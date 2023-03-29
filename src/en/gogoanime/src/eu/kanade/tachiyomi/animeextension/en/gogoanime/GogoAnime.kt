package eu.kanade.tachiyomi.animeextension.en.gogoanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.gogoanime.extractors.GogoCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

@ExperimentalSerializationApi
class GogoAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Gogoanime"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://gogoanime.lu")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.img a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/popular.html?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.selectFirst("img")!!.attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun episodeListSelector() = "ul#episode_page li a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val totalEpisodes = document.select(episodeListSelector()).last()!!.attr("ep_end")
        val id = document.select("input#movie_id").attr("value")
        return episodesRequest(totalEpisodes, id)
    }

    private fun episodesRequest(totalEpisodes: String, id: String): List<SEpisode> {
        val request = GET("https://ajax.gogo-load.com/ajax/load-list-episode?ep_start=0&ep_end=$totalEpisodes&id=$id", headers)
        val epResponse = client.newCall(request).execute()
        val document = epResponse.asJsoup()
        return document.select("a").map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("href").substringAfter(" "))
        val ep = element.selectFirst("div.name")!!.ownText().substringAfter(" ")
        episode.episode_number = ep.toFloat()
        episode.name = "Episode $ep"
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val extractor = GogoCdnExtractor(network.client, json)
        val videoList = mutableListOf<Video>()
        // GogoCdn:
        document.select("div.anime_muti_link > ul > li.vidcdn > a")
            .firstOrNull()?.attr("data-video")
            ?.let { videoList.addAll(extractor.videosFromUrl("https:$it")) }
        // Vidstreaming:
        document.select("div.anime_muti_link > ul > li.anime > a")
            .firstOrNull()?.attr("data-video")
            ?.let { videoList.addAll(extractor.videosFromUrl("https:$it")) }
        // Doodstream mirror:
        document.select("div.anime_muti_link > ul > li.doodstream > a")
            .firstOrNull()?.attr("data-video")
            ?.let { videoList.addAll(DoodExtractor(client).videosFromUrl(it)) }
        // StreamSB mirror:
        document.select("div.anime_muti_link > ul > li.streamsb > a")
            .firstOrNull()?.attr("data-video")
            ?.let { videoList.addAll(StreamSBExtractor(client).videosFromUrl(it, headers)) }
        return videoList.sort()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "Gogostream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server) },
            ),
        ).reversed()
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.selectFirst("img")!!.attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun searchAnimeSelector(): String = "div.img a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val recentFilter = filterList.find { it is RecentFilter } as RecentFilter
        val seasonFilter = filterList.find { it is SeasonFilter } as SeasonFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search.html?keyword=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}?page=$page")
            recentFilter.state != 0 -> GET("https://ajax.gogo-load.com/ajax/page-recent-release.html?page=$page&type=${recentFilter.toUriPart()}")
            seasonFilter.state != 0 -> GET("$baseUrl/${seasonFilter.toUriPart()}?page=$page", headers)
            else -> GET("$baseUrl/popular.html?page=$page")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.anime_info_body_bg h1").text()
        anime.genre = document.select("p.type:eq(5) a").joinToString("") { it.text() }
        anime.description = document.selectFirst("p.type:eq(4)")!!.ownText()
        anime.status = parseStatus(document.select("p.type:eq(7) a").text())

        // add alternative name to anime description
        val altName = "Other name(s): "
        document.selectFirst("p.type:eq(8)")?.ownText()?.let {
            if (it.isBlank().not()) {
                anime.description = when {
                    anime.description.isNullOrBlank() -> altName + it
                    else -> anime.description + "\n\n$altName" + it
                }
            }
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.attr("href"))
        val style = element.select("div.thumbnail-popular").attr("style")
        anime.thumbnail_url = style.substringAfter("background: url('").substringBefore("');")
        anime.title = element.attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request =
        GET("https://ajax.gogo-load.com/ajax/page-recent-release-ongoing.html?page=$page&type=1", headers)

    override fun latestUpdatesSelector(): String = "div.added_series_body.popular li a:has(div)"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("gogoanime.tel", "gogoanime.ar")
            entryValues = arrayOf("https://gogoanime.tel", "https://gogoanime.ar")
            setDefaultValue("https://gogoanime.tel")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
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
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Gogostream", "Vidstreaming", "Doodstream", "StreamSB")
            entryValues = arrayOf("Gogostream", "Vidstreaming", "Doodstream", "StreamSB")
            setDefaultValue("Gogostream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }

    // Filters
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter(),
        RecentFilter(),
        SeasonFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adult Cast", "adult-cast"),
            Pair("Adventure", "adventure"),
            Pair("Anthropomorphic", "anthropomorphic"),
            Pair("Avant Garde", "avant-garde"),
            Pair("Boys Love", "shounen-ai"),
            Pair("Cars", "cars"),
            Pair("CGDCT", "cgdct"),
            Pair("Childcare", "childcare"),
            Pair("Comedy", "comedy"),
            Pair("Comic", "comic"),
            Pair("Crime", "crime"),
            Pair("Crossdressing", "crossdressing"),
            Pair("Delinquents", "delinquents"),
            Pair("Dementia", "dementia"),
            Pair("Demons", "demons"),
            Pair("Detective", "detective"),
            Pair("Drama", "drama"),
            Pair("Dub", "dub"),
            Pair("Ecchi", "ecchi"),
            Pair("Erotica", "erotica"),
            Pair("Family", "family"),
            Pair("Fantasy", "fantasy"),
            Pair("Gag Humor", "gag-humor"),
            Pair("Game", "game"),
            Pair("Gender Bender", "gender-bender"),
            Pair("Gore", "gore"),
            Pair("Gourmet", "gourmet"),
            Pair("Harem", "harem"),
            Pair("Hentai", "hentai"),
            Pair("High Stakes Game", "high-stakes-game"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Isekai", "isekai"),
            Pair("Iyashikei", "iyashikei"),
            Pair("Josei", "josei"),
            Pair("Kids", "kids"),
            Pair("Magic", "magic"),
            Pair("Magical Sex Shift", "magical-sex-shift"),
            Pair("Mahou Shoujo", "mahou-shoujo"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Medical", "medical"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Mythology", "mythology"),
            Pair("Organized Crime", "organized-crime"),
            Pair("Parody", "parody"),
            Pair("Performing Arts", "performing-arts"),
            Pair("Pets", "pets"),
            Pair("Police", "police"),
            Pair("Psychological", "psychological"),
            Pair("Reincarnation", "reincarnation"),
            Pair("Romance", "romance"),
            Pair("Romantic Subtext", "romantic-subtext"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Space", "space"),
            Pair("Sports", "sports"),
            Pair("Strategy Game", "strategy-game"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Suspense", "suspense"),
            Pair("Team Sports", "team-sports"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("Vampire", "vampire"),
            Pair("Work Life", "work-life"),
            Pair("Workplace", "workplace"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    private class RecentFilter : UriPartFilter(
        "Recent Episodes",
        arrayOf(
            Pair("<select>", ""),
            Pair("Recent Release", "1"),
            Pair("Recent Dub", "2"),
            Pair("Recent Chinese", "3"),
        ),
    )

    private class SeasonFilter : UriPartFilter(
        "Season",
        arrayOf(
            Pair("<select>", ""),
            Pair("Latest season", "new-season.html"),
            Pair("Winter 2023", "sub-category/winter-2023-anime"),
            Pair("Fall 2022", "sub-category/fall-2022-anime"),
            Pair("Summer 2022", "sub-category/summer-2022-anime"),
            Pair("Spring 2022", "sub-category/spring-2022-anime"),
            Pair("Winter 2022", "sub-category/winter-2022-anime"),
            Pair("Fall 2021", "sub-category/fall-2021-anime"),
            Pair("Summer 2021", "sub-category/summer-2021-anime"),
            Pair("Spring 2021", "sub-category/spring-2021-anime"),
            Pair("Winter 2021", "sub-category/winter-2021-anime"),
            Pair("Fall 2020", "sub-category/fall-2020-anime"),
            Pair("Summer 2020", "sub-category/summer-2020-anime"),
            Pair("Spring 2020", "sub-category/spring-2020-anime"),
            Pair("Winter 2020", "sub-category/winter-2020-anime"),
            Pair("Fall 2019", "sub-category/fall-2019-anime"),
            Pair("Summer 2019", "sub-category/summer-2019-anime"),
            Pair("Spring 2019", "sub-category/spring-2019-anime"),
            Pair("Winter 2019", "sub-category/winter-2019-anime"),
            Pair("Fall 2018", "sub-category/fall-2018-anime"),
            Pair("Summer 2018", "sub-category/summer-2018-anime"),
            Pair("Spring 2018", "sub-category/spring-2018-anime"),
            Pair("Winter 2018", "sub-category/winter-2018-anime"),
            Pair("Fall 2017", "sub-category/fall-2017-anime"),
            Pair("Summer 2017", "sub-category/summer-2017-anime"),
            Pair("Spring 2017", "sub-category/spring-2017-anime"),
            Pair("Winter 2017", "sub-category/winter-2017-anime"),
            Pair("Fall 2016", "sub-category/fall-2016-anime"),
            Pair("Summer 2016", "sub-category/summer-2016-anime"),
            Pair("Spring 2016", "sub-category/spring-2016-anime"),
            Pair("Winter 2016", "sub-category/winter-2016-anime"),
            Pair("Fall 2015", "sub-category/fall-2015-anime"),
            Pair("Summer 2015", "sub-category/summer-2015-anime"),
            Pair("Spring 2015", "sub-category/spring-2015-anime"),
            Pair("Winter 2015", "sub-category/winter-2015-anime"),
            Pair("Fall 2014", "sub-category/fall-2014-anime"),
            Pair("Summer 2014", "sub-category/summer-2014-anime"),
            Pair("Spring 2014", "sub-category/spring-2014-anime"),
            Pair("Winter 2014", "sub-category/winter-2014-anime"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
