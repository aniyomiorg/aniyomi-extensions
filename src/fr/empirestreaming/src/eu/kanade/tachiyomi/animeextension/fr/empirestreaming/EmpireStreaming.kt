package eu.kanade.tachiyomi.animeextension.fr.empirestreaming

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
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
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class EmpireStreaming : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EmpireStreaming"

    override val baseUrl by lazy { preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "fr"

    override val supportsLatest = false

    override val client = network.cloudflareClient

    private val vclient: OkHttpClient = network.client

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    override fun popularAnimeSelector(): String = "div.d-f.fd-r.h-100.ox-s.w-100.py-2 div.card-custom-4"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.url = "/" + element.select("a.btn-link-card-5").attr("href")
        Log.i("animeUrl", anime.url)
        anime.thumbnail_url = baseUrl + element.select("picture img").attr("data-src")
        anime.title = element.select("h3.line-h-s").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        if (document.select("div.c-w span.ff-fb.tt-u").text().contains("serie")) {
            val season = document.select("div.episode.w-100 ul.episode-by-season")
            season.forEach {
                val episode = parseEpisodesFromSeries(it, response)
                episodeList.addAll(episode)
            }
        } else {
            val episode = SEpisode.create()
            episode.name = document.select("h1.fs-84").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element, response: Response): List<SEpisode> {
        val episodeElements = element.select("li.card-serie")
        return episodeElements.map { episodeFromElementR(it, response) }
    }

    private fun episodeFromElementR(element: Element, response: Response): SEpisode {
        val episode = SEpisode.create()
        val url = response.request.url.toString()
        val season = element.attr("data-season")
        val ep = element.attr("data-episode")
        episode.name = "Saison $season Épisode $ep : " + element.select("p.mb-0.fs-14").text()
        episode.episode_number = element.attr("data-episode").toFloat()
        episode.setUrlWithoutDomain("$url?saison=$season&episode=$ep")
        return episode
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("not Used")

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val season = response.request.url.toString()
            .substringAfter("saison=").substringBefore("&").toInt()
        val ep = response.request.url.toString()
            .substringAfter("episode=").toInt()
        return videosFromElement(document, season, ep)
    }

    private fun videosFromElement(document: Document, season: Int, ep: Int): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        if (document.select("div.c-w span.ff-fb.tt-u").text().contains("film")) {
            val script = document.select("script:containsData(const result = [)").toString()
            val hosts = script.split("},{")
            hosts.forEach {
                val hostn = it.substringAfter("\"property\":\"").substringBefore("\",")
                when {
                    hostn.contains("voe") && hosterSelection.contains("voe") -> {
                        val id = it.substringAfter("\"code\":\"").substringBefore("\",")
                        val url = "https://voe.sx/e/$id"
                        val video = VoeExtractor(vclient).videoFromUrl(url)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }

                    hostn.contains("streamsb") && hosterSelection.contains("streamsb") -> {
                        val id = it.substringAfter("\"code\":\"").substringBefore("\",")
                        val url = "https://playersb.com/e/$id"
                        val video = StreamSBExtractor(vclient).videosFromUrl(url, headers, common = false)
                        videoList.addAll(video)
                    }

                    hostn.contains("doodstream") && hosterSelection.contains("dood") -> {
                        val id = it.substringAfter("\"code\":\"").substringBefore("\",")
                        val url = "https://dood.pm/e/$id"
                        val quality = "Dood"
                        val video = DoodExtractor(vclient).videosFromUrl(url, quality)
                        videoList.addAll(video)
                    }
                }
            }
        } else {
            val script = document.select("script:containsData(const result = {\"1\")").toString()
            val hosts = script.split("]},{")
            hosts.forEach { host ->
                if (host.substringAfter("\"episode\":").substringBefore(",").toInt() == ep && host.substringAfter("\"saison\":").substringBefore(",").toInt() == season) {
                    val videoarray = host.substringAfter("\"video\":[").substringBefore("],")
                    val videos = videoarray.split("},{")
                    videos.forEach { videofile ->
                        val hostn = videofile.substringAfter("\"property\":\"").substringBefore("\",")
                        when {
                            hostn.contains("voe") && hosterSelection.contains("voe") -> {
                                val id = videofile.substringAfter("\"code\":\"").substringBefore("\",")
                                val version = videofile.substringAfter("\"version\":\"").substringBefore("\"")
                                val quality = "Voe $version"
                                val url = "https://voe.sx/e/$id"
                                val video = VoeExtractor(vclient).videoFromUrl(url, quality)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }

                            hostn.contains("streamsb") && hosterSelection.contains("streamsb") -> {
                                val id = videofile.substringAfter("\"code\":\"").substringBefore("\",")
                                val quality = videofile.substringAfter("\"version\":\"").substringBefore("\"")
                                val url = "https://playersb.com/e/$id"
                                val video = StreamSBExtractor(vclient).videosFromUrl(url, headers, quality, common = false)
                                videoList.addAll(video)
                            }

                            hostn.contains("doodstream") && hosterSelection.contains("dood") -> {
                                val id = videofile.substringAfter("\"code\":\"").substringBefore("\",")
                                val url = "https://dood.pm/e/$id"
                                val version = videofile.substringAfter("\"version\":\"").substringBefore("\"")
                                val quality = "Dood $version"
                                val video = DoodExtractor(vclient).videosFromUrl(url, quality)
                                videoList.addAll(video)
                            }
                        }
                    }
                }
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        return sortedWith(
            compareByDescending { it.url.contains(hoster) },
        ).reversed()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not Used")

    override fun searchAnimeNextPageSelector(): String? = null

    override fun searchAnimeSelector(): String = throw Exception("not Used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = POST("$baseUrl/api/views/search", body = "{\"search\":\"$query\"}".toRequestBody("application/json".toMediaType()))

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parseSearchAnimeJson(responseString)
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jObject = json.decodeFromString<JsonObject>(jsonData)
        val animeList = mutableListOf<SAnime>()
        val data = jObject["data"]!!.jsonObject
        val arrayf = data.jsonObject["films"]!!.jsonArray
        Log.i("search", arrayf.toString())
        for (item in arrayf) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val urlpath = item.jsonObject["urlPath"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("/$urlpath")
            val symimage = item.jsonObject["sym_image"]!!.jsonObject
            val poster = symimage.jsonObject["poster"]!!.jsonPrimitive.content
            anime.thumbnail_url = "$baseUrl/images/medias/$poster"
            animeList.add(anime)
        }
        val arrays = data.jsonObject["series"]!!.jsonArray
        for (item in arrays) {
            val anime = SAnime.create()
            anime.title = item.jsonObject["title"]!!.jsonPrimitive.content
            val urlpath = item.jsonObject["urlPath"]!!.jsonPrimitive.content
            anime.setUrlWithoutDomain("/$urlpath")
            val symimage = item.jsonObject["sym_image"]!!.jsonObject
            val poster = symimage.jsonObject["poster"]!!.jsonPrimitive.content
            anime.thumbnail_url = "$baseUrl/images/medias/$poster"
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage = false)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.fs-40").text()
        anime.genre = document.select("ul.d-f li.mr-1 font").joinToString(", ") { it.text() }
        anime.description = document.select("p.description").text()
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_VALUES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_DOMAIN_KEY = "preferred_domain"
        private const val PREF_DOMAIN_TITLE = "Preferred domain (requires app restart)"
        private const val PREF_DOMAIN_DEFAULT = "https://empire-stream.net"
        private val PREF_DOMAIN_ENTRIES = arrayOf("https://empire-stream.net", "https://empire-streaming.app")
        private val PREF_DOMAIN_VALUES = PREF_DOMAIN_ENTRIES

        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Hébergeur standard"
        private const val PREF_HOSTER_DEFAULT = "https://voe.sx"
        private val PREF_HOSTER_ENTRIES = arrayOf("Voe", "StreamSB", "Dood")
        private val PREF_HOSTER_VALUES = arrayOf("https://voe.sx", "https://playersb.com", "https://dood")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Sélectionnez l'hôte"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Voe", "StreamSB", "Dood")
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("voe", "streamsb", "dood")
        private val PREF_HOSTER_SELECTION_DEFAULT = setOf("voe", "streamsb", "dood")
    }
}
