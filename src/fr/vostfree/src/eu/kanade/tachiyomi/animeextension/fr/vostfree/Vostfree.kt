package eu.kanade.tachiyomi.animeextension.fr.vostfree

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Vostfree : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Vostfree"

    override val baseUrl = "https://vostfree.ws"

    override val lang = "fr"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div#page-content div.page-left div#content div#dle-content div.movie-poster"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/films-vf-vostfr/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.movie-poster div.play a").attr("href"),
        )
        anime.title = element.select("div.movie-poster div.info.hidden div.title").text()
        anime.thumbnail_url = baseUrl + element.select("div.movie-poster span.image img").attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "span.next-page"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        jsoup.select("select.new_player_selector option").forEachIndexed { index, it ->
            val epNum = it.text().replace("Episode", "").drop(2)

            if (it.text() == "Film") {
                val episode = SEpisode.create().apply {
                    episode_number = "1".toFloat()
                    name = "Film"
                }
                episode.url = "?episode:0/${response.request.url}"
                episodes.add(episode)
            } else {
                val episode = SEpisode.create().apply {
                    episode_number = epNum.toFloat()
                    name = "Épisode $epNum"
                }
                episode.setUrlWithoutDomain("?episode:$index/${response.request.url}")
                episodes.add(episode)
            }
        }

        return episodes
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val epNum = response.request.url.toString().substringAfter("$baseUrl/?episode:").substringBefore("/")
        val realUrl = response.request.url.toString().replace("$baseUrl/?episode:$epNum/", "")

        val document = client.newCall(GET(realUrl)).execute().asJsoup()
        val videoList = mutableListOf<Video>()
        val allPlayerIds = document.select("div.tab-content div div.new_player_top div.new_player_bottom div.button_box")[epNum.toInt()]

        allPlayerIds.select("div").forEach {
            val server = it.text()
            if (server.lowercase() == "vudeo") {
                val playerId = it.attr("id")
                val url = document.select("div#player-tabs div.tab-blocks div.tab-content div div#content_$playerId").text()
                runCatching {
                    val video = VudeoExtractor(client).videosFromUrl(url)
                    videoList.addAll(video)
                }
            }
            if (server.lowercase() == "ok") {
                val playerId = it.attr("id")
                val url = "https://ok.ru/videoembed/" + document.select("div#player-tabs div.tab-blocks div.tab-content div div#content_$playerId").text()
                val video = OkruExtractor(client).videosFromUrl(url, "", false)
                videoList.addAll(video)
            }
            if (server.lowercase() == "doodstream") {
                val playerId = it.attr("id")
                val url = document.select("div#player-tabs div.tab-blocks div.tab-content div div#content_$playerId").text()
                val video = DoodExtractor(client).videoFromUrl(url, "DoodStream", false)
                if (video != null) {
                    videoList.add(video)
                }
            }
        }

        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Vudeo")
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
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter

        val formData = FormBody.Builder()
            .addEncoded("do", "search")
            .addEncoded("subaction", "search")
            .addEncoded("search_start", "0")
            .addEncoded("full_search", "0")
            .addEncoded("result_from", "1")
            .addEncoded("story", query)
            .build()

        return when {
            query.isNotBlank() -> try { POST("$baseUrl/index.php?do=search", headers, formData) } catch (e: Exception) { GET("$baseUrl/animes-vostfr/page/$page/") }
            genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/page/$page/")
            typeFilter.state != 0 -> GET("$baseUrl/${typeFilter.toUriPart()}/page/$page/")
            else -> GET("$baseUrl/animes-vostfr/page/$page/")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.search-result")
        val animeList2 = document.select("div.movie-poster")
        val animes = animeList.map {
            searchAnimeFromElement(it)
        } + animeList2.map {
            searchAnimeFromElement(it)
        }
        return AnimesPage(animes, false)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return when {
            element.select("div.search-result").toString() != "" -> searchPopularAnimeFromElement(element)
            else -> popularAnimeFromElement(element)
        }
    }

    private fun searchPopularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.search-result div.info div.title a").attr("href"),
        )
        anime.title = element.select("div.search-result div.info div.title a").text()
        anime.thumbnail_url = baseUrl + element.select("div.search-result span.image img").attr("src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = "div#dle-content"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.slide-middle h1").text()
        anime.description = document.selectFirst("div.slide-desc")!!.ownText()
        anime.genre = document.select("div.image-bg-content div.slide-block div.slide-middle ul.slide-top li.right a").joinToString { it.text() }
        return anime
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "types",
        arrayOf(
            Pair("<pour sélectionner>", ""),
            Pair("Animes VF", "animes-vf"),
            Pair("Animes VOSTFR", "animes-vostfr"),
            Pair("FILMS", "films-vf-vostfr"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "genre",
        arrayOf(
            Pair("<pour sélectionner>", ""),
            Pair("Action", "Action"),
            Pair("Comédie", "Comédie"),
            Pair("Drame", "Drame"),
            Pair("Surnaturel", "Surnaturel"),
            Pair("Shonen", "Shonen"),
            Pair("Romance", "Romance"),
            Pair("Tranche de vie", "Tranche+de+vie"),
            Pair("Fantasy", "Fantasy"),
            Pair("Mystère", "Mystère"),
            Pair("Psychologique", "Psychologique"),
            Pair("Sci-Fi", "Sci-Fi"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Vudeo", "Doodstream")
            entryValues = arrayOf("Vudeo", "Doodstream")
            setDefaultValue("Vudeo")
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
