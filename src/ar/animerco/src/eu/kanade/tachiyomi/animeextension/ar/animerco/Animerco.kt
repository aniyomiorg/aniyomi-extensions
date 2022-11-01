package eu.kanade.tachiyomi.animeextension.ar.animerco

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.animerco.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.ar.animerco.extractors.SharedExtractor
import eu.kanade.tachiyomi.animeextension.ar.animerco.extractors.UQLoadExtractor
import eu.kanade.tachiyomi.animeextension.ar.animerco.extractors.VidBomExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
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

class Animerco : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animerco"

    override val baseUrl = "https://animerco.com"

    override val lang = "ar"

    override val supportsLatest = false

    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeSelector(): String = "div.media-block"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/page/$page/") // page/$page

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a").attr("data-src")
        anime.title = element.select("div.info a h3").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.ti-arrow-left-c"

    // Episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        // val seriesLink1 = document.select("ol[itemscope] li:last-child a").attr("href")
        val seriesLink = document.select("link[rel=canonical]").attr("href")
        val type = document.select("link[rel=canonical]").attr("href")
        if (type.contains("animes")) {
            val seasonsHtml = client.newCall(
                GET(
                    seriesLink
                    // headers = Headers.headersOf("Referer", document.location())
                )
            ).execute().asJsoup()
            val seasonsElements = seasonsHtml.select("ul.chapters-list li a.title")
            seasonsElements.reversed().forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            val movieUrl = seriesLink
            val episode = SEpisode.create()
            episode.name = document.select("span.alt-title").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(movieUrl)
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonName = element.text()
        val episodesUrl = element.attr("abs:href")
        val episodesHtml = client.newCall(
            GET(
                episodesUrl,
            )
        ).execute().asJsoup()
        val episodeElements = episodesHtml.select("ul.chapters-list li")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = getNumberFromEpsString(element.select("a.title h3").text())
        episode.episode_number = when {
            (epNum.isNotEmpty()) -> epNum.toFloat()
            else -> 1F
        }
        // element.select("td > span.Num").text().toFloat()
        // val SeasonNum = element.ownerDocument().select("div.Title span").text()
        val seasonName = element.ownerDocument().select("div.media-title h1").text()
        episode.name = "$seasonName : " + element.select("a.title h3").text()
        episode.setUrlWithoutDomain(element.select("a.title").attr("abs:href"))
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video urls

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val location = element.ownerDocument().location()
            val videoHeaders = Headers.headersOf("Referer", location)
            val qualityy = element.text()
            val post = element.attr("data-post")
            val num = element.attr("data-nume")
            val type = element.attr("data-type")
            val pageData = FormBody.Builder()
                .add("action", "doo_player_ajax")
                .add("nume", num)
                .add("post", post)
                .add("type", type)
                .build()
            val ajaxUrl = "https://animerco.com/wp-admin/admin-ajax.php"
            val callAjax = client.newCall(POST(ajaxUrl, videoHeaders, pageData)).execute().asJsoup()
            val embedUrlT = callAjax.text().substringAfter("embed_url\":\"").substringBefore("\"")
            val embedUrl = embedUrlT.replace("\\/", "/")

            when {
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
                /*embedUrl.contains("ok.ru") -> {
                    val videos = OkruExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }*/
                embedUrl.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(embedUrl)
                    if (video != null) {
                        videoList.add(video)
                    }
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
                    val fUrl = embedUrl.replace("\\/", "/")
                        .replace("https://www.fembed.com", "https://vanfem.com")
                    val videos = FembedExtractor(client).videosFromUrl(fUrl)
                    videoList.addAll(videos)
                }
                embedUrl.contains("drive.google")
                -> {
                    val embedUrlG = "https://gdriveplayer.to/embed2.php?link=" + embedUrl
                    val videos = GdrivePlayerExtractor(client).videosFromUrl(embedUrlG)
                    videoList.addAll(videos)
                }
                embedUrl.contains("streamtape") -> {
                    val video = StreamTapeExtractor(client).videoFromUrl(embedUrl)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                embedUrl.contains("4shared") -> {
                    val qualityy = "4shared"
                    val video = SharedExtractor(client).videoFromUrl(embedUrl, qualityy)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                embedUrl.contains("uqload") -> {
                    val qualityy = "uqload"
                    val video = UQLoadExtractor(client).videoFromUrl(embedUrl, qualityy)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                embedUrl.contains("vidbom.com") ||
                    embedUrl.contains("vidbem.com") || embedUrl.contains("vidbm.com") || embedUrl.contains("vedpom.com") ||
                    embedUrl.contains("vedbom.com") || embedUrl.contains("vedbom.org") || embedUrl.contains("vadbom.com") ||
                    embedUrl.contains("vidbam.org") || embedUrl.contains("myviid.com") || embedUrl.contains("myviid.net") ||
                    embedUrl.contains("myvid.com") || embedUrl.contains("vidshare.com") || embedUrl.contains("vedsharr.com") ||
                    embedUrl.contains("vedshar.com") || embedUrl.contains("vedshare.com") || embedUrl.contains("vadshar.com") || embedUrl.contains("vidshar.org")
                -> { // , vidbm, vidbom
                    val videos = VidBomExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
                embedUrl.contains("vidbm") -> { // , vidbm, vidbom
                    val videos = VidBomExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
                embedUrl.contains("vidbom") -> { // , vidbm, vidbom
                    val videos = VidBomExtractor(client).videosFromUrl(embedUrl)
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    override fun videoFromElement(element: Element) = throw Exception("not used")

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

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a.image").attr("href"))
        anime.thumbnail_url = element.select("a.image").attr("data-src")
        anime.title = element.select("a.image").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.ti-arrow-left-c"

    override fun searchAnimeSelector(): String = "div.media-block"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select(".poster").attr("data-src")
        anime.title = document.select("div.media-title h1").text()
        anime.genre = document.select("nav.Nvgnrs a, ul.media-info li:contains(النوع) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.media-story p").text()
        anime.author = document.select("ul.media-info li:contains(الشبكات) a").joinToString(", ") { it.text() }
        anime.artist = document.select("ul.media-info li:contains(الأستوديو) a").joinToString(", ") { it.text() }
        // anime.status = parseStatus(document.select("div.row-line:contains(Status)").text().replace("Status: ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Airing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    /*// Filter

    override fun getFilterList() = AnimeFilterList(
        TypeList(typesName),
    )

    private class TypeList(types: Array<String>) : AnimeFilter.Select<String>("Drame Type", types)
    private data class Type(val name: String, val query: String)
    private val typesName = getTypeList().map {
        it.name
    }.toTypedArray()

    private fun getTypeList() = listOf(
        Type("Select", ""),
        Type("Recently Added Sub", ""),
        Type("Recently Added Raw", "recently-added-raw"),
        Type("Drama Movie", "movies"),
        Type("KShow", "kshow"),
        Type("Ongoing Series", "ongoing-series")
    )*/

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
            entryValues = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")
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
