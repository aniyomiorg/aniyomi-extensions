package eu.kanade.tachiyomi.animeextension.id.minioppai

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.net.URLDecoder
import java.util.Locale

class MiniOppai : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val baseUrl: String = "https://minioppai.org"
    override val lang: String = "id"
    override val name: String = "MiniOppai"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val infox = document.select("div.infox")
        val status = parseStatus(infox.select("div.spe > span:nth-child(2)").text().replace("Status: ", ""))
        anime.title = infox.select("div.infox > h1").text().replace("Judul: ", "")
        anime.genre = infox.select("div.spe > span:nth-child(1)").joinToString(", ") { it.text() }
        anime.status = status
        anime.artist = infox.select("div.spe > span:nth-child(3)").text().replace("Studio: ", "")
        anime.author = "UNKNOWN"
        anime.description = "Synopsis: \n" + document.select("div.desc > div > span").text()
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString.toLowerCase(Locale.US)) {
            "ongoing" -> SAnime.ONGOING
            "completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epsNum = getNumberFromEpsString(element.select("span.lchx").text())
        episode.setUrlWithoutDomain(element.select("a").first().attr("href"))
        episode.episode_number = when {
            (epsNum.isNotEmpty()) -> epsNum.toFloat()
            else -> 1F
        }
        episode.name = element.select("span.lchx").text()

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun episodeListSelector(): String = "div.bixbox.bxcl > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    private fun getAnimeFromAnimeElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div > a").first().attr("href"))
        anime.thumbnail_url = element.select("img").first().attr("data-src")
        anime.title = element.select("div > a > div.tt").text()
        return anime
    }
    override fun latestUpdatesNextPageSelector(): String = "div.pagination > a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=ongoing&sub=&order=update")

    override fun latestUpdatesSelector(): String = "div.listupd > div"

    override fun popularAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "div.pagination > a.next"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page")

    override fun popularAnimeSelector(): String = "div.listupd > div"

    override fun searchAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun searchAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // filter and stuff in v2
        return GET("$baseUrl/page/$page/?s=$query")
    }

    override fun searchAnimeSelector(): String = "div.listupd > div"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val patternZippy = "div.bixbox.mctn > div.soraddlx > div.dlmx > a:contains(zippy)"

        val zippy = document.select(patternZippy).mapNotNull {
            runCatching { zippyFromElement(it) }.getOrNull()
        }

        return zippy
    }

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    private fun zippyFromElement(element: Element): Video {
        val zipurl = URLDecoder.decode(element.attr("href"), "UTF-8").substringAfter("?s=")
        val res = client.newCall(GET(zipurl)).execute().asJsoup()
        val scr = res.select("script:containsData(dlbutton)").html()
        var url = zipurl.substringBefore("/v/")
        val numbs = scr.substringAfter("\" + (").substringBefore(") + \"")
        val firstString = scr.substringAfter(" = \"").substringBefore("\" + (")
        val num = numbs.substringBefore(" % ").toInt()
        val lastString = scr.substringAfter("913) + \"").substringBefore("\";")
        val nums = num % 51245 + num % 913
        url += firstString + nums.toString() + lastString
        val quality = with(lastString) {
            when {
                contains("1080p") -> "ZippyShare - 1080p"
                contains("720p") -> "ZippyShare - 720p"
                contains("480p") -> "ZippyShare - 480p"
                contains("360p") -> "ZippyShare - 360p"
                else -> "ZippyShare - Unknown Resolution"
            }
        }
        return Video(url, quality, url)
    }

    override fun videoListSelector(): String = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

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
