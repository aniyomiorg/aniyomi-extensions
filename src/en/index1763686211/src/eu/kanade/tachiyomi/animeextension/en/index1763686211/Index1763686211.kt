package eu.kanade.tachiyomi.animeextension.en.index1763686211

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class Index1763686211 : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Index - 176.36.86.211"

    override val baseUrl = Base64.decode("aHR0cDovLzE3Ni4zNi44Ni4yMTEvdmlkZW8vYW5pbWU=", Base64.DEFAULT).toString(Charsets.UTF_8)

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "en"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val badNames = arrayOf("../", "gifs/")
        val animeList = mutableListOf<SAnime>()

        document.select(popularAnimeSelector()).forEach {
            val name = it.text()
            if (name in badNames) return@forEach

            if (videoFormats.any { t -> name.endsWith(t) }) {
                val anime = SAnime.create()
                anime.title = videoFormats.fold(name) { acc, suffix -> acc.removeSuffix(suffix) }
                anime.setUrlWithoutDomain(
                    LinkData(
                        "single",
                        "$baseUrl/${it.attr("href")}",
                        it.nextSibling()?.toString()?.substringAfterLast(" ")?.trim(),
                    ).toJsonString(),
                )
                animeList.add(anime)
            } else if (name.endsWith("/")) {
                val anime = SAnime.create()
                anime.title = name.removeSuffix("/")
                anime.setUrlWithoutDomain(
                    LinkData(
                        "multi",
                        "/${it.attr("href")}",
                    ).toJsonString(),
                )
                animeList.add(anime)
            } else { }
        }

        return AnimesPage(animeList, false)
    }

    override fun popularAnimeSelector(): String = "pre > a"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    // =============================== Search ===============================

    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> {
        return Observable.defer {
            try {
                client.newCall(searchAnimeRequest(page, query, filters)).asObservableSuccess()
            } catch (e: NoClassDefFoundError) {
                // RxJava doesn't handle Errors, which tends to happen during global searches
                // if an old extension using non-existent classes is still around
                throw RuntimeException(e)
            }
        }
            .map { response ->
                searchAnimeParse(response, query)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)

    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()
        val badNames = arrayOf("../", "gifs/")
        val animeList = mutableListOf<SAnime>()

        document.select(popularAnimeSelector()).forEach {
            val name = it.text()
            if (name in badNames || !name.contains(query, ignoreCase = true)) return@forEach

            if (videoFormats.any { t -> name.endsWith(t) }) {
                val anime = SAnime.create()
                anime.title = videoFormats.fold(name) { acc, suffix -> acc.removeSuffix(suffix) }
                anime.setUrlWithoutDomain(
                    LinkData(
                        "single",
                        "$baseUrl/${it.attr("href")}",
                        it.nextSibling()?.toString()?.substringAfterLast(" ")?.trim(),
                    ).toJsonString(),
                )
                animeList.add(anime)
            } else if (name.endsWith("/")) {
                val anime = SAnime.create()
                anime.title = name.removeSuffix("/")
                anime.setUrlWithoutDomain(
                    LinkData(
                        "multi",
                        "/${it.attr("href")}",
                    ).toJsonString(),
                )
                animeList.add(anime)
            } else { }
        }

        return AnimesPage(animeList, false)
    }

    override fun searchAnimeSelector(): String = throw Exception("Not used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("Not used")

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return Observable.just(anime)
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    // ============================== Episodes ==============================

    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> {
        val episodeList = mutableListOf<SEpisode>()
        val parsed = json.decodeFromString<LinkData>(anime.url)
        var counter = 1

        if (parsed.type == "single") {
            val episode = SEpisode.create()
            val size = if (parsed.info == null) {
                ""
            } else {
                " - ${parsed.info}"
            }
            episode.name = "${parsed.url.toHttpUrl().pathSegments.last()}$size"
            episode.url = parsed.url
            episode.episode_number = 1F
            episodeList.add(episode)
        } else if (parsed.type == "multi") {
            fun traverseDirectory(url: String) {
                val doc = client.newCall(GET(url)).execute().asJsoup()

                doc.select("a").forEach { link ->
                    val href = link.attr("href")
                    val text = link.text()
                    if (href.isNotBlank() && text != "../") {
                        val fullUrl = url + href
                        if (fullUrl.endsWith("/")) {
                            traverseDirectory(fullUrl)
                        }
                        if (videoFormats.any { t -> fullUrl.endsWith(t) }) {
                            val episode = SEpisode.create()
                            val paths = fullUrl.toHttpUrl().pathSegments
                            val season = if (paths.size == 4) {
                                ""
                            } else {
                                "[${paths[3]}] "
                            }

                            val extraInfo = if (paths.size > 5) {
                                paths.subList(4, paths.size - 1).joinToString("/")
                            } else {
                                ""
                            }
                            val size = link.nextSibling()?.toString()?.substringAfterLast(" ")?.trim()

                            episode.name = "${season}Episode ${videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix) }}${if (size == null) "" else " - $size"}"
                            episode.url = fullUrl
                            episode.scanlator = extraInfo
                            episode.episode_number = counter.toFloat()
                            counter++

                            episodeList.add(episode)
                        }
                    }
                }
            }

            traverseDirectory(baseUrl + parsed.url)
        }

        return Observable.just(episodeList.reversed())
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    override fun episodeListSelector(): String = throw Exception("Not Used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return Observable.just(listOf(Video(episode.url, "Video", episode.url)))
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    @Serializable
    data class LinkData(
        val type: String,
        val url: String,
        val info: String? = null,
    )

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) { }
}
