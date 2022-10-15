package eu.kanade.tachiyomi.animeextension.id.otakudesu

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class OtakuDesu : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "OtakuDesu"

    override val baseUrl = "https://otakudesu.video"

    override val lang = "id"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val zing = document.select("div.infozingle")
        val status = parseStatus(zing.select("p:nth-child(6) > span").text().replace("Status: ", ""))
        anime.title = zing.select("p:nth-child(1) > span").text().replace("Judul: ", "")
        anime.genre = zing.select("p:nth-child(11) > span").text().replace("Genre: ", "")
        anime.status = status
        anime.artist = zing.select("p:nth-child(10) > span").text()
        anime.author = zing.select("p:nth-child(4) > span").text()

        // Others
        // Jap title
        anime.description = document.select("p:nth-child(2) > span").text()
        // Score
        anime.description = anime.description + "\n" + document.select("p:nth-child(3) > span").text()
        // Total Episode
        anime.description = anime.description + "\n" + document.select("p:nth-child(7) > span").text()
        // Synopsis
        anime.description = anime.description + "\n\n\nSynopsis: \n" + document.select("div.sinopc > p").joinToString("\n\n") { it.text() }

        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epsNum = getNumberFromEpsString(element.select("span > a").text())
        episode.setUrlWithoutDomain(element.select("span > a").attr("href"))
        episode.episode_number = when {
            (epsNum.isNotEmpty()) -> epsNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("span > a").text().replace(".+?(?=Episode)|\\sSubtitle.+".toRegex(), "")
        episode.date_upload = reconstructDate(element.select("span.zeebr").text())

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    private fun reconstructDate(Str: String): Long {
        val newStr = Str.replace("Januari", "Jan").replace("Februari", "Feb").replace("Maret", "Mar").replace("April", "Apr").replace("Mei", "May").replace("Juni", "Jun").replace("Juli", "Jul").replace("Agustus", "Aug").replace("September", "Sep").replace("Oktober", "Oct").replace("November", "Nov").replace("Desember", "Dec")

        val pattern = SimpleDateFormat("d MMM yyyy", Locale.US)
        return pattern.parse(newStr.replace(",", " "))!!.time
    }

    override fun episodeListSelector(): String = "#venkonten > div.venser > div:nth-child(8) > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumb > a").first().attr("href"))
        anime.thumbnail_url = element.select("div.thumb > a > div.thumbz > img").first().attr("src")
        anime.title = element.select("div.thumb > a > div.thumbz > h2").text()
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "a.next.page-numbers"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ongoing-anime/page/$page")

    override fun latestUpdatesSelector(): String = "div.detpost"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.thumb > a").first().attr("href"))
        anime.thumbnail_url = element.select("div.thumb > a > div.thumbz > img").first().attr("src")
        anime.title = element.select("div.thumb > a > div.thumbz > h2").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/complete-anime/page/$page")

    override fun popularAnimeSelector(): String = "div.detpost"

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    private fun searchAnimeFromElement(element: Element, ui: String): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            when (ui) {
                "search" -> element.select("h2 > a").first().attr("href")
                "genres" -> element.select(".col-anime-title > a").attr("href")
                else -> element.select("div.thumb > a").first().attr("href")
            }
        )

        anime.thumbnail_url = when (ui) {
            "search" -> element.select("img").first().attr("src")
            "genres" -> element.select(".col-anime-cover > img").attr("src")
            else -> element.select("div.thumb > a > div.thumbz > img").first().attr("src")
        }
        anime.title = when (ui) {
            "search" -> element.select("h2 > a").text().replace(" Subtitle Indonesia", "")
            "genres" -> element.select(".col-anime-title > a").text()
            else -> element.select("div.thumb > a > div.thumbz > h2").text()
        }
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?s=$query&post_type=anime")
            genreFilter.state != 0 -> GET("$baseUrl/genres/${genreFilter.toUriPart()}/page/$page")
            else -> GET("$baseUrl/complete-anime/page/$page")
        }
    }

    override fun searchAnimeSelector(): String = "#venkonten > div > div.venser > div > div > ul > li"

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val ui = when {
            document.select(".col-anime").isNullOrEmpty() -> "search"
            document.select("#venkonten > div > div.venser > div > div > ul > li").isNullOrEmpty() -> "genres"
            else -> "unknown"
        }

        val animes = when (ui) {
            "genres" -> document.select(".col-anime").map { element -> searchAnimeFromElement(element, ui) }
            "search" -> document.select("#venkonten > div > div.venser > div > div > ul > li").map { element -> searchAnimeFromElement(element, ui) }
            else -> document.select("div.detpost").map { element -> popularAnimeFromElement(element) }
        }

        val hasNextPage = searchAnimeNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun videoListSelector() = "div.download > ul > li > a:nth-child(2)"

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

    override fun videoFromElement(element: Element): Video {
        val res = client.newCall(GET(element.attr("href"))).execute().asJsoup()
        val scr = res.select("script:containsData(dlbutton)").html()
        var url = element.attr("href").substringBefore("/v/")
        val numbs = scr.substringAfter("\" + (").substringBefore(") + \"")
        val firstString = scr.substringAfter(" = \"").substringBefore("\" + (")
        val num = numbs.substringBefore(" % ").toInt()
        val lastString = scr.substringAfter("913) + \"").substringBefore("\";")
        val nums = num % 51245 + num % 913
        url += firstString + nums.toString() + lastString
        val quality = with(lastString) {
            when {
                contains("1080p") -> "1080p"
                contains("720p") -> "720p"
                contains("480p") -> "480p"
                contains("360p") -> "360p"
                else -> "Default"
            }
        }
        return Video(url, quality, url)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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

    // filter
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Action", "action"),
            Pair("Adventure", "adventure"),
            Pair("Comedy", "comedy"),
            Pair("Demons", "demons"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Fantasy", "fantasy"),
            Pair("Game", "game"),
            Pair("Harem", "harem"),
            Pair("Historical", "historical"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Magic", "magic"),
            Pair("Martial Arts", "martial-arts"),
            Pair("Mecha", "mecha"),
            Pair("Military", "military"),
            Pair("Music", "music"),
            Pair("Mystery", "mystery"),
            Pair("Psychological", "psychological"),
            Pair("Parody", "parody"),
            Pair("Police", "police"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("School", "school"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Slice of Life", "slice-of-life"),
            Pair("Sports", "sports"),
            Pair("Space", "space"),
            Pair("Super Power", "super-power"),
            Pair("Supernatural", "supernatural"),
            Pair("Thriller", "thriller"),
            Pair("Vampire", "vampire"),
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
