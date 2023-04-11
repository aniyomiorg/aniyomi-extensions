package eu.kanade.tachiyomi.animeextension.fr.jeanyves

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class JeanYves : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Jean-Yves"

    override val baseUrl = "https://jeanyves.pro/data/"

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "fr"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val chunkedSize = 200

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl#$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val page = response.request.url.encodedFragment!!.toInt()
        val items = document.select(popularAnimeSelector())

        items.chunked(chunkedSize)[page - 1].forEach {
            val anime = SAnime.create()
            anime.title = it.selectFirst("div.header")!!.text()
            anime.setUrlWithoutDomain(
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments(it.attr("href"))
                    .build().encodedPath,
            )
            anime.thumbnail_url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(it.selectFirst("img")!!.attr("src"))
                .build().toString()
            animeList.add(anime)
        }

        return AnimesPage(animeList, page * chunkedSize <= items.size)
    }

    override fun popularAnimeSelector(): String = "div.cards > a.card"

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET("$baseUrl#$page")
    }

    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val page = response.request.url.encodedFragment!!.toInt()
        val items = document.select(popularAnimeSelector()).filter { t ->
            t.selectFirst("div.header")!!.text().contains(query, true)
        }

        items.chunked(chunkedSize)[page - 1].forEach {
            val anime = SAnime.create()
            val name = it.selectFirst("div.header")!!.text()

            anime.title = name
            anime.setUrlWithoutDomain(
                baseUrl.toHttpUrl().newBuilder()
                    .addPathSegments(it.attr("href"))
                    .build().encodedPath,
            )
            anime.thumbnail_url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegments(it.selectFirst("img")!!.attr("src"))
                .build().toString()
            animeList.add(anime)
        }

        return AnimesPage(animeList, page * chunkedSize <= items.size)
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
        var counter = 1

        fun traverseDirectory(url: String) {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            doc.select(episodeListSelector()).forEach { link ->
                val href = link.selectFirst("a[href]")!!.attr("href")

                if (href.isNotBlank() && href != "../") {
                    val fullUrl = joinUrl(url, href)
                    if (
                        href.startsWith("?dir=") &&
                        !(
                            preferences.getBoolean("ignore_bonus", true) &&
                                fullUrl.endsWith("/bonus", true)
                            )
                    ) {
                        traverseDirectory(fullUrl)
                    }
                    if (videoFormats.any { t -> fullUrl.toHttpUrl().encodedPath.endsWith(t) }) {
                        val episode = SEpisode.create()
                        val paths = fullUrl.toHttpUrl().pathSegments

                        val extraInfo = if (paths.size > 4) {
                            "/" + paths.subList(3, paths.size - 1).joinToString("/") { it.trimInfo() }
                        } else {
                            ""
                        }
                        val size = link.selectFirst("span.file-size")?.let { it.text().trim() }
                        val episodeNumRegex = Regex(""" - \d+x(\d+) - """)

                        episode.name = URLDecoder.decode(
                            videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix).trimInfo() },
                            "UTF-8",
                        )
                        episode.url = fullUrl
                        episode.scanlator = URLDecoder.decode("${extraInfo.ifBlank { "/" }}${if (size == null) "" else " • $size"}", "UTF-8")
                        episode.episode_number = episodeNumRegex.find(paths.last())?.let {
                            it.groupValues[1].toFloatOrNull()
                        } ?: counter.toFloat()
                        counter++

                        episodeList.add(episode)
                    }
                }
            }
        }

        traverseDirectory("https://${baseUrl.toHttpUrl().host}${anime.url}".toHttpUrl().toString())

        val episodes = if (preferences.getBoolean("attempt_sort", true)) {
            val seasonRegex = Regex(""" - (\d+|None)+x(?:\d+|None) - """)
            val episodeRegex = Regex(""" - (?:\d+|None)x(\d+|None) - """)
            episodeList.sortedWith(
                compareBy(
                    { epName ->
                        seasonRegex.find(epName.name)?.let {
                            it.groupValues[1].toFloatOrNull()
                        } ?: 0F
                    },
                    { epName ->
                        episodeRegex.find(epName.name)?.let {
                            it.groupValues[1].toFloatOrNull()
                        } ?: 0F
                    },
                ),
            ).reversed()
        } else {
            episodeList.reversed()
        }

        return Observable.just(episodes)
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")

    override fun episodeListSelector(): String = "ul#directory-listing > li"

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        return Observable.just(listOf(Video(episode.url, "Video", episode.url)))
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    private fun joinUrl(url: String, path2: String): String {
        return url.toHttpUrl().newBuilder()
            .query("")
            .toString()
            .removeSuffix("?")
            .removeSuffix("/") +
            "/" +
            path2.removePrefix("/")
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] """.toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val ignoreExtras = SwitchPreferenceCompat(screen.context).apply {
            key = "ignore_bonus"
            title = "Ignore \"Bonus\" folder"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        val attemptEpsiodeSort = SwitchPreferenceCompat(screen.context).apply {
            key = "attempt_sort"
            title = "Attempt episode sorting"
            summary = "Attempt sorting based on season and episode number"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        screen.addPreference(ignoreExtras)
        screen.addPreference(attemptEpsiodeSort)
    }
}
