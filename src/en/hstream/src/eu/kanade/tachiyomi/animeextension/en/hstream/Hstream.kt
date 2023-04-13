package eu.kanade.tachiyomi.animeextension.en.hstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class Hstream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Hstream"

    override val baseUrl = "https://hstream.moe"

    override val lang = "en"

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

    override fun popularAnimeSelector(): String = "div.soralist  div  ul  li a.series"

    override fun popularAnimeRequest(page: Int): Request =
        GET("$baseUrl/hentai/list?")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.select("a.series").attr("href"))
        anime.title = element.select("a.series").text()
        anime.thumbnail_url = baseUrl + "/images" + element.select("a.series").attr("href") + "/cover.webp"
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a[rel=next]"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.eplister ul li"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.select("div.epl-num").text()

        episode.setUrlWithoutDomain(baseUrl + element.select("a").attr("href"))
        episode.name = "Episode - $epNum"
        episode.date_upload = SimpleDateFormat("yyyy-mm-dd", Locale.US).parse(element.select("div.epl-date").text()).time
        episode.episode_number = epNum.toFloat()

        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val regex = Regex("https?:\\/\\/[^\\s][^']+")
        val allLinks = regex.findAll(document.select("body script:nth-last-child(2)").toString())
        val urls = allLinks.map { it.value }.toList()

        val videoUrls = mutableListOf<String>()
        val subtitleUrls = mutableListOf<Track>()

        val languageMap = mapOf(
            "eng" to "English",
        )

        for (url in urls) {
            if (url.endsWith(".webm") || url.endsWith(".mp4")) {
                videoUrls.add(url)
            } else if (url.endsWith(".ass")) {
                try {
                    val subName = url.split("/").last().split(".").first().toString()
                    subtitleUrls.add(Track(url, languageMap[subName].toString()))
                } catch (e: Error) {}
            }
        }

        val sub = subLangOrder(subtitleUrls)

        val newHeaders = Headers.Builder()
            .add("Accept", "*/*")
            .add("Accept-Encoding", "identity;q=1, *;q=0")
            .add("Accept-Language", "en-US,en;q=0.9")
            .add("DNT", "1")
            .add("Referer", "$baseUrl/")
            .add("Sec-Fetch-Dest", "video")
            .add("Sec-Fetch-Mode", "no-cors")
            .add("Sec-Fetch-Site", "cross-site")
            .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Safari/537.36")
            .build()

        val videoList = mutableListOf<Video>()

        for (video in videoUrls) {
            val resolution = video.split('.')
            try {
                videoList.add(Video(video, resolution.get(resolution.size - 2), video, headers = newHeaders, subtitleTracks = sub))
            } catch (e: Error) {
                videoList.add(Video(video, resolution.get(resolution.size - 2), video, headers = newHeaders))
            }
        }

        return videoList
    }

    private fun subLangOrder(tracks: List<Track>): List<Track> {
        val language = preferences.getString(PREF_SUB_KEY, null)
        if (language != null) {
            val newList = mutableListOf<Track>()
            var preferred = 0
            for (track in tracks) {
                if (track.lang == language) {
                    newList.add(preferred, track)
                    preferred++
                } else {
                    newList.add(track)
                }
            }
            return newList
        }
        return tracks
    }

    override fun videoListSelector() = throw Exception("not used")

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

    // Search
    private var filterSearch = false

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        if (filterSearch) {
            // filter search
            anime.setUrlWithoutDomain(baseUrl + element.select("a.series").attr("href"))
            anime.title = element.select("a.series").text()
            anime.thumbnail_url = baseUrl + "/images" + element.select("a.series").attr("href") + "/cover.webp"
            return anime
        } else {
            // normal search
            anime.setUrlWithoutDomain(baseUrl + element.select("a").attr("href"))
            anime.title = element.select("h2").text()
            anime.thumbnail_url = baseUrl + element.select("img").attr("src")
            return anime
        }
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a[rel=next]"

    override fun searchAnimeSelector(): String {
        return if (filterSearch) {
            "div.soralist div ul li a.series"
        } else {
            "article.bs"
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            filterSearch = false
            GET("$baseUrl/search?s=${query.replace(("[\\.]").toRegex(), "")}") // regular search
        } else {
            filterSearch = true
            GET("$baseUrl/hentai/list?$parameters") // filter search //work
        }
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.selectFirst("div.thumbook div.thumb img")!!.attr("src")
        val title = document.select("div.infox h1").text()
        anime.title = title
        anime.genre = document.select("div.info-content div.genxed a")
            .joinToString(", ") { it.text() }
        anime.description = document.select("div.info-content div.desc").text().substringAfter("Watch $title on HentaiStream.moe in 720p 1080p and (if available) 2160p (4k).").trim()
        anime.author = document.select("div.info-content div.spe span:nth-child(2)  a")
            .joinToString(", ") { it.text() }
        anime.status = parseStatus(document.select("div.info-content div.spe span:nth-child(1)").text().trim().substringAfter("Status: "))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Ongoing" -> SAnime.ONGOING
            else -> SAnime.COMPLETED
        }
    }

    // Latest

    override fun latestUpdatesSelector(): String = "div.soralist  div  ul  li a.series"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/hentai/list?order=latest")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.select("a.series").attr("href"))
        anime.title = element.select("a.series").text()
        anime.thumbnail_url = baseUrl + "/images" + element.select("a.series").attr("href") + "/cover.webp"
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination li a[rel=next]"

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue("720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subLangPref = ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_ENTRIES
            setDefaultValue("English")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(subLangPref)
    }

    // Filters

    internal class Genre(val id: String, val value: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Genre", genres)
    private fun getGenres() = listOf(
        Genre("4K", "4k"),
        Genre("Ahegao", "ahegao"),
        Genre("Anal", "anal"),
        Genre("Bdsm", "bdsm"),
        Genre("Big Boobs", "big-boobs"),
        Genre("Blow Job", "blow-job"),
        Genre("Bondage", "bondage"),
        Genre("Boob Job", "boob-job"),
        Genre("Censored", "censored"),
        Genre("Comedy", "comedy"),
        Genre("Cosplay", "cosplay"),
        Genre("Creampie", "creampie"),
        Genre("Dark Skin", "dark-skin"),
        Genre("Facial", "facial"),
        Genre("Fantasy", "fantasy"),
        Genre("Filmed", "filmed"),
        Genre("Foot Job", "foot-job"),
        Genre("Futanari", "futanari"),
        Genre("Gangbang", "gangbang"),
        Genre("Glasses", "glasses"),
        Genre("Hand Job", "hand-job"),
        Genre("Harem", "harem"),
        Genre("Horror", "horror"),
        Genre("Incest", "incest"),
        Genre("Inflation", "inflation"),
        Genre("Lactation", "lactation"),
        Genre("Loli", "loli"),
        Genre("Maid", "maid"),
        Genre("Masturbation", "masturbation"),
        Genre("Milf", "milf"),
        Genre("Mind Break", "mind-break"),
        Genre("Mind Control", "mind-control"),
        Genre("Monster", "monster"),
        Genre("Nekomimi", "nekomimi"),
        Genre("Ntr", "ntr"),
        Genre("Nurse", "nurse"),
        Genre("Orgy", "orgy"),
        Genre("Pov", "pov"),
        Genre("Pregnant", "pregnant"),
        Genre("Public Sex", "public-sex"),
        Genre("Rape", "rape"),
        Genre("Reverse Rape", "reverse-rape"),
        Genre("Rimjob", "rimjob"),
        Genre("Scat", "scat"),
        Genre("School Girl", "school-girl"),
        Genre("Shota", "shota"),
        Genre("Swim Suit", "swim-suit"),
        Genre("Teacher", "teacher"),
        Genre("Tentacle", "tentacle"),
        Genre("Threesome", "threesome"),
        Genre("Toys", "toys"),
        Genre("Trap", "trap"),
        Genre("Tsundere", "tsundere"),
        Genre("Ugly Bastard", "ugly-bastard"),
        Genre("Uncensored", "uncensored"),
        Genre("Vanilla", "vanilla"),
        Genre("Virgin", "virgin"),
        Genre("X-Ray", "x-ray"),
        Genre("Yuri", "yuri"),
    )

    internal class Studio(val id: String, val value: String) : AnimeFilter.CheckBox(id)
    private class StudioList(studios: List<Studio>) : AnimeFilter.Group<Studio>("Studio", studios)
    private fun getStudio() = listOf(
        Studio("T-Rex", "t-rex"),
        Studio("PoRO", "poro"),
        Studio("Suzuki Mirano", "suzuki-mirano"),
        Studio("Collaboration Works", "collaboration-works"),
        Studio("Majin", "majin"),
        Studio("Pashmina", "pashmina"),
        Studio("Pink Pineapple", "pink-pineapple"),
        Studio("Digital Works", "digital-works"),
        Studio("Studio 1st", "studio-1st"),
        Studio("BOMB! CUTE! BOMB!", "bomb-cute-bomb"),
        Studio("Toranoana", "toranoana"),
        Studio("BreakBottle", "breakbottle"),
        Studio("Lune Pictures", "lune-pictures"),
        Studio("Suiseisha", "suiseisha"),
        Studio("Mary Jane", "mary-jane"),
        Studio("MS Pictures", "ms-pictures"),
        Studio("White Bear", "white-bear"),
        Studio("Queen Bee", "queen-bee"),
        Studio("ChuChu", "chuchu"),
        Studio("Peak Hunt", "peak-hunt"),
        Studio("Nur", "nur"),
        Studio("ZIZ", "ziz"),
        Studio("Mediabank", "mediabank"),
        Studio("Natural High", "natural-high"),
        Studio("Studio Fantasia", "studio-fantasia"),
        Studio("Himajin Planning", "himajin-planning"),
        Studio("Circle Tribute", "circle-tribute"),
        Studio("Studio Eromatick", "studio-eromatick"),
        Studio("Pixy", "pixy"),
        Studio("Showten", "showten"),
        Studio("Mousou Senka", "mousou-senka"),
        Studio("Union Cho", "union-cho"),
        Studio("SELFISH", "selfish"),

    )

    private data class Order(val id: String, val value: String)
    private class OrderList(Orders: Array<String>) : AnimeFilter.Select<String>("Order", Orders)
    private val orderName = getOrder().map {
        it.id
    }.toTypedArray()
    private fun getOrder() = listOf(
        Order("A-Z", "title"),
        Order("Z-A", "titledesc"),
        Order("Latest", "latest"),
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
                                totalstring + "&tags%5B%5D=" + Genre.value
                        }
                    }
                }

                is StudioList -> { // ---Producer
                    filter.state.forEach { Studio ->
                        if (Studio.state) {
                            totalstring =
                                totalstring + "&studios%5B%5D=" + Studio.value
                        }
                    }
                }
                is OrderList -> { // ---Order
                    sortBy = getOrder()[filter.state].value
                }

                else -> {}
            }
        }

        return "?$totalstring&order=$sortBy"
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ignored if using Text Search"),
        AnimeFilter.Separator(),
        OrderList(orderName),
        GenreList(getGenres()),
        StudioList(getStudio()),
    )
    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred video quality"
        private val PREF_QUALITY_ENTRIES = arrayOf("720p", "1080p", "2160p")

        private const val PREF_SUB_KEY = "preferred_subLang"
        private const val PREF_SUB_TITLE = "Preferred sub language"
        private val PREF_SUB_ENTRIES = arrayOf(
            "English",
        )
    }
}
