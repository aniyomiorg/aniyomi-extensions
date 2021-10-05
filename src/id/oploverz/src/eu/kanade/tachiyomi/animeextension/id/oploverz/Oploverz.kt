package eu.kanade.tachiyomi.animeextension.id.oploverz

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class Oploverz : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val baseUrl: String = "https://oploverz.biz"
    override val lang: String = "id"
    override val name: String = "Oploverz"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val infox = document.select("div.bigcontent > div.infox")
        val status = parseStatus(infox.select("div > div.info-content > div.spe > span:nth-child(1)").text().replace("Status: ", ""))
        anime.title = infox.select("h1").text().replace("Judul: ", "")
        anime.genre = infox.select("div > div.info-content > div.genxed > a").joinToString(", ") { it.text() }
        anime.status = status
        anime.artist = infox.select("div > div.info-content > div.spe > span:nth-child(2)").text()

        // Others
        // Jap title
        anime.author = when {
            infox.select("div > span.alter").isNullOrEmpty() -> "Alternative = -"
            else -> "Alternative = " + infox.select("div > span.alter").text()
        }
        // Score
        anime.description = "\n" + document.select("div.bigcontent > div.thumbook > div.rt > div.rating > strong").text()
        // Total Episode
        anime.description = anime.description + "\n" + document.select("div > div.info-content > div.spe > span:nth-child(7)").text()
        // Synopsis
        anime.description = anime.description + "\n\n\nSynopsis: \n" + document.select("div.bixbox.synp > div.entry-content > p").joinToString("\n\n") { it.text() }
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
        val epsNum = getNumberFromEpsString(element.select(".epl-num").text())
        episode.setUrlWithoutDomain(element.select("a").attr("href"))
        episode.episode_number = when {
            (epsNum.isNotEmpty()) -> epsNum.toFloat()
            else -> "1".toFloat()
        }
        episode.name = element.select(".epl-title").text()
        episode.date_upload = reconstructDate(element.select(".epl-date").text())

        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }
    private fun reconstructDate(Str: String): Long {
        val pattern = SimpleDateFormat("MMMM d yyyy", Locale.US)
        return pattern.parse(Str.replace(",", " "))!!.time
    }
    override fun episodeListSelector(): String = "div.bixbox.bxcl.epcheck > div.eplister > ul > li"

    override fun latestUpdatesFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    private fun getAnimeFromAnimeElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div > a").first().attr("href"))
        anime.thumbnail_url = element.select("div > a > div.limit > img").first().attr("src")
        anime.title = element.select("div > a > div.tt > h2").text()
        return anime
    }
    override fun latestUpdatesNextPageSelector(): String = "div.hpage > a.r"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=ongoing&sub=&order=update")

    override fun latestUpdatesSelector(): String = "div.listupd > article"

    override fun popularAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "div.hpage > a.r"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page")

    override fun popularAnimeSelector(): String = "div.listupd > article"

    override fun searchAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun searchAnimeNextPageSelector(): String = "a.next.page-numbers"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // filter and stuff in v2
        return GET("$baseUrl/page/$page/?s=$query")
    }

    override fun searchAnimeSelector(): String = "div.listupd > article"

    override fun videoFromElement(element: Element): Video {
        val res = client.newCall(GET(element.attr("href"))).execute().asJsoup()
        val scr = res.select("script:containsData(dlbutton)").html()
        var url = element.attr("href").substringBefore("/v/")
        val firstString = scr.substringAfter(" = \"").substringBefore("\" + ")
        val num1 = scr.substringAfter("+ (").substringBefore(" % ").toInt()
        val num2 = scr.substringAfter(" % ").substringBefore(" + ").toInt()
        val num4 = scr.substringAfter(" % ").substringBefore(") + ").substringAfter(" % ").toInt()
        val lastString = scr.substringAfter(") + \"").substringBefore("\";")
        val num = (num1 % num2) + (num1 % num4)
        url += firstString + num.toString() + lastString
        val quality = with(url) {
            when {
                contains("1080p") -> "1080p"
                contains("720p") -> "720p"
                contains("480p") -> "480p"
                contains("360p") -> "360p"
                else -> "Unknown Resolution"
            }
        }
        return Video(url, quality, url, null)
    }

    override fun videoListSelector(): String = "div.mctnx > div > div > a:nth-child(3)"

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
