package eu.kanade.tachiyomi.animeextension.pl.ogladajanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.cdaextractor.CdaPlExtractor
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
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
        .set("Host", baseUrl.toHttpUrl().host)
        .build()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/search/page/$page", headers)
    }
    override fun popularAnimeSelector(): String = "div#anime_main div.card.bg-white"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
            thumbnail_url = element.selectFirst("img")?.attr("data-srcset")
            title = element.selectFirst("h5.card-title > a")!!.text()
        }
    }
    override fun popularAnimeNextPageSelector(): String = "section:has(div#anime_main)" // To nie działa zostało to tylko dlatego by ładowało ale na końcu niestety wyskakuje ze "nie znaleziono" i tak zostaje zamiast zniknać możliwe ze zle fetchuje.

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/search/new/$page", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/name/$query", headers)

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
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector(): String = "ul#ep_list > li:has(div > img)"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val episodeNumber = element.attr("value").toFloatOrNull() ?: 0f
        val episodeText = element.select("div > div > p").text()

        val episodeImg = element.select("div > img").attr("alt").uppercase()

        if (episodeText.isNotEmpty()) {
            episode.name = if (episodeImg == "PL") {
                "${episodeNumber.toInt()} $episodeText"
            } else {
                "${episodeNumber.toInt()} [$episodeImg] $episodeText"
            }
        } else {
            episode.name = if (episodeImg == "PL") {
                "${episodeNumber.toInt()} Odcinek"
            } else {
                "${episodeNumber.toInt()} [$episodeImg] Odcinek"
            }
        }

        episode.episode_number = episodeNumber
        episode.url = element.attr("ep_id")

        return episode
    }

    // ============================ Video Links =============================

    private fun getPlayerUrl(id: String): String {
        val body = FormBody.Builder()
            .add("action", "change_player_url")
            .add("id", id)
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

    override fun videoListRequest(episode: SEpisode): Request {
        val body = FormBody.Builder()
            .add("action", "get_player_list")
            .add("id", episode.url)
            .build()
        return POST("$baseUrl/manager.php", apiHeaders, body)
    }

    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val cdaExtractor by lazy { CdaPlExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val jsonResponse = json.decodeFromString<ApiResponse>(response.body.string())
        val dataObject = json.decodeFromString<ApiData>(jsonResponse.data)
        val serverList = dataObject.players.mapNotNull { player ->
            var sub = player.sub.uppercase()
            if (player.audio == "pl") {
                sub = "Lektor"
            } else if (player.sub.isEmpty() && sub != "Lektor") {
                sub = "Dub " + player.sub.uppercase()
            }

            val subGroup = if (sub == player.sub_group?.uppercase()) "" else player.sub_group
            val subGroupPart = if (subGroup?.isNotEmpty() == true) " $subGroup - " else " "

            val prefix = if (player.ismy > 0) {
                if (player.sub == "pl" && player.sub_group?.isNotEmpty() == true) {
                    "[Odwrócone Kolory] $subGroup - "
                } else {
                    "[$sub/Odwrócone Kolory]$subGroupPart"
                }
            } else {
                if (player.sub == "pl" && player.sub_group?.isNotEmpty() == true) {
                    "$subGroup - "
                } else {
                    "[$sub]$subGroupPart"
                }
            }

            if (player.url !in listOf("vk", "cda", "mp4upload", "sibnet", "dailymotion")) {
                return@mapNotNull null
            }
            val url = getPlayerUrl(player.id)
            Pair(url, prefix)
        }
        // Jeśli dodadzą opcje z mozliwością edytowania mpv to zrobić tak ze jak bedą odwrócone kolory to ustawia dane do mkv <3
        return serverList.parallelCatchingFlatMapBlocking { (serverUrl, prefix) ->
            when {
                serverUrl.contains("vk.com") -> {
                    vkExtractor.videosFromUrl(serverUrl, prefix)
                }
                serverUrl.contains("mp4upload") -> {
                    mp4uploadExtractor.videosFromUrl(serverUrl, headers, prefix)
                }
                serverUrl.contains("cda.pl") -> {
                    cdaExtractor.getVideosFromUrl(serverUrl, headers, prefix)
                }
                serverUrl.contains("dailymotion") -> {
                    dailymotionExtractor.videosFromUrl(serverUrl, "$prefix Dailymotion -")
                }
                serverUrl.contains("sibnet.ru") -> {
                    sibnetExtractor.videosFromUrl(serverUrl, prefix)
                }
                else -> emptyList()
            }
        }
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    @Serializable
    data class ApiPlayer(
        val id: String,
        val audio: String? = null,
        val sub: String,
        val url: String,
        val sub_group: String? = null,
        val ismy: Int,
    )

    @Serializable
    data class ApiData(
        val players: List<ApiPlayer>,
    )

    @Serializable
    data class ApiResponse(
        val data: String,
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
