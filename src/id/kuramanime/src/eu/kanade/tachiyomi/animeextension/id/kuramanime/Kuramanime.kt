package eu.kanade.tachiyomi.animeextension.id.kuramanime

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

class Kuramanime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val name = "Kuramanime"

    override val baseUrl = "https://kuramanime.net"

    override val lang = "id"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val status = parseStatus(document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)").text().replace("Status: ", ""))
        anime.title = document.select("div.anime__details__title > h3").text().replace("Judul: ", "")
        anime.genre = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)").text().replace("Genre: ", "")
        anime.status = status
        anime.artist = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(5)").text()
        anime.author = "UNKNOWN"
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Sedang Tayang" -> SAnime.ONGOING
            "Selesai Tayang" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epsNum = getNumberFromEpsString(element.text())
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = when {
            (epsNum.isNotEmpty()) -> epsNum.toFloat()
            else -> 1F
        }
        episode.name = element.text()

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun episodeListSelector(): String = "#episodeLists"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()

        val html = document.select(episodeListSelector()).attr("data-content")
        val jsoupE = Jsoup.parse(html)

        return jsoupE.select("a").filter { ele -> !ele.attr("href").contains("batch") }.map { episodeFromElement(it) }
    }

    private fun parseShortInfo(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").first().attr("href"))
        anime.thumbnail_url = element.select("a > div").first().attr("data-setbg")
        anime.title = element.select("div.product__item__text > h5").text()
        return anime
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = parseShortInfo(element)

    override fun latestUpdatesNextPageSelector(): String = "div.product__pagination > a:last-child"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime?order_by=updated&page=$page")

    override fun latestUpdatesSelector(): String = "div.product__item"

    override fun popularAnimeFromElement(element: Element): SAnime = parseShortInfo(element)

    override fun popularAnimeNextPageSelector(): String = "div.product__pagination > a:last-child"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime")

    override fun popularAnimeSelector(): String = "div.product__item"

    override fun searchAnimeFromElement(element: Element): SAnime = parseShortInfo(element)

    override fun searchAnimeNextPageSelector(): String = "div.product__pagination > a:last-child"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/anime?search=$query&page=$page")

    override fun searchAnimeSelector(): String = "div.product__item"

    override fun videoListSelector() = "#player > source"
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
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

    override fun videoFromElement(element: Element): Video {
        val url = element.attr("src")
        val quality = with(element.attr("size")) {
            when {
                contains("1080") -> "1080p"
                contains("720") -> "720p"
                contains("480") -> "480p"
                contains("360") -> "360p"
                else -> "Default"
            }
        }
        return Video(url, quality, url)
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
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
        screen.addPreference(videoQualityPref)
    }
}
