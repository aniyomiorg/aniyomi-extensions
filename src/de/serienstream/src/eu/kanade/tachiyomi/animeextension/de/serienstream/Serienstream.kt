package eu.kanade.tachiyomi.animeextension.de.serienstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Serienstream : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Serienstream"

    override val baseUrl = "http://186.2.175.5"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val json: Json by injectLazy()

    // ===== POPULAR ANIME =====
    override fun popularAnimeSelector(): String = "div.seriesListContainer div"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/beliebte-serien")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")!!
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img")!!.attr("data-src")
        anime.title = element.selectFirst("h3")!!.text()
        return anime
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "div.seriesListContainer div"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neu")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")!!
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img")!!.attr("data-src")
        anime.title = element.selectFirst("h3")!!.text()
        return anime
    }

    // ===== SEARCH =====

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = Headers.Builder()
            .add("Referer", "http://186.2.175.5/search")
            .add("origin", baseUrl)
            .add("connection", "keep-alive")
            .add("user-agent", "Mozilla/5.0 (Linux; Android 12; Pixel 5 Build/SP2A.220405.004; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.127 Safari/537.36")
            .add("Upgrade-Insecure-Requests", "1")
            .add("content-length", query.length.plus(8).toString())
            .add("cache-control", "")
            .add("accept", "*/*")
            .add("content-type", "application/x-www-form-urlencoded; charset=UTF-8")
            .add("x-requested-with", "XMLHttpRequest")
            .build()
        return POST("$baseUrl/ajax/search", body = FormBody.Builder().add("keyword", query).build(), headers = headers)
    }
    override fun searchAnimeSelector() = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body.string()
        val results = json.decodeFromString<JsonArray>(body)
        val animes = results.filter {
            val link = it.jsonObject["link"]!!.jsonPrimitive.content
            link.startsWith("/serie/stream/") &&
                link.count { c -> c == '/' } == 3
        }.map {
            animeFromSearch(it.jsonObject)
        }
        return AnimesPage(animes, false)
    }

    private fun animeFromSearch(result: JsonObject): SAnime {
        val anime = SAnime.create()
        val title = result["title"]!!.jsonPrimitive.content
        val link = result["link"]!!.jsonPrimitive.content
        anime.title = title.replace("<em>", "").replace("</em>", "")
        val thumpage = client.newCall(GET("$baseUrl$link")).execute().asJsoup()
        anime.thumbnail_url = baseUrl + thumpage.selectFirst("div.seriesCoverBox img")!!.attr("data-src")
        anime.url = link
        return anime
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.series-title h1 span")!!.text()
        anime.thumbnail_url = baseUrl + document.selectFirst("div.seriesCoverBox img")!!.attr("data-src")
        anime.genre = document.select("div.genres ul li").joinToString { it.text() }
        anime.description = document.selectFirst("p.seri_des")!!.attr("data-full-description")
        document.selectFirst("div.cast li:contains(Produzent:) ul")?.let {
            val author = it.select("li").joinToString { li -> li.text() }
            anime.author = author
        }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val seasonsElements = document.select("#stream > ul:nth-child(1) > li > a")
        if (seasonsElements.attr("href").contains("/filme")) {
            seasonsElements.forEach {
                val seasonEpList = parseMoviesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        } else {
            seasonsElements.forEach {
                val seasonEpList = parseEpisodesFromSeries(it)
                episodeList.addAll(seasonEpList)
            }
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).execute().asJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.map { episodeFromElement(it) }
    }

    private fun parseMoviesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = client.newCall(GET(seasonId)).execute().asJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        if (element.select("td.seasonEpisodeTitle a").attr("href").contains("/film")) {
            val num = element.attr("data-episode-season-id")
            episode.name = "Film $num" + " : " + element.select("td.seasonEpisodeTitle a span").text()
            episode.episode_number = element.attr("data-episode-season-id").toFloat()
            episode.url = element.selectFirst("td.seasonEpisodeTitle a")!!.attr("href")
        } else {
            val season = element.select("td.seasonEpisodeTitle a").attr("href")
                .substringAfter("staffel-").substringBefore("/episode")
            val num = element.attr("data-episode-season-id")
            episode.name = "Staffel $season Folge $num" + " : " + element.select("td.seasonEpisodeTitle a span").text()
            episode.episode_number = element.select("td meta").attr("content").toFloat()
            episode.url = element.selectFirst("td.seasonEpisodeTitle a")!!.attr("href")
        }
        return episode
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val redirectlink = document.select("div.hosterSiteVideo ul.row li")
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet(SConstants.HOSTER_SELECTION, null)
        redirectlink.forEach {
            val langkey = it.attr("data-lang-key")
            val language = getlanguage(langkey)
            val redirectgs = baseUrl + it.selectFirst("a.watchEpisode")!!.attr("href")
            val hoster = it.select("a h4").text()
            if (hosterSelection != null) {
                when {
                    hoster.contains("VOE") && hosterSelection.contains(SConstants.NAME_VOE) -> {
                        val url = client.newCall(GET(redirectgs)).execute().request.url.toString()
                        videoList.addAll(VoeExtractor(client).videosFromUrl(url, "($language) "))
                    }

                    hoster.contains("Doodstream") && hosterSelection.contains(SConstants.NAME_DOOD) -> {
                        val quality = "Doodstream $language"
                        val url = client.newCall(GET(redirectgs)).execute().request.url.toString()
                        val video = DoodExtractor(client).videoFromUrl(url, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }

                    hoster.contains("Streamtape") && hosterSelection.contains(SConstants.NAME_STAPE) -> {
                        val quality = "Streamtape $language"
                        val url = client.newCall(GET(redirectgs)).execute().request.url.toString()
                        val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
            }
        }
        return videoList
    }

    private fun getlanguage(langkey: String): String? {
        when {
            langkey.contains("${SConstants.KEY_GER_SUB}") -> {
                return "Deutscher Sub"
            }
            langkey.contains("${SConstants.KEY_GER_DUB}") -> {
                return "Deutscher Dub"
            }
            langkey.contains("${SConstants.KEY_ENG_SUB}") -> {
                return "Englischer Sub"
            }
            else -> {
                return null
            }
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(SConstants.PREFERRED_HOSTER, null)
        val subPreference = preferences.getString(SConstants.PREFERRED_LANG, "Sub")!!
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else {
            otherList += this
        }
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }

        return newList
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = SConstants.PREFERRED_HOSTER
            title = "Standard-Hoster"
            entries = SConstants.HOSTER_NAMES
            entryValues = SConstants.HOSTER_URLS
            setDefaultValue(SConstants.URL_STAPE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = SConstants.PREFERRED_LANG
            title = "Bevorzugte Sprache"
            entries = SConstants.LANGS
            entryValues = SConstants.LANGS
            setDefaultValue(SConstants.LANG_GER_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hosterSelection = MultiSelectListPreference(screen.context).apply {
            key = SConstants.HOSTER_SELECTION
            title = "Hoster auswählen"
            entries = SConstants.HOSTER_NAMES
            entryValues = SConstants.HOSTER_NAMES
            setDefaultValue(SConstants.HOSTER_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(hosterSelection)
    }
}
