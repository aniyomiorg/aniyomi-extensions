package eu.kanade.tachiyomi.animeextension.pl.ogladajanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pl.ogladajanime.extractors.CdaPlExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class OgladajAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "OgladajAnime"

    override val baseUrl = "https://ogladajanime.pl"

    override val lang = "pl"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val apiHeaders = Headers.Builder()
        .set("Accept", "application/json, text/plain, */*")
        .set("Referer", "$baseUrl/")
        .set("Origin", baseUrl)
        .set("Accept-Language", "pl,en-US;q=0.7,en;q=0.3")
        .set("Host", "ogladajanime.pl")
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0")
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/search/page/$page")
    }

    override fun popularAnimeSelector(): String = "div#anime_main div.card.bg-white"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            url = element.selectFirst("a")!!.attr("href")
            thumbnail_url = element.selectFirst("img")?.attr("data-srcset")
            title = element.selectFirst("h5.card-title > a")!!.text()
        }
    }
    override fun popularAnimeNextPageSelector(): String = "section:has(div#anime_main)" // To nie działa zostało to tylko dlatego by ładowało ale na końcu niestety wyskakuje ze "nie znaleziono" i tak zostaje zamiast zniknać możliwe ze zle fetchuje.

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search/new/$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

//    override suspend fun getSearchAnime(
//        page: Int,
//        query: String,
//        filters: AnimeFilterList,
//    ): AnimesPage {
//        return client.newCall(searchAnimeRequest(page, query, filters))
//            .awaitSuccess()
//            .let { response ->
//                searchAnimeParse(response, query)
//            }
//    }
//
//    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
//        val document = response.asJsoup()
//
//        val animes = document.select(searchAnimeSelector()).map { element ->
//            searchAnimeFromElement(element)
//        }
//
//        return AnimesPage(animes, false)
//    }
//  private fun searchAnimeSelector(query: String): String = "null"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/name/$query")

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String? = null

    // prosta bez filtrów jak na razie :) są dziury ale to kiedyś sie naprawi hihi. Wystarczy dobrze wyszukać animca i powinno wyszukać.

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply {
            // status = document.selectFirst("div.toggle-content > ul > li:contains(Status)")?.let { parseStatus(it.text()) } ?: SAnime.UNKNOWN // Nie pamietam kiedyś sie to naprawi.
            description = document.selectFirst("p#animeDesc")?.text()
            genre = document.select("div.row > div.col-12 > span.badge[href^=/search/name/]").joinToString(", ") {
                it.text()
            }
            author = document.select("div.row > div.col-12:contains(Studio:) > span.badge[href=#]").joinToString(", ") {
                it.text()
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val url = baseUrl + anime.url
        return GET(url, apiHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select("ul#ep_list > li").forEach { element ->
            val episode = SEpisode.create()
            val episodeNumber = element.attr("value").toFloatOrNull() ?: 0f
            val episodeText = element.select("div > div > p").text()

            val episodeImg = element.select("div > img").attr("alt")
            val check = element.select("div > img")

            if (check.isNotEmpty()) {
                episode.name = if (episodeText.isNotEmpty()) {
                    "[${episodeNumber.toInt()}] $episodeText ($episodeImg)"
                } else {
                    "Episode ${episodeNumber.toInt()} ($episodeImg)"
                }
                episode.episode_number = episodeNumber
                episode.url = element.attr("ep_id")
                episodeList.add(episode)
            }
        }
        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    private fun getPlayerUrl(player: String): String {
        val body = FormBody.Builder()
            .add("action", "change_player_url")
            .add("id", player)
            .build()
        return client.newCall(POST("$baseUrl/manager.php", apiHeaders, body))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"data\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val body = FormBody.Builder()
            .add("action", "get_player_list")
            .add("id", episode.url)
            .build()
        val response = client.newCall(POST("$baseUrl/manager.php", apiHeaders, body)).await()

        val serverList = mutableListOf<Pair<String, String>>()

        val jsonObject = JSONObject(response.body.string())
        val dataString = jsonObject.getString("data")

        val dataObject = JSONObject(dataString)
        val playersArray = dataObject.getJSONArray("players")

        (0 until playersArray.length()).forEach {
            val player = playersArray.getJSONObject(it)
            val id = player.getString("id")
            var sub = player.getString("sub").uppercase()
            val ismy = player.getInt("ismy")

            // Modify sub if it's PL
            if (player.getString("audio") == "pl") {
                sub = "Dub PL"
            }

            val prefix = if (ismy > 0) {
                "[$sub/Odwrócone Kolory] "
            } else {
                "[$sub] "
            }

            val check = player.getString("url")
            if (check in listOf("vk", "cda", "mp4upload", "sibnet", "dailymotion")) {
                val url = getPlayerUrl(id)
                serverList.add(Pair(url, prefix))
            }
        }

        val videoList = serverList.parallelCatchingFlatMap { (serverUrl, prefix) ->
            when {
                serverUrl.contains("vk.com") -> {
                    VkExtractor(client, headers).videosFromUrl(serverUrl, prefix)
                }
                serverUrl.contains("mp4upload") -> {
                    Mp4uploadExtractor(client).videosFromUrl(serverUrl, headers, prefix)
                }
                serverUrl.contains("cda.pl") -> {
                    CdaPlExtractor(client).getVideosFromUrl(serverUrl, headers, prefix)
                }
                serverUrl.contains("dailymotion") -> {
                    DailymotionExtractor(client, headers).videosFromUrl(serverUrl, "$prefix Dailymotion -")
                }
                serverUrl.contains("sibnet.ru") -> {
                    SibnetExtractor(client).videosFromUrl(serverUrl, prefix)
                }
                else -> null
            }.orEmpty()
        }

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun EpisodeType.toJsonString(): String {
        return json.encodeToString(this)
    }

    @Serializable
    data class EpisodeType(
        val type: String,
        val url: List<String>,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "cda.pl")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferowana jakość"
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
        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferowany serwer"
            entries = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            entryValues = arrayOf("cda.pl", "Dailymotion", "Mp4upload", "Sibnet", "vk.com")
            setDefaultValue("cda.pl")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }
}
