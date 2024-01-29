package eu.kanade.tachiyomi.animeextension.id.neonime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.id.neonime.extractors.LinkBoxExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class NeoNime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val baseUrl: String = "https://neonime.ink"
    override val lang: String = "id"
    override val name: String = "NeoNime"
    override val supportsLatest: Boolean = true

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
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        anime.thumbnail_url = element.selectFirst("a > div.image > img")!!.attr("data-src")
        anime.title = element.select("div.fixyear > div > h2").text()
        return anime
    }

    private fun getAnimeFromEpisodeElement(element: Element): SAnime {
        val animepage = client.newCall(GET(element.selectFirst("td.bb > a")!!.attr("href"))).execute().asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(animepage.selectFirst("#fixar > div.imagen > a")!!.attr("href"))
        anime.thumbnail_url = animepage.selectFirst("#fixar > div.imagen > a > img")!!.attr("data-src")
        anime.title = animepage.selectFirst("#fixar > div.imagen > a > img")!!.attr("alt")
        return anime
    }

    private fun getAnimeFromSearchElement(element: Element): SAnime {
        val url = element.selectFirst("a")!!.attr("href")
        val animepage = client.newCall(GET(url)).execute().asJsoup()
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(url)
        anime.title = animepage.select("#info > div:nth-child(2) > span").text()
        anime.thumbnail_url = animepage.selectFirst("div.imagen > img")!!.attr("data-src")
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

    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    private fun generateSelector(query: String): String = "div.letter-section > ul > li > a:contains($query)"

    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

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

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val returnedSearch = searchAnimeRequest(page, query, filters)
        return client.newCall(returnedSearch)
            .awaitSuccess()
            .let { response ->
                searchQueryParse(response, query)
            }
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("#info > div:nth-child(2) > span").text()
        anime.thumbnail_url = document.selectFirst("div.imagen > img")!!.attr("data-src")
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
            epsNum.isNotEmpty() -> epsNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("div.episodiotitle > a").text()
        episode.date_upload = reconstructDate(element.select("div.episodiotitle > span.date").text())

        return episode
    }

    // Video

    override fun videoListSelector() = "div > ul >ul > li >a:nth-child(6)"

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val hosterSelection = preferences.getStringSet(
            "hoster_selection",
            setOf("blogger", "linkbox", "okru", "yourupload", "gdriveplayer"),
        )!!

        document.select("div.player2 > div.embed2 > div").forEach {
            val iframe = it.selectFirst("iframe") ?: return@forEach

            var link = iframe.attr("data-src")
            if (!link.startsWith("http")) {
                link = "https:$link"
            }

            when {
                hosterSelection.contains("linkbox") && link.contains("linkbox.to") -> {
                    videoList.addAll(LinkBoxExtractor(client).videosFromUrl(link, it.text()))
                }
                hosterSelection.contains("okru") && link.contains("ok.ru") -> {
                    videoList.addAll(OkruExtractor(client).videosFromUrl(link))
                }
                hosterSelection.contains("yourupload") && link.contains("blogger.com") -> {
                    videoList.addAll(BloggerExtractor(client).videosFromUrl(link, headers, it.text()))
                }
                hosterSelection.contains("linkbox") && link.contains("yourupload.com") -> {
                    videoList.addAll(YourUploadExtractor(client).videoFromUrl(link, headers, it.text(), "Original - "))
                }
                hosterSelection.contains("gdriveplayer") && link.contains("neonime.fun") -> {
                    val headers = Headers.headersOf(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                        "Referer",
                        response.request.url.toString(),
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0",
                    )
                    var iframe = client.newCall(
                        GET(link, headers = headers),
                    ).execute().asJsoup()

                    var iframeUrl = iframe.selectFirst("iframe")!!.attr("src")

                    if (!iframeUrl.startsWith("http")) {
                        iframeUrl = "https:$iframeUrl"
                    }

                    when {
                        iframeUrl.contains("gdriveplayer.to") -> {
                            val newHeaders = headersBuilder().add("Referer", baseUrl).build()
                            videoList.addAll(GdrivePlayerExtractor(client).videosFromUrl(iframeUrl, it.text(), headers = newHeaders))
                        }
                    }
                }
            }
        }

        return videoList.sort()
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
        val hostSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Enable/Disable Hosts"
            entries = arrayOf("Blogger", "Linkbox", "Ok.ru", "YourUpload", "GdrivePlayer")
            entryValues = arrayOf("blogger", "linkbox", "okru", "yourupload", "gdriveplayer")
            setDefaultValue(setOf("blogger", "linkbox", "okru", "yourupload", "gdriveplayer"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
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
        screen.addPreference(hostSelection)
        screen.addPreference(videoQualityPref)
    }
}
