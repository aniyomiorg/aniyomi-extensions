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
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.lang.RuntimeException
import java.lang.StringBuilder
import java.text.SimpleDateFormat
import java.util.Locale

class Oploverz : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val baseUrl: String = "https://oploverz.asia"
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
            else -> 1F
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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val patternZippy = "div.mctnx > div > div > a:nth-child(3)"
        val patternGoogle = "iframe[src^=https://www.blogger.com/video.g?token=]"
        val iframe = document.select(patternGoogle).firstOrNull()

        val zippy = document.select(patternZippy).mapNotNull {
            runCatching { zippyFromElement(it) }.getOrNull()
        }
        val google = if (iframe == null) { mutableListOf() } else try {
            googleLinkFromElement(iframe)
        } catch (e: Exception) { mutableListOf() }

        return google + zippy
    }

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    private fun googleLinkFromElement(iframe: Element): List<Video> {
        val iframeResponse = client.newCall(GET(iframe.attr("src"))).execute()
        val streams = iframeResponse.body!!.string().substringAfter("\"streams\":[").substringBefore("]")
        val videoList = mutableListOf<Video>()
        streams.split("},").reversed().forEach {
            val url = unescape(it.substringAfter("{\"play_url\":\"").substringBefore("\""))
            val quality = when (it.substringAfter("\"format_id\":").substringBefore("}")) {
                "18" -> "Google - 360p"
                "22" -> "Google - 720p"
                else -> "Unknown Resolution"
            }
            videoList.add(Video(url, quality, url))
        }
        return videoList
    }

    private fun zippyFromElement(element: Element): Video {
        val res = client.newCall(GET(element.attr("href"))).execute().asJsoup()
        val scr = res.select("script:containsData(dlbutton)").html()
        var url = element.attr("href").substringBefore("/v/")
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
        val services = preferences.getString("preferred_services", null)
        if (quality != null && services != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality) && video.quality.contains(services)) {
                    newList.add(preferred, video)
                    preferred++
                } else if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else if (video.quality.contains(services)) {
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
        val streamServicePref = ListPreference(screen.context).apply {
            key = "preferred_services"
            title = "Preferred Stream Services"
            entries = arrayOf("ZippyShare", "Google")
            entryValues = arrayOf("ZippyShare", "Google")
            setDefaultValue("ZippyShare")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(streamServicePref)
    }

    private fun unescape(input: String): String {
        val builder = StringBuilder()
        var i = 0
        while (i < input.length) {
            val delimiter = input[i]
            i++ // consume letter or backslash
            if (delimiter == '\\' && i < input.length) {

                // consume first after backslash
                val ch = input[i]
                i++
                if (ch == '\\' || ch == '/' || ch == '"' || ch == '\'') {
                    builder.append(ch)
                } else if (ch == 'n') builder.append('\n') else if (ch == 'r') builder.append('\r') else if (ch == 't') builder.append(
                    '\t'
                ) else if (ch == 'b') builder.append('\b') else if (ch == 'f') builder.append('\u000C') else if (ch == 'u') {
                    val hex = StringBuilder()

                    // expect 4 digits
                    if (i + 4 > input.length) {
                        throw RuntimeException("Not enough unicode digits! ")
                    }
                    for (x in input.substring(i, i + 4).toCharArray()) {
                        if (!Character.isLetterOrDigit(x)) {
                            throw RuntimeException("Bad character in unicode escape.")
                        }
                        hex.append(Character.toLowerCase(x))
                    }
                    i += 4 // consume those four digits.
                    val code = hex.toString().toInt(16)
                    builder.append(code.toChar())
                } else {
                    throw RuntimeException("Illegal escape sequence: \\$ch")
                }
            } else { // it's not a backslash, or it's the last character.
                builder.append(delimiter)
            }
        }
        return builder.toString()
    }
}
