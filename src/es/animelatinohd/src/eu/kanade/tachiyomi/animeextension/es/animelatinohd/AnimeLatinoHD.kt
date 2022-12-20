package eu.kanade.tachiyomi.animeextension.es.animelatinohd

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animelatinohd.extractors.SolidFilesExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeLatinoHD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeLatinoHD"

    override val baseUrl = "https://www.animelatinohd.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "#__next main[class*='Animes_container'] div[class*='ListAnimes_box'] div[class*='ListAnimes'] div[class*='AnimeCard_anime']"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/populares")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select("#__next > main > div > div[class*=\"Animes_paginate\"] a:last-child svg").any()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val data = pageProps["data"]!!.jsonObject
                val popularToday = data["popular_today"]!!.jsonArray
                popularToday.forEach { item ->
                    val animeItem = item!!.jsonObject
                    val anime = SAnime.create()
                    anime.setUrlWithoutDomain(externalOrInternalImg("anime/${animeItem["slug"]!!.jsonPrimitive!!.content}"))
                    anime.thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]!!.jsonPrimitive!!.content}"
                    anime.title = animeItem["name"]!!.jsonPrimitive!!.content
                    animeList.add(anime)
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")

    override fun popularAnimeNextPageSelector(): String = "uwu"

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val newAnime = SAnime.create()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val data = pageProps["data"]!!.jsonObject

                newAnime.title = data["name"]!!.jsonPrimitive!!.content
                newAnime.genre = data["genres"]!!.jsonPrimitive!!.content.split(",").joinToString()
                newAnime.description = data["overview"]!!.jsonPrimitive!!.content
                newAnime.status = parseStatus(data["status"]!!.jsonPrimitive!!.content)
                newAnime.thumbnail_url = "https://image.tmdb.org/t/p/w600_and_h900_bestv2${data["poster"]!!.jsonPrimitive!!.content}"
                newAnime.setUrlWithoutDomain(externalOrInternalImg("anime/${data["slug"]!!.jsonPrimitive!!.content}"))
            }
        }
        return newAnime
    }

    override fun animeDetailsParse(document: Document) = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val data = pageProps["data"]!!.jsonObject
                val arrEpisode = data["episodes"]!!.jsonArray
                arrEpisode.forEach { item ->
                    val animeItem = item!!.jsonObject
                    val episode = SEpisode.create()
                    episode.setUrlWithoutDomain(externalOrInternalImg("ver/${data["slug"]!!.jsonPrimitive!!.content}/${animeItem["number"]!!.jsonPrimitive!!.content!!.toFloat()}"))
                    episode.episode_number = animeItem["number"]!!.jsonPrimitive!!.content!!.toFloat()
                    episode.name = "Episodio ${animeItem["number"]!!.jsonPrimitive!!.content!!.toFloat()}"
                    episodeList.add(episode)
                }
            }
        }
        return episodeList
    }

    override fun episodeListSelector() = "uwu"

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    private fun parseJsonArray(json: JsonElement?): List<JsonElement> {
        var list = mutableListOf<JsonElement>()
        json!!.jsonObject!!.entries!!.forEach { list.add(it.value) }
        return list
    }

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val data = pageProps["data"]!!.jsonObject
                val playersElement = data["players"]
                val players = if (playersElement !is JsonArray) JsonArray(parseJsonArray(playersElement)) else playersElement!!.jsonArray
                players.forEach { player ->
                    val servers = player!!.jsonArray
                    servers.forEach { server ->
                        val item = server!!.jsonObject
                        val request = client.newCall(
                            GET(
                                url = "https://api.animelatinohd.com/stream/${item["id"]!!.jsonPrimitive.content}",
                                headers = headers.newBuilder().add("Referer", "https://www.animelatinohd.com/").build()
                            )
                        ).execute()
                        val locationsDdh = request!!.networkResponse.toString()
                        fetchUrls(locationsDdh).map { url ->
                            val language = if (item["languaje"]!!.jsonPrimitive!!.content == "1") "[Lat] " else "[Sub] "
                            val embedUrl = url.lowercase()
                            if (embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
                                embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
                                embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
                                embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
                                embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
                                embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
                                embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
                                embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
                                embedUrl.contains("sblanh.com")
                            ) {
                                val videos = StreamSBExtractor(client).videosFromUrl(url, headers, language)
                                videoList.addAll(videos)
                            }
                            if (embedUrl.contains("fembed") || embedUrl.contains("anime789.com") || embedUrl.contains("24hd.club") ||
                                embedUrl.contains("fembad.org") || embedUrl.contains("vcdn.io") || embedUrl.contains("sharinglink.club") ||
                                embedUrl.contains("moviemaniac.org") || embedUrl.contains("votrefiles.club") || embedUrl.contains("femoload.xyz") ||
                                embedUrl.contains("albavido.xyz") || embedUrl.contains("feurl.com") || embedUrl.contains("dailyplanet.pw") ||
                                embedUrl.contains("ncdnstm.com") || embedUrl.contains("jplayer.net") || embedUrl.contains("xstreamcdn.com") ||
                                embedUrl.contains("fembed-hd.com") || embedUrl.contains("gcloud.live") || embedUrl.contains("vcdnplay.com") ||
                                embedUrl.contains("superplayxyz.club") || embedUrl.contains("vidohd.com") || embedUrl.contains("vidsource.me") ||
                                embedUrl.contains("cinegrabber.com") || embedUrl.contains("votrefile.xyz") || embedUrl.contains("zidiplay.com") ||
                                embedUrl.contains("ndrama.xyz") || embedUrl.contains("fcdn.stream") || embedUrl.contains("mediashore.org") ||
                                embedUrl.contains("suzihaza.com") || embedUrl.contains("there.to") || embedUrl.contains("femax20.com") ||
                                embedUrl.contains("javstream.top") || embedUrl.contains("viplayer.cc") || embedUrl.contains("sexhd.co") ||
                                embedUrl.contains("fembed.net") || embedUrl.contains("mrdhan.com") || embedUrl.contains("votrefilms.xyz") ||
                                embedUrl.contains("embedsito.com") || embedUrl.contains("dutrag.com") || embedUrl.contains("youvideos.ru") ||
                                embedUrl.contains("streamm4u.club") || embedUrl.contains("moviepl.xyz") || embedUrl.contains("asianclub.tv") ||
                                embedUrl.contains("vidcloud.fun") || embedUrl.contains("fplayer.info") || embedUrl.contains("diasfem.com") ||
                                embedUrl.contains("javpoll.com") || embedUrl.contains("reeoov.tube") || embedUrl.contains("suzihaza.com") ||
                                embedUrl.contains("ezsubz.com") || embedUrl.contains("vidsrc.xyz") || embedUrl.contains("diampokusy.com") ||
                                embedUrl.contains("diampokusy.com") || embedUrl.contains("i18n.pw") || embedUrl.contains("vanfem.com") ||
                                embedUrl.contains("fembed9hd.com") || embedUrl.contains("votrefilms.xyz") || embedUrl.contains("watchjavnow.xyz")
                            ) {
                                val videos = FembedExtractor(client).videosFromUrl(url, language)
                                videoList.addAll(videos)
                            }
                            if (url.lowercase().contains("streamtape")) {
                                val video = StreamTapeExtractor(client).videoFromUrl(url, language + "Streamtape")
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }
                            if (url.lowercase().contains("dood")) {
                                val video = try {
                                    DoodExtractor(client).videoFromUrl(url, language + "DoodStream")
                                } catch (e: Exception) {
                                    null
                                }
                                if (video != null) {
                                    videoList.add(video)
                                }
                            }
                            if (url.lowercase().contains("okru")) {
                                val videos = OkruExtractor(client).videosFromUrl(url, language)
                                videoList.addAll(videos)
                            }
                            if (url.lowercase().contains("www.solidfiles.com")) {
                                val videos = SolidFilesExtractor(client).videosFromUrl(url, language)
                                videoList.addAll(videos)
                            }
                            if (url.lowercase().contains("od.lk")) {
                                videoList.add(Video(url, language + "Od.lk", url))
                            }
                            if (url.lowercase().contains("cldup.com")) {
                                videoList.add(Video(url, language + "CldUp", url))
                            }
                        }
                    }
                }
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "[Sub] Fembed:720p")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?page=$page&search=$query")
            genreFilter.state != 0 -> GET("$baseUrl/animes?page=$page&genre=${genreFilter.toUriPart()}")
            else -> GET("$baseUrl/animes?page=$page")
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val hasNextPage = document.select("#__next > main > div > div[class*=\"Animes_paginate\"] a:last-child svg").any()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val data = pageProps["data"]!!.jsonObject
                val arrData = data["data"]!!.jsonArray
                arrData.forEach { item ->
                    val animeItem = item!!.jsonObject
                    val anime = SAnime.create()
                    anime.setUrlWithoutDomain(externalOrInternalImg("anime/${animeItem["slug"]!!.jsonPrimitive!!.content}"))
                    anime.thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]!!.jsonPrimitive!!.content}"
                    anime.title = animeItem["name"]!!.jsonPrimitive!!.content
                    animeList.add(anime)
                }
            }
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<Selecionar>", ""),
            Pair("Acción", "accion"),
            Pair("Aliens", "aliens"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Aventura", "aventura"),
            Pair("Ciencia Ficción", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Detectives", "detectives"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolar", "escolar"),
            Pair("Espacio", "espacio"),
            Pair("Fantasía", "fantasia"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Histórico", "historico"),
            Pair("Horror", "horror"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Kodomo", "kodomo"),
            Pair("Magia", "magia"),
            Pair("Maho Shoujo", "maho-shoujo"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Musica", "musica"),
            Pair("Parodia", "parodia"),
            Pair("Policial", "policial"),
            Pair("Psicológico", "psicologico"),
            Pair("Recuentos De La Vida", "recuentos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurais", "samurais"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shoujo Ai", "shoujo-ai"),
            Pair("Shounen", "shounen"),
            Pair("Shounen Ai", "shounen-ai"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Soft Hentai", "soft-hentai"),
            Pair("Super Poderes", "super-poderes"),
            Pair("Suspenso", "suspenso"),
            Pair("Terror", "terror"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun externalOrInternalImg(url: String) = if (url.contains("https")) url else "$baseUrl/$url"

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("1") -> SAnime.ONGOING
            statusString.contains("0") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?page=$page&status=1")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val options = arrayOf(
            "[Sub] Fembed:1080p", "[Sub] Fembed:720p", "[Sub] Fembed:480p", "[Sub] Fembed:360p", "[Sub] Fembed:240p", // Fembed [Sub]
            "[Lat] Fembed:1080p", "[Lat] Fembed:720p", "[Lat] Fembed:480p", "[Lat] Fembed:360p", "[Lat] Fembed:240p", // Fembed [Lat]
            "[Sub] Okru:1080p", "[Sub] Okru:720p", "[Sub] Okru:480p", "[Sub] Okru:360p", "[Sub] Okru:240p", // Okru [Sub]
            "[Lat] Okru:1080p", "[Lat] Okru:720p", "[Lat] Okru:480p", "[Lat] Okru:360p", "[Lat] Okru:240p", // Okru [Lat]
            "[Sub] StreamSB:1080p", "[Sub] StreamSB:720p", "[Sub] StreamSB:480p", "[Sub] StreamSB:360p", "[Sub] StreamSB:240p", // StreamSB [Sub]
            "[Lat] StreamSB:1080p", "[Lat] StreamSB:720p", "[Lat] StreamSB:480p", "[Lat] StreamSB:360p", "[Lat] StreamSB:240p", // StreamSB [Lat]
            "[Sub] StreamTape", "[Lat] StreamTape", // video servers without resolution
            "[Sub] DoodStream", "[Lat] DoodStream", // video servers without resolution
            "[Sub] SolidFiles", "[Lat] SolidFiles", // video servers without resolution
            "[Sub] Od.lk", "[Lat] Od.lk", // video servers without resolution
            "[Sub] CldUp", "[Lat] CldUp"
        ) // video servers without resolution
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = options
            entryValues = options
            setDefaultValue("[Sub] Fembed:720p")
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
