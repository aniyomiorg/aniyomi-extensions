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
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.lang.Exception

class Hentaila : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Hentaila"

    override val baseUrl = "https://www3.hentaila.com"

    override val lang = "es"

    private val json: Json by injectLazy()

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("section.latest-hentais div.slider > div.item")
        val animes = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("h2.h-title a").attr("abs:href"))
                title = element.selectFirst("h2.h-title a")!!.text()
                thumbnail_url = element.selectFirst("figure.bg img")!!.attr("abs:src").replace("/fondos/", "/portadas/")
            }
        }
        return AnimesPage(animes, false)
    }

    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("section.hentai-list div.hentais article.hentai")
        val animes = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("a").attr("abs:href"))
                title = element.selectFirst("h2.h-title")!!.text()
                thumbnail_url = element.selectFirst("figure img")!!.attr("abs:src")
            }
        }
        return AnimesPage(animes, false)
    }

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

        val hasNextPage = document.select("a.btn.rnd.npd.fa-arrow-right").isNullOrEmpty().not()

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

            if (nameServer.lowercase() == "arc") {
                val videoUrl = urlServer.substringAfter("#")
                videoList.add(Video(videoUrl, "Arc", videoUrl))
            }

            if (nameServer.lowercase() == "yupi") {
                videoList.addAll(YourUploadExtractor(client).videoFromUrl(urlServer, headers = headers))
            }

            if (nameServer.lowercase() == "mp4upload") {
                videoList.addAll(Mp4uploadExtractor(client).videosFromUrl(urlServer, headers = headers))
            }

            if (nameServer.lowercase() == "stream") {
                videoList.addAll(StreamSBExtractor(client).videosFromUrl(urlServer, headers = headers))
            }

            if (nameServer.lowercase() == "burst") {
                videoList.addAll(BurstCloudExtractor(client).videoFromUrl(urlServer, headers = headers))
            }
        }

        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) },
            ).toMutableList()
            val userPreferredQuality = preferences.getString("preferred_quality", "YourUpload")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                val temp = videoSorted[preferredIdx]
                videoSorted.removeAt(preferredIdx)
                videoSorted.add(0, temp)
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
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
        val qualities = arrayOf(
            "YourUpload",
            "BurstCloud",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("YourUpload")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }
}
