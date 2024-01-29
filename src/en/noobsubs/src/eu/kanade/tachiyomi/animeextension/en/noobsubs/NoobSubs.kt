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
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class NoobSubs : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "NoobSubs"

    override val baseUrl = "https://noobftp1.noobsubs.com"

    private val videoFormats = arrayOf(".mkv", ".mp4", ".avi")

    override val lang = "en"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val badNames = arrayOf("../", "gifs/")

        val animeList = document.select(popularAnimeSelector()).mapNotNull {
            val a = it.selectFirst("a")!!
            val name = a.text()
            if (name in badNames) return@mapNotNull null

            SAnime.create().apply {
                title = name.removeSuffix("/")
                setUrlWithoutDomain(a.attr("href"))
                thumbnail_url = ""
            }
        }

        return AnimesPage(animeList, false)
    }

    override fun popularAnimeSelector(): String = "table tr:has(a)"

    override fun popularAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return client.newCall(searchAnimeRequest(page, query, filters))
            .awaitSuccess()
            .let { response ->
                searchAnimeParse(response, query)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = popularAnimeRequest(page)

    private fun searchAnimeParse(response: Response, query: String): AnimesPage {
        val document = response.asJsoup()
        val badNames = arrayOf("../", "gifs/")

        val animeList = document.select(popularAnimeSelector()).mapNotNull {
            val name = it.text()
            if (name in badNames || !name.contains(query, ignoreCase = true)) return@mapNotNull null
            if (it.selectFirst("span.size")?.text()?.contains(" KiB") == true) return@mapNotNull null

            SAnime.create().apply {
                title = name.removeSuffix("/")
                setUrlWithoutDomain(it.selectFirst("a")!!.attr("href"))
                thumbnail_url = ""
            }
        }

        return AnimesPage(animeList, false)
    }

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = anime

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    // ============================== Episodes ==============================

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()
        var counter = 1

        fun traverseDirectory(url: String) {
            val doc = client.newCall(GET(url)).execute().asJsoup()

            doc.select(popularAnimeSelector()).forEach { link ->
                val href = link.selectFirst("a")!!.attr("href")
                val text = link.selectFirst("a")!!.text()
                if ("""\bOST\b""".toRegex().matches(text) || text.contains("original sound", true)) return@forEach
                if (preferences.getBoolean(PREF_IGNORE_EXTRA_KEY, PREF_IGNORE_EXTRA_DEFAULT) && text.equals("extras", ignoreCase = true)) return@forEach

                if (href.isNotBlank() && href != "..") {
                    val fullUrl = baseUrl + href
                    if (fullUrl.endsWith("/")) {
                        traverseDirectory(fullUrl)
                    }
                    if (videoFormats.any { t -> fullUrl.endsWith(t) }) {
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

                        episodeList.add(
                            SEpisode.create().apply {
                                name = "${season}${videoFormats.fold(paths.last()) { acc, suffix -> acc.removeSuffix(suffix).trimInfo() }}"
                                this.url = fullUrl
                                scanlator = "${if (size == null) "" else "$size • "}$seasonInfo$extraInfo"
                                episode_number = counter.toFloat()
                            },
                        )
                        counter++
                    }
                }
            }
        }

        traverseDirectory(baseUrl + anime.url)

        return episodeList.reversed()
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> =
        listOf(Video(episode.url, "Video", episode.url))

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

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

    companion object {
        private const val PREF_IGNORE_EXTRA_KEY = "ignore_extras"
        private const val PREF_IGNORE_EXTRA_DEFAULT = true
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_IGNORE_EXTRA_KEY
            title = "Ignore \"Extras\" folder"
            setDefaultValue(PREF_IGNORE_EXTRA_DEFAULT)
            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(key, newValue as Boolean).commit()
            }
        }.also(screen::addPreference)
    }
}
