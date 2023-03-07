package eu.kanade.tachiyomi.animeextension.it.animelove

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
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeLove : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeLove"

    override val baseUrl = "https://www.animelove.tv"

    override val lang = "it"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-in-corso/page/$page/")

    override fun popularAnimeSelector(): String = "div.containerlista > div.row > div.col-6"

    override fun popularAnimeNextPageSelector(): String = "div > ul.page-nav > li:last-child:not(:has(a.disabled))"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img")?.attr("src")
            title = element.selectFirst("div.default-text")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/nuovi-anime/page/$page/")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val letterFilter = filterList.find { it is LetterFilter } as LetterFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/cerca?q=$query")
            genreFilter.state != 0 -> GET("$baseUrl/genere/${genreFilter.toUriPart()}/page/$page/")
            letterFilter.state != 0 -> {
                val slug = if (page == 1) "/lista-anime?alphabet=${letterFilter.toUriPart()}" else "/lista-anime/page/$page/${letterFilter.toUriPart()}/"
                GET(baseUrl + slug)
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.encodedPath != "/cerca") {
            return super.searchAnimeParse(response)
        }

        val document = response.asJsoup()

        val animes = document.select(searchAnimeSelectorSearch()).map { element ->
            searchAnimeFromElementSearch(element)
        }

        return AnimesPage(animes, false)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun searchAnimeSelectorSearch(): String = "div.col-md-8 > div.card > div.card-body > div.row > div.col-6"

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    private fun searchAnimeFromElementSearch(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img")?.attr("src")
            title = element.selectFirst("p.card-text")!!.text()
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La ricerca testuale ignora i filtri"),
        GenreFilter(),
        LetterFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Generi",
        arrayOf(
            Pair("<Selezionare>", ""),
            Pair("Action", "Action"),
            Pair("Adventure", "Adventure"),
            Pair("Avant GardeAvant Garde", "Avant-GardeAvant-Garde"),
            Pair("Award WiningAward Wining", "Award-WiningAward-Wining"),
            Pair("Bender", "Bender"),
            Pair("Boys LoveBoys Love", "Boys-LoveBoys-Love"),
            Pair("Cars", "Cars"),
            Pair("Comedy", "Comedy"),
            Pair("Dantasy", "Dantasy"),
            Pair("Dementia", "Dementia"),
            Pair("Demons", "Demons"),
            Pair("Detective", "Detective"),
            Pair("Drama", "Drama"),
            Pair("Drammatico", "Drammatico"),
            Pair("Ecchi", "Ecchi"),
            Pair("Erotic", "Erotic"),
            Pair("Erotica", "Erotica"),
            Pair("Fantasy", "Fantasy"),
            Pair("Fighting", "Fighting"),
            Pair("Game", "Game"),
            Pair("Gender", "Gender"),
            Pair("Girls LoveGirls Love", "Girls-LoveGirls-Love"),
            Pair("Goofy", "Goofy"),
            Pair("Gourmet", "Gourmet"),
            Pair("Harem", "Harem"),
            Pair("Hentai", "Hentai"),
            Pair("Historical", "Historical"),
            Pair("Horror", "Horror"),
            Pair("Josei", "Josei"),
            Pair("Kids", "Kids"),
            Pair("Lifestyle", "Lifestyle"),
            Pair("Lolicon", "Lolicon"),
            Pair("Magic", "Magic"),
            Pair("Martial Arts", "Martial-Arts"),
            Pair("Mecha", "Mecha"),
            Pair("Military", "Military"),
            Pair("Music", "Music"),
            Pair("Mystery", "Mystery"),
            Pair("N/A", "A"),
            Pair("null", "null"),
            Pair("Parody", "Parody"),
            Pair("Police", "Police"),
            Pair("Psychological", "Psychological"),
            Pair("Romance", "Romance"),
            Pair("SAction", "SAction"),
            Pair("Samurai", "Samurai"),
            Pair("School", "School"),
            Pair("Sci-fi", "Sci-fi"),
            Pair("Sci-FiSci-Fi", "Sci-FiSci-Fi"),
            Pair("Seinen", "Seinen"),
            Pair("Sentimental", "Sentimental"),
            Pair("Shoujo", "Shoujo"),
            Pair("Shoujo Ai", "Shoujo-Ai"),
            Pair("Shounen", "Shounen"),
            Pair("Shounen Ai", "Shounen-Ai"),
            Pair("Slice of Life", "Slice-of-Life"),
            Pair("Slice of LifeSlice of Life", "Slice-of-LifeSlice-of-Life"),
            Pair("Smut", "Smut"),
            Pair("Space", "Space"),
            Pair("Splatter", "Splatter"),
            Pair("Sport", "Sport"),
            Pair("Super Power", "Super-Power"),
            Pair("Supernatural", "Supernatural"),
            Pair("Suspense", "Suspense"),
            Pair("Tamarro", "Tamarro"),
            Pair("Thriller", "Thriller"),
            Pair("Vampire", "Vampire"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "Yuri"),
        ),
    )

    private class LetterFilter : UriPartFilter(
        "Lettera",
        arrayOf(
            Pair("<Selezionare>", ""),
            Pair("A", "A"),
            Pair("B", "B"),
            Pair("C", "C"),
            Pair("D", "D"),
            Pair("E", "E"),
            Pair("F", "F"),
            Pair("G", "G"),
            Pair("H", "H"),
            Pair("I", "I"),
            Pair("J", "J"),
            Pair("K", "K"),
            Pair("L", "L"),
            Pair("M", "M"),
            Pair("N", "N"),
            Pair("O", "O"),
            Pair("P", "P"),
            Pair("Q", "Q"),
            Pair("R", "R"),
            Pair("S", "S"),
            Pair("T", "T"),
            Pair("U", "U"),
            Pair("V", "V"),
            Pair("W", "W"),
            Pair("X", "X"),
            Pair("Y", "Y"),
            Pair("Z", "Z"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val moreInfo = (document.selectFirst("div.card-body > p:contains(TIPO:)")?.text() ?: "") +
            "\n" +
            (document.selectFirst("div.card-body > p:contains(ANNO)")?.text() ?: "")

        return SAnime.create().apply {
            title = document.selectFirst("div.card-body > p:contains(TITOLO:)")?.ownText() ?: ""
            thumbnail_url = document.selectFirst("div.card-body > div > img")?.attr("src") ?: ""
            author = document.selectFirst("div.card-body > p:contains(STUDIO:)")?.ownText() ?: ""
            status = document.selectFirst("div.card-body > p:contains(STATO:)")?.let {
                parseStatus(it.ownText())
            } ?: SAnime.UNKNOWN
            description = (document.selectFirst("div.card-body > p:contains(TRAMA:) ~ p")?.text() ?: "") + "\n\n$moreInfo"
            genre = document.selectFirst("div.card-body > p:contains(GENERI:)")?.ownText() ?: ""
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector(): String = "div.card ul.page-nav-list-episodi > li"

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            name = "Episodi ${element.text()}"
            episode_number = element.selectFirst("span")?.text()?.toFloatOrNull() ?: 0F
            setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath)
        }
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val videosREGEX = Regex("""\('\.video-container'\)\.append\((.*?)\);""")

        val script = document.selectFirst("script:containsData(.video-container)")
        if (script == null) {
            val video = document.selectFirst("div.video-container source")?.attr("src")
            if (video != null) {
                videoList.add(extractAnimeLove(video))
            }
        } else {
            videosREGEX.findAll(script.data()).forEach { videoSource ->
                val url = videoSource.groupValues[1].substringAfter("src=\"").substringBefore("\"")
                when {
                    url.contains("animelove.tv") -> {
                        videoList.add(extractAnimeLove(url))
                    }
                    url.contains("streamtape") -> {
                        StreamTapeExtractor(client).videoFromUrl(url)?.let {
                            videoList.add(it)
                        }
                    }
                }
            }
        }

        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun extractAnimeLove(videoUrl: String): Video {
        val redirected = client.newCall(GET(videoUrl)).execute().request
        val videoHeaders = headers.newBuilder()
            .add("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
            .add("Host", redirected.url.host)
            .build()

        return Video(
            redirected.url.toString(),
            "AnimeLove",
            redirected.url.toString(),
            headers = videoHeaders,
        )
    }

    override fun List<Video>.sort(): List<Video> {
        val server = preferences.getString("preferred_server", "AnimeLove")!!

        return this.sortedWith(
            compareBy { it.quality.contains(server, true) },
        ).reversed()
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "In Corso" -> SAnime.ONGOING
            "Terminato" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("AnimeLove", "StreamTape")
            entryValues = arrayOf("AnimeLove", "StreamTape")
            setDefaultValue("AnimeLove")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoServerPref)
    }
}
