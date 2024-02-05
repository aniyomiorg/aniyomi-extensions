package eu.kanade.tachiyomi.animeextension.de.animeloads

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import kotlin.Exception

class AnimeLoads : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime-Loads"

    override val baseUrl = "https://www.anime-loads.org"

    override val lang = "de"

    override val supportsLatest = false

    override val id: Long = 655155856096L

    override val client = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.row div.col-sm-6 div.panel-body"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/anime-series/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.row a.cover-img").attr("href"))
        anime.thumbnail_url = element.select("div.row a.cover-img img").attr("src")
        anime.title = element.select("div.row h4.title-list a").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "i.glyphicon-forward"

    // episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        val series = document.select("a[title=\"Anime Serien\"]")
        if (series.attr("title").contains("Anime Serien")) {
            val eplist = document.select("#streams_episodes_1 div.list-group")
            val url = document.select("meta[property=\"og:url\"]").attr("content")
            val ep = parseEpisodesFromSeries(eplist, url)
            episodeList.addAll(ep)
        } else {
            episode.name = document.select("div.page-header > h1").attr("title")
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(document.select("meta[property=\"og:url\"]").attr("content"))
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Elements, url: String): List<SEpisode> {
        val episodeElement = element.select("a.list-group-item")
        return episodeElement.map { episodeFromElement(it, url) }
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    private fun episodeFromElement(element: Element, url: String): SEpisode {
        val episode = SEpisode.create()
        val id = element.attr("aria-controls")
        episode.setUrlWithoutDomain("$url#$id")
        episode.name = "Ep." + element.select("span:nth-child(1)").text()
        episode.episode_number = element.select("span strong").text().toFloat()
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val url = response.request.url.toString()
        val idep = url
            .substringAfter("#")
        return videosFromElement(document, idep, url)
    }

    private fun videosFromElement(document: Document, idep: String, url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("dood", "voe", "stape"))
        val subSelection = preferences.getStringSet("sub_selection", setOf("sub", "dub"))
        val lang = document.select("div#streams ul.nav li[role=\"presentation\"]")
        lang.forEach { langit ->
            Log.i("videosFromElement", "Langit: $langit")
            when {
                langit.select("a i.flag-de").attr("title").contains("Subtitles: German") || langit.select("a i.flag-de").attr("title").contains("Untertitel: Deutsch") && subSelection?.contains("sub") == true -> {
                    val aria = langit.select("a").attr("aria-controls")
                    val id = document.select("#$aria div.episodes").attr("id")
                    val epnum = idep.substringAfter("streams_episodes_1")
                    val element = document.select("div#$id$epnum")
                    val enc = element.attr("data-enc")
                    val capfiles = client.newCall(
                        POST(
                            "$baseUrl/files/captcha",
                            body = "cID=0&rT=1".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                            headers = Headers.headersOf(
                                "X-Requested-With",
                                "XMLHttpRequest",
                                "Referer",
                                url.replace("#$id$epnum", ""),
                                "Accept",
                                "application/json, text/javascript, */*; q=0.01",
                                "cache-control",
                                "max-age=15",
                            ),
                        ),
                    ).execute().asJsoup()

                    val hashes = capfiles.toString().substringAfter("[").substringBefore("]").split(",")
                    val hashlist = mutableListOf<String>()
                    val pnglist = mutableListOf<String>()
                    var max = "1"
                    var min = "99999"
                    hashes.forEach {
                        val hash = it.replace("<body>", "")
                            .replace("[", "")
                            .replace("\"", "").replace("]", "")
                            .replace("</body>", "").replace("%20", "")
                        val png = client.newCall(
                            GET(
                                "$baseUrl/files/captcha?cid=0&hash=$hash",
                                headers = Headers.headersOf(
                                    "Referer",
                                    url.replace("#$id$epnum", ""),
                                    "Accept",
                                    "image/avif,image/webp,*/*",
                                    "cache-control",
                                    "max-age=15",
                                ),
                            ),
                        ).execute().body.byteString()
                        val size = png.toString()
                            .substringAfter("[size=").substringBefore(" hex")
                        pnglist.add("$size | $hash")
                        hashlist.add(size)
                        for (num in hashlist) {
                            if (max < num) {
                                max = num
                            }
                        }
                        for (num in hashlist) {
                            if (min > num) {
                                min = num
                            }
                        }
                    }
                    var int = 0

                    pnglist.forEach { diffit ->
                        if (int == 0) {
                            if (diffit.substringBefore(" |").toInt() != max.toInt() && diffit.substringBefore(" |").toInt() != min.toInt()) {
                                int = 1
                                val hash = diffit.substringBefore(" |").toInt()
                                val diffmax = max.toInt() - hash
                                val diffmin = hash - min.toInt()
                                if (diffmax > diffmin) {
                                    pnglist.forEach { itmax ->
                                        if (max.toInt() == itmax.substringBefore(" |").toInt()) {
                                            val maxhash = itmax.substringAfter("| ")
                                            network.client.newCall(
                                                POST(
                                                    "$baseUrl/files/captcha",
                                                    body = "cID=0&pC=$maxhash&rT=2".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin", baseUrl, "X-Requested-With", "XMLHttpRequest", "Referer", url.replace("#$id$epnum", ""), "Accept", "*/*", "cache-control", "max-age=15",
                                                    ),
                                                ),
                                            ).execute()
                                            val maxdoc = client.newCall(
                                                POST(
                                                    "$baseUrl/ajax/captcha",
                                                    body = "enc=${enc.replace("=", "%3D")}&response=captcha&captcha-idhf=0&captcha-hf=$maxhash".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin", baseUrl, "X-Requested-With", "XMLHttpRequest", "Referer", url.replace("#$id$epnum", ""),
                                                        "Accept", "application/json, text/javascript, */*; q=0.01", "cache-control", "max-age=15",
                                                    ),
                                                ),
                                            ).execute().asJsoup().toString()
                                            if (maxdoc.substringAfter("\"code\":\"").substringBefore("\",").contains("error")) {
                                                throw Exception("Captcha bypass failed! Clear Cookies & Webview data. Or wait some time.")
                                            } else {
                                                val links = maxdoc.substringAfter("\"content\":").substringBefore("</body>").split("{\"links\":")
                                                links.forEach {
                                                    if (it.contains("link")) {
                                                        val hoster = it.substringAfter("\"hoster\":\"").substringBefore("\",\"")
                                                        val linkpart = it.substringAfter("\"link\":\"").substringBefore("\"}]")
                                                        val leaveurl = client.newCall(GET("$baseUrl/leave/$linkpart")).execute().request.url.toString()
                                                        val decode = "https://www." + URLDecoder.decode(leaveurl.substringAfter("www."), "utf-8")
                                                        if (decode.contains(baseUrl)) {
                                                            val link = client.newCall(GET(decode)).execute().request.url.toString()
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(link, "(Deutsch Sub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Sub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstreams Deutsch Sub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(leaveurl, "(Deutsch Sub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Sub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstreams Deutsch Sub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    pnglist.forEach { itmin ->
                                        if (min.toInt() == itmin.substringBefore(" |").toInt()) {
                                            val minhash = itmin.substringAfter("| ")
                                            network.client.newCall(
                                                POST(
                                                    "$baseUrl/files/captcha",
                                                    body = "cID=0&pC=$minhash&rT=2".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin",
                                                        baseUrl,
                                                        "X-Requested-With",
                                                        "XMLHttpRequest",
                                                        "Referer",
                                                        url.replace("#$id$epnum", ""),
                                                        "Accept",
                                                        "*/*",
                                                    ),
                                                ),
                                            ).execute()
                                            val mindoc = client.newCall(
                                                POST(
                                                    "$baseUrl/ajax/captcha",
                                                    body = "enc=${enc.replace("=", "%3D")}&response=captcha&captcha-idhf=0&captcha-hf=$minhash".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin",
                                                        baseUrl,
                                                        "X-Requested-With",
                                                        "XMLHttpRequest",
                                                        "Referer",
                                                        url.replace("#$id$epnum", ""),
                                                        "Accept",
                                                        "application/json, text/javascript, */*; q=0.01",
                                                    ),
                                                ),
                                            ).execute().asJsoup().toString()
                                            if (mindoc.substringAfter("\"code\":\"").substringBefore("\",").contains("error")) {
                                                throw Exception("Captcha bypass failed! Clear Cookies & Webview data. Or wait some time.")
                                            } else {
                                                val links = mindoc.substringAfter("\"content\":[").substringBefore("</body>").split("{\"links\":")
                                                links.forEach {
                                                    if (it.contains("link")) {
                                                        val hoster = it.substringAfter("\"hoster\":\"").substringBefore("\",\"")
                                                        val linkpart = it.substringAfter("\"link\":\"").substringBefore("\"}]")
                                                        val leaveurl = client.newCall(GET("$baseUrl/leave/$linkpart")).execute().request.url.toString()
                                                        val decode = "https://www." + URLDecoder.decode(leaveurl.substringAfter("www."), "utf-8")
                                                        if (decode.contains(baseUrl)) {
                                                            val link = client.newCall(GET(decode)).execute().request.url.toString()
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(link, "(Deutsch Sub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Sub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstreams Deutsch Sub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(leaveurl, "(Deutsch Sub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Sub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstreams Deutsch Sub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                langit.select("a i.flag-de").attr("title").contains("Language: German") || langit.select("a i.flag-de").attr("title").contains("Sprache: Deutsch") && subSelection?.contains("dub") == true -> {
                    val aria = langit.select("a").attr("aria-controls")
                    val id = document.select("#$aria div.episodes").attr("id")
                    val epnum = idep.substringAfter("streams_episodes_1")
                    val element = document.select("div#$id$epnum")
                    val enc = element.attr("data-enc")
                    val capfiles = client.newCall(
                        POST(
                            "$baseUrl/files/captcha",
                            body = "cID=0&rT=1".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                            headers = Headers.headersOf(
                                "X-Requested-With",
                                "XMLHttpRequest",
                                "Referer",
                                url.replace("#$id$epnum", ""),
                                "Accept",
                                "application/json, text/javascript, */*; q=0.01",
                                "cache-control",
                                "max-age=15",
                            ),
                        ),
                    ).execute().asJsoup()

                    val hashes = capfiles.toString().substringAfter("[").substringBefore("]").split(",")
                    val hashlist = mutableListOf<String>()
                    val pnglist = mutableListOf<String>()
                    var max = "1"
                    var min = "99999"
                    hashes.forEach {
                        val hash = it.replace("<body>", "")
                            .replace("[", "")
                            .replace("\"", "").replace("]", "")
                            .replace("</body>", "").replace("%20", "")
                        val png = client.newCall(
                            GET(
                                "$baseUrl/files/captcha?cid=0&hash=$hash",
                                headers = Headers.headersOf(
                                    "Referer",
                                    url.replace("#$id$epnum", ""),
                                    "Accept",
                                    "image/avif,image/webp,*/*",
                                    "cache-control",
                                    "max-age=15",
                                ),
                            ),
                        ).execute().body.byteString()
                        val size = png.toString()
                            .substringAfter("[size=").substringBefore(" hex")
                        pnglist.add("$size | $hash")
                        hashlist.add(size)
                        for (num in hashlist) {
                            if (max < num) {
                                max = num
                            }
                        }
                        for (num in hashlist) {
                            if (min > num) {
                                min = num
                            }
                        }
                    }
                    var int = 0

                    pnglist.forEach { diffit ->
                        if (int == 0) {
                            if (diffit.substringBefore(" |").toInt() != max.toInt() && diffit.substringBefore(" |").toInt() != min.toInt()) {
                                int = 1
                                val hash = diffit.substringBefore(" |").toInt()
                                val diffmax = max.toInt() - hash
                                val diffmin = hash - min.toInt()
                                if (diffmax > diffmin) {
                                    pnglist.forEach { itmax ->
                                        if (max.toInt() == itmax.substringBefore(" |").toInt()) {
                                            val maxhash = itmax.substringAfter("| ")
                                            network.client.newCall(
                                                POST(
                                                    "$baseUrl/files/captcha",
                                                    body = "cID=0&pC=$maxhash&rT=2".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin", baseUrl, "X-Requested-With", "XMLHttpRequest", "Referer", url.replace("#$id$epnum", ""), "Accept", "*/*", "cache-control", "max-age=15",
                                                    ),
                                                ),
                                            ).execute()
                                            val maxdoc = client.newCall(
                                                POST(
                                                    "$baseUrl/ajax/captcha",
                                                    body = "enc=${enc.replace("=", "%3D")}&response=captcha&captcha-idhf=0&captcha-hf=$maxhash".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin", baseUrl, "X-Requested-With", "XMLHttpRequest", "Referer", url.replace("#$id$epnum", ""),
                                                        "Accept", "application/json, text/javascript, */*; q=0.01", "cache-control", "max-age=15",
                                                    ),
                                                ),
                                            ).execute().asJsoup().toString()
                                            if (maxdoc.substringAfter("\"code\":\"").substringBefore("\",").contains("error")) {
                                                throw Exception("Captcha bypass failed! Clear Cookies & Webview data. Or wait some time.")
                                            } else {
                                                val links = maxdoc.substringAfter("\"content\":").substringBefore("</body>").split("{\"links\":")
                                                links.forEach {
                                                    if (it.contains("link")) {
                                                        val hoster = it.substringAfter("\"hoster\":\"").substringBefore("\",\"")
                                                        val linkpart = it.substringAfter("\"link\":\"").substringBefore("\"}]")
                                                        val leaveurl = client.newCall(GET("$baseUrl/leave/$linkpart")).execute().request.url.toString()
                                                        val decode = "https://www." + URLDecoder.decode(leaveurl.substringAfter("www."), "utf-8")
                                                        if (decode.contains(baseUrl)) {
                                                            val link = client.newCall(GET(decode)).execute().request.url.toString()
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(link, "(Deutsch Dub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Dub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstream Deutsch Dub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(leaveurl, "(Deutsch Dub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Dub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstream Deutsch Dub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    pnglist.forEach { itmin ->
                                        if (min.toInt() == itmin.substringBefore(" |").toInt()) {
                                            val minhash = itmin.substringAfter("| ")
                                            network.client.newCall(
                                                POST(
                                                    "$baseUrl/files/captcha",
                                                    body = "cID=0&pC=$minhash&rT=2".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin",
                                                        baseUrl,
                                                        "X-Requested-With",
                                                        "XMLHttpRequest",
                                                        "Referer",
                                                        url.replace("#$id$epnum", ""),
                                                        "Accept",
                                                        "*/*",
                                                    ),
                                                ),
                                            ).execute()
                                            val mindoc = client.newCall(
                                                POST(
                                                    "$baseUrl/ajax/captcha",
                                                    body = "enc=${enc.replace("=", "%3D")}&response=captcha&captcha-idhf=0&captcha-hf=$minhash".toRequestBody("application/x-www-form-urlencoded".toMediaType()),
                                                    headers = Headers.headersOf(
                                                        "Origin",
                                                        baseUrl,
                                                        "X-Requested-With",
                                                        "XMLHttpRequest",
                                                        "Referer",
                                                        url.replace("#$id$epnum", ""),
                                                        "Accept",
                                                        "application/json, text/javascript, */*; q=0.01",
                                                    ),
                                                ),
                                            ).execute().asJsoup().toString()
                                            if (mindoc.substringAfter("\"code\":\"").substringBefore("\",").contains("error")) {
                                                throw Exception("Captcha bypass failed! Clear Cookies & Webview data. Or wait some time.")
                                            } else {
                                                val links = mindoc.substringAfter("\"content\":[").substringBefore("</body>").split("{\"links\":")
                                                links.forEach {
                                                    if (it.contains("link")) {
                                                        val hoster = it.substringAfter("\"hoster\":\"").substringBefore("\",\"")
                                                        val linkpart = it.substringAfter("\"link\":\"").substringBefore("\"}]")
                                                        val leaveurl = client.newCall(GET("$baseUrl/leave/$linkpart")).execute().request.url.toString()
                                                        val decode = "https://www." + URLDecoder.decode(leaveurl.substringAfter("www."), "utf-8")
                                                        if (decode.contains(baseUrl)) {
                                                            val link = client.newCall(GET(decode)).execute().request.url.toString()
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(link, "(Deutsch Dub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Dub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstream Deutsch Dub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(link, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        } else {
                                                            when {
                                                                hoster.contains("voesx") && hosterSelection?.contains("voe") == true -> {
                                                                    videoList.addAll(VoeExtractor(client).videosFromUrl(leaveurl, "(Deutsch Dub) "))
                                                                }

                                                                hoster.contains("streamtapecom") && hosterSelection?.contains("stape") == true -> {
                                                                    val quality = "Streamtape Deutsch Dub"
                                                                    val video = try {
                                                                        StreamTapeExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }

                                                                hoster.contains("doodstream") && hosterSelection?.contains("dood") == true -> {
                                                                    val quality = "Doodstream Deutsch Dub"
                                                                    val video = try {
                                                                        DoodExtractor(client).videoFromUrl(leaveurl, quality)
                                                                    } catch (e: Exception) {
                                                                        null
                                                                    }
                                                                    if (video != null) {
                                                                        videoList.add(video)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        if (hoster != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(hoster)) {
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

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.row a.cover-img").attr("href"))
        anime.thumbnail_url = element.select("div.row a.cover-img img").attr("src")
        anime.title = element.select("div.row h4.title-list a").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "i.glyphicon-forward"

    override fun searchAnimeSelector(): String = "div.row div.col-sm-6 div.panel-body"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/page/$page?q=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("#description img.img-responsive").attr("src")
        anime.title = document.select("div.page-header > h1").attr("title")
        anime.genre = document.select("#description div.label-group a.label.label-info").joinToString(", ") { it.text() }
        anime.description = document.select("div.pt20").not("strong").text()
        anime.author = document.select("div.col-md-6.text-left p:nth-child(3) a").joinToString(", ") { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Doodstream", "Voe", "MIXdrop")
            entryValues = arrayOf("https://dood", "https://voe.sx", "https://streamtape.com")
            setDefaultValue("https://voe.sx")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hostSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Hoster auswählen"
            entries = arrayOf("Doodstream", "Voe", "Streamtape")
            entryValues = arrayOf("dood", "voe", "stape")
            setDefaultValue(setOf("dood", "voe", "stape"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = "sub_selection"
            title = "Sprache auswählen"
            entries = arrayOf("SUB", "DUB")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue(setOf("sub"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(hostSelection)
        screen.addPreference(subSelection)
    }
}
