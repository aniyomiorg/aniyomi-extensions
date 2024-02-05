package eu.kanade.tachiyomi.animeextension.es.animemovil

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
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.UnsupportedEncodingException
import java.net.URLEncoder

class AnimeMovil : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeMovil"

    override val baseUrl = "https://animemeow.xyz"

    override val lang = "es"

    private val json: Json by injectLazy()

    override val supportsLatest = false

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
            "PlusTube", "PlusVid", "PlusIm", "PlusWish", "PlusHub", "PlusDex",
            "YourUpload", "Voe", "StreamWish", "Mp4Upload", "Doodstream",
            "Uqload", "BurstCloud", "Upstream", "StreamTape", "PlusFilm",
            "Fastream", "FileLions",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/directorio/?p=$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".grid-animes article")
        val nextPage = document.select(".pagination .right:not(.disabledd) .page-link").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst("a")?.attr("abs:href") ?: "")
                title = element.selectFirst("a > p")?.text() ?: ""
                thumbnail_url = element.selectFirst("a .main-img img")?.attr("abs:src") ?: ""
                status = when (element.select("a .figure-title > p").text().trim()) {
                    "Finalizado" -> SAnime.COMPLETED
                    "En emision" -> SAnime.ONGOING
                    else -> SAnime.UNKNOWN
                }
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val statusFilter = filterList.find { it is StatusFilter } as StatusFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val languageFilter = filterList.find { it is LanguageFilter } as LanguageFilter

        val params = HashMap<String, String>()
        if (genreFilter.state != 0) {
            params["genero"] = genreFilter.toUriPart()
        }
        if (statusFilter.state != 0) {
            params["estado"] = statusFilter.toUriPart()
        }
        if (typeFilter.state != 0) {
            params["tipo"] = typeFilter.toUriPart()
        }
        if (languageFilter.state != 0) {
            params["idioma"] = languageFilter.toUriPart()
        }
        params["p"] = "$page"

        return when {
            query.isNotBlank() -> GET("$baseUrl/directorio/?p=$page&q=$query", headers)
            else -> GET("$baseUrl/directorio/?${urlEncodeUTF8(params)}", headers)
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst(".banner-info div.titles h1")?.text() ?: ""
            description = document.select("#sinopsis").text()
            thumbnail_url = document.selectFirst("#anime_image")?.attr("abs:src")
            genre = document.select(".generos-wrap .item").joinToString { it.text() }
            status = when (document.select(".banner-img .estado").text().trim()) {
                "Finalizado" -> SAnime.COMPLETED
                "En emision" -> SAnime.ONGOING
                else -> SAnime.UNKNOWN
            }
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        val seasons = document.select(".temporadas-lista .btn-temporada")
        if (seasons.any()) {
            val token = try {
                response.headers.first { it.first == "set-cookie" && it.second.startsWith("csrftoken") }
                    .second.substringAfter("=").substringBefore(";").replace("%3D", "=")
            } catch (_: Exception) { "" }
            seasons.reversed().map {
                val sid = it.attr("data-sid")
                val t = it.attr("data-t")

                val mediaType = "application/json".toMediaType()
                val requestBody = "{\"show\": \"$sid\",\"temporada\": \"$t\"}"
                val request = Request.Builder()
                    .url("https://animemeow.xyz/api/obtener_episodios_x_temporada/")
                    .post(requestBody.toRequestBody(mediaType))
                    .header("authority", response.request.url.host)
                    .header("origin", "https://${response.request.url.host}")
                    .header("referer", response.request.url.toString())
                    .header("x-csrftoken", token)
                    .header("x-requested-with", "XMLHttpRequest")
                    .header("cookie", "csrftoken=$token")
                    .header("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().asJsoup().let {
                    json.decodeFromString<EpisodesDto>(it.body().text()).episodios.forEachIndexed { idx, it ->
                        val episode = SEpisode.create().apply {
                            setUrlWithoutDomain(it.url)
                            name = "T$t - " + it.epNombre.replace("Ver", "").trim()
                            episode_number = idx.toFloat()
                        }
                        episodes.add(episode)
                    }
                }
            }
        } else {
            document.select("#eps li > a").reversed().forEachIndexed { idx, it ->
                val nameEp = it.selectFirst("p")?.ownText() ?: ""
                val episode = SEpisode.create().apply {
                    setUrlWithoutDomain(it.attr("abs:href"))
                    name = nameEp.replace("Ver", "").trim()
                    episode_number = idx.toFloat()
                }
                episodes.add(episode)
            }
        }
        return episodes
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(http|ftp|https):\\/\\/([\\w_-]+(?:(?:\\.[\\w_-]+)+))([\\w.,@?^=%&:\\/~+#-]*[\\w@?^=%&\\/~+#-])".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("#fuentes button").forEach {
            try {
                val url = it.attr("data-url").substringAfter("redirect.php?id=").trim()
                if (url.contains("php?id=")) {
                    val serverName = it.ownText().trim()
                    val serverDocument = client.newCall(GET(url)).execute().asJsoup()
                    val fileData = serverDocument.selectFirst("script:containsData(sources: [{file:)")?.data() ?: ""
                    val genericFiles = fetchUrls(fileData)
                    if (genericFiles.any()) {
                        genericFiles.forEach { fileSrc ->
                            if (fileSrc.contains(".m3u8")) {
                                videoList.add(Video(fileSrc, "$serverName:HLS", fileSrc, headers = null))
                            }
                            if (fileSrc.contains(".mp4")) {
                                videoList.add(Video(fileSrc, "$serverName:MP4", fileSrc, headers = null))
                            }
                        }
                    } else {
                        serverVideoResolver(url).let { videoList.addAll(it) }
                    }
                } else {
                    serverVideoResolver(url).let { videoList.addAll(it) }
                }
            } catch (_: Exception) {}
        }
        return videoList
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
                FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:").also(videoList::addAll)
            }
            if (embedUrl.contains("uqload")) {
                UqloadExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("mp4upload")) {
                val newHeaders = headers.newBuilder().add("referer", "https://re.animepelix.net/").build()
                Mp4uploadExtractor(client).videosFromUrl(url, newHeaders).also(videoList::addAll)
            }
            if (embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("wish")) {
                val docHeaders = headers.newBuilder()
                    .add("Referer", "$baseUrl/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
                DoodExtractor(client).videoFromUrl(url, "DoodStream", false)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("streamlare")) {
                StreamlareExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("yourupload")) {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers).also(videoList::addAll)
            }
            if (embedUrl.contains("burstcloud") || embedUrl.contains("burst")) {
                BurstCloudExtractor(client).videoFromUrl(url, headers = headers).also(videoList::addAll)
            }
            if (embedUrl.contains("fastream")) {
                FastreamExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("upstream")) {
                UpstreamExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("streamtape")) {
                StreamTapeExtractor(client).videoFromUrl(url)?.also(videoList::add)
            }
            if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" }).also(videoList::addAll)
            }
        } catch (_: Exception) {}
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
        TypeFilter(),
        StatusFilter(),
        LanguageFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Acción", "1"),
            Pair("Escolares", "2"),
            Pair("Romance", "3"),
            Pair("Shoujo", "4"),
            Pair("Comedia", "5"),
            Pair("Drama", "6"),
            Pair("Seinen", "7"),
            Pair("Deportes", "8"),
            Pair("Shounen", "9"),
            Pair("Recuentos de la vida", "10"),
            Pair("Ecchi", "11"),
            Pair("Sobrenatural", "12"),
            Pair("Fantasía", "13"),
            Pair("Magia", "14"),
            Pair("Superpoderes", "15"),
            Pair("Demencia", "16"),
            Pair("Misterio", "17"),
            Pair("Psicológico", "18"),
            Pair("Suspenso", "19"),
            Pair("Ciencia Ficción", "20"),
            Pair("Mecha", "21"),
            Pair("Militar", "22"),
            Pair("Aventuras", "23"),
            Pair("Historico", "24"),
            Pair("Infantil", "25"),
            Pair("Artes Marciales", "26"),
            Pair("Terror", "27"),
            Pair("Harem", "28"),
            Pair("Josei", "29"),
            Pair("Parodia", "30"),
            Pair("Policía", "31"),
            Pair("Juegos", "32"),
            Pair("Carreras", "33"),
            Pair("Samurai", "34"),
            Pair("Espacial", "35"),
            Pair("Música", "36"),
            Pair("Yuri", "37"),
            Pair("Demonios", "38"),
            Pair("Vampiros", "39"),
            Pair("Yaoi", "40"),
            Pair("Humor Negro", "41"),
            Pair("Crimen", "42"),
            Pair("Hentai", "43"),
            Pair("Youtuber", "44"),
            Pair("MaiNess Random", "45"),
            Pair("Donghua", "46"),
            Pair("Horror", "47"),
            Pair("Sin Censura", "48"),
            Pair("Gore", "49"),
            Pair("Live Action", "50"),
            Pair("Isekai", "51"),
            Pair("Gourmet", "52"),
            Pair("spokon", "53"),
            Pair("Zombies", "54"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Tipos",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("TV", "1"),
            Pair("Película", "2"),
            Pair("OVA", "3"),
            Pair("Especial", "4"),
            Pair("Serie", "9"),
            Pair("Dorama", "11"),
            Pair("Corto", "14"),
            Pair("Donghua", "15"),
            Pair("ONA", "16"),
            Pair("Live Action", "17"),
            Pair("Manhwa", "18"),
            Pair("Teatral", "19"),
        ),
    )

    private class StatusFilter : UriPartFilter(
        "Estados",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Finalizado", "1"),
            Pair("En emision", "2"),
            Pair("Proximamente", "3"),
        ),
    )

    private class LanguageFilter : UriPartFilter(
        "Idioma",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Japonés", "1"),
            Pair("Latino", "2"),
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

    @Serializable
    data class EpisodesDto(
        val autenticado: Boolean,
        val episodios: List<Episodio>,
    )

    @Serializable
    data class Episodio(
        val id: Long,
        @SerialName("ep_nombre")
        val epNombre: String,
        val url: String,
    )

    private fun urlEncodeUTF8(s: String?): String? {
        return try {
            URLEncoder.encode(s, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            throw UnsupportedOperationException()
        }
    }

    private fun urlEncodeUTF8(map: Map<*, *>): String? {
        val sb = StringBuilder()
        for ((key, value) in map) {
            if (sb.isNotEmpty()) {
                sb.append("&")
            }
            sb.append(String.format("%s=%s", urlEncodeUTF8(key.toString()), urlEncodeUTF8(value.toString())))
        }
        return sb.toString()
    }
}
