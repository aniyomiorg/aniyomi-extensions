package eu.kanade.tachiyomi.animeextension.ar.animerco

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
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
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
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

class Animerco : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Animerco"

    override val baseUrl = "https://animerco.org"

    override val lang = "ar"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/page/$page/")

    override fun popularAnimeSelector() = "div.media-block > div > a.image"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        thumbnail_url = element.attr("data-src")
        title = element.attr("title")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li a:has(i.fa-left-long)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesSelector(): String = throw Exception("Not used")
    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")
    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/page/$page/?s=$query")

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        document.selectFirst("a.poster")?.run {
            thumbnail_url = attr("data-src")
            title = attr("title").ifEmpty {
                document.selectFirst("div.media-title h1")!!.text()
            }
        }

        val infosDiv = document.selectFirst("ul.media-info")!!
        author = infosDiv.select("li:contains(الشبكات) a").eachText()
            .joinToString()
            .takeIf(String::isNotBlank)
        artist = infosDiv.select("li:contains(الأستوديو) a").eachText()
            .joinToString()
            .takeIf(String::isNotBlank)
        genre = document.select("nav.Nvgnrs a, ul.media-info li:contains(النوع) a")
            .eachText()
            .joinToString()

        description = buildString {
            document.selectFirst("div.media-story p")?.also {
                append(it.text())
            }
            document.selectFirst("div.media-title > h3.alt-title")?.also {
                append("\n\nAlternative title: " + it.text())
            }
        }

        status = document.select("ul.chapters-list a.se-title > span.badge")
            .eachText()
            .let { items ->
                when {
                    items.all { it.contains("مكتمل") } -> SAnime.COMPLETED
                    items.any { it.contains("يعرض الأن") } -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
    }

    // ============================== Episodes ==============================
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
                    seriesLink,
                    // headers = Headers.headersOf("Referer", document.location())
                ),
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
            ),
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
        // val SeasonNum = element.ownerDocument()!!.select("div.Title span").text()
        val seasonName = element.ownerDocument()!!.select("div.media-title h1").text()
        episode.name = "$seasonName : " + element.select("a.title h3").text()
        episode.setUrlWithoutDomain(element.select("a.title").attr("abs:href"))
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "li.dooplay_player_option" // ul#playeroptionsul

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val elements = document.select(videoListSelector())
        for (element in elements) {
            val location = element.ownerDocument()!!.location()
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
            val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
            val callAjax = client.newCall(POST(ajaxUrl, videoHeaders, pageData)).execute().asJsoup()
            val embedUrlT = callAjax.text().substringAfter("embed_url\":\"").substringBefore("\"")
            val embedUrl = embedUrlT.replace("\\/", "/")

            when {
                embedUrl.contains("dood") -> {
                    val video = DoodExtractor(client).videoFromUrl(embedUrl)
                    if (video != null) {
                        videoList.add(video)
                    }
                }
                embedUrl.contains("drive.google")
                -> {
                    val embedUrlG = "https://gdriveplayer.to/embed2.php?link=" + embedUrl
                    val videos = GdrivePlayerExtractor(client).videosFromUrl(embedUrlG, "GdrivePlayer", headers = headers)
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
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "Doodstream", "StreamTape")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360", "Doodstream", "StreamTape")
    }
}
