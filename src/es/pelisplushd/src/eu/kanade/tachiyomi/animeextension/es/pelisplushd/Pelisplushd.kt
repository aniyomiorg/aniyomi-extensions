package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.StreamSBExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Pelisplushd : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Pelisplushd"

    override val baseUrl = "https://pelisplushd.net"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.Posters a.Posters-link"

    override fun popularAnimeRequest(page: Int): Request = GET("https://pelisplushd.net/series?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("a").attr("href")
        )
        anime.title = element.select("a div.listing-content p").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.page-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                val epnum = 1
                episode_number = epnum.toFloat()
                name = "PELICULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        } else {
            jsoup.select("div.tab-content div a").forEachIndexed { index, element ->
                Log.i("bruh", "episodio:$index, nombre:${element.text()}")
                val epNum = index + 1
                val episode = SEpisode.create()
                episode.episode_number = epNum.toFloat()
                episode.name = element.text()
                episode.setUrlWithoutDomain(element.attr("href"))
                episodes.add(episode)
                Log.i("bruh", "${episodes[index].name}")
            }
            episodes.removeLast()
        }
        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()

        document.select("ul.TbVideoNv li").forEach { it ->
            val server = it.select("a").text()
            val option = it.attr("data-id")
            document.select("script").forEach() { script ->
                if (script.data().contains("video[1] =")) {

                    if (server == "PlusTo") {
                        val iframeUrl = script.data().substringAfter("video[$option] = '").substringBefore("';")
                        val jsoup = Jsoup.connect(iframeUrl).get()
                        val url = jsoup.select("iframe").attr("src").replace("#caption=&poster=#", "")
                        Log.i("bruh", "$url")

                        val videos = FembedExtractor().videosFromUrl(url)
                        videoList.addAll(videos)
                    }
                    if (server == "DoodStream") {
                        val url = script.data().substringAfter("video[$option] = '").substringBefore("';")
                        val video = try { DoodExtractor(client).videoFromUrl(url, "DoodStream") } catch (e: Exception) { null }
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                    if (server == "SBFast") {
                        val url = script.data().substringAfter("video[$option] = '").substringBefore("';")
                        val headers = headers.newBuilder()
                            .set("Referer", url)
                            .set("User-Agent", "Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:96.0) Gecko/20100101 Firefox/96.0")
                            .set("Accept-Language", "en-US,en;q=0.5")
                            .set("watchsb", "streamsb")
                            .build()
                        val video = StreamSBExtractor(client).videosFromUrl(url, headers)
                        videoList.addAll(video)
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "StreamTape")
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
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/search?s=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> GET("$baseUrl/peliculas?page=$page ")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("h1.m-b-5").text()
        anime.description = document.selectFirst("div.col-sm-4 div.text-large").ownText()
        anime.genre = document.select("div.p-v-20.p-h-15.text-center a span").joinToString { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Doramas", "generos/dorama"),
            Pair("Animes", "animes")

        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("StreamSB:360p", "StreamSB:720p", "StreamSB:1080p", "Fembed:480p", "Fembed:720p", "Fembed:1080p", "DoodStream")
            entryValues = arrayOf("StreamSB:360p", "StreamSB:720p", "StreamSB:1080p", "Fembed:480p", "Fembed:720p", "Fembed:1080p", "DoodStream")
            setDefaultValue("Fembed:720p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
