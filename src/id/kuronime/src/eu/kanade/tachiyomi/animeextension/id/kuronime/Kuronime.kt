package eu.kanade.tachiyomi.animeextension.id.kuronime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.id.kuronime.extractors.AnimekuExtractor
import eu.kanade.tachiyomi.animeextension.id.kuronime.extractors.HxFileExtractor
import eu.kanade.tachiyomi.animeextension.id.kuronime.extractors.LinkBoxExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale

class Kuronime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {
    override val baseUrl: String = "https://tv1.kuronime.vip"
    override val lang: String = "id"
    override val name: String = "Kuronime"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val infodetail = document.select("div.infodetail")
        val status = parseStatus(infodetail.select("ul > li:nth-child(3)").text().replace("Status: ", ""))
        anime.title = infodetail.select("ul > li:nth-child(1)").text().replace("Judul: ", "")
        anime.genre = infodetail.select("ul > li:nth-child(2)").joinToString(", ") { it.text() }
        anime.status = status
        anime.artist = infodetail.select("ul > li:nth-child(4)").text().replace("Studio: ", "")
        anime.author = "UNKNOWN"
        anime.description = "Synopsis: \n" + document.select("div.main-info > div.con > div.r > div > span > p").text()
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
        episode.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        episode.episode_number = when {
            epsNum.isNotEmpty() -> epsNum.toFloatOrNull() ?: 1F
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
        anime.setUrlWithoutDomain(element.selectFirst("div > a")!!.attr("href"))

        val thumbnailElement = element.selectFirst("div > a > div.limit > img")!!
        val thumbnail = thumbnailElement.attr("src")
        anime.thumbnail_url = if (thumbnail.startsWith("https:")) {
            thumbnail
        } else {
            if (thumbnailElement.hasAttr("data-src")) thumbnailElement.attr("data-src") else ""
        }
        anime.title = element.select("div > a > div.tt > h4").text()
        return anime
    }
    override fun latestUpdatesNextPageSelector(): String = "div.pagination > a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime/?page=$page&status=ongoing&sub=&order=update")

    override fun latestUpdatesSelector(): String = "div.listupd > article"

    override fun popularAnimeFromElement(element: Element): SAnime = getAnimeFromAnimeElement(element)

    override fun popularAnimeNextPageSelector(): String = "div.pagination > a.next"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/anime/page/$page")

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
        val videoList = mutableListOf<Video>()

        val hosterSelection = preferences.getStringSet(
            "hoster_selection",
            setOf("animeku", "mp4upload", "yourupload", "streamlare", "linkbox"),
        )!!

        document.select("select.mirror > option[value]").forEach { opt ->
            val decoded = if (opt.attr("value").isEmpty()) {
                document.selectFirst("iframe")!!.attr("data-src")
            } else {
                Jsoup.parse(
                    String(Base64.decode(opt.attr("value"), Base64.DEFAULT)),
                ).select("iframe[data-src~=.]").attr("data-src")
            }

            when {
                hosterSelection.contains("animeku") && decoded.contains("animeku.org") -> {
                    videoList.addAll(AnimekuExtractor(client).getVideosFromUrl(decoded, opt.text()))
                }
                hosterSelection.contains("mp4upload") && decoded.contains("mp4upload.com") -> {
                    val videos = Mp4uploadExtractor(client).videosFromUrl(decoded, headers, suffix = " - ${opt.text()}")
                    videoList.addAll(videos)
                }
                hosterSelection.contains("yourupload") && decoded.contains("yourupload.com") -> {
                    videoList.addAll(YourUploadExtractor(client).videoFromUrl(decoded, headers, opt.text(), "Original - "))
                }
                hosterSelection.contains("streamlare") && decoded.contains("streamlare.com") -> {
                    videoList.addAll(StreamlareExtractor(client).videosFromUrl(decoded, suffix = "- " + opt.text()))
                }
                hosterSelection.contains("hxfile") && decoded.contains("hxfile.co") -> {
                    videoList.addAll(HxFileExtractor(client).getVideoFromUrl(decoded, opt.text()))
                }
                hosterSelection.contains("linkbox") && decoded.contains("linkbox.to") -> {
                    videoList.addAll(LinkBoxExtractor(client).videosFromUrl(decoded, opt.text()))
                }
            }
        }

        return videoList.sort()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

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
        val hostSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Enable/Disable Hosts"
            entries = arrayOf("Animeku", "Mp4Upload", "YourUpload", "Streamlare", "Hxfile", "Linkbox")
            entryValues = arrayOf("animeku", "mp4upload", "yourupload", "streamlare", "hxfile", "linkbox")
            setDefaultValue(setOf("animeku", "mp4upload", "yourupload", "streamlare", "linkbox"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "HD", "SD")
            entryValues = arrayOf("1080", "720", "480", "360", "HD", "SD")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(hostSelection)
        screen.addPreference(videoQualityPref)
    }
}
