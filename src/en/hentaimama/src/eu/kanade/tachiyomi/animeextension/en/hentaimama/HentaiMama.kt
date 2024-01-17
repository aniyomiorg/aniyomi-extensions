package eu.kanade.tachiyomi.animeextension.en.hentaimama

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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class HentaiMama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "HentaiMama"

    override val baseUrl = "https://hentaimama.io"

    override val lang = "en"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", baseUrl)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "article.tvshows"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/advance-search/page/$page/?submit=Submit&filter=weekly")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination-wraper div.resppages a"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response)
    }

    override fun episodeListSelector() = "div.series div.items article"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val date = SimpleDateFormat("MMM. dd, yyyy", Locale.US).parse(element.select("div.data > span").text())
        val epNumPattern = Regex("Episode (\\d+\\.?\\d*)")
        val epNumMatch = epNumPattern.find(element.select("div.season_m a span.c").text())

        episode.setUrlWithoutDomain(element.select("div.season_m a").attr("href"))
        episode.name = element.select("div.data h3").text()
        episode.date_upload = runCatching { date?.time }.getOrNull() ?: 0L
        episode.episode_number = runCatching { epNumMatch?.groups?.get(1)!!.value.toFloat() }.getOrNull() ?: 1F

        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        // POST body data
        val body = FormBody.Builder()
            .add("action", "get_player_contents")
            .add(
                "a",
                document.selectFirst("#post_report input:nth-child(5)")?.attr("value").toString(),
            )
            .build()

        // Call POST
        val newHeaders = Headers.headersOf("referer", "$baseUrl/")

        val listOfiFrame = client.newCall(
            POST("$baseUrl/wp-admin/admin-ajax.php", newHeaders, body),
        )
            .execute().asJsoup()
            .body().select("iframe").toString()

        val regex = Regex("https?[\\S][^\"]+")
        val allLinks = regex.findAll(listOfiFrame)
        val urls = allLinks.map { it.value }.toList()

        val videoRegex = Regex("(https:[^\"]+\\.mp4*)")

        val videoList = mutableListOf<Video>()

        for (url in urls) {
            val req = client.newCall(GET(url)).execute().asJsoup()
                .body().toString()

            val videoLink = videoRegex.find(req)
            val videoRes = when {
                url.contains("newr2") -> "Beta"
                url.contains("new1") -> "Mirror 1"
                url.contains("new2") -> "Mirror 2"
                url.contains("new3") -> "Mirror 3"
                else -> ""
            }

            if (videoLink != null) {
                videoList.add(Video(videoLink.value, videoRes, videoLink.value))
            }
        }

        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

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

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search
    private var filterSearch = false

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        if (filterSearch) {
            // filter search
            anime.setUrlWithoutDomain(element.select("a").attr("href"))
            anime.title = element.select("div.data h3 a").text()
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
            return anime
        } else {
            // normal search
            anime.setUrlWithoutDomain(element.select("div.details > div.title a").attr("href"))
            anime.thumbnail_url = element.select("div.image div a img").attr("src")
            anime.title = element.select("div.details > div.title a").text()
            return anime
        }
    }

    override fun searchAnimeNextPageSelector(): String {
        return if (filterSearch) {
            "div.pagination-wraper div.resppages a" // filter search
        } else {
            "link[rel=next]" // normal search
        }
    }

    override fun searchAnimeSelector(): String = "article"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            filterSearch = false
            GET("$baseUrl/page/$page/?s=${query.replace(Regex("[\\W]"), " ")}") // regular search
        } else {
            filterSearch = true
            GET("$baseUrl/advance-search/page/$page/?$parameters") // filter search
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.sheader div.poster img")!!.attr("data-src")
        anime.title = document.select("#info1 div:nth-child(2) span").text()
        anime.genre = document.select("div.sheader  div.data  div.sgeneros a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("#info1 div.wp-content p").text()
        anime.author = document.select("#info1 div:nth-child(3) span div  div a")
            .joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("#info1 div:nth-child(6) span").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = "article.tvshows"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/tvshows/page/$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "link[rel=next]"

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("Mirror 2")
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

    // Filters

    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)
    private fun getGenres() = listOf(
        Genre("3D"),
        Genre("Action"),
        Genre("Adventure"),
        Genre("Ahegao"),
        Genre("Anal"),
        Genre("Animal Ears"),
        Genre("Beastiality"),
        Genre("Blackmail"),
        Genre("Blowjob"),
        Genre("Bondage"),
        Genre("Brainwashed"),
        Genre("Bukakke"),
        Genre("Cat Girl"),
        Genre("Comedy"),
        Genre("Cosplay"),
        Genre("Creampie"),
        Genre("Cross-dressing"),
        Genre("Dark Skin"),
        Genre("DeepThroat"),
        Genre("Demons"),
        Genre("Doctor"),
        Genre("Double Penatration"),
        Genre("Drama"),
        Genre("Dubbed"),
        Genre("Ecchi"),
        Genre("Elf"),
        Genre("Eroge"),
        Genre("Facesitting"),
        Genre("Facial"),
        Genre("Fantasy"),
        Genre("Female Doctor"),
        Genre("Female Teacher"),
        Genre("Femdom"),
        Genre("Footjob"),
        Genre("Futanari"),
        Genre("Gangbang"),
        Genre("Gore"),
        Genre("Gyaru"),
        Genre("Harem"),
        Genre("Historical"),
        Genre("Horny Slut"),
        Genre("Housewife"),
        Genre("Humiliation"),
        Genre("Incest"),
        Genre("Inflation"),
        Genre("Internal Cumshot"),
        Genre("Lactation"),
        Genre("Large Breasts"),
        Genre("Lolicon"),
        Genre("Magical Girls"),
        Genre("Maid"),
        Genre("Martial Arts"),
        Genre("Megane"),
        Genre("MILF"),
        Genre("Mind Break"),
        Genre("Molestation"),
        Genre("Non-Japanese"),
        Genre("NTR"),
        Genre("Nuns"),
        Genre("Nurses"),
        Genre("Office Ladies"),
        Genre("Police"),
        Genre("POV"),
        Genre("Pregnant"),
        Genre("Princess"),
        Genre("Public Sex"),
        Genre("Rape"),
        Genre("Rim job"),
        Genre("Romance"),
        Genre("Scat"),
        Genre("School Girls"),
        Genre("Sci-Fi"),
        Genre("Shimapan"),
        Genre("Short"),
        Genre("Shoutacon"),
        Genre("Slaves"),
        Genre("Sports"),
        Genre("Squirting"),
        Genre("Stocking"),
        Genre("Strap-on"),
        Genre("Strapped On"),
        Genre("Succubus"),
        Genre("Super Power"),
        Genre("Supernatural"),
        Genre("Swimsuit"),
        Genre("Tentacles"),
        Genre("Three some"),
        Genre("Tits Fuck"),
        Genre("Torture"),
        Genre("Toys"),
        Genre("Train Molestation"),
        Genre("Tsundere"),
        Genre("Uncensored"),
        Genre("Urination"),
        Genre("Vampire"),
        Genre("Vanilla"),
        Genre("Virgins"),
        Genre("Widow"),
        Genre("X-Ray"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Year", years)
    private fun getYears() = listOf(
        Year("2022"),
        Year("2021"),
        Year("2020"),
        Year("2019"),
        Year("2018"),
        Year("2017"),
        Year("2016"),
        Year("2015"),
        Year("2014"),
        Year("2013"),
        Year("2012"),
        Year("2011"),
        Year("2010"),
        Year("2009"),
        Year("2008"),
        Year("2007"),
        Year("2006"),
        Year("2005"),
        Year("2004"),
        Year("2003"),
        Year("2002"),
        Year("2001"),
        Year("2000"),
        Year("1999"),
        Year("1998"),
        Year("1997"),
        Year("1996"),
        Year("1995"),
        Year("1994"),
        Year("1993"),
        Year("1992"),
        Year("1991"),
        Year("1987"),
    )

    internal class Producer(val id: String) : AnimeFilter.CheckBox(id)
    private class ProducerList(producers: List<Producer>) : AnimeFilter.Group<Producer>("Producer", producers)
    private fun getProducer() = listOf(
        Producer("8bit"),
        Producer("Actas"),
        Producer("Active"),
        Producer("AIC"),
        Producer("AIC A.S.T.A."),
        Producer("Alice Soft"),
        Producer("An DerCen"),
        Producer("Angelfish"),
        Producer("Animac"),
        Producer("AniMan"),
        Producer("Animax"),
        Producer("Antechinus"),
        Producer("APPP"),
        Producer("Armor"),
        Producer("Arms"),
        Producer("Asahi Production"),
        Producer("AT-2"),
        Producer("Blue Eyes"),
        Producer("BOMB! CUTE! BOMB!"),
        Producer("BOOTLEG"),
        Producer("Bunnywalker"),
        Producer("Central Park Media"),
        Producer("CherryLips"),
        Producer("ChiChinoya"),
        Producer("Chippai"),
        Producer("ChuChu"),
        Producer("Circle Tribute"),
        Producer("CLOCKUP"),
        Producer("Collaboration Works"),
        Producer("Comic Media"),
        Producer("Cosmic Ray"),
        Producer("Cosmo"),
        Producer("Cotton Doll"),
        Producer("Cranberry"),
        Producer("D3"),
        Producer("Daiei"),
        Producer("Digital Works"),
        Producer("Discovery"),
        Producer("Dream Force"),
        Producer("Dubbed"),
        Producer("Easy Film"),
        Producer("Echo"),
        Producer("EDGE"),
        Producer("Filmlink International"),
        Producer("Five Ways"),
        Producer("Front Line"),
        Producer("Frontier Works"),
        Producer("Godoy"),
        Producer("Gold Bear"),
        Producer("Green Bunny"),
        Producer("Himajin Planning"),
        Producer("Hokiboshi"),
        Producer("Hoods Entertainment"),
        Producer("Horipro"),
        Producer("Hot Bear"),
        Producer("HydraFXX"),
        Producer("Innocent Grey"),
        Producer("Jam"),
        Producer("JapanAnime"),
        Producer("King Bee"),
        Producer("Kitty Films"),
        Producer("Kitty Media"),
        Producer("Knack Productions"),
        Producer("KSS"),
        Producer("Lemon Heart"),
        Producer("Lune Pictures"),
        Producer("Majin"),
        Producer("Marvelous Entertainment"),
        Producer("Mary Jane"),
        Producer("Media"),
        Producer("Media Blasters"),
        Producer("Milkshake"),
        Producer("Mitsu"),
        Producer("Moonstone Cherry"),
        Producer("Mousou Senka"),
        Producer("MS Pictures"),
        Producer("Nihikime no Dozeu"),
        Producer("Nur"),
        Producer("NuTech Digital"),
        Producer("Obtain Future"),
        Producer("Office Take Off"),
        Producer("OLE-M"),
        Producer("Oriental Light and Magic"),
        Producer("Oz"),
        Producer("Pashmina"),
        Producer("Pink Pineapple"),
        Producer("Pixy"),
        Producer("PoRO"),
        Producer("Production I.G"),
        Producer("Queen Bee"),
        Producer("Sakura Purin Animation"),
        Producer("Schoolzone"),
        Producer("Selfish"),
        Producer("Seven"),
        Producer("Shelf"),
        Producer("Shinkuukan"),
        Producer("Shinyusha"),
        Producer("Shouten"),
        Producer("Silky’s"),
        Producer("Soft Garage"),
        Producer("SoftCel Pictures"),
        Producer("SPEED"),
        Producer("Studio 9 Maiami"),
        Producer("Studio Eromatick"),
        Producer("Studio Fantasia"),
        Producer("Studio Jack"),
        Producer("Studio Kyuuma"),
        Producer("Studio Matrix"),
        Producer("Studio Sign"),
        Producer("Studio Tulip"),
        Producer("Studio Unicorn"),
        Producer("Suzuki Mirano"),
        Producer("T-Rex"),
        Producer("The Right Stuf International"),
        Producer("Toho Company"),
        Producer("Top-Marschal"),
        Producer("Toranoana"),
        Producer("Toshiba Entertainment"),
        Producer("Triangle Bitter"),
        Producer("Triple X"),
        Producer("Union Cho"),
        Producer("Valkyria"),
        Producer("White Bear"),
        Producer("Y.O.U.C"),
        Producer("ZIZ Entertainment"),
        Producer("Zyc"),

    )

    private data class Order(val name: String, val id: String)
    private class OrderList(Orders: Array<String>) : AnimeFilter.Select<String>("Order", Orders)
    private val orderName = getOrder().map {
        it.name
    }.toTypedArray()
    private fun getOrder() = listOf(
        Order("Weekly Views", "weekly"),
        Order("Monthly Views", "monthly"),
        Order("Alltime Views", "alltime"),
        Order("A-Z", "alphabet"),
        Order("Rating", "rating"),

    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var sortBy = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring =
                                totalstring + "&genres_filter%5B" + "%5D=" + Genre.id
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring =
                                totalstring + "&years_filter%5B" + "%5D=" + Year.id
                        }
                    }
                }

                is ProducerList -> { // ---Producer
                    filter.state.forEach { Producer ->
                        if (Producer.state) {
                            totalstring =
                                totalstring + "&studios_filter%5B" + "%5D=" + Producer.id
                        }
                    }
                }
                is OrderList -> { // ---Order
                    sortBy = getOrder()[filter.state].id
                }

                else -> {}
            }
        }

        return "$totalstring&submit=Submit&filter=$sortBy"
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        AnimeFilter.Separator(),
        OrderList(orderName),
        GenreList(getGenres()),
        YearList(getYears()),
        ProducerList(getProducer()),
    )

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("Mirror 1", "Mirror 2", "Mirror 3", "Beta")
    }
}
