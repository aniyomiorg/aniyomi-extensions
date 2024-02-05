package eu.kanade.tachiyomi.animeextension.de.filmpalast

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors.EvoloadExtractor
import eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors.StreamHideVidExtractor
import eu.kanade.tachiyomi.animeextension.de.filmpalast.extractors.UpstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class FilmPalast : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "FilmPalast"

    override val baseUrl = "https://filmpalast.to"

    override val lang = "de"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "article.liste > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/movies/top/page/$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val file = element.select("img").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = element.attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.pageing:contains(vorwärts)"

    // episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.name = "Film"
        episode.episode_number = 1F
        episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
        episodeList.add(episode)
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val elements = document.select("ul.currentStreamLinks > li > a")
        val hosterSelection = preferences.getStringSet(PREF_SELECTION_KEY, PREF_SELECTION_DEFAULT)!!
        return elements.mapNotNull { element ->
            val url = element.attr("abs:href").ifEmpty {
                element.attr("abs:data-player-url")
            }
            when {
                url.contains("https://voe.sx") && hosterSelection.contains("voe") ->
                    VoeExtractor(client).videosFromUrl(url)

                url.contains("https://upstream.to") && hosterSelection.contains("up") ->
                    UpstreamExtractor(client).videoFromUrl(url)

                url.contains("https://streamtape.com") && hosterSelection.contains("stape") -> {
                    runCatching {
                        val stapeHeaders = Headers.headersOf(
                            "Referer",
                            baseUrl,
                            "Cookie",
                            "Fuck Streamtape because they add concatenation to fuck up scrapers",
                        )
                        // from lib streamtape-extractor
                        // TODO: add headers param to lib, so we can use the
                        // lib in cases like this.
                        val doc = client.newCall(GET(url, headers = stapeHeaders))
                            .execute()
                            .asJsoup()

                        val targetLine = "document.getElementById('robotlink')"
                        val script = doc.selectFirst("script:containsData($targetLine)")
                            ?.data()
                            ?.substringAfter("$targetLine.innerHTML = '")
                            ?: return@runCatching null
                        val videoUrl = "https:" + script.substringBefore("'") +
                            script.substringAfter("+ ('xcd").substringBefore("'")
                        listOf(Video(videoUrl, "Streamtape", videoUrl))
                    }.getOrNull()
                }

                url.contains("https://evoload.io") && hosterSelection.contains("evo") -> {
                    val quality = "Evoload"
                    document.selectFirst("#EvoVid_html5_api")?.attr("src")?.let { videoUrl ->
                        if (videoUrl.contains("EvoStreams")) {
                            listOf(Video(videoUrl, quality, videoUrl))
                        } else {
                            EvoloadExtractor(client).videoFromUrl(url, quality)
                        }
                    }
                }

                url.contains("filemoon.sx") && hosterSelection.contains("moon") ->
                    FilemoonExtractor(client).videosFromUrl(url)
                url.contains("hide.com") && hosterSelection.contains("hide") ->
                    StreamHideVidExtractor(client).videosFromUrl(url, "StreamHide")
                url.contains("streamvid.net") && hosterSelection.contains("vid") ->
                    StreamHideVidExtractor(client).videosFromUrl(url, "StreamVid")

                "wolfstream" in url && hosterSelection.contains("wolf") -> {
                    client.newCall(GET(url, headers)).execute()
                        .asJsoup()
                        .selectFirst("script:containsData(sources)")
                        ?.data()
                        ?.let { jsData ->
                            val videoUrl = jsData.substringAfter("{file:\"").substringBefore("\"")
                            listOf(Video(videoUrl, "WolfStream", videoUrl, headers = headers))
                        }
                }
                else -> null
            }
        }.flatten()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        return sortedWith(
            compareBy { it.url.contains(hoster) },
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val file = element.select("img").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.pageing:contains(vorwärts)"

    override fun searchAnimeSelector(): String = "article.liste > a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/search/title/$query/$page"
        return GET(url)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val file = document.select("img.cover2").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = document.select("h2.bgDark").text()
        anime.genre = document.select("#detail-content-list > li:nth-child(2) > span").joinToString(", ") { it.text() }
        anime.description = document.select("#detail-content-list > li:nth-child(3) > span").text()
        anime.author = document.select("#detail-content-list > li:nth-child(4) > span").joinToString(", ") { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = "a.pageing:contains(vorwärts)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        val file = element.select("img").attr("src")
        anime.thumbnail_url = "$baseUrl$file"
        anime.title = element.attr("title")
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page")

    override fun latestUpdatesSelector(): String = "article.liste > a"

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = PREF_SELECTION_KEY
            title = PREF_SELECTION_TITLE
            entries = PREF_SELECTION_ENTRIES
            entryValues = PREF_SELECTION_VALUES
            setDefaultValue(PREF_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }

    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Standard-Hoster"
        private const val PREF_HOSTER_DEFAULT = "https://voe.sx"
        private val PREF_HOSTER_ENTRIES = arrayOf(
            "Voe",
            "Streamtape",
            "Evoload",
            "Upstream",
            "Filemoon",
            "StreamHide",
            "StreamVid",
            "WolfStream",
        )
        private val PREF_HOSTER_VALUES = arrayOf(
            "https://voe.sx",
            "https://streamtape.com",
            "https://evoload.io",
            "https://upstream.to",
            "https://filemoon.sx",
            "hide.com",
            "streamvid.net",
            "https://wolfstream",
        )

        private const val PREF_SELECTION_KEY = "hoster_selection"
        private const val PREF_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_SELECTION_ENTRIES = PREF_HOSTER_ENTRIES
        private val PREF_SELECTION_VALUES = arrayOf(
            "voe",
            "stape",
            "evo",
            "up",
            "moon",
            "hide",
            "vid",
            "wolf",
        )
        private val PREF_SELECTION_DEFAULT = PREF_SELECTION_VALUES.toSet()
    }
}
