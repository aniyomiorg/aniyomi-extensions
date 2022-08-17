package eu.kanade.tachiyomi.animeextension.fr.nekosama

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.nekosama.extractors.StreamTapeExtractor
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

class NekoSama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Neko-Sama"

    override val baseUrl = "https://neko-sama.fr"

    override val lang = "fr"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.anime"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.info a").attr("href")
        )
        anime.title = element.select("div.info a div").text()
        val thumb1 = element.select("div.cover a div img").attr("data-src")
        val thumb2 = element.select("div.cover a div img").attr("src")
        anime.thumbnail_url = thumb1.ifBlank { thumb2 }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nekosama.pagination a svg"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageBody = response.asJsoup()
        return pageBody.select("div.row.no-gutters.js-list-episode-container div.col-lg-4.col-sm-6.col-xs-6").map {
            SEpisode.create().apply {
                url = it.select("div div.text a").attr("href")
                episode_number = it.select("div div.text a span").text().substringAfter(". ").toFloat()
                name = it.select("div div.text a span").text()
            }
        }
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        // probably exists a better way to make this idk
        val script = document.selectFirst("script:containsData(var video = [];)").data()
        val firstVideo = script.substringBefore("else {").substringAfter("video[0] = '").substringBefore("'").lowercase()
        val secondVideo = script.substringAfter("else {").substringAfter("video[0] = '").substringBefore("'").lowercase()

        when {
            firstVideo.contains("streamtape") -> StreamTapeExtractor(client).videoFromUrl(firstVideo, "StreamTape")?.let { videoList.add(it) }
            firstVideo.contains("pstream") -> videoList.add(PstreamExtractor(firstVideo))
        }
        when {
            secondVideo.contains("streamtape") -> StreamTapeExtractor(client).videoFromUrl(secondVideo, "StreamTape")?.let { videoList.add(it) }
            secondVideo.contains("pstream") -> videoList.add(PstreamExtractor(secondVideo))
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Sabrosio")
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
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter

        return when {
            query.isNotBlank() -> throw Exception("Recherche de texte non prise en charge")
            typeFilter.state != 0 || query.isNotBlank() -> when (page) {
                1 -> GET("$baseUrl/${typeFilter.toUriPart()}")
                else -> GET("$baseUrl/${typeFilter.toUriPart()}/$page")
            }
            else -> when (page) {
                1 -> GET("$baseUrl/anime/")
                else -> GET("$baseUrl/anime/page/$page")
            }
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.col.offset-lg-3.offset-md-4 h1").text()
        anime.description = document.select("div.synopsis p").text()
        anime.thumbnail_url = document.select("div.cover img").attr("src")
        val a = document.select("div.cover img")

        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("Not used")

    override fun latestUpdatesSelector() = throw Exception("Not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Utilisez ce filtre pour affiner votre recherche"),
        TypeFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "VOSTFR or VF",
        arrayOf(
            Pair("<sélectionner>", "none"),
            Pair("VOSTFR", "anime"),
            Pair("VF", "anime-vf")
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
            entries = arrayOf("Pstream")
            entryValues = arrayOf("Pstream")
            setDefaultValue("Pstream")
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

    private fun PstreamExtractor(url: String): Video {
        val noVideo = "http://commondatastorage.googleapis.com/gtv-videos-bucket/sample/TearsOfSteel.mp4"
        val document = Jsoup.connect(url).headers(
            mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Connection" to "keep-alive"
            )
        ).get()
        document.select("script").forEach { Script ->

            if (Script.attr("src").contains("https://www.pstream.net/u/player-script")) {
                val playerScript = Jsoup.connect(Script.attr("src")).headers(
                    mapOf(
                        "Accept" to "*/*",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Connection" to "keep-alive"
                    )
                ).ignoreContentType(true).execute().body()

                val base64Data = playerScript.substringAfter("e.parseJSON(atob(t).slice(2))}(\"").substringBefore("\"")

                val base64Decoded = Base64.decode(base64Data, Base64.DEFAULT).toString(Charsets.UTF_8)

                val videoUrl = base64Decoded.substringAfter("mmmm\":\"").substringBefore("\"")

                val videoUrlDecoded = videoUrl.replace("\\", "")
                val headers = headers.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Accept-Encoding", "gzip, deflate, br")
                    add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                    add("Connection", "keep-alive")
                    add("Referer", url)
                }.build()
                return Video(videoUrlDecoded, "Pstream", videoUrlDecoded, headers = headers)
            }
        }
        return Video(noVideo, "NO VIDEO", noVideo)
    }
}

/*

 */
