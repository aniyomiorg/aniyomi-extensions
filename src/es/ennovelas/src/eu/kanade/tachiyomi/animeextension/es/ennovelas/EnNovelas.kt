package eu.kanade.tachiyomi.animeextension.es.ennovelas

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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.Normalizer

class EnNovelas : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EnNovelas"

    override val baseUrl = "https://www.ennovelas.com"

    override val lang = "es"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#container section.search-videos div.section-content div.row div div.col-xs-6 div.video-post"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/?op=categories_all&per_page=60&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(changeUrlFormat(element.select("a").attr("href")))
        anime.title = element.select("a p").text()
        anime.thumbnail_url = element.select("a div.thumb").attr("style")
            .substringAfter("background-image:url(").substringBefore(")")
        anime.description = ""
        return anime
    }

    private fun changeUrlFormat(link: String): String {
        val novel = link.substringAfter("/category/").replace("+", "%20")
        return "$baseUrl/?cat_name=$novel&op=search&per_page=all"
    }

    override fun popularAnimeNextPageSelector(): String = "#container section div.section-content div.paging a:last-of-type"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        document.select("#col3 div.videobox").forEach { element ->
            val ep = SEpisode.create()
            val noEpisode = getNumberFromEpsString(
                element.selectFirst("a:nth-child(2)").text().substringAfter("Cap")
                    .substringBefore("FIN").substringBefore("fin")
            )
            ep.setUrlWithoutDomain(element.selectFirst("a.video200").attr("href"))
            ep.name = "Cap" + element.selectFirst("a:nth-child(2)").text().substringAfter("Cap")
            ep.episode_number = noEpisode.toFloat()
            episodeList.add(ep)
        }
        return episodeList.sortedByDescending { x -> x.episode_number }
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("window.hola_player({")) {
                val url = script.data().substringAfter("sources: [{src: \"").substringBefore("\",")
                val quality = script.data().substringAfter("res: ").substringBefore(",")
                videoList.add(Video(url, "${quality}p", url, headers = null))
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720p")
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
        return when {
            query.isNotBlank() -> GET("$baseUrl/?name=$query&op=categories_all&page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val descriptionElement = document.selectFirst("#inwg").text()
            .substringAfter("Notifications:")
            .substringBefore("Capitulo")
            .substringBefore("Capítulo")
        val anime = SAnime.create()
        val title = document.selectFirst("#inwg h3 span.first-word").text()
        val idx = idxDescription(descriptionElement, title)
        val description = descriptionElement.substring(0, idx).trim()

        anime.title = title.trim()
        anime.description = description.ifEmpty { title }
        anime.genre = "novela"
        anime.status = SAnime.UNKNOWN
        return anime
    }

    private fun String.removeNonSpacingMarks() = Normalizer.normalize(this, Normalizer.Form.NFD).replace("\\p{Mn}+".toRegex(), "")

    private fun idxDescription(text: String, title: String): Int {
        val descriptionLowercase = text.lowercase().removeNonSpacingMarks()
        val titleLowercase = title.lowercase().removeNonSpacingMarks()
        val idx = descriptionLowercase.lastIndexOf(titleLowercase)
        return if (idx == -1) descriptionLowercase.length - 1 else idx
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/browse?order=added&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p")
            entryValues = arrayOf("1080p", "720p", "480p")
            setDefaultValue("720p")
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
