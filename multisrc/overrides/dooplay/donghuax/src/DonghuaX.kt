package eu.kanade.tachiyomi.animeextension.pt.donghuax

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.donghuax.extractors.DailymotionExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class DonghuaX : DooPlay(
    "pt-BR",
    "DonghuaX",
    "https://donghuax.com",
) {
    private val json: Json by injectLazy()

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div > aside article.w_item_a"

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = "div#archive-content > article.item"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/donghua/page/$page/")

    override fun latestUpdatesNextPageSelector(): String = "div.pagination > span.current + a"

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "ul.episodios > li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return if (response.request.url.pathSegments.first().contains("movie", true)) {
            val document = response.use { getRealAnimeDoc(it.asJsoup()) }
            listOf(
                SEpisode.create().apply {
                    episode_number = 0F
                    date_upload = document.selectFirst("div.extra > span.date")
                        ?.text()
                        ?.toDate() ?: 0L
                    name = "Movie"
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            response.use {
                getRealAnimeDoc(it.asJsoup())
            }.select(episodeListSelector()).map(::episodeFromElement).reversed()
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val epNum = element.selectFirst("div.numerando")!!.text()
                .trim()
                .let(episodeNumberRegex::find)
                ?.groupValues
                ?.last() ?: "0"
            val href = element.selectFirst("a[href]")!!
            val episodeName = href.ownText()
            episode_number = epNum.toFloatOrNull() ?: 0F
            date_upload = element.selectFirst(episodeDateSelector)
                ?.text()
                ?.toDate() ?: 0L
            name = episodeName
            setUrlWithoutDomain(href.attr("href"))
        }
    }

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val url = response.request.url.toString()

        val animes = when {
            "/?s=" in url -> { // Search by name.
                document.select(searchAnimeSelector()).map { element ->
                    searchAnimeFromElement(element)
                }
            }
            else -> { // Search by some kind of filter, like genres or popularity.
                document.select("div.items > article.item").map { element ->
                    latestUpdatesFromElement(element)
                }
            }
        }

        val hasNextPage = document.selectFirst(searchAnimeNextPageSelector()) != null
        return AnimesPage(animes, hasNextPage)
    }

    // ============================== Filters ===============================

    override val fetchGenres = false

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header(genreFilterHeader),
        GenreFilter(),
        YearFilter(),
        LetterFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Gêneros",
        arrayOf(
            Pair("<Selecione>", ""),
            Pair("Ação", "genero/acao/"),
            Pair("Artes Marciais", "genero/artes-marciais/"),
            Pair("Aventura", "genero/aventura/"),
            Pair("BL", "genero/bl/"),
            Pair("Comédia", "genero/comedia/"),
            Pair("Drama", "genero/drama/"),
            Pair("Escolar", "genero/escolar/"),
            Pair("Fantasia", "genero/fantasia/"),
            Pair("Ficção Científica", "genero/ficcao-cientifica/"),
            Pair("Gourmet", "genero/gourmet/"),
            Pair("Harem", "genero/harem/"),
            Pair("Histórico", "genero/historico/"),
            Pair("Mistério", "genero/misterio/"),
            Pair("Mitologia", "genero/mitologia/"),
            Pair("Reencarnação", "genero/reencarnacao/"),
            Pair("Romance", "genero/romance/"),
            Pair("Slice of Life", "genero/slice-of-life/"),
            Pair("Sobrenatural", "genero/sobrenatural/"),
            Pair("Suspense", "genero/suspense/"),
            Pair("Vampiro", "genero/vampiro/"),
            Pair("Viagem no Tempo", "genero/viagem-no-tempo/"),
            Pair("Video Game", "genero/video-game/"),
        ),
    )

    private class YearFilter : UriPartFilter(
        "Anos",
        arrayOf(
            Pair("<Selecione>", ""),
            Pair("2023", "ano/2023/"),
            Pair("2022", "ano/2022/"),
            Pair("2021", "ano/2021/"),
            Pair("2020", "ano/2020/"),
            Pair("2019", "ano/2019/"),
            Pair("2018", "ano/2018/"),
            Pair("2017", "ano/2017/"),
            Pair("2016", "ano/2016/"),
            Pair("2015", "ano/2015/"),
            Pair("2014", "ano/2014/"),
        ),
    )

    private class LetterFilter : UriPartFilter(
        "Letra",
        arrayOf(
            Pair("<Selecione>", ""),
            Pair("#", "letra/0-9"),
            Pair("A", "letra/a"),
            Pair("B", "letra/b"),
            Pair("C", "letra/c"),
            Pair("D", "letra/d"),
            Pair("E", "letra/e"),
            Pair("F", "letra/f"),
            Pair("G", "letra/g"),
            Pair("H", "letra/h"),
            Pair("I", "letra/i"),
            Pair("J", "letra/j"),
            Pair("K", "letra/k"),
            Pair("L", "letra/l"),
            Pair("M", "letra/m"),
            Pair("N", "letra/n"),
            Pair("O", "letra/o"),
            Pair("P", "letra/p"),
            Pair("Q", "letra/q"),
            Pair("R", "letra/r"),
            Pair("S", "letra/s"),
            Pair("T", "letra/t"),
            Pair("U", "letra/u"),
            Pair("V", "letra/v"),
            Pair("W", "letra/w"),
            Pair("X", "letra/x"),
            Pair("Y", "letra/y"),
            Pair("Z", "letra/z"),
        ),
    )

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val playerUrls = response.asJsoup().select("ul#playeroptionsul li").map {
            getPlayerUrl(it)
        }
        return playerUrls.parallelMap { media ->
            runCatching {
                getPlayerVideos(media)
            }.getOrNull()
        }.filterNotNull().flatten()
    }

    private fun getPlayerVideos(url: String): List<Video> {
        return when {
            url.contains("${baseUrl.toHttpUrl().host}/jwplayer/", true) -> {
                val videoUrl = url.toHttpUrl().queryParameter("source")
                videoUrl?.let {
                    listOf(Video(videoUrl, "Internal Video - ${videoUrl.toHttpUrl().host}", videoUrl))
                } ?: emptyList()
            }
            url.contains("play.${baseUrl.toHttpUrl().host}/player1", true) -> {
                val playerScript = client.newCall(
                    GET(url),
                ).execute().asJsoup().selectFirst("script:containsData(sources)")?.data()
                playerScript?.let {
                    json.decodeFromString<List<PlayerSource>>(
                        "[${it.substringAfter("sources: [").substringBefore("]")}]",
                    ).map { source ->
                        Video(source.file, "Internal player - ${source.label}", source.file)
                    }
                } ?: emptyList()
            }
            url.contains("play.${baseUrl.toHttpUrl().host}/mdplayer", true) -> {
                val id = client.newCall(
                    GET(url),
                ).execute().asJsoup().selectFirst("vm-dailymotion[video-id]")?.attr("video-id")
                id?.let {
                    DailymotionExtractor(client).videosFromUrl("https://www.dailymotion.com/embed/video/$it", "Dailymotion - ")
                } ?: emptyList()
            }
            url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                url.contains("vidmovie.xyz") || url.contains("sbspeed.com") || url.contains("streamsss.net") ||
                url.contains("sblanh.com") || url.contains("sbbrisk.com") || url.contains("lvturbo.com") ||
                url.contains("sbrapid.com") -> {
                StreamSBExtractor(client).videosFromUrl(url, headers)
            }
            url.contains("csst.online") -> {
                val urlRegex = Regex("""\[(.*?)\](https?:\/\/(?:www\.)?[-a-zA-Z0-9@:%._\+~#=]{1,256}\.[a-zA-Z0-9()]{1,6}\b(?:[-a-zA-Z0-9()@:%_\+.~#?&\/=]*))""")
                val dataScript = client.newCall(GET(url)).execute()
                    .asJsoup()
                    .selectFirst("script:containsData(isMobile)")
                    ?.data()
                dataScript?.let {
                    urlRegex.findAll(it).distinctBy { match ->
                        match.groupValues[2]
                    }.map { match ->
                        val videoUrl = match.groupValues[2]
                        val videoHeaders = Headers.Builder()
                            .add("Referer", "https://${videoUrl.toHttpUrl().host}/")
                            .build()
                        Video(videoUrl, "AllVideo - ${match.groupValues[1]}", videoUrl, headers = videoHeaders)
                    }.toList()
                } ?: emptyList()
            }
            url.contains("blogger.com") -> {
                val response = client.newCall(GET(url, headers = headers)).execute()
                val streams = response.body.string().substringAfter("\"streams\":[").substringBefore("]")
                streams.split("},")
                    .map {
                        val url = it.substringAfter("{\"play_url\":\"").substringBefore('"')
                        val quality = when (it.substringAfter("\"format_id\":").substringBefore("}")) {
                            "18" -> "Blogger - 360p"
                            "22" -> "Blogger - 720p"
                            else -> "Unknown Resolution"
                        }
                        Video(url, quality, url, null, headers)
                    }
            }
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()
        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES
            entryValues = PREF_SERVER_VALUES
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================

    override val prefQualityValues = arrayOf("288p", "360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.lowercase().contains(quality.lowercase()) },
                { it.quality.lowercase().contains(server.lowercase()) },
            ),
        ).reversed()
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    @Serializable
    data class PlayerSource(
        val file: String,
        val label: String,
    )

    companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Preferred server"
        private const val PREF_SERVER_DEFAULT = "AllVideo"
        private val PREF_SERVER_ENTRIES = arrayOf("AllVideo", "Dailymotion", "StreamSB", "Internal Player", "Internal Video", "Blogger")
        private val PREF_SERVER_VALUES = PREF_SERVER_ENTRIES
    }
}
