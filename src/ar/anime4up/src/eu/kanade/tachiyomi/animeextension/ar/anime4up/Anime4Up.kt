package eu.kanade.tachiyomi.animeextension.ar.anime4up

import android.app.Application
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.VidYardExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.vidbomextractor.VidBomExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
import java.util.Base64

class Anime4Up : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime4Up"

    override val baseUrl = "https://w1.anime4up.tv"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://w1.anime4up.tv/") // https://s12.gemzawy.com https://moshahda.net
    }

    // Popular

    override fun popularAnimeSelector(): String = "div.anime-list-content div.row div.col-lg-2"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime-list-3/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("div.anime-card-poster div.ehover6 img").attr("src")
        anime.setUrlWithoutDomain(element.select("div.anime-card-poster div.ehover6 a").attr("href"))
        anime.title = element.select("div.anime-card-poster div.ehover6 img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li a.next"

    // episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.episodes-list-content div#DivEpisodesList div.col-md-3" // "ul.episodes-links li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("div.episodes-card-container div.episodes-card div.ehover6 h3 a").text())
        episode.setUrlWithoutDomain(element.select("div.episodes-card-container div.episodes-card div.ehover6 h3 a").attr("href"))
        // episode.episode_number = element.select("span:nth-child(3)").text().replace(" - ", "").toFloat()
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("div.episodes-card-container div.episodes-card div.ehover6 h3 a").text()

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video links

    @RequiresApi(Build.VERSION_CODES.O)
    override fun videoListParse(response: Response): List<Video> {
        val base64 = response.asJsoup().select("input[name=wl]").attr("value")
        val jHash = String(Base64.getDecoder().decode(base64))
        val parsedJ = json.decodeFromString<JsonObject>(jHash)
        val streamLinks = parsedJ["fhd"]!!.jsonObject.entries + parsedJ["hd"]!!.jsonObject.entries + parsedJ["sd"]!!.jsonObject.entries
        return streamLinks.distinctBy { it.key }.parallelMap {
            val url = it.value.toString().replace("\"", "")
            runCatching { extractVideos(url) }.getOrElse { emptyList() }
        }.flatten()
    }

    private inline fun <A, B> Iterable<A>.parallelMap(crossinline f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
    private fun extractVideos(url: String): List<Video> {
        return when {
            url.contains("shared") -> {
                SharedExtractor(client).videosFromUrl(url)?.let(::listOf)
            }
            url.contains("drive.google") -> {
                val embedUrlG = "https://gdriveplayer.to/embed2.php?link=$url"
                GdrivePlayerExtractor(client).videosFromUrl(embedUrlG)
            }
            url.contains("vidyard") -> {
                val headers = headers.newBuilder()
                    .set("Referer", "https://play.vidyard.com")
                    .set("Accept-Encoding", "gzip, deflate, br")
                    .set("Accept-Language", "en-US,en;q=0.5")
                    .set("TE", "trailers")
                    .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                    .build()
                val id = url.substringAfter("com/").substringBefore("?")
                val vidUrl = "https://play.vidyard.com/player/$id.json"
                VidYardExtractor(client).videosFromUrl(vidUrl, headers)
            }
            url.contains("ok.ru") -> {
                OkruExtractor(client).videosFromUrl(url)
            }
            url.contains("voe") -> {
                VoeExtractor(client).videoFromUrl(url)?.let(::listOf)
            }
            DOOD_REGEX.containsMatchIn(url) -> {
                DoodExtractor(client).videoFromUrl(url, "Dood mirror")?.let(::listOf)
            }
            VIDBOM_REGEX.containsMatchIn(url) -> {
                val finalUrl = VIDBOM_REGEX.find(url)!!.groupValues[0]
                VidBomExtractor(client).videosFromUrl("https://www.$finalUrl.html")
            }
            STREAMWISH_REGEX.containsMatchIn(url)  -> {
                val headers = headers.newBuilder()
                    .set("Referer", url)
                    .set("Accept-Encoding", "gzip, deflate, br")
                    .set("Accept-Language", "en-US,en;q=0.5")
                    .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                    .build()
                val finalUrl = STREAMWISH_REGEX.find(url)!!.groupValues[0]
                StreamWishExtractor(client).videosFromUrl("https://www.$finalUrl", headers)
            }
            STREAMSB_REGEX.containsMatchIn(url) -> {
                StreamSBExtractor(client).videosFromUrl(url, headers)
            }
            else -> null
        } ?: emptyList()
    }
    // override fun videoListSelector() = "script:containsData(m3u8)"
    override fun videoListSelector() = "li[data-i] a"

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

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = element.select("div.anime-card-container div.anime-card-poster div.ehover6 img").attr("src")
        anime.setUrlWithoutDomain(element.select("div.anime-card-container div.anime-card-poster div.ehover6 a").attr("href"))
        anime.title = element.select("div.anime-card-container div.anime-card-poster div.ehover6 img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination li a.next"

    override fun searchAnimeSelector(): String = "div.anime-list-content div.row.display-flex div.col-md-4"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/?search_param=animes&s=$query"
        } else {
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is GenreList -> {
                        if (filter.state > 0) {
                            val genreN = getGenreList()[filter.state].query
                            val genreUrl = "$baseUrl/anime-genre/$genreN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(genreUrl.toString(), headers)
                        }
                    }
                    is StatusList -> {
                        if (filter.state > 0) {
                            val statusN = getStatusList()[filter.state].query
                            val statusUrl = "$baseUrl/anime-status/$statusN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(statusUrl.toString(), headers)
                        }
                    }
                    is TypeList -> {
                        if (filter.state > 0) {
                            val typeN = getTypeList()[filter.state].query
                            val typeUrl = "$baseUrl/anime-type/$typeN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(typeUrl.toString(), headers)
                        }
                    }
                    else -> {}
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("img.thumbnail")!!.attr("src")
        anime.title = document.select("h1.anime-details-title").text()
        anime.genre = document.select("ul.anime-genres > li > a, div.anime-info > a").joinToString(", ") { it.text() }
        anime.description = document.select("p.anime-story").text()
        document.select("div.anime-info a").text().also { statusText ->
            when {
                statusText.contains("يعرض الان", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }

        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "DOODStream")
            entryValues = arrayOf("1080", "720", "480", "360", "dood")
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

    // Filter

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("الفلترات مش هتشتغل لو بتبحث او وهي فاضيه"),
        GenreList(genresName),
        TypeList(typesName),
        StatusList(statusesName),
    )

    private class GenreList(genres: Array<String>) : AnimeFilter.Select<String>("تصنيف الأنمي", genres)
    private data class Genre(val name: String, val query: String)
    private val genresName = getGenreList().map {
        it.name
    }.toTypedArray()

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("نوع الأنمي", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private class StatusList(statuse: Array<String>) : AnimeFilter.Select<String>("حالة الأنمي", statuse)
    private data class Status(val name: String, val query: String)
    private val statusesName = getStatusList().map {
        it.name
    }.toTypedArray()

    private fun getGenreList() = listOf(
        Genre("اختر", ""),
        Genre("أطفال", "%d8%a3%d8%b7%d9%81%d8%a7%d9%84"),
        Genre("أكشن", "%d8%a3%d9%83%d8%b4%d9%86/"),
        Genre("إيتشي", "%d8%a5%d9%8a%d8%aa%d8%b4%d9%8a/"),
        Genre("اثارة", "%d8%a7%d8%ab%d8%a7%d8%b1%d8%a9/"),
        Genre("العاب", "%d8%a7%d9%84%d8%b9%d8%a7%d8%a8/"),
        Genre("بوليسي", "%d8%a8%d9%88%d9%84%d9%8a%d8%b3%d9%8a/"),
        Genre("تاريخي", "%d8%aa%d8%a7%d8%b1%d9%8a%d8%ae%d9%8a/"),
        Genre("جنون", "%d8%ac%d9%86%d9%88%d9%86/"),
        Genre("جوسي", "%d8%ac%d9%88%d8%b3%d9%8a/"),
        Genre("حربي", "%d8%ad%d8%b1%d8%a8%d9%8a/"),
        Genre("حريم", "%d8%ad%d8%b1%d9%8a%d9%85/"),
        Genre("خارق للعادة", "%d8%ae%d8%a7%d8%b1%d9%82-%d9%84%d9%84%d8%b9%d8%a7%d8%af%d8%a9/"),
        Genre("خيال علمي", "%d8%ae%d9%8a%d8%a7%d9%84-%d8%b9%d9%84%d9%85%d9%8a/"),
        Genre("دراما", "%d8%af%d8%b1%d8%a7%d9%85%d8%a7/"),
        Genre("رعب", "%d8%b1%d8%b9%d8%a8/"),
        Genre("رومانسي", "%d8%b1%d9%88%d9%85%d8%a7%d9%86%d8%b3%d9%8a/"),
        Genre("رياضي", "%d8%b1%d9%8a%d8%a7%d8%b6%d9%8a/"),
        Genre("ساموراي", "%d8%b3%d8%a7%d9%85%d9%88%d8%b1%d8%a7%d9%8a/"),
        Genre("سحر", "%d8%b3%d8%ad%d8%b1/"),
        Genre("سينين", "%d8%b3%d9%8a%d9%86%d9%8a%d9%86/"),
        Genre("شريحة من الحياة", "%d8%b4%d8%b1%d9%8a%d8%ad%d8%a9-%d9%85%d9%86-%d8%a7%d9%84%d8%ad%d9%8a%d8%a7%d8%a9/"),
        Genre("شوجو", "%d8%b4%d9%88%d8%ac%d9%88/"),
        Genre("شوجو اَي", "%d8%b4%d9%88%d8%ac%d9%88-%d8%a7%d9%8e%d9%8a/"),
        Genre("شونين", "%d8%b4%d9%88%d9%86%d9%8a%d9%86/"),
        Genre("شونين اي", "%d8%b4%d9%88%d9%86%d9%8a%d9%86-%d8%a7%d9%8a/"),
        Genre("شياطين", "%d8%b4%d9%8a%d8%a7%d8%b7%d9%8a%d9%86/"),
        Genre("غموض", "%d8%ba%d9%85%d9%88%d8%b6/"),
        Genre("فضائي", "%d9%81%d8%b6%d8%a7%d8%a6%d9%8a/"),
        Genre("فنتازيا", "%d9%81%d9%86%d8%aa%d8%a7%d8%b2%d9%8a%d8%a7/"),
        Genre("فنون قتالية", "%d9%81%d9%86%d9%88%d9%86-%d9%82%d8%aa%d8%a7%d9%84%d9%8a%d8%a9/"),
        Genre("قوى خارقة", "%d9%82%d9%88%d9%89-%d8%ae%d8%a7%d8%b1%d9%82%d8%a9/"),
        Genre("كوميدي", "%d9%83%d9%88%d9%85%d9%8a%d8%af%d9%8a/"),
        Genre("محاكاة ساخرة", "%d9%85%d8%ad%d8%a7%d9%83%d8%a7%d8%a9-%d8%b3%d8%a7%d8%ae%d8%b1%d8%a9/"),
        Genre("مدرسي", "%d9%85%d8%af%d8%b1%d8%b3%d9%8a/"),
        Genre("مصاصي دماء", "%d9%85%d8%b5%d8%a7%d8%b5%d9%8a-%d8%af%d9%85%d8%a7%d8%a1/"),
        Genre("مغامرات", "%d9%85%d8%ba%d8%a7%d9%85%d8%b1%d8%a7%d8%aa/"),
        Genre("موسيقي", "%d9%85%d9%88%d8%b3%d9%8a%d9%82%d9%8a/"),
        Genre("ميكا", "%d9%85%d9%8a%d9%83%d8%a7/"),
        Genre("نفسي", "%d9%86%d9%81%d8%b3%d9%8a/"),
    )

    private fun getTypeList() = listOf(
        Type("أختر", ""),
        Type("Movie", "movie-3"),
        Type("ONA", "ona1"),
        Type("OVA", "ova1"),
        Type("Special", "special1"),
        Type("TV", "tv2"),

        )

    private fun getStatusList() = listOf(
        Status("أختر", ""),
        Status("لم يعرض بعد", "%d9%84%d9%85-%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a8%d8%b9%d8%af"),
        Status("مكتمل", "complete"),
        Status("يعرض الان", "%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a7%d9%84%d8%a7%d9%86-1"),

        )
    companion object {
        private val VIDBOM_REGEX = Regex("(?:v[aie]d[bp][aoe]?m|myvii?d|segavid|v[aei]{1,2}dshar[er]?)\\.(?:com|net|org|xyz)(?::\\d+)?/(?:embed[/-])?([A-Za-z0-9]+)")
        private val STREAMSB_REGEX = Regex("(?:view|watch|embed(?:tv)?|tube|player|cloudemb|japopav|javplaya|p1ayerjavseen|gomovizplay|stream(?:ovies)?|vidmovie|javside|aintahalu|finaltayibin|yahlusubh|taeyabathuna|)?s{0,2}b?(?:embed\\d?|play\\d?|video|fast|full|streams{0,3}|the|speed|l?anh|tvmshow|longvu|arslanrocky|chill|rity|hight|brisk|face|lvturbo|net|one|asian|ani|rapid|sonic|lona)?\\.(?:com|net|org|one|tv|xyz|fun|pro|sbs)")
        private val DOOD_REGEX = Regex("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)")
        private val STREAMWISH_REGEX = Regex("((?:streamwish|anime7u|animezd|ajmidyad|khadhnayad|yadmalik|hayaatieadhab)\\.(?:com|to|sbs))/(?:e/|v/|f/)?([0-9a-zA-Z]+)")
    }
}
