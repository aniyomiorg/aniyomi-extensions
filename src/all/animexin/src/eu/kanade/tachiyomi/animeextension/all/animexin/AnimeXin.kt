package eu.kanade.tachiyomi.animeextension.all.animexin

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.GdrivePlayerExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animeextension.all.animexin.extractors.YouTubeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeXin : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeXin"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://animexin.vip")!! }

    override val lang = "all"

    override val id = 4620219025406449669

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
        }
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/")

    override fun popularAnimeSelector(): String = "div.wpop-weekly > ul > li"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a.series")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img")!!.attr("src").substringBefore("?resize")
            title = element.selectFirst("a.series:not(:has(img))")!!.text()
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=&type=&order=update")

    override fun latestUpdatesSelector(): String = searchAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = searchAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("Not used")

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AnimeXinFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    private fun searchAnimeRequest(page: Int, query: String, filters: AnimeXinFilters.FilterSearchParams): Request {
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val multiChoose = mutableListOf<String>()
            if (filters.genres.isNotEmpty()) multiChoose.add(filters.genres)
            if (filters.SEASONS.isNotEmpty()) multiChoose.add(filters.SEASONS)
            if (filters.studios.isNotEmpty()) multiChoose.add(filters.studios)
            val multiString = if (multiChoose.isEmpty()) "" else multiChoose.joinToString("&") + "&"
            GET("$baseUrl/anime/?page=$page&${multiString}status=${filters.status}&type=${filters.type}&sub=${filters.sub}&order=${filters.order}")
        }
    }

    override fun searchAnimeSelector(): String = "div.listupd > article"

    override fun searchAnimeNextPageSelector(): String = "div.hpage > a:contains(Next)"

    override fun searchAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img")!!.attr("src").substringBefore("?resize")
            title = element.selectFirst("div.tt")!!.text()
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeXinFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            title = document.selectFirst("h1.entry-title")!!.text()
            thumbnail_url = document.selectFirst("div.thumb > img")!!.attr("src").substringBefore("?resize")
            status = SAnime.COMPLETED
            description = document.select("div[itemprop=description] p")?.let {
                it.joinToString("\n\n") { t -> t.text() } +
                    "\n\n" +
                    document.select("div.info-content > div > span").joinToString("\n") { info ->
                        info.text().replace(":", ": ")
                    }
            } ?: ""
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        return document.select("div.eplister > ul > li").map { episodeElement ->
            val numberText = episodeElement.selectFirst("div.epl-num")!!.text()
            val numberString = numberText.substringBefore(" ")
            val episodeNumber = if (numberText.contains("part 2", true)) {
                numberString.toFloatOrNull()?.plus(0.5F) ?: 0F
            } else {
                numberString.toFloatOrNull() ?: 0F
            }

            SEpisode.create().apply {
                episode_number = episodeNumber
                name = numberText
                date_upload = parseDate(episodeElement.selectFirst("div.epl-date")?.text() ?: "")
                setUrlWithoutDomain(episodeElement.selectFirst("a")!!.attr("href").toHttpUrl().encodedPath)
            }
        }
    }

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not Used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        videoList.addAll(
            document.select("select.mirror > option[value~=.]").parallelMap { source ->
                runCatching {
                    var decoded = Jsoup.parse(
                        String(Base64.decode(source.attr("value"), Base64.DEFAULT)),
                    ).select("iframe[src~=.]").attr("src")
                    if (!decoded.startsWith("http")) decoded = "https:$decoded"
                    val prefix = "${source.text()} - "

                    when {
                        decoded.contains("ok.ru") -> {
                            OkruExtractor(client).videosFromUrl(decoded, prefix = prefix)
                        }
                        decoded.contains("sbhight") || decoded.contains("sbrity") || decoded.contains("sbembed.com") || decoded.contains("sbembed1.com") || decoded.contains("sbplay.org") ||
                            decoded.contains("sbvideo.net") || decoded.contains("streamsb.net") || decoded.contains("sbplay.one") ||
                            decoded.contains("cloudemb.com") || decoded.contains("playersb.com") || decoded.contains("tubesb.com") ||
                            decoded.contains("sbplay1.com") || decoded.contains("embedsb.com") || decoded.contains("watchsb.com") ||
                            decoded.contains("sbplay2.com") || decoded.contains("japopav.tv") || decoded.contains("viewsb.com") ||
                            decoded.contains("sbfast") || decoded.contains("sbfull.com") || decoded.contains("javplaya.com") ||
                            decoded.contains("ssbstream.net") || decoded.contains("p1ayerjavseen.com") || decoded.contains("sbthe.com") ||
                            decoded.contains("vidmovie.xyz") || decoded.contains("sbspeed.com") || decoded.contains("streamsss.net") ||
                            decoded.contains("sblanh.com") || decoded.contains("tvmshow.com") || decoded.contains("sbanh.com") ||
                            decoded.contains("streamovies.xyz") -> {
                            StreamSBExtractor(client).videosFromUrl(decoded, headers, prefix = prefix)
                        }
                        decoded.contains("dailymotion") -> {
                            DailymotionExtractor(client).videosFromUrl(decoded, prefix = prefix)
                        }
                        decoded.contains("https://dood") -> {
                            DoodExtractor(client).videosFromUrl(decoded, quality = source.text())
                        }
                        decoded.contains("fembed") ||
                            decoded.contains("anime789.com") || decoded.contains("24hd.club") || decoded.contains("fembad.org") ||
                            decoded.contains("vcdn.io") || decoded.contains("sharinglink.club") || decoded.contains("moviemaniac.org") ||
                            decoded.contains("votrefiles.club") || decoded.contains("femoload.xyz") || decoded.contains("albavido.xyz") ||
                            decoded.contains("feurl.com") || decoded.contains("dailyplanet.pw") || decoded.contains("ncdnstm.com") ||
                            decoded.contains("jplayer.net") || decoded.contains("xstreamcdn.com") || decoded.contains("fembed-hd.com") ||
                            decoded.contains("gcloud.live") || decoded.contains("vcdnplay.com") || decoded.contains("superplayxyz.club") ||
                            decoded.contains("vidohd.com") || decoded.contains("vidsource.me") || decoded.contains("cinegrabber.com") ||
                            decoded.contains("votrefile.xyz") || decoded.contains("zidiplay.com") || decoded.contains("ndrama.xyz") ||
                            decoded.contains("fcdn.stream") || decoded.contains("mediashore.org") || decoded.contains("suzihaza.com") ||
                            decoded.contains("there.to") || decoded.contains("femax20.com") || decoded.contains("javstream.top") ||
                            decoded.contains("viplayer.cc") || decoded.contains("sexhd.co") || decoded.contains("fembed.net") ||
                            decoded.contains("mrdhan.com") || decoded.contains("votrefilms.xyz") || // decoded.contains("") ||
                            decoded.contains("embedsito.com") || decoded.contains("dutrag.com") || // decoded.contains("") ||
                            decoded.contains("youvideos.ru") || decoded.contains("streamm4u.club") || // decoded.contains("") ||
                            decoded.contains("moviepl.xyz") || decoded.contains("asianclub.tv") || // decoded.contains("") ||
                            decoded.contains("vidcloud.fun") || decoded.contains("fplayer.info") || // decoded.contains("") ||
                            decoded.contains("diasfem.com") || decoded.contains("javpoll.com") || decoded.contains("reeoov.tube") ||
                            decoded.contains("suzihaza.com") || decoded.contains("ezsubz.com") || decoded.contains("vidsrc.xyz") ||
                            decoded.contains("diampokusy.com") || decoded.contains("diampokusy.com") || decoded.contains("i18n.pw") ||
                            decoded.contains("vanfem.com") || decoded.contains("fembed9hd.com") || decoded.contains("votrefilms.xyz") || decoded.contains("watchjavnow.xyz")
                        -> {
                            val newUrl = decoded.replace("https://www.fembed.com", "https://vanfem.com")
                            FembedExtractor(client).videosFromUrl(newUrl, prefix = prefix)
                        }
                        decoded.contains("gdriveplayer") -> {
                            GdrivePlayerExtractor(client).videosFromUrl(decoded, name = source.text())
                        }
                        decoded.contains("youtube.com") -> {
                            YouTubeExtractor(client).videosFromUrl(decoded, prefix = prefix)
                        }
                        decoded.contains("vidstreaming") -> {
                            VidstreamingExtractor(client).videosFromUrl(decoded, prefix = prefix)
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val language = preferences.getString("preferred_language", "All Sub")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(language, true) },
            ),
        ).reversed()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("animexin.vip")
            entryValues = arrayOf("https://animexin.vip")
            setDefaultValue("https://animexin.vip")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
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
        val videoLangPref = ListPreference(screen.context).apply {
            key = "preferred_language"
            title = "Preferred Video Language"
            entries = arrayOf("All Sub", "English", "Spanish", "Arabic", "German", "Indonesia", "Italian", "Polish", "Portuguese", "Thai", "Turkish")
            entryValues = arrayOf("All Sub", "English", "Spanish", "Arabic", "German", "Indonesia", "Italian", "Polish", "Portuguese", "Thai", "Turkish")
            setDefaultValue("All Sub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
        screen.addPreference(videoLangPref)
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
