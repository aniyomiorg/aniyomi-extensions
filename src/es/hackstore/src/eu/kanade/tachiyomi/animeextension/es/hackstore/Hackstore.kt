
package eu.kanade.tachiyomi.animeextension.es.hackstore

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hackstore : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Hackstore"

    override val baseUrl = "https://hackstore.to"

    override val lang = "es"

    override val supportsLatest = false // currently not supported

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas/page/$page/")

    override fun popularAnimeSelector(): String = "div.movie-thumbnail"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            title = element.select(".movie-title").attr("title")
            thumbnail_url = element.select(".poster-pad img").attr("abs:data-src")
            description = ""
            setUrlWithoutDomain(element.select(".movie-thumbnail a").attr("abs:href"))
        }
    }

    override fun popularAnimeNextPageSelector(): String = "div.wp-pagenavi .current ~ a"

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/page/$page/?s")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    // =============================== Search ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Animes", "animes"),
        ),
    )
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query")
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}/page/$page/")
            else -> popularAnimeRequest(page)
        }
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val isMovie = document.location().contains("/peliculas/")
        if (isMovie) {
            val infoText = document.select(".watch-content .watch-text strong ~ p").text()
            return SAnime.create().apply {
                description = document.selectFirst(".watch-content .watch-text p:nth-child(1)")?.text()?.removeSurrounding("\"")
                title = if (infoText.contains("Título Latino:", true)) infoText.substringAfter("Título Latino:").substringBefore(")").trim() + ")" else ""
                genre = if (infoText.contains("Genero:", true)) infoText.substringAfter("Genero:").substringBefore("País").trim().replace(",", ", ") else null
                author = if (infoText.contains("Director:", true)) infoText.substringAfter("Director:").substringBefore(",").trim() else null
                artist = if (infoText.contains("Elenco:", true)) infoText.substringAfter("Elenco:").substringBefore(",").trim() else null
                thumbnail_url = document.selectFirst(".watch-content img")?.attr("abs:data-src")?.replace("-200x300", "")
                status = SAnime.COMPLETED
            }
        } else {
            return SAnime.create().apply {
                title = document.selectFirst(".serieee h2")?.text() ?: ""
                description = document.selectFirst("#pcontent > p")?.text()?.trim() ?: document.selectFirst("#zcontent > p")?.text()?.trim() ?: ""
                genre = document.select("#ggenre [rel=tag]").joinToString { it.text() }
                thumbnail_url = document.selectFirst(".imghacks")?.attr("abs:data-src")
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val ismovie = response.request.url.toString().contains("/peliculas/")
        return if (ismovie) {
            listOf(
                SEpisode.create().apply {
                    name = "PELÍCULA"
                    setUrlWithoutDomain(response.request.url.toString())
                    episode_number = 1f
                },
            )
        } else {
            document.select(".movie-thumbnail").map { thumbnail ->
                val episodeLink = thumbnail.select("a").attr("href")
                val seasonMatch = Regex("-(\\d+)x(\\d+)/$").find(episodeLink)
                val seasonNumber = seasonMatch?.groups?.get(1)?.value?.toInt() ?: 0
                val episodeNumber = seasonMatch?.groups?.get(2)?.value?.toInt() ?: 0
                SEpisode.create().apply {
                    name = "T$seasonNumber - E$episodeNumber"
                    episode_number = episodeNumber.toFloat()
                    setUrlWithoutDomain(episodeLink)
                }
            }
        }.reversed()
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element): SEpisode { throw UnsupportedOperationException() }

    // ============================ Video Links =============================
    private fun extractUrlFromDonFunction(fullUrl: String): String {
        val response = client.newCall(GET(fullUrl, headers)).execute()
        val body = response.body.string()
        val document = Jsoup.parse(body)
        val scriptElement = document.selectFirst("script:containsData(function don())")
        val urlPattern = Regex("window\\.location\\.href\\s*=\\s*'([^']+)'")
        val matchResult = scriptElement?.data()?.let { urlPattern.find(it) }
        return matchResult?.groupValues?.get(1) ?: "url not found"
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("ul.TbVideoNv li.pres").parallelCatchingFlatMapBlocking { tab ->
            val server = tab.select("a.playr").text()
            val deco = tab.select("a.playr").attr("data-href")
            val langs = tab.select("a.playr").attr("data-lang")
            val fullUrl = baseUrl + deco
            val url = extractUrlFromDonFunction(fullUrl)
            val isLatino = langs.contains("latino")
            val isSub = langs.contains("subtitulado") || langs.contains("sub") || langs.contains("japonés")
            val prefix = if (isLatino) "[LAT]" else if (isSub) "[SUB]" else "[CAST]"

            when {
                server.contains("streamtape") || server.contains("stp") || server.contains("stape") -> {
                    listOf(streamTapeExtractor.videoFromUrl(url, quality = "$prefix StreamTape")!!)
                }
                server.contains("voe") -> voeExtractor.videosFromUrl(url, prefix)
                server.contains("filemoon") -> filemoonExtractor.videosFromUrl(url, prefix = "$prefix Filemoon:")
                server.contains("wishembed") || server.contains("streamwish") || server.contains("strwish") || server.contains("wish") -> {
                    streamWishExtractor.videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                }
                server.contains("doodstream") || server.contains("dood.") || server.contains("ds2play") || server.contains("doods.") -> {
                    doodExtractor.videosFromUrl(url, "$prefix DoodStream")
                }
                else -> emptyList()
            }
        }
    }

    override fun videoListSelector(): String = "ul.TbVideoNv li.pres a.playr"

    override fun videoFromElement(element: Element): Video { throw UnsupportedOperationException() }

    override fun videoUrlParse(document: Document): String { throw UnsupportedOperationException() }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // =========================== Preferences =============================

    companion object {
        const val PREFIX_SEARCH = "id:"
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("Latino", "Castellano", "Subtitulado")
        private val LANGUAGE_VALUES = arrayOf("[LAT]", "[CAST]", "[SUB]")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "StreamWish"
        private val SERVER_LIST = arrayOf("DoodStream", "StreamTape", "Voe", "Filemoon", "StreamWish")
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
