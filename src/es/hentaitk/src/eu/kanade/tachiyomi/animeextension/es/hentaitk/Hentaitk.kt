package eu.kanade.tachiyomi.animeextension.es.hentaitk

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Hentaitk : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "HentaiTk"

    override val baseUrl = "https://hentaitk.net"

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
            title = document.selectFirst(".video-info h1")?.text() ?: ""
            status = SAnime.UNKNOWN
            description = document.selectFirst(".video-details .post-entry p:not([style])")?.text()
            genre = document.select(".video-details .meta a").joinToString { it.text() }
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        }
        return animeDetails
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/hentais/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".video-section .item")
        val nextPage = document.select(".pagination .page-item .next").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select(".post-header .post-title a").attr("abs:href"))
                title = element.selectFirst(".post-header .post-title a")!!.text()
                thumbnail_url = getImageLink(element.selectFirst(".item-img a img"))
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesParse(response: Response) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val tagFilter = filterList.find { it is TagFilter } as TagFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/page/$page/?s=$query", headers)
            tagFilter.state != 0 -> GET("$baseUrl/${tagFilter.toUriPart()}page/$page/", headers)
            typeFilter.state != 0 -> GET("$baseUrl/${typeFilter.toUriPart()}page/$page/", headers)
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return listOf(
            SEpisode.create().apply {
                episode_number = 1F
                name = "Episode"
                setUrlWithoutDomain(document.location())
            },
        )
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return if (document.select(".post-tape .page-link").any()) {
            document.select(".post-tape .page-link").parallelCatchingFlatMapBlocking {
                val urlPage = it.attr("abs:href")
                val videoDoc = client.newCall(GET(urlPage)).execute().asJsoup()
                val videoUrl = videoDoc.select(".embed-responsive-item iframe").attr("src")
                serverVideoResolver(videoUrl)
            }
        } else {
            document.select(".embed-responsive-item iframe").parallelCatchingFlatMapBlocking {
                serverVideoResolver(it.attr("src"))
            }
        }
    }

    /*--------------------------------Video extractors------------------------------------*/
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    private fun serverVideoResolver(url: String): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            embedUrl.contains("ok.ru") || embedUrl.contains("okru")
            -> okruExtractor.videosFromUrl(url)
            embedUrl.contains("filelions") || embedUrl.contains("lion")
            -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "FileLions:$it" })
            embedUrl.contains("wishembed") || embedUrl.contains("streamwish") ||
                embedUrl.contains("strwish") || embedUrl.contains("wish")
            -> streamWishExtractor.videosFromUrl(url, videoNameGen = { "StreamWish:$it" })
            embedUrl.contains("vidhide") || embedUrl.contains("streamhide") ||
                embedUrl.contains("guccihide") || embedUrl.contains("streamvid")
            -> streamHideVidExtractor.videosFromUrl(url)
            embedUrl.contains("voe") -> voeExtractor.videosFromUrl(url)
            embedUrl.contains("yourupload") || embedUrl.contains("upload")
            -> yourUploadExtractor.videoFromUrl(url, headers = headers)
            embedUrl.contains("doodstream") || embedUrl.contains("dood.") ||
                embedUrl.contains("d000d") || embedUrl.contains("d0000d") ||
                embedUrl.contains("ds2play") || embedUrl.contains("doods")
            -> doodExtractor.videosFromUrl(url, "DoodStream")
            embedUrl.contains("streamtape") || embedUrl.contains("stp") ||
                embedUrl.contains("stape")
            -> streamTapeExtractor.videosFromUrl(url, quality = "StreamTape")
            else -> emptyList()
        }
    }

    private fun getImageLink(element: Element?): String? {
        element ?: return null
        return if (element.hasAttr("srcset")) {
            val imageLinks = element.attr("srcset")
                .split(", ")
                .map { it.split(" ") }
                .map { it[0] to it[1].removeSuffix("w").toInt() }
            imageLinks.maxByOrNull { it.second }?.first
        } else {
            element.attr("src")
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
        TypeFilter(),
        AnimeFilter.Header("La busqueda por Tag ignora el filtro de Tipo"),
        TagFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Hentais", "hentais/"),
            Pair("Audio Latino", "hentai-audio-latino/"),
            Pair("3D", "3d/"),
            Pair("JAV", "jav/"),
        ),
    )

    private class TagFilter : UriPartFilter(
        "Tags",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("3P", "video_tag/3p/"),
            Pair("4HR+", "video_tag/4hr/"),
            Pair("4P", "video_tag/4p/"),
            Pair("Abuse", "video_tag/abuse/"),
            Pair("Accion", "video_tag/accion/"),
            Pair("Affair", "video_tag/affair/"),
            Pair("Amateur", "video_tag/amateur/"),
            Pair("Anal", "video_tag/anal/"),
            Pair("Aventura", "video_tag/aventura/"),
            Pair("Bakunyuu", "video_tag/bakunyuu/"),
            Pair("BBW", "video_tag/bbw/"),
            Pair("Beautiful Girl", "video_tag/beautiful-girl/"),
            Pair("Best", "video_tag/best/"),
            Pair("Bestiality", "video_tag/bestiality/"),
            Pair("Big Tits", "video_tag/big-tits/"),
            Pair("Blow", "video_tag/blow/"),
            Pair("Bondage", "video_tag/bondage/"),
            Pair("Breasts", "video_tag/breasts/"),
            Pair("Bukkake", "video_tag/bukkake/"),
            Pair("Busty Fetish", "video_tag/busty-fetish/"),
            Pair("Butt", "video_tag/butt/"),
            Pair("Chantaje", "video_tag/chantaje/"),
            Pair("colegio", "video_tag/colegio/"),
            Pair("Comedia", "video_tag/comedia/"),
            Pair("Cosplay", "video_tag/cosplay/"),
            Pair("Cowgirl", "video_tag/cowgirl/"),
            Pair("Creampie", "video_tag/creampie/"),
            Pair("Cuckold", "video_tag/cuckold/"),
            Pair("Debut Production", "video_tag/debut-production/"),
            Pair("Deep Throating", "video_tag/deep-throating/"),
            Pair("Degeneracion Mental", "video_tag/degeneracion-mental/"),
            Pair("Digital Mosaic", "video_tag/digital-mosaic/"),
            Pair("Dirty Words", "video_tag/dirty-words/"),
            Pair("Documentary", "video_tag/documentary/"),
            Pair("Drama", "video_tag/drama/"),
            Pair("Entertainer", "video_tag/entertainer/"),
            Pair("Escolar", "video_tag/escolar/"),
            Pair("Facials", "video_tag/facials/"),
            Pair("Female College Student", "video_tag/female-college-student/"),
            Pair("Futanari", "video_tag/futanari/"),
            Pair("Gal", "video_tag/gal/"),
            Pair("Game Character", "video_tag/game-character/"),
            Pair("Gangbang", "video_tag/gangbang/"),
            Pair("Handjob", "video_tag/handjob/"),
            Pair("Hardcore", "video_tag/hardcore/"),
            Pair("Harem", "video_tag/harem/"),
            Pair("Hot Spring", "video_tag/hot-spring/"),
            Pair("Huge Butt", "video_tag/huge-butt/"),
            Pair("Humiliation", "video_tag/humiliation/"),
            Pair("Incest", "video_tag/incest/"),
            Pair("Incesto", "video_tag/incesto/"),
            Pair("Juguetes sexuales", "video_tag/juguetes-sexuales/"),
            Pair("Kimomen", "video_tag/kimomen/"),
            Pair("Kiss", "video_tag/kiss/"),
            Pair("Loli", "video_tag/loli/"),
            Pair("Lolicon", "video_tag/lolicon/"),
            Pair("Lotion", "video_tag/lotion/"),
            Pair("Maid", "video_tag/maid/"),
            Pair("Married Woman", "video_tag/married-woman/"),
            Pair("Masturbation", "video_tag/masturbation/"),
            Pair("Mature Woman", "video_tag/mature-woman/"),
            Pair("Milf", "video_tag/milf/"),
            Pair("Milfs", "video_tag/milfs/"),
            Pair("Misterio", "video_tag/misterio/"),
            Pair("Nakadashi", "video_tag/nakadashi/"),
            Pair("Nasty", "video_tag/nasty/"),
            Pair("Netorare", "video_tag/netorare/"),
            Pair("Ninfomana", "video_tag/ninfomana/"),
            Pair("OL", "video_tag/ol/"),
            Pair("Older Sister", "video_tag/older-sister/"),
            Pair("Omnibus", "video_tag/omnibus/"),
            Pair("Oppai", "video_tag/oppai/"),
            Pair("Orgía", "video_tag/orgia/"),
            Pair("Original Collaboration", "video_tag/original-collaboration/"),
            Pair("Other Fetish", "video_tag/other-fetish/"),
            Pair("POV", "video_tag/pov/"),
            Pair("Promiscuity", "video_tag/promiscuity/"),
            Pair("Prostitutes", "video_tag/prostitutes/"),
            Pair("publico", "video_tag/publico/"),
            Pair("Risky Mosaic", "video_tag/risky-mosaic/"),
            Pair("Romance", "video_tag/romance/"),
            Pair("School Girls", "video_tag/school-girls/"),
            Pair("Shaved", "video_tag/shaved/"),
            Pair("Shotacon", "video_tag/shotacon/"),
            Pair("Sister", "video_tag/sister/"),
            Pair("Slender", "video_tag/slender/"),
            Pair("Slut", "video_tag/slut/"),
            Pair("Solowork", "video_tag/solowork/"),
            Pair("Squirting", "video_tag/squirting/"),
            Pair("Subjectivity", "video_tag/subjectivity/"),
            Pair("Sumision", "video_tag/sumision/"),
            Pair("Sweat", "video_tag/sweat/"),
            Pair("Tentáculos", "video_tag/tentaculos/"),
            Pair("Titty Fuck", "video_tag/titty-fuck/"),
            Pair("Toy", "video_tag/toy/"),
            Pair("Trio", "video_tag/trio/"),
            Pair("Uniform", "video_tag/uniform/"),
            Pair("Violacion", "video_tag/violacion/"),
            Pair("Virgen", "video_tag/virgen/"),
            Pair("Yuri", "video_tag/yuri/"),
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
