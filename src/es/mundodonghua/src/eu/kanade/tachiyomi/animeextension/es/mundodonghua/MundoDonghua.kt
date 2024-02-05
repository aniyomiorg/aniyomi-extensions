package eu.kanade.tachiyomi.animeextension.es.mundodonghua

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.mundodonghua.extractors.JsUnpacker
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.dailymotionextractor.DailymotionExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
class MundoDonghua : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "MundoDonghua"

    override val baseUrl = "https://www.mundodonghua.com"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector() = "div > div.row > div.item > a"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/lista-donghuas/$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        title = element.selectFirst("h5")!!.text().removeSurrounding("\"")
        thumbnail_url = element.selectFirst("img")?.attr("abs:src")
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination li:last-child a"

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element).apply {
            url = url.replace("/ver/", "/donghua/").substringBeforeLast("/")
        }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/lista-episodios/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = baseUrl + document.selectFirst("div.col-md-4.col-xs-12.mb-10 div.row.sm-row > div.side-banner > div.banner-side-serie")!!
            .attr("style").substringAfter("background-image: url(").substringBefore(")")
        anime.title = document.selectFirst("div.col-md-4.col-xs-12.mb-10 div.row.sm-row div div.sf.fc-dark.ls-title-serie")!!.html()
        anime.description = document.selectFirst("section div.row div.col-md-8 div.sm-row p.text-justify")!!.text().removeSurrounding("\"")
        anime.genre = document.select("div.col-md-8.col-xs-12 div.sm-row a.generos span.label").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.col-md-4.col-xs-12.mb-10 div.row.sm-row div:nth-child(2) div:nth-child(2) p span.badge").text())
        return anime
    }

    override fun episodeListSelector() = "div.sm-row.mt-10 div.donghua-list-scroll ul.donghua-list a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("href").split("/").last().toFloat()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.episode_number = epNum
        episode.name = "Episodio $epNum"
        return episode
    }

    private fun getAndUnpack(string: String): Sequence<String> {
        return JsUnpacker.unpack(string)
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("eval(function(p,a,c,k,e")) {
                val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,.*\\)\\)")
                packedRegex.findAll(script.data()).map {
                    it.value
                }.toList().map {
                    val unpack = getAndUnpack(it).first()
                    if (unpack.contains("amagi_tab")) {
                        fetchUrls(unpack).map { url ->
                            try {
                                VoeExtractor(client).videosFromUrl(url).also(videoList::addAll)
                            } catch (_: Exception) {}
                        }
                    }
                    if (unpack.contains("fmoon_tab")) {
                        fetchUrls(unpack).map { url ->
                            try {
                                val newHeaders = headers.newBuilder()
                                    .add("authority", url.toHttpUrl().host)
                                    .add("referer", "$baseUrl/")
                                    .add("Origin", "https://${url.toHttpUrl().host}")
                                    .build()
                                FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = newHeaders).also(videoList::addAll)
                            } catch (_: Exception) {}
                        }
                    }
                    if (unpack.contains("protea_tab")) {
                        try {
                            val slug = unpack.substringAfter("\"slug\":\"").substringBefore("\"")

                            val newHeaders = headers.newBuilder()
                                .add("referer", "${response.request.url}")
                                .add("authority", baseUrl.substringAfter("//"))
                                .add("accept", "*/*")
                                .build()

                            val slugPlayer = client.newCall(GET("$baseUrl/api_donghua.php?slug=$slug", headers = newHeaders)).execute().asJsoup().body().toString().substringAfter("\"url\":\"").substringBefore("\"")

                            val videoHeaders = headers.newBuilder()
                                .add("authority", "www.mdplayer.xyz")
                                .add("referer", "$baseUrl/")
                                .build()

                            val videoId = client.newCall(GET("https://www.mdplayer.xyz/nemonicplayer/dmplayer.php?key=$slugPlayer", headers = videoHeaders))
                                .execute().asJsoup().body().toString().substringAfter("video-id=\"").substringBefore("\"")

                            DailymotionExtractor(client, headers).videosFromUrl("https://www.dailymotion.com/embed/video/$videoId", prefix = "Dailymotion:").let { videoList.addAll(it) }
                        } catch (_: Exception) {}
                    }
                    if (unpack.contains("asura_tab")) {
                        fetchUrls(unpack).map { url ->
                            try {
                                if (url.contains("redirector")) {
                                    val newHeaders = headers.newBuilder()
                                        .add("authority", "www.mdnemonicplayer.xyz")
                                        .add("accept", "*/*")
                                        .add("origin", baseUrl)
                                        .add("referer", "$baseUrl/")
                                        .build()

                                    PlaylistUtils(client, newHeaders).extractFromHls(url, videoNameGen = { "Asura:$it" }).let { videoList.addAll(it) }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "VoeCDN")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/busquedas/$query")
            genreFilter.state != 0 -> GET("$baseUrl/genero/${genreFilter.toUriPart()}")
            else -> popularAnimeRequest(page)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Acción", "Acción"),
            Pair("Artes Marciales", "Artes Marciales"),
            Pair("Aventura", "Aventura"),
            Pair("Ciencia Ficción", "Ciencia Ficción"),
            Pair("Comedia", "Comedia"),
            Pair("Comida", "Comida"),
            Pair("Cultivación", "Cultivación"),
            Pair("Demonios", "Demonios"),
            Pair("Deportes", "Deportes"),
            Pair("Drama", "Drama"),
            Pair("Ecchi", "Ecchi"),
            Pair("Escolar", "Escolar"),
            Pair("Fantasía", "Fantasía"),
            Pair("Harem", "Harem"),
            Pair("Harem Inverso", "Harem Inverso"),
            Pair("Historico", "Historico"),
            Pair("Idols", "Idols"),
            Pair("Juegos", "Juegos"),
            Pair("Lucha", "Lucha"),
            Pair("Magia", "Magia"),
            Pair("Mechas", "Mechas"),
            Pair("Militar", "Militar"),
            Pair("Misterio", "Misterio"),
            Pair("Música", "Música"),
            Pair("Por Definir", "Por Definir"),
            Pair("Psicológico", "Psicológico"),
            Pair("Reencarnación", "Reencarnación"),
            Pair("Romance", "Romance"),
            Pair("Seinen", "Seinen"),
            Pair("Shojo", "Shojo"),
            Pair("Shonen", "Shonen"),
            Pair("Sobrenatural", "Sobrenatural"),
            Pair("Sucesos de la Vida", "Sucesos de la Vida"),
            Pair("Superpoderes", "Superpoderes"),
            Pair("Suspenso", "Suspenso"),
            Pair("Terror", "Terror"),
            Pair("Vampiros", "Vampiros"),
            Pair("Viaje a Otro Mundo", "Viaje a Otro Mundo"),
            Pair("Videojuegos", "Videojuegos"),
            Pair("Zombis", "Zombis"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("En Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizada") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "VoeCDN",
            "Dailymotion:1080p",
            "Dailymotion:720p",
            "Dailymotion:480p",
            "Filemoon:1080p",
            "Filemoon:720p",
            "Filemoon:480p",
            "Asura:1080p",
            "Asura:720p",
            "Asura:480p",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("VoeCDN")
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
