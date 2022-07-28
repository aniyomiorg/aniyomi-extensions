package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.JsUnpacker
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.uploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimeonlineNinja : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeOnline.Ninja"

    override val baseUrl = "https://www1.animeonline.ninja"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.content.right div.items article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tendencias/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.data h3 a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src").replace("-185x278", "")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.arrow_pag i#nextpagination"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val url = response.request.url.toString()
        if (url.contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1.0F
                name = "Pelicula"
                setUrlWithoutDomain(url)
            }
            return listOf(episode)
        }
        val document = response.asJsoup()
        document.select("div#serie_contenido div#seasons div.se-c div.se-a ul.episodios li").forEach {
            val epTemp = it.select("div.numerando").text().substringBefore("-").replace(" ", "")
            val epNum = it.select("div.numerando").text().substringAfter("-").replace(" ", "").replace(".", "")
            val epName = it.select("div.episodiotitle a").text()
            if (epTemp.isNotBlank() && epNum.isNotBlank()) {
                val episode = SEpisode.create().apply {
                    episode_number = "$epTemp.$epNum".toFloat()
                    name = "T$epTemp $epName"
                }
                episode.setUrlWithoutDomain(it.select("div.episodiotitle a").attr("href"))
                episodes.add(episode)
            }
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        if (multiserverCheck(document)) {
            val datapost = document.selectFirst("#playeroptionsul li").attr("data-post")
            val datatype = document.selectFirst("#playeroptionsul li").attr("data-type")
            val apiCall = client.newCall(GET("https://www1.animeonline.ninja/wp-json/dooplayer/v1/post/$datapost?type=$datatype&source=1")).execute().asJsoup().body()
            val iframeLink = apiCall.toString().substringAfter("{\"embed_url\":\"").substringBefore("\"")
            val sDocument = client.newCall(GET(iframeLink)).execute().asJsoup()
            sDocument.select("div.ODDIV div").forEach {
                val lang = it.attr("class").toString().substringAfter("OD OD_").replace("REactiv", "")
                it.select("li").forEach { source ->
                    val sourceUrl = source.attr("onclick").toString().substringAfter("go_to_player('").substringBefore("')")
                    serverslangParse(sourceUrl, lang).map { video -> videoList.add(video) }
                }
            }
        } else {
            val datapost = document.selectFirst("#playeroptionsul li").attr("data-post")
            val datatype = document.selectFirst("#playeroptionsul li").attr("data-type")
            document.select("#playeroptionsul li").forEach {
                val sourceId = it.attr("data-nume")
                val apiCall = client.newCall(GET("https://www1.animeonline.ninja/wp-json/dooplayer/v1/post/$datapost?type=$datatype&source=$sourceId")).execute().asJsoup().body()
                val sourceUrl = apiCall.toString().substringAfter("{\"embed_url\":\"").substringBefore("\"").replace("\\/", "/")

                val lang2 = preferences.getString("preferred_lang", "SUB").toString()
                serverslangParse(sourceUrl, lang2).map { video -> videoList.add(video) }
            }
        }

        return videoList
    }

    private fun multiserverCheck(document: Document): Boolean {
        document.select("#playeroptionsul li").forEach {
            val title = it.select("span.title").text()
            val url = it.select("span.server").toString()
            if (title.lowercase() == "multiserver" || url.contains("saidochesto.top")) {
                return true
            }
        }
        return false
    }

    private fun serverslangParse(serverUrl: String, lang: String): List<Video> {
        val videos = mutableListOf<Video>()
        val langSelect = preferences.getString("preferred_lang", "SUB").toString()
        when {
            serverUrl.contains("fembed888") && lang.contains(langSelect) -> {
                videos.addAll(FembedExtractor().videosFromUrl(serverUrl, lang))
            }
            serverUrl.contains("streamtape") && lang.contains(langSelect) -> {
                StreamTapeExtractor(client).videoFromUrl(serverUrl, "$lang StreamTape")?.let { it1 -> videos.add(it1) }
            }
            serverUrl.contains("dood") && lang.contains(langSelect) -> {
                DoodExtractor(client).videoFromUrl(serverUrl, "$lang DoodStream")?.let { it1 -> videos.add(it1) }
            }
            serverUrl.contains("sb") && lang.contains(langSelect) -> {
                try {
                    val headers = headers.newBuilder()
                        .set("referer", serverUrl)
                        .set(
                            "User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/103.0.0.0 Safari/537.36"
                        )
                        .set("Accept-Language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                        .set("watchsb", "streamsb")
                        .set("authority", "embedsb.com")
                        .build()
                    videos.addAll(StreamSBExtractor(client).videosFromUrl(serverUrl, headers, lang))
                } catch (e: Exception) { }
            }
            serverUrl.contains("mixdrop") && lang.contains(langSelect) -> {
                val jsE = client.newCall(GET(serverUrl)).execute().asJsoup().selectFirst("script:containsData(eval)").data()
                if (jsE.contains("MDCore.wurl=")) {
                    val url = "http:" + JsUnpacker(jsE).unpack().toString().substringAfter("MDCore.wurl=\"").substringBefore("\"")
                    if (!url.contains("\$(document).ready(function(){});")) {
                        videos.add(Video(url, "$lang MixDrop", url))
                    }
                }
            }
            serverUrl.contains("wolfstream") && lang.contains(langSelect) -> {
                val jsE = client.newCall(GET(serverUrl)).execute().asJsoup().selectFirst("script:containsData(sources)").data()
                val url = jsE.substringAfter("{file:\"").substringBefore("\"")
                videos.add(Video(url, "$lang WolfStream", url))
            }
            serverUrl.contains("uqload") && lang.contains(langSelect) -> {
                val headers = headers.newBuilder().add("referer", "https://uqload.com/").build()
                val video = uploadExtractor(client).videofromurl(serverUrl, headers, lang)
                videos.add(video)
            }
        }

        return videos
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Amazon")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query")
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("article div.details div.title a").attr("href"))
        anime.title = element.select("article div.details div.title a").text()
        anime.thumbnail_url = element.select("article div.image div.thumbnail.animation-2 a img").attr("data-src").replace("-150x150", "")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "div.search-page div.result-item"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.sheader div.data h1").text()
        val uselessTags = listOf("supergoku", "younime", "zonamixs", "monoschinos", "otakustv", "Hanaojara", "series flv", "zenkimex", "Crunchyroll")
        anime.genre = document.select("div.sheader div.data div.sgeneros a").joinToString("") {
            if (it.text() in uselessTags || it.text().lowercase().contains("anime")) {
                ""
            } else {
                it.text() + ", "
            }
        }
        anime.description = document.select("div.wp-content p").joinToString { it.text() }
        anime.author = document.select("div.sheader div.data div.extra span a").text()
        return anime
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("https://www1.animeonline.ninja/genero/en-emision/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("SUB Fembed:480p", "SUB Fembed:720p", "SUB Fembed:1080p")
            entryValues = arrayOf("SUB Fembed:480p", "SUB Fembed:720p", "SUB Fembed:1080p")
            setDefaultValue("SUB Fembed:720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val langPref = ListPreference(screen.context).apply {
            key = "preferred_lang"
            title = "Preferred language"
            entries = arrayOf("SUB", "All", "ES", "LAT")
            entryValues = arrayOf("SUB", "", "ES", "LAT")
            setDefaultValue("SUB")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(langPref)
    }
}
