package eu.kanade.tachiyomi.animeextension.es.hentaila

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
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class Hentaila : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Hentaila"

    override val baseUrl = "https://www4.hentaila.com"

    override val lang = "es"

    private val json: Json by injectLazy()

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "StreamWish",
            "Voe",
            "Arc",
            "YourUpload",
            "Mp4Upload",
            "BurstCloud",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/directorio?filter=popular&p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".hentais .hentai")
        val nextPage = document.select(".pagination .fa-arrow-right").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("a").attr("abs:href"))
                title = element.selectFirst(".h-header .h-title")!!.text()
                thumbnail_url = element.selectFirst(".h-thumb img")!!.attr("abs:src").replace("/fondos/", "/portadas/")
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/directorio?filter=recent&p=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        if (query.isNotEmpty()) {
            if (query.length < 2) throw IOException("La búsqueda debe tener al menos 2 caracteres")
            return POST("$baseUrl/api/search", headers, FormBody.Builder().add("value", query).build())
        }

        var url = "$baseUrl/directorio?p=$page".toHttpUrl().newBuilder()

        if (genreFilter.state != 0) {
            url = "$baseUrl/genero/${genreFilter.toUriPart()}?p=$page".toHttpUrl().newBuilder()
        }

        filterList.forEach { filter ->
            when (filter) {
                is OrderFilter -> {
                    url.addQueryParameter("filter", filter.toUriPart())
                }
                is StatusOngoingFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("status[1]", "on")
                    }
                }
                is StatusCompletedFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("status[2]", "on")
                    }
                }
                is UncensoredFilter -> {
                    if (filter.state) {
                        url.addQueryParameter("uncensored", "on")
                    }
                }

                else -> {}
            }
        }

        return GET(url.build().toString(), headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        if (response.request.url.toString().startsWith("$baseUrl/api/search")) {
            val jsonString = response.body.string()
            val results = json.decodeFromString<List<HentailaDto>>(jsonString)
            val animeList = results.map { anime ->
                SAnime.create().apply {
                    title = anime.title
                    thumbnail_url = "$baseUrl/uploads/portadas/${anime.id}.jpg"
                    setUrlWithoutDomain("$baseUrl/hentai-${anime.slug}")
                }
            }
            return AnimesPage(animeList, false)
        }

        val document = response.asJsoup()
        val animes = document.select("div.columns main section.section div.grid.hentais article.hentai").map {
            SAnime.create().apply {
                title = it.select("header.h-header h2").text()
                setUrlWithoutDomain(it.select("a").attr("abs:href"))
                thumbnail_url = it.select("div.h-thumb figure img").attr("abs:src")
            }
        }

        val hasNextPage = document.select("a.btn.rnd.npd.fa-arrow-right").any()

        return AnimesPage(animes, hasNextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            thumbnail_url = baseUrl + document.selectFirst("div.h-thumb figure img")!!.attr("src")
            with(document.selectFirst("article.hentai-single")!!) {
                title = selectFirst("header.h-header h1")!!.text()
                description = select("div.h-content p").text()
                genre = select("footer.h-footer nav.genres a.btn.sm").joinToString { it.text() }
                status = if (selectFirst("span.status-on") != null) {
                    SAnime.ONGOING
                } else {
                    SAnime.COMPLETED
                }
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val animeId = response.request.url.toString().substringAfter("hentai-").lowercase()
        val jsoup = response.asJsoup()

        jsoup.select("div.episodes-list article").forEach { it ->
            val epNum = it.select("a").attr("href").replace("/ver/$animeId-", "")
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                url = "/ver/$animeId-$epNum"
                date_upload = try {
                    val date = it.select(".h-header time").text()
                    SimpleDateFormat("MMMMM dd, yyyy", Locale.ENGLISH).parse(date).time
                } catch (_: Exception) { System.currentTimeMillis() }
            }
            episodes.add(episode)
        }

        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val videoServers = document.selectFirst("script:containsData(var videos = [)")!!.data().substringAfter("videos = ").substringBefore(";")
            .replace("[[", "").replace("]]", "")
        val videoServerList = videoServers.split("],[")
        videoServerList.forEach {
            val server = it.split(",").map { a -> a.replace("\"", "") }
            val urlServer = server[1].replace("\\/", "/")
            val nameServer = server[0]

            when (nameServer.lowercase()) {
                "streamwish" -> {
                    videoList.addAll(StreamWishExtractor(client, headers).videosFromUrl(urlServer, videoNameGen = { "StreamWish:$it" }))
                }
                "voe" -> {
                    videoList.addAll(VoeExtractor(client).videosFromUrl(urlServer))
                }
                "arc" -> {
                    val videoUrl = urlServer.substringAfter("#")
                    videoList.add(Video(videoUrl, "Arc", videoUrl))
                }
                "yupi" -> {
                    videoList.addAll(YourUploadExtractor(client).videoFromUrl(urlServer, headers = headers))
                }
                "mp4upload" -> {
                    videoList.addAll(Mp4uploadExtractor(client).videosFromUrl(urlServer, headers = headers))
                }
                "burst" -> {
                    videoList.addAll(BurstCloudExtractor(client).videoFromUrl(urlServer, headers = headers))
                }
            }
        }

        return videoList
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
        GenreFilter(),
        AnimeFilter.Separator(),
        OrderFilter(),
        AnimeFilter.Separator(),
        StatusOngoingFilter(),
        StatusCompletedFilter(),
        AnimeFilter.Separator(),
        UncensoredFilter(),
    )

    private class StatusOngoingFilter : AnimeFilter.CheckBox("En Emision")
    private class StatusCompletedFilter : AnimeFilter.CheckBox("Finalizado")
    private class UncensoredFilter : AnimeFilter.CheckBox("Sin Censura")

    private class OrderFilter : UriPartFilter(
        "Ordenar por",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Populares", "popular"),
            Pair("Recientes", "recent"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("3D", "3d"),
            Pair("Ahegao", "ahegao"),
            Pair("Anal", "anal"),
            Pair("Casadas", "casadas"),
            Pair("Chikan", "chikan"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Enfermeras", "enfermeras"),
            Pair("Futanari", "futanari"),
            Pair("Harem", "Harem"),
            Pair("Gore", "gore"),
            Pair("Hardcore", "hardcore"),
            Pair("Incesto", "incesto"),
            Pair("Juegos Sexuales", "juegos-sexuales"),
            Pair("Maids", "maids"),
            Pair("Milfs", "milfs"),
            Pair("Netorare", "netorare"),
            Pair("Ninfomania", "ninfomania"),
            Pair("Ninjas", "ninjas"),
            Pair("Orgia", "orgia"),
            Pair("Romance", "romance"),
            Pair("Shota", "shota"),
            Pair("Softcore", "softcore"),
            Pair("Succubus", "succubus"),
            Pair("Teacher", "teacher"),
            Pair("Tentaculos", "tentaculos"),
            Pair("Tetonas", "tetonas"),
            Pair("Vanilla", "vanilla"),
            Pair("Violacion", "violacion"),
            Pair("Virgenes", "virgenes"),
            Pair("Yaoi", "Yaoi"),
            Pair("Yuri", "yuri"),
            Pair("Bondage", "bondage"),
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
