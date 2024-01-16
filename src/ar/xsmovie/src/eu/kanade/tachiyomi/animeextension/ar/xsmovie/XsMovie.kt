package eu.kanade.tachiyomi.animeextension.ar.xsmovie

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
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class XsMovie : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "XS Movie"

    override val baseUrl = "https://ww.xsanime.com"

    override val lang = "ar"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime
    override fun popularAnimeSelector(): String = "ul.boxes--holder div.itemtype_anime a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies_list/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.attr("title")
        anime.thumbnail_url = element.selectFirst("div.itemtype_anime_poster img")!!.attr("data-src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    // Episodes
    override fun episodeListSelector() = "h1.post--inner-title > a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.text().replace("فيلم ", "").replace("مترجم ", "").replace("اون لاين ", "").replace("بلوراي", "") // "movie"

        return episode
    }

    // Video Links

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val srcVid = preferences.getString("preferred_quality", "الجودة العالية")!!
        val iframe = document.select("div.downloads ul div.listServ:contains($srcVid) div.serL a[href~=4shared]").attr("href").substringBeforeLast("/").replace("video", "web/embed/file")
        val referer = response.request.url.toString()
        val refererHeaders = Headers.headersOf("referer", referer)
        val iframeResponse = client.newCall(GET(iframe, refererHeaders))
            .execute().asJsoup()
        return iframeResponse.select(videoListSelector()).map { videoFromElement(it) }
    }

    override fun videoListSelector() = "source"

    override fun videoFromElement(element: Element): Video {
        return Video(element.attr("src"), "Default: If you want to change the quality go to extension settings", element.attr("src"))
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.attr("title")
        anime.thumbnail_url = element.selectFirst("div.itemtype_anime_poster img")!!.attr("data-src")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.page-numbers li a.next"

    override fun searchAnimeSelector(): String = "ul.boxes--holder div.itemtype_anime a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl/?s=$query&type=movie&page=$page", headers)
    }

    // Anime Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.inner--image img")!!.attr("src")
        anime.title = document.select("h1.post--inner-title").text()
        anime.genre = document.select("ul.terms--and--metas > li:contains(تصنيفات الأنمي) > a").joinToString(", ") { it.text() }
        anime.description = document.select("div.post--content--inner").text()
        document.select("ul.terms--and--metas li:contains(عدد الحلقات) a").text()?.also { statusText ->
            when {
                statusText.contains("غير معروف", true) -> anime.status = SAnime.ONGOING
                else -> anime.status = SAnime.COMPLETED
            }
        }
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String? = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred Quality"
            entries = arrayOf("الجودة العالية", "الجودة الخارقة", "الجودة المتوسطة")
            entryValues = arrayOf("الجودة العالية", "الجودة الخارقة", "الجودة المتوسطة")
            setDefaultValue("الجودة العالية")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(qualityPref)
    }
}
