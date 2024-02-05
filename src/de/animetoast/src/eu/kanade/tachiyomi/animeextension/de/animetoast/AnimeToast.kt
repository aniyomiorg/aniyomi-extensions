package eu.kanade.tachiyomi.animeextension.de.animetoast

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.Exception

class AnimeToast : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeToast"

    override val baseUrl = "https://www.animetoast.cc"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.row div.col-md-4 div.video-item"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.item-thumbnail a").attr("href"))
        anime.thumbnail_url = element.select("div.item-thumbnail a img").attr("src")
        anime.title = element.select("div.item-thumbnail a").attr("title")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val file = document.select("a[rel=\"category tag\"]").text()
        if (file.contains("Serie")) {
            if (document.select("#multi_link_tab0").attr("id").isNotEmpty()) {
                val elements = document.select("#multi_link_tab0")
                elements.forEach {
                    val episode = parseEpisodesFromSeries(it)
                    episodeList.addAll(episode)
                }
            } else {
                val elements = document.select("#multi_link_tab1")
                elements.forEach {
                    val episode = parseEpisodesFromSeries(it)
                    episodeList.addAll(episode)
                }
            }
        } else {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(document.select("link[rel=canonical]").attr("href"))
            episode.name = document.select("h1.light-title").text()
            episode.episode_number = 1F
            episodeList.add(episode)
        }
        return episodeList.reversed()
    }

    private fun parseEpisodesFromSeries(element: Element): List<SEpisode> {
        val episodeElements = element.select("div.tab-pane a")
        val epT = episodeElements.text()
        if (epT.contains(":") || epT.contains("-")) {
            val url = episodeElements.attr("href")
            val document = client.newCall(GET(url)).execute().asJsoup()
            val nUrl = document.select("#player-embed a").attr("href")
            val nDoc = client.newCall(GET(nUrl)).execute().asJsoup()
            val nEpEl = nDoc.select("div.tab-pane a")
            return nEpEl.map { episodeFromElement(it) }
        } else {
            return episodeElements.map { episodeFromElement(it) }
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.episode_number = try {
            element.text().replace("Ep. ", "").toFloat()
        } catch (e: Exception) {
            100.0f
        }
        episode.name = element.text()
        episode.setUrlWithoutDomain(element.attr("href"))
        return episode
    }

    // Video Extractor

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("voe", "dood", "fmoon", "mp4u"))
        val fEp = document.select("div.tab-pane")
        if (fEp.text().contains(":") || fEp.text().contains("-")) {
            val tx = document.select("div.tab-pane")
            var here = false
            tx.forEach {
                if ((it.text().contains(":") || it.text().contains("-")) && !here) {
                    here = true
                    val sUrl = it.select("a").attr("href")
                    val doc = client.newCall(GET(sUrl)).execute().asJsoup()
                    val nUrl = doc.select("#player-embed a").attr("href")
                    val nDoc = client.newCall(GET(nUrl)).execute().asJsoup()
                    val nEpEl = nDoc.select("div.tab-pane a")
                    val nEpcu = try {
                        nDoc.select("div.tab-pane a.current-link").text()
                            .substringAfter("Ep.").toFloat()
                    } catch (e: Exception) {
                        100.0f
                    }
                    nEpEl.forEach { tIt ->
                        if (try { tIt.text().substringAfter("Ep.").toFloat() } catch (_: Exception) {} == nEpcu) {
                            val url = tIt.attr("href")
                            val newdoc = client.newCall(GET(url)).execute().asJsoup()
                            val element = newdoc.select("#player-embed")
                            for (elements in element) {
                                val link = element.select("a").attr("abs:href")
                                when {
                                    link.contains("https://voe.sx") && hosterSelection?.contains(
                                        "voe",
                                    ) == true -> {
                                        videoList.addAll(VoeExtractor(client).videosFromUrl(link))
                                    }
                                }
                            }
                            for (elements in element) {
                                val link = element.select("iframe").attr("abs:src")
                                when {
                                    (link.contains("https://dood") || link.contains("https://ds2play")) && hosterSelection?.contains(
                                        "dood",
                                    ) == true -> {
                                        val quality = "DoodStream"
                                        val video =
                                            DoodExtractor(client).videoFromUrl(
                                                link,
                                                quality,
                                                false,
                                            )
                                        if (video != null) {
                                            videoList.add(video)
                                        }
                                    }

                                    link.contains("https://filemoon.sx") && hosterSelection?.contains(
                                        "fmoon",
                                    ) == true -> {
                                        val videos =
                                            FilemoonExtractor(client).videosFromUrl(link)
                                        videoList.addAll(videos)
                                    }

                                    link.contains("mp4upload") && hosterSelection?.contains("mp4u") == true -> {
                                        val videos =
                                            Mp4uploadExtractor(client).videosFromUrl(
                                                link,
                                                headers,
                                            )
                                        videoList.addAll(videos)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val epcu = try {
                document.select("div.tab-pane a.current-link").text().substringAfter("Ep.")
                    .toFloat()
            } catch (e: Exception) {
                100.0f
            }
            val ep = document.select("div.tab-pane a")
            ep.forEach {
                if (try { it.text().substringAfter("Ep.").toFloat() } catch (_: Exception) {} == epcu) {
                    val url = it.attr("href")
                    val newdoc = client.newCall(GET(url)).execute().asJsoup()
                    val element = newdoc.select("#player-embed")
                    for (elements in element) {
                        val link = element.select("a").attr("abs:href")
                        when {
                            link.contains("https://voe.sx") && hosterSelection?.contains("voe") == true -> {
                                videoList.addAll(VoeExtractor(client).videosFromUrl(link))
                            }
                        }
                    }
                    for (elements in element) {
                        val link = element.select("iframe").attr("abs:src")
                        when {
                            (link.contains("https://dood") || link.contains("https://ds2play")) && hosterSelection?.contains(
                                "dood",
                            ) == true -> {
                                val quality = "DoodStream"
                                val video =
                                    DoodExtractor(client).videoFromUrl(link, quality, false)
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }

                            link.contains("https://filemoon.sx") && hosterSelection?.contains("fmoon") == true -> {
                                val videos = FilemoonExtractor(client).videosFromUrl(link)
                                videoList.addAll(videos)
                            }

                            link.contains("mp4upload") && hosterSelection?.contains("mp4u") == true -> {
                                val videos =
                                    Mp4uploadExtractor(client).videosFromUrl(link, headers)
                                videoList.addAll(videos)
                            }
                        }
                    }
                }
            }
        }
        return videoList.reversed()
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString("preferred_hoster", null)
        if (hoster != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(hoster)) {
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

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // Search

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.thumbnail_url = element.select("a img").attr("src")
        anime.title = element.attr("title")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = ".nextpostslink"

    override fun searchAnimeSelector(): String = "div.item-thumbnail a[href]"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/page/$page/?s=$query"
        return GET(url)
    }

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select(".item-content p img").attr("src")
        anime.title = document.select("h1.light-title.entry-title").text()
        anime.genre = document.select("a[rel=tag]").joinToString(", ") { it.text() }
        val height = document.select("div.item-content p img").attr("height")
        anime.description = document.select("div.item-content div + p").text()
        anime.status = parseStatus(document.select("a[rel=\"category tag\"]").text())
        return anime
    }

    private fun parseStatus(status: String?) = when {
        status == null -> SAnime.UNKNOWN
        status.contains("Airing", ignoreCase = true) -> SAnime.ONGOING
        else -> SAnime.COMPLETED
    }

    // Latest

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = "preferred_hoster"
            title = "Standard-Hoster"
            entries = arrayOf("Voe", "DoodStream", "Filemoon", "Mp4upload")
            entryValues = arrayOf("https://voe.sx", "https://dood", "https://filemoon", "https://www.mp4upload")
            setDefaultValue("https://voe.sx")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Hoster auswählen"
            entries = arrayOf("Voe", "DoodStream", "Filemoon", "Mp4upload")
            entryValues = arrayOf("voe", "dood", "fmoon", "mp4u")
            setDefaultValue(setOf("voe", "dood", "fmoon", "mp4u"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
