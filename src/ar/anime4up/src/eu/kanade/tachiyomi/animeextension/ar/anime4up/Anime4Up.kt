package eu.kanade.tachiyomi.animeextension.ar.anime4up

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.MoshahdaExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.VidBomExtractor
import eu.kanade.tachiyomi.animeextension.ar.anime4up.extractors.VidYardExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
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

class Anime4Up : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime4Up"

    override val baseUrl = "https://wc.anime4up.vip"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return super.headersBuilder()
            .add("Referer", "https://wc.anime4up.vip/") // https://s12.gemzawy.com https://moshahda.net
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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val iframe = document.select("iframe").attr("src")
        if (iframe.contains("http")) {
            val referer = response.request.url.encodedPath
            val newHeaders = Headers.headersOf("referer", baseUrl + referer)
            val iframeResponse = client.newCall(GET(iframe, newHeaders))
                .execute().asJsoup()
            return videosFromElement(iframeResponse.selectFirst(videoListSelector()))
        } else {
            val postUrl = document.select("form[method=post]").attr("action")
            val ur = document.select("input[name=ur]").attr("value")
            val wl = document.select("input[name=wl]").attr("value")
            val dl = document.select("input[name=dl]").attr("value")
            val moshahda = document.select("input[name=moshahda]").attr("value")
            val submit = document.select("input[name=submit]").attr("value")
            // POST data
            val body = FormBody.Builder()
                .add("dl", "$dl")
                .add("moshahda", "$moshahda")
                .add("submit", "$submit")
                .add("ur", "$ur")
                .add("wl", "$wl")
                .build()
            // Call POST
            val referer = response.request.url.encodedPath
            val newHeaders = Headers.headersOf("referer", "$postUrl")
            val iframeResponse = client.newCall(POST(postUrl, newHeaders, body)).execute().asJsoup()
            /*val iframe2 = iframe.select("li[data-i=moshahda] a").attr("data-ep-url")
            val iframeResponse = client.newCall(GET(iframe2, newHeaders))
                .execute().asJsoup()*/
            return videosFromElement(iframeResponse)
        }
    }

    // override fun videoListSelector() = "script:containsData(m3u8)"
    override fun videoListSelector() = "li[data-i] a"

    private fun videosFromElement(element: Element): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = element.select(videoListSelector())
        for (element in elements) {
            val location = element.ownerDocument().location()
            val embedUrl = element.attr("data-ep-url")
            val qualityy = element.text()
            Log.i("embedUrl", "$embedUrl")
            when {
                embedUrl.contains("moshahda")
                -> {
                    val headers = headers.newBuilder()
                        .set("referer", "https://gamertak.com/Watch1.php")
                        .build()
                    val videos = MoshahdaExtractor(client).videosFromUrl(embedUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("drive.google")
                -> {
                    val embedUrlG = "https://gdriveplayer.to/embed2.php?link=" + embedUrl
                    val videos = GdrivePlayerExtractor(client).videosFromUrl(embedUrlG)
                    videoList.addAll(videos)
                }
                embedUrl.contains("vidyard")
                -> {
                    val headers = headers.newBuilder()
                        .set("Referer", "https://play.vidyard.com")
                        .set("Accept-Encoding", "gzip, deflate, br")
                        .set("Accept-Language", "en-US,en;q=0.5")
                        .set("TE", "trailers")
                        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                        .build()
                    val id = embedUrl.substringAfter("com/").substringBefore("?")
                    val vidUrl = "https://play.vidyard.com/player/" + id + ".json"
                    val videos = VidYardExtractor(client).videosFromUrl(vidUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
                    embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
                    embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
                    embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
                    embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
                    embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
                    embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
                    embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
                    embedUrl.contains("sblanh.com") || embedUrl.contains("tvmshow.com") || embedUrl.contains("sbanh.com") ||
                    embedUrl.contains("streamovies.xyz")
                -> {
                    val videos = StreamSBExtractor(client).videosFromUrl(embedUrl, headers)
                    videoList.addAll(videos)
                }
                embedUrl.contains("fembed") ||
                    embedUrl.contains("anime789.com") || embedUrl.contains("24hd.club") || embedUrl.contains("fembad.org") ||
                    embedUrl.contains("vcdn.io") || embedUrl.contains("sharinglink.club") || embedUrl.contains("moviemaniac.org") ||
                    embedUrl.contains("votrefiles.club") || embedUrl.contains("femoload.xyz") || embedUrl.contains("albavido.xyz") ||
                    embedUrl.contains("feurl.com") || embedUrl.contains("dailyplanet.pw") || embedUrl.contains("ncdnstm.com") ||
                    embedUrl.contains("jplayer.net") || embedUrl.contains("xstreamcdn.com") || embedUrl.contains("fembed-hd.com") ||
                    embedUrl.contains("gcloud.live") || embedUrl.contains("vcdnplay.com") || embedUrl.contains("superplayxyz.club") ||
                    embedUrl.contains("vidohd.com") || embedUrl.contains("vidsource.me") || embedUrl.contains("cinegrabber.com") ||
                    embedUrl.contains("votrefile.xyz") || embedUrl.contains("zidiplay.com") || embedUrl.contains("ndrama.xyz") ||
                    embedUrl.contains("fcdn.stream") || embedUrl.contains("mediashore.org") || embedUrl.contains("suzihaza.com") ||
                    embedUrl.contains("there.to") || embedUrl.contains("femax20.com") || embedUrl.contains("javstream.top") ||
                    embedUrl.contains("viplayer.cc") || embedUrl.contains("sexhd.co") || embedUrl.contains("fembed.net") ||
                    embedUrl.contains("mrdhan.com") || embedUrl.contains("votrefilms.xyz") || // embedUrl.contains("") ||
                    embedUrl.contains("embedsito.com") || embedUrl.contains("dutrag.com") || // embedUrl.contains("") ||
                    embedUrl.contains("youvideos.ru") || embedUrl.contains("streamm4u.club") || // embedUrl.contains("") ||
                    embedUrl.contains("moviepl.xyz") || embedUrl.contains("asianclub.tv") || // embedUrl.contains("") ||
                    embedUrl.contains("vidcloud.fun") || embedUrl.contains("fplayer.info") || // embedUrl.contains("") ||
                    embedUrl.contains("diasfem.com") || embedUrl.contains("javpoll.com") || embedUrl.contains("reeoov.tube") ||
                    embedUrl.contains("suzihaza.com") || embedUrl.contains("ezsubz.com") || embedUrl.contains("vidsrc.xyz") ||
                    embedUrl.contains("diampokusy.com") || embedUrl.contains("diampokusy.com") || embedUrl.contains("i18n.pw") ||
                    embedUrl.contains("vanfem.com") || embedUrl.contains("fembed9hd.com") || embedUrl.contains("votrefilms.xyz") || embedUrl.contains("watchjavnow.xyz")
                -> {
                    val videos = FembedExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
                embedUrl.contains("4shared") -> {
                    val video = SharedExtractor(client).videoFromUrl(embedUrl, qualityy)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                embedUrl.contains("vidbom") ||
                    embedUrl.contains("vidbem") || embedUrl.contains("vidbm") || embedUrl.contains("vedpom") ||
                    embedUrl.contains("vedbom") || embedUrl.contains("vedbam") || embedUrl.contains("vadbom") ||
                    embedUrl.contains("vidbam") || embedUrl.contains("vadbam") || embedUrl.contains("myviid") ||
                    embedUrl.contains("myvid") || embedUrl.contains("vidshare") || embedUrl.contains("vedsharr") ||
                    embedUrl.contains("vedshar") || embedUrl.contains("vedshare") || embedUrl.contains("vadshar") || embedUrl.contains("vidshar")
                -> {
                    val videos = VidBomExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
            }
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
                            val GenreN = getGenreList()[filter.state].query
                            val genreUrl = "$baseUrl/anime-genre/$GenreN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(genreUrl.toString(), headers)
                        }
                    }
                    is StatusList -> {
                        if (filter.state > 0) {
                            val StatusN = getStatusList()[filter.state].query
                            val statusUrl = "$baseUrl/anime-status/$StatusN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(statusUrl.toString(), headers)
                        }
                    }
                    is TypeList -> {
                        if (filter.state > 0) {
                            val TypeN = getTypeList()[filter.state].query
                            val typeUrl = "$baseUrl/anime-type/$TypeN".toHttpUrlOrNull()!!.newBuilder()
                            return GET(typeUrl.toString(), headers)
                        }
                    }
                }
            }
            throw Exception("اختر فلتر")
        }
        return GET(url, headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("img.thumbnail").first().attr("src")
        anime.title = document.select("h1.anime-details-title").text()
        anime.genre = document.select("ul.anime-genres > li > a, div.anime-info > a").joinToString(", ") { it.text() }
        anime.description = document.select("p.anime-story").text()
        document.select("div.anime-info a").text()?.also { statusText ->
            when {
                statusText.contains("يعرض الان", true) -> anime.status = SAnime.ONGOING
                statusText.contains("مكتمل", true) -> anime.status = SAnime.COMPLETED
                else -> anime.status = SAnime.UNKNOWN
            }
        }

        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Settings

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
        Genre("نفسي", "%d9%86%d9%81%d8%b3%d9%8a/")
    )

    private fun getTypeList() = listOf(
        Type("أختر", ""),
        Type("Movie", "movie-3"),
        Type("ONA", "ona1"),
        Type("OVA", "ova1"),
        Type("Special", "special1"),
        Type("TV", "tv2")

    )

    private fun getStatusList() = listOf(
        Status("أختر", ""),
        Status("لم يعرض بعد", "%d9%84%d9%85-%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a8%d8%b9%d8%af"),
        Status("مكتمل", "complete"),
        Status("يعرض الان", "%d9%8a%d8%b9%d8%b1%d8%b6-%d8%a7%d9%84%d8%a7%d9%86-1")

    )
}
