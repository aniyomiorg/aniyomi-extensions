package eu.kanade.tachiyomi.animeextension.en.noobsubs

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

class NoobSubs : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "NoobSubs"

    override val baseUrl = "https://noobftp1.noobsubs.com"

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "en"

    override val supportsLatest = false

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
            val a = it.selectFirst("a")!!
            val name = a.text()
            if (name in badNames) return@forEach

            val anime = SAnime.create()
            anime.title = name.removeSuffix("/")
            anime.setUrlWithoutDomain(a.attr("href"))
            animeList.add(anime)
        }

        return AnimesPage(animeList, false)
    }

    override fun popularAnimeSelector(): String = "table tr:has(a)"

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
            if (it.selectFirst("span.size")?.text()?.contains(" KiB") == true) return@forEach

            val anime = SAnime.create()
            anime.title = name.removeSuffix("/")
            anime.setUrlWithoutDomain(it.selectFirst("a")!!.attr("href"))
            animeList.add(anime)
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
        var counter = 1

        fun traverseDirectory(url: String) {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            doc.select(popularAnimeSelector()).forEach { link ->
                val href = link.selectFirst("a")!!.attr("href")
                val text = link.selectFirst("a")!!.text()
                if ("""\bOST\b""".toRegex().matches(text) || text.contains("original sound", true)) return@forEach
                if (preferences.getBoolean("ignore_extras", true) && text.equals("extras", ignoreCase = true)) return@forEach

                if (href.isNotBlank() && href != "..") {
                    val fullUrl = baseUrl + href
                    if (fullUrl.endsWith("/")) {
                        traverseDirectory(fullUrl)
                    }
                    if (videoFormats.any { t -> fullUrl.endsWith(t) }) {
                        val episode = SEpisode.create()
                        val paths = fullUrl.toHttpUrl().pathSegments

                        val seasonInfoRegex = """(\([\s\w-]+\))(?: ?\[[\s\w-]+\])?${'$'}""".toRegex()
                        val seasonInfo = if (seasonInfoRegex.containsMatchIn(paths[1])) {
                            "${seasonInfoRegex.find(paths[1])!!.groups[1]!!.value} • "
                        } else {
                            ""
                        }

                        val season = if (paths.size == 2) {
                            ""
                        } else {
                            "[${paths[1].trimInfo()}] "
                        }

                        val extraInfo = if (paths.size > 3) {
                            "/" + paths.subList(2, paths.size - 1).joinToString("/") { it.trimInfo() }
                        } else {
                            ""
                        }
                        val size = link.selectFirst("td.fb-s")?.text()

                        episode.name = "${season}${videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix).trimInfo() }}${if (size == null) "" else " - $size"}"
                        episode.url = fullUrl
                        episode.scanlator = seasonInfo + extraInfo
                        episode.episode_number = counter.toFloat()
                        counter++

                        episodeList.add(episode)
                    }
                }
            }
        }

        traverseDirectory(baseUrl + anime.url)

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
            key = "ignore_extras"
            title = "Ignore \"Extras\" folder"
            setDefaultValue(true)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }
        screen.addPreference(ignoreExtras)
    }
}
