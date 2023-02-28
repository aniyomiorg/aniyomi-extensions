package eu.kanade.tachiyomi.animeextension.it.toonitalia

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.it.toonitalia.extractors.StreamZExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Toonitalia : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Toonitalia"

    override val baseUrl = "https://toonitalia.co"

    override val lang = "it"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/page/$page", headers = headers)
    }

    override fun popularAnimeSelector(): String = "div#primary > main#main > article"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("h2 > a").text()
        anime.thumbnail_url = element.selectFirst("img")!!.attr("src")
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nav-links > span.current ~ a"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = if (response.request.url.toString().substringAfter(baseUrl).startsWith("/?s=")) {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        } else {
            document.select(searchIndexAnimeSelector()).map { element ->
                searchIndexAnimeFromElement(element)
            }
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/?s=$query", headers = headers)
        } else {
            val url = "$baseUrl".toHttpUrlOrNull()!!.newBuilder()
            filters.forEach { filter ->
                when (filter) {
                    is IndexFilter -> url.addPathSegment(filter.toUriPart())
                    else -> {}
                }
            }
            var newUrl = url.toString()
            if (page > 1) {
                newUrl += "/?lcp_page0=$page#lcp_instance_0"
            }
            GET(newUrl, headers = headers)
        }
    }

    override fun searchAnimeSelector(): String = "section#primary > main#main > article"

    private fun searchIndexAnimeSelector(): String = "div.entry-content > ul.lcp_catlist > li"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.selectFirst("h2")!!.text()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    private fun searchIndexAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.title = element.select("a").text()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href").substringAfter(baseUrl))
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.lcp_paginator > li.lcp_currentpage ~ li"

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.entry-content > h2 > img").attr("src")
        anime.title = document.select("header.entry-header > h1.entry-title").text()

        var descInfo = ""
        document.selectFirst("div.entry-content > h2 + p + p")!!.childNodes().filter {
                s ->
            s.nodeName() != "br"
        }.forEach {
            if (it.nodeName() == "span") {
                if (it.nextSibling() != null) {
                    descInfo += "\n"
                }
                descInfo += "${it.childNode(0)} "
            } else if (it.nodeName() == "#text") {
                val infoStr = it.toString().trim()
                if (infoStr.isNotBlank()) descInfo += infoStr
            }
        }

        var descElement = document.selectFirst("div.entry-content > h3:contains(Trama:) + p")
        if (descElement == null) {
            descElement = document.selectFirst("div.entry-content > p:has(span:contains(Trama:))")
        }

        val description = if (descElement == null) {
            "Nessuna descrizione disponibile\n\n$descInfo"
        } else {
            descElement.childNodes().filter {
                    s ->
                s.nodeName() == "#text"
            }.joinToString(separator = "\n\n") { it.toString() }.trim() + "\n\n" + descInfo
        }

        anime.description = description

        anime.genre = document.select("footer.entry-footer > span.cat-links > a").joinToString(separator = ", ") { it.text() }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        // Select single seasons episodes
        val singleEpisode = document.select("div.entry-content > h3:contains(Episodi) + p")
        if (singleEpisode.isNotEmpty() && singleEpisode.text().isNotEmpty()) {
            var episode = SEpisode.create()

            var isValid = false
            var counter = 1
            for (child in singleEpisode.first()!!.childNodes()) {
                if (child.nodeName() == "br" || (child.nextSibling() == null && child.nodeName() == "a")) {
                    episode.url = response.request.url.toString() + "#$counter"

                    if (isValid) {
                        episodeList.add(episode)
                        isValid = false
                    }
                    episode = SEpisode.create()
                    counter++
                } else if (child.nodeName() == "a") {
                    isValid = true
                } else {
                    val name = child.toString().trim().substringBeforeLast("–")
                    if (name.isNotEmpty()) {
                        episode.name = "Episode ${name.trim()}"
                        episode.episode_number = counter.toFloat()
                    }
                }
            }
        }

        // Select multiple seasons
        val seasons = document.select("div.entry-content > h3:contains(Stagione) + p")
        if (seasons.isNotEmpty()) {
            var counter = 1
            seasons.forEach {
                var episode = SEpisode.create()

                var isValid = false
                for (child in it.childNodes()) {
                    if (child.nodeName() == "br" || (child.nextSibling() == null && child.nodeName() == "a")) {
                        episode.url = response.request.url.toString() + "#$counter"
                        if (isValid) {
                            episodeList.add(episode)
                            isValid = false
                        }
                        episode = SEpisode.create()
                        counter++
                    } else if (child.nodeName() == "a") {
                        isValid = true
                    } else {
                        val name = child.toString().trim().substringBeforeLast("–")
                        if (name.isNotEmpty()) {
                            episode.name = "Episode ${name.trim()}"
                            episode.episode_number = counter.toFloat()
                        }
                    }
                }
            }
        }

        // Select movie
        val movie = document.select("div.entry-content > p:contains(Link Streaming)")
        if (movie.isNotEmpty()) {
            val episode = SEpisode.create()
            for (child in movie.first()!!.childNodes()) {
                if (child.nodeName() == "br" || (child.nextSibling() == null && child.nodeName() == "a")) {
                    // episode.url = links.joinToString(separator = "///")
                    episode.url = response.request.url.toString() + "#1"
                } else if (child.nodeName() == "a") {
                } else {
                    val name = child.toString().trim().substringBeforeLast("–")
                    if (name.isNotEmpty()) {
                        episode.name = "Movie"
                        episode.episode_number = 1F
                    }
                }
            }
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    override fun episodeListSelector(): String = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url, headers = headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        val episodeNumber = response.request.url.fragment!!.toInt()

        // Select single seasons episodes
        val singleEpisode = document.select("div.entry-content > h3:contains(Episodi) + p")
        if (singleEpisode.isNotEmpty() && singleEpisode.text().isNotEmpty()) {
            var counter = 1
            for (child in singleEpisode.first()!!.childNodes()) {
                if (child.nodeName() == "a" && counter == episodeNumber) {
                    videoList.addAll(extractVideos(child.attr("href"), child.childNode(0).toString()))
                }

                if (child.nodeName() == "br" || child.nextSibling() == null) {
                    counter++
                }
            }
        }

        // Select multiple seasons
        val seasons = document.select("div.entry-content > h3:contains(Stagione) + p")
        if (seasons.isNotEmpty()) {
            var counter = 1
            seasons.forEach {
                for (child in it.childNodes()) {
                    if (child.nodeName() == "a" && counter == episodeNumber) {
                        videoList.addAll(extractVideos(child.attr("href"), child.childNode(0).toString()))
                    }

                    if (child.nodeName() == "br" || child.nextSibling() == null) {
                        counter++
                    }
                }
            }
        }

        // Select movie
        val movie = document.select("div.entry-content > p:contains(Link Streaming)")
        if (movie.isNotEmpty()) {
            for (child in movie.first()!!.childNodes()) {
                if (child.nodeName() == "a") {
                    videoList.addAll(extractVideos(child.attr("href"), child.childNode(0).toString()))
                }
            }
        }

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not used")

    override fun videoListSelector(): String = throw Exception("Not used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not used")

    // ============================= Utilities ==============================

    private fun extractVideos(url: String, name: String): List<Video> {
        return when {
            url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                url.contains("sbchill.com") || url.contains("sblongvu.com") || url.contains("sbanh.com") ||
                url.contains("sblanh.com") || url.contains("sbhight.com") || url.contains("sbbrisk.com") ||
                url.contains("sbspeed.com")
            -> {
                val videos = StreamSBExtractor(client).videosFromUrl(url, headers, suffix = name, common = false)
                videos
            }
            url.contains("https://voe.sx") || url.contains("https://20demidistance9elongations.com") ||
                url.contains("https://telyn610zoanthropy.com")
            -> {
                val video = VoeExtractor(client).videoFromUrl(url, name)
                if (video == null) {
                    emptyList()
                } else {
                    listOf(video)
                }
            }
            url.contains("https://streamz") || url.contains("streamz.cc") -> {
                val video = StreamZExtractor(client).videoFromUrl(url, name)
                if (video == null) {
                    emptyList()
                } else {
                    listOf(video)
                }
            }

            else -> { emptyList() }
        }
    }

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTA: ignorato se si utilizza la ricerca di testo!"),
        AnimeFilter.Separator(),
        IndexFilter(getIndexList()),
    )

    private class IndexFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Indice", vals)

    private fun getIndexList() = arrayOf(
        Pair("<selezionare>", ""),
        Pair("Anime", "anime"),
        Pair("Anime Sub-ita", "anime-sub-ita"),
        Pair("Serie Tv", "serie-tv"),
        Pair("Film Animazione", "film-animazione"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "StreamSB")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("StreamSB", "StreamZ", "VOE", "StreamSB Sub-Ita", "StreamZ Sub-Ita", "VOE Sub-Ita")
            entryValues = arrayOf("StreamSB", "StreamZ", "VOE", "StreamSB Sub-Ita", "StreamZ Sub-Ita", "VOE Sub-Ita")
            setDefaultValue("StreamZ")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(serverPref)
    }
}
