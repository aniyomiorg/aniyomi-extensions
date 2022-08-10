package eu.kanade.tachiyomi.animeextension.id.neonime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class NeoNime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val baseUrl: String = "https://neonime.watch"
    override val lang: String = "id"
    override val name: String = "NeoNime"
    override val supportsLatest: Boolean = true
    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Private Fun
    private fun reconstructDate(Str: String): Long {
        val pattern = SimpleDateFormat("dd-MM-yyyy", Locale.US)
        return runCatching { pattern.parse(Str)?.time }
            .getOrNull() ?: 0L
    }
    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.toLowerCase(Locale.US).contains("ongoing") -> SAnime.ONGOING
            statusString.toLowerCase(Locale.US).contains("completed") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun getAnimeFromAnimeElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").first().attr("href"))
        anime.thumbnail_url = element.select("a > div.image > img").first().attr("data-src")
        anime.title = element.select("div.fixyear > div > h2").text()
        return anime
    }

    private fun getAnimeFromEpisodeElement(element: Element): SAnime {
        val animepage = client.newCall(GET(element.select("td.bb > a").first().attr("href"))).execute().asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(animepage.select("#fixar > div.imagen > a").first().attr("href"))
        anime.thumbnail_url = animepage.select("#fixar > div.imagen > a > img").first().attr("data-src")
        anime.title = animepage.select("#fixar > div.imagen > a > img").first().attr("alt")
        return anime
    }

    private fun getAnimeFromSearchElement(element: Element): SAnime {
        val url = element.select("a").first().attr("href")
        val animepage = client.newCall(GET(url)).execute().asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(url)
        anime.title = animepage.select("#info > div:nth-child(2) > span").text()
        anime.thumbnail_url = animepage.select("div.imagen > img").first().attr("data-src")
        anime.status = parseStatus(animepage.select("#info > div:nth-child(13) > span").text())
        anime.genre = animepage.select("#info > div:nth-child(3) > span > a").joinToString(", ") { it.text() }
        // this site didnt provide artist and author
        anime.artist = "Unknown"
        anime.author = "Unknown"

        anime.description = animepage.select("#info > div.contenidotv").text()
        return anime
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Popular
    override fun popularAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "#contenedor > div > div.box > div.box_item > div.peliculas > div.item_1.items > div.respo_pag > div.pag_b > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page")

    override fun popularAnimeSelector(): String = "div.items > div.item"

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "#contenedor > div > div.box > div.box_item > div.peliculas > div.item_1.items > div.respo_pag > div.pag_b > a"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/episode/page/$page")

    override fun latestUpdatesSelector(): String = "table > tbody > tr"

    override fun latestUpdatesFromElement(element: Element): SAnime = getAnimeFromEpisodeElement(element)

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime = getAnimeFromSearchElement(element)

    override fun searchAnimeNextPageSelector(): String = "#contenedor > div > div.box > div.box_item > div.peliculas > div.item_1.items > div.respo_pag > div.pag_b > a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // filter is not avilable in neonime site
        return GET("$baseUrl/list-anime/")
    }

    override fun searchAnimeSelector() = throw Exception("Not Used")

    private fun generateSelector(query: String): String = "div.letter-section > ul > li > a:contains($query)"

    override fun searchAnimeParse(response: Response) = throw Exception("Not Used")

    private fun searchQueryParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()

        val animes = filterNoBatch(document.select(generateSelector(query))).map { element ->
            searchAnimeFromElement(element)
        }

        return AnimesPage(animes, false)
    }

    private fun filterNoBatch(eles: Elements): Elements {
        val retElements = Elements()

        for (ele in eles) {
            if (ele.attr("href").contains("/tvshows")) {
                retElements.add(ele)
            }
        }

        return retElements
    }

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val returnedSearch = searchAnimeRequest(page, query, filters)
        return client.newCall(returnedSearch)
            .asObservableSuccess()
            .map { response ->
                searchQueryParse(response, query)
            }
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("#info > div:nth-child(2) > span").text()
        anime.thumbnail_url = document.select("div.imagen > img").first().attr("data-src")
        anime.status = parseStatus(document.select("#info > div:nth-child(13) > span").text())
        anime.genre = document.select("#info > div:nth-child(3) > span > a").joinToString(", ") { it.text() }
        // this site didnt provide artist and author
        anime.artist = "Unknown"
        anime.author = "Unknown"

        anime.description = document.select("#info > div.contenidotv").text()
        return anime
    }

    // Episode List

    override fun episodeListSelector(): String = "ul.episodios > li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epsNum = getNumberFromEpsString(element.select("div.episodiotitle > a").text())
        episode.setUrlWithoutDomain(element.select("div.episodiotitle > a").attr("href"))
        episode.episode_number = when {
            (epsNum.isNotEmpty()) -> epsNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("div.episodiotitle > a").text()
        episode.date_upload = reconstructDate(element.select("div.episodiotitle > span.date").text())

        return episode
    }

    // Video
    override fun videoListSelector() = "div > ul >ul > li >a:nth-child(6)"

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

    // screen
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
