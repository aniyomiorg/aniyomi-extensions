package eu.kanade.tachiyomi.animeextension.de.aniworld

import android.app.Application
import android.content.SharedPreferences
import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.aniworld.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.de.aniworld.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.de.aniworld.extractors.VoeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AniWorld : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AniWorld"

    override val baseUrl = "https://aniworld.to"

    private val baseLogin by lazy { AWConstants.getPrefBaseLogin(preferences) }
    private val basePassword by lazy { AWConstants.getPrefBasePassword(preferences) }

    override val lang = "de"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor(DdosGuardInterceptor(network.client))
        .build()

    private val authClient = network.client.newBuilder()
        .addInterceptor(AniWorldInterceptor(client, preferences))
        .build()

    private val json: Json by injectLazy()

    val context = Injekt.get<Application>()

    // ===== POPULAR ANIME =====
    override fun popularAnimeSelector(): String = "div.seriesListContainer div"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request {
        val headers = Headers.Builder()
            .add("Referer", baseUrl)
            .add("Upgrade-Insecure-Requests", "1")
            .build()
        return GET("$baseUrl/beliebte-animes")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        context
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img").attr("data-src")
        anime.title = element.selectFirst("h3").text()
        return anime
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "div.seriesListContainer div"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/neu")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = baseUrl + linkElement.selectFirst("img").attr("data-src")
        anime.title = element.selectFirst("h3").text()
        return anime
    }

    // ===== SEARCH =====

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val headers = Headers.Builder()
            .add("Referer", "https://aniworld.to/search")
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
    override fun searchAnimeSelector() = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeNextPageSelector() = throw UnsupportedOperationException("Not used.")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val body = response.body!!.string()
        val results = json.decodeFromString<JsonArray>(body)
        val animes = results.filter {
            val link = it.jsonObject["link"]!!.jsonPrimitive.content
            link.startsWith("/anime/stream/") &&
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
        anime.url = link
        return anime
    }

    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException("Not used.")

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.series-title h1 span").text()
        anime.thumbnail_url = baseUrl +
            document.selectFirst("div.seriesCoverBox img").attr("data-src")
        anime.genre = document.select("div.genres ul li").joinToString { it.text() }
        anime.description = document.selectFirst("p.seri_des").attr("data-full-description")
        document.selectFirst("div.cast li:contains(Produzent:) ul")?.let {
            val author = it.select("li").joinToString { li -> li.text() }
            anime.author = author
        }
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListSelector() = throw UnsupportedOperationException("Not used.")

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
        val episodesHtml = authClient.newCall(GET(seasonId)).execute().asJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.map { episodeFromElement(it) }
    }

    private fun parseMoviesFromSeries(element: Element): List<SEpisode> {
        val seasonId = element.attr("abs:href")
        val episodesHtml = authClient.newCall(GET(seasonId)).execute().asJsoup()
        val episodeElements = episodesHtml.select("table.seasonEpisodesList tbody tr")
        return episodeElements.map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        if (element.select("td.seasonEpisodeTitle a").attr("href").contains("/film")) {
            val num = element.attr("data-episode-season-id")
            episode.name = "Film $num" + " : " + element.select("td.seasonEpisodeTitle a span").text()
            episode.episode_number = element.attr("data-episode-season-id").toFloat()
            episode.url = element.selectFirst("td.seasonEpisodeTitle a").attr("href")
        } else {
            val season = element.select("td.seasonEpisodeTitle a").attr("href")
                .substringAfter("staffel-").substringBefore("/episode")
            val num = element.attr("data-episode-season-id")
            episode.name = "Staffel $season Folge $num" + " : " + element.select("td.seasonEpisodeTitle a span").text()
            episode.episode_number = element.select("td meta").attr("content").toFloat()
            episode.url = element.selectFirst("td.seasonEpisodeTitle a").attr("href")
        }
        return episode
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector() = throw UnsupportedOperationException("Not used.")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val gerSubs = getRedirectLinks(document, AWConstants.KEY_GER_SUB)
        val gerDubs = getRedirectLinks(document, AWConstants.KEY_GER_DUB)
        val engSubs = getRedirectLinks(document, AWConstants.KEY_ENG_SUB)
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet(AWConstants.HOSTER_SELECTION, null)
        val redirectInterceptor = client.newBuilder().addInterceptor(RedirectInterceptor()).build()
        gerSubs.forEach {
            val redirectgs = baseUrl + it.selectFirst("a.watchEpisode").attr("href")
            val redirectsgs = redirectInterceptor.newCall(GET(redirectgs)).execute().request.url.toString()
            if (hosterSelection != null) {
                when {
                    redirectsgs.contains("https://voe.sx") || redirectsgs.contains("https://launchreliantcleaverriver") && hosterSelection.contains(AWConstants.NAME_VOE) -> {
                        val quality = "Voe Deutscher Sub"
                        val video = VoeExtractor(client).videoFromUrl(redirectsgs, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    redirectsgs.contains("https://dood") && hosterSelection.contains(AWConstants.NAME_DOOD) -> {
                        val quality = "Doodstream Deutscher Sub"
                        val video = DoodExtractor(client).videoFromUrl(redirectsgs, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    redirectsgs.contains("https://streamtape") && hosterSelection.contains(AWConstants.NAME_STAPE) -> {
                        val quality = "Streamtape Deutscher Sub"
                        val video = StreamTapeExtractor(client).videoFromUrl(redirectsgs, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
            }
        }
        gerDubs.forEach {
            val redirectgd = baseUrl + it.selectFirst("a.watchEpisode").attr("href")
            val redirectsgd = redirectInterceptor.newCall(GET(redirectgd)).execute().request.url.toString()
            if (hosterSelection != null) {
                when {
                    redirectsgd.contains("https://voe.sx") || redirectsgd.contains("https://launchreliantcleaverriver") && hosterSelection.contains(AWConstants.NAME_VOE) -> {
                        val quality = "Voe Deutscher Dub"
                        val video = VoeExtractor(client).videoFromUrl(redirectsgd, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    redirectsgd.contains("https://dood") && hosterSelection.contains(AWConstants.NAME_DOOD) -> {
                        val quality = "Doodstream Deutscher Dub"
                        val video = DoodExtractor(client).videoFromUrl(redirectsgd, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    redirectsgd.contains("https://streamtape") && hosterSelection.contains(AWConstants.NAME_STAPE) -> {
                        val quality = "Streamtape Deutscher Dub"
                        val video = StreamTapeExtractor(client).videoFromUrl(redirectsgd, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
            }
        }
        engSubs.forEach {
            val redirecten = baseUrl + it.selectFirst("a.watchEpisode").attr("href")
            val redirectsen = redirectInterceptor.newCall(GET(redirecten)).execute().request.url.toString()
            if (hosterSelection != null) {
                when {
                    redirectsen.contains("https://voe.sx") || redirectsen.contains("https://launchreliantcleaverriver") && hosterSelection.contains(AWConstants.NAME_VOE) -> {
                        val quality = "Voe Englischer Sub"
                        val video = VoeExtractor(client).videoFromUrl(redirectsen, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    redirectsen.contains("https://dood") && hosterSelection.contains(AWConstants.NAME_DOOD) -> {
                        val quality = "Doodstream Englischer Sub"
                        val video = DoodExtractor(client).videoFromUrl(redirectsen, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    redirectsen.contains("https://streamtape") && hosterSelection.contains(AWConstants.NAME_STAPE) -> {
                        val quality = "Streamtape Englischer Sub"
                        val video = StreamTapeExtractor(client).videoFromUrl(redirectsen, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
            }
        }
        return videoList
    }

    private fun getRedirectLinks(document: Document, key: Int): List<Element> {
        val hosterSelection = preferences.getStringSet(AWConstants.HOSTER_SELECTION, null)
        val selector = "li[class*=episodeLink][data-lang-key="
        return document.select("$selector$key]")
            .filter { hosterSelection?.contains(it.select("div > a > h4").text()) == true }
    }

    override fun videoFromElement(element: Element): Video = throw Exception("not Used")

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(AWConstants.PREFERRED_HOSTER, null)
        val subPreference = preferences.getString(AWConstants.PREFERRED_LANG, "Sub")!!
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
        } else otherList += this
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }

        return newList
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used.")

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = AWConstants.PREFERRED_HOSTER
            title = "Standard-Hoster"
            entries = AWConstants.HOSTER_NAMES
            entryValues = AWConstants.HOSTER_URLS
            setDefaultValue(AWConstants.URL_STAPE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = AWConstants.PREFERRED_LANG
            title = "Bevorzugte Sprache"
            entries = AWConstants.LANGS
            entryValues = AWConstants.LANGS
            setDefaultValue(AWConstants.LANG_GER_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hosterSelection = MultiSelectListPreference(screen.context).apply {
            key = AWConstants.HOSTER_SELECTION
            title = "Hoster auswählen"
            entries = AWConstants.HOSTER_NAMES
            entryValues = AWConstants.HOSTER_NAMES
            setDefaultValue(AWConstants.HOSTER_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(screen.editTextPreference(AWConstants.LOGIN_TITLE, AWConstants.LOGIN_DEFAULT, baseLogin, false, ""))
        screen.addPreference(screen.editTextPreference(AWConstants.PASSWORD_TITLE, AWConstants.PASSWORD_DEFAULT, basePassword, true, ""))
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(hosterSelection)
    }

    private fun PreferenceScreen.editTextPreference(title: String, default: String, value: String, isPassword: Boolean = false, placeholder: String): EditTextPreference {
        return EditTextPreference(context).apply {
            key = title
            this.title = title
            summary = value.ifEmpty { placeholder }
            this.setDefaultValue(default)
            dialogTitle = title

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val res = preferences.edit().putString(title, newValue as String).commit()
                    Toast.makeText(context, "Starte Aniyomi neu, um die Einstellungen zu übernehmen.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    Log.e("Anicloud", "Fehler beim festlegen der Einstellung.", e)
                    false
                }
            }
        }
    }
}
