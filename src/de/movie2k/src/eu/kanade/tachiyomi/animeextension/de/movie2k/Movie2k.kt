package eu.kanade.tachiyomi.animeextension.de.movie2k

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.movie2k.extractors.DroploadExtractor
import eu.kanade.tachiyomi.animeextension.de.movie2k.extractors.UpstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Movie2k : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Movie2k"

    override val baseUrl = "https://movie2k.skin"

    override val lang = "de"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.item-container div.item"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a div.item-inner img").attr("data-src")
        anime.title = element.select("a div.item-inner img").attr("alt")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination a.next"

    // episodes

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        val episode = SEpisode.create()
        episode.episode_number = 1F
        episode.name = "Film"
        val hostdoc = client.newCall(GET(document.select("#multiplayer a").attr("href"))).execute().asJsoup()
        episode.url = hostdoc.select("#video-container div.server1 iframe").attr("src")
        episodeList.add(episode)
        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // Video Extractor

    override fun videoListRequest(episode: SEpisode): Request {
        return GET(episode.url.replace(baseUrl, ""))
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val videoList = mutableListOf<Video>()
        val hosterSelection = preferences.getStringSet("hoster_selection", setOf("dood", "stape", "mix", "up", "drop"))
        document.select("ul._player-mirrors li").forEach {
            val purl = it.attr("data-link")
            when {
                purl.contains("//dood") && hosterSelection?.contains("dood") == true -> {
                    val quality = "Doodstream"
                    if (!purl.contains("https://")) {
                        val url = "https:$purl"
                        val video = DoodExtractor(client).videoFromUrl(url, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    } else {
                        val video = DoodExtractor(client).videoFromUrl(purl, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
                purl.contains("//streamtape.com") && hosterSelection?.contains("stape") == true -> {
                    val quality = "Streamtape"
                    if (!purl.contains("https://")) {
                        val url = "https:$purl"
                        val video = StreamTapeExtractor(client).videoFromUrl(url, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    } else {
                        val video = StreamTapeExtractor(client).videoFromUrl(purl, quality)
                        if (video != null) {
                            videoList.add(video)
                        }
                    }
                }
                purl.contains("//mixdrop") && hosterSelection?.contains("mix") == true -> {
                    if (!purl.contains("https://")) {
                        val url = "https:$purl"
                        val videos = MixDropExtractor(client).videoFromUrl(url)
                        videoList.addAll(videos)
                    } else {
                        val videos = MixDropExtractor(client).videoFromUrl(purl)
                        videoList.addAll(videos)
                    }
                }
                purl.contains("//upstream") && hosterSelection?.contains("up") == true -> {
                    if (!purl.contains("https://")) {
                        val url = "https:$purl"
                        val videos = UpstreamExtractor(client).videoFromUrl(url)
                        if (videos != null) {
                            videoList.addAll(videos)
                        }
                    } else {
                        val videos = UpstreamExtractor(client).videoFromUrl(purl)
                        if (videos != null) {
                            videoList.addAll(videos)
                        }
                    }
                }
                purl.contains("//dropload") && hosterSelection?.contains("drop") == true -> {
                    if (!purl.contains("https://")) {
                        val url = "https:$purl"
                        val videos = DroploadExtractor(client).videoFromUrl(url)
                        if (videos != null) {
                            videoList.addAll(videos)
                        }
                    } else {
                        val videos = DroploadExtractor(client).videoFromUrl(purl)
                        if (videos != null) {
                            videoList.addAll(videos)
                        }
                    }
                }
            }
        }
        return videoList
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
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.thumbnail_url = element.select("a div.item-inner img").attr("data-src")
        anime.title = element.select("a div.item-inner img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination a.next"

    override fun searchAnimeSelector(): String = "div.item-container div.item"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/page/$page/?s=$query")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.movie-image img").attr("src")
        anime.title = document.select("div.movie-image img").attr("alt")
        anime.description = document.select("p.movie-description span").text()
        anime.author = document.select("span[itemprop=\"director\"] a").joinToString(", ") { it.text() }
        anime.genre = document.select("span[itemprop=\"genre\"] a").joinToString(", ") { it.text() }
        anime.status = SAnime.COMPLETED
        return anime
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
            entries = arrayOf("Doodstream", "Streamtape", "Mixdrop", "Upstream", "Dropload")
            entryValues = arrayOf("https://dood", "https://streamtape", "https://mixdrop", "https://upstream", "https://dropload")
            setDefaultValue("https://dood")
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
            entries = arrayOf("Doodstream", "Streamtape", "MixDrop", "Upstream", "Dropload")
            entryValues = arrayOf("dood", "stape", "mix", "up", "drop")
            setDefaultValue(setOf("dood", "stape", "mix", "up", "drop"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
