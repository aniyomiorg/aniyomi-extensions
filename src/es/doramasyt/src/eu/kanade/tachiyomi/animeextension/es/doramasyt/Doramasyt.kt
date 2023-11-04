package eu.kanade.tachiyomi.animeextension.es.doramasyt

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.doramasyt.extractors.SolidFilesExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class Doramasyt : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "DoramasYT"

    override val baseUrl = "https://doramasyt.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "FileLions"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "Okru",
            "Amazon", "AmazonES", "Fireload", "FileLions",
            "Uqload", "Streamlare", "StreamHideVid",
        )
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/doramas/?p=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val url = response.request.url.toString()
        val document = response.asJsoup()
        val elements = document.select("div.col-lg-2.col-md-4.col-6 div.animes")
        val nextPage = document.select("ul.pagination li:last-child a").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(
                    when {
                        url.contains("emision") -> element.select("a").attr("abs:href")
                        else -> element.select("div.anithumb a").attr("abs:href")
                    },
                )
                title = element.select("div.animedtls p").text()
                description = element.select("div.animedtls p").text()
                thumbnail_url = when {
                    url.contains("emision") -> element.select("a > img").attr("abs:src")
                    else -> element.select(".anithumb a img").attr("abs:src")
                }
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/emision?p=$page", headers)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/buscar?q=$query&p=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/doramas?categoria=false&genero=${genreFilter.toUriPart()}&fecha=false&letra=false&p=$page")
            else -> GET("$baseUrl/doramas/?p=$page")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("div.col-lg-2.col-md-4.col-6 div.animes")
        val nextPage = document.select("ul.pagination li:last-child a").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
                title = element.select("div.animedtls p").text()
                thumbnail_url = element.select("a img").attr("src")
                description = element.select("div.animedtls p").text()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        val sub = document.selectFirst(".herohead > .heroheadmain > h2")?.text()?.trim() ?: ""
        val title = document.selectFirst("div.herohead div.heroheadmain h1")?.text()?.trim() ?: ""
        anime.title = title + if (sub.isNotEmpty()) " ($sub)" else ""
        anime.description = document.selectFirst("div.herohead div.heroheadmain div.flimdtls p.textComplete")!!.ownText()
        anime.genre = document.select("div.herohead div.heroheadmain div.writersdiv div.nobel h6 a").joinToString { it.text() }
        anime.status = parseStatus(document.select("div.herohead div.heroheadmain div.writersdiv div.state h6").text())
        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("div.mainrowdiv.pagesdiv div.jpage div.col-item").map { element ->
            val epNum = element.select("a div.flimss div.dtlsflim p").text().filter { it.isDigit() }
            val formatedEp = when {
                epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
                else -> 1F
            }
            SEpisode.create().apply {
                setUrlWithoutDomain(element.select("a").attr("abs:href"))
                episode_number = formatedEp
                name = "Episodio $formatedEp"
            }
        }.reversed()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("div.playermain ul.dropcaps li#play-video a.cap").forEach { players ->
            val urlEncoded = players.attr("data-player")
            val byte = android.util.Base64.decode(urlEncoded, android.util.Base64.DEFAULT)
            val url = String(byte, charset("UTF-8")).substringAfter("?url=")
            serverVideoResolver(url).also(videoList::addAll)
        }
        return videoList
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videoFromUrl(url, prefix = "Voe:")?.let { videoList.add(it) }
            }
            if ((embedUrl.contains("amazon") || embedUrl.contains("amz")) && !embedUrl.contains("disable")) {
                val video = amazonExtractor(baseUrl + url.substringAfter(".."))
                if (video.isNotBlank()) {
                    if (url.contains("&ext=es")) {
                        videoList.add(Video(video, "AmazonES", video))
                    } else {
                        videoList.add(Video(video, "Amazon", video))
                    }
                }
            }
            if (embedUrl.contains("ok.ru") || embedUrl.contains("okru")) {
                runCatching {
                    OkruExtractor(client).videosFromUrl(url).also(videoList::addAll)
                }
            }
            if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = vidHeaders).also(videoList::addAll)
            }
            if (embedUrl.contains("uqload") || embedUrl.contains("upload")) {
                UqloadExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("mp4upload")) {
                Mp4uploadExtractor(client).videosFromUrl(url, headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("wishembed") || embedUrl.contains("embedwish") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish")) {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
                DoodExtractor(client).videoFromUrl(url, "DoodStream", false)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("streamlare")) {
                StreamlareExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("yourupload") || embedUrl.contains("upload")) {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("burstcloud") || embedUrl.contains("burst")) {
                BurstCloudExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("fastream")) {
                FastreamExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("upstream")) {
                UpstreamExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape")) {
                StreamTapeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("ahvsh") || embedUrl.contains("streamhide")) {
                StreamHideVidExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("solid")) {
                val videos = SolidFilesExtractor(client).videosFromUrl(url)
                videoList.addAll(videos)
            }
        } catch (_: Exception) { }
        return videoList
    }

    private fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)")!!.data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")

        return try {
            if (client.newCall(GET(videoURl)).execute().code == 200) videoURl else ""
        } catch (e: Exception) {
            ""
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

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Estreno") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<selecionar>", "false"),
            Pair("Acción", "accion"),
            Pair("Amistad", "amistad"),
            Pair("Artes marciales", "artes-marciales"),
            Pair("Aventuras", "aventuras"),
            Pair("Bélico", "belico"),
            Pair("C-Drama", "c-drama"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Comida", "comida"),
            Pair("Crimen ", "crimen"),
            Pair("Deporte", "deporte"),
            Pair("Documental", "documental"),
            Pair("Drama", "drama"),
            Pair("Escolar", "escolar"),
            Pair("Familiar", "familiar"),
            Pair("Fantasia", "fantasia"),
            Pair("Histórico", "historico"),
            Pair("HK-Drama", "hk-drama"),
            Pair("Horror", "horror"),
            Pair("Idols", "idols"),
            Pair("J-Drama", "j-drama"),
            Pair("Juvenil", "juvenil"),
            Pair("K-Drama", "k-drama"),
            Pair("Legal", "legal"),
            Pair("Médico", "medico"),
            Pair("Melodrama", "melodrama"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Musical", "musical"),
            Pair("Negocios", "negocios"),
            Pair("Policial", "policial"),
            Pair("Política", "politica"),
            Pair("Psicológico", "psicologico"),
            Pair("Reality Show", "reality-show"),
            Pair("Recuentos de la vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Supervivencia", "supervivencia"),
            Pair("Suspenso", "suspenso"),
            Pair("Thai-Drama", "thai-drama"),
            Pair("Thriller", "thriller"),
            Pair("Time Travel", "time-travel"),
            Pair("TW-Drama", "tw-drama"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
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
    }
}
