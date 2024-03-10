package eu.kanade.tachiyomi.animeextension.es.lacartoons

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Lacartoons : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "LACartoons"

    override val baseUrl = "https://www.lacartoons.com"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "FileLions"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Voe",
            "Okru",
            "YourUpload",
            "FileLions",
            "StreamHideVid",
        )
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val animeDetails = SAnime.create().apply {
            title = document.selectFirst(".subtitulo-serie-seccion")?.ownText() ?: ""
            author = document.selectFirst(".marcador-cartoon")?.text()
            status = SAnime.COMPLETED
            description = document.selectFirst(".informacion-serie-seccion > p:contains(Reseña) span")?.text()
            genre = document.selectFirst(".marcador-cartoon")?.text()
            thumbnail_url = document.selectFirst("div.h-thumb figure img")?.attr("abs:src")
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/?page=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".conjuntos-series a")
        val nextPage = document.select(".pagination a[rel=next]").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".serie .informacion-serie .nombre-serie")!!.text()
                thumbnail_url = element.selectFirst(".serie img")!!.attr("abs:src")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val studioFilter = filterList.find { it is StudioFilter } as StudioFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/?utf8=✓&Titulo=$query", headers)
            studioFilter.state != 0 -> GET("$baseUrl/?Categoria_id=${studioFilter.toUriPart()}&page=$page", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()

        var realEpNumber = 1
        jsoup.select(".estilo-temporada").forEachIndexed { idx, seasonInfo ->
            val seasonNumber = seasonInfo.ownText().filter { it.isDigit() }
            jsoup.select(".episodio-panel").getOrNull(idx)?.select("ul > li > a")?.forEach { ep ->
                val noEp = ep.select("span").text().filter { it.isDigit() }
                val episode = SEpisode.create().apply {
                    episode_number = realEpNumber.toFloat()
                    name = "T$seasonNumber - E$noEp - ${ep.ownText().trim()}"
                    setUrlWithoutDomain(ep.attr("abs:href"))
                }
                realEpNumber += 1
                episodes.add(episode)
            }
        }

        return episodes.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select("iframe").parallelCatchingFlatMapBlocking {
            serverVideoResolver(it.attr("src"))
        }
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url)
            embedUrl.contains("filelions") || embedUrl.contains("lion") -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            }
            embedUrl.contains("vidhide") || embedUrl.contains("streamhide") ||
                embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> StreamHideVidExtractor(client).videosFromUrl(url)
            embedUrl.contains("voe") -> VoeExtractor(client).videosFromUrl(url)
            embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            else -> emptyList()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        StudioFilter(),
    )

    private class StudioFilter : UriPartFilter(
        "Estudio",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Nickelodeon", "1"),
            Pair("Cartoon Network", "2"),
            Pair("Fox Kids", "3"),
            Pair("Hanna Barbera", "4"),
            Pair("Disney", "5"),
            Pair("Warner Channel", "6"),
            Pair("Marvel", "7"),
            Pair("Otros", "8"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
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
    }
}
