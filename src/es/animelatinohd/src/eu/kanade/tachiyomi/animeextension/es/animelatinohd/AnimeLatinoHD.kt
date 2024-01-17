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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AnimeLatinoHD : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeLatinoHD"

    override val baseUrl = "https://www.animelatinohd.com"

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        const val PREF_QUALITY_KEY = "preferred_quality"
        const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "FileLions"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "FileLions", "StreamHideVid", "SolidFiles", "Od.lk", "CldUp",
        )

        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]")
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes/populares")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = mutableListOf<SAnime>()
        val url = response.request.url.toString().lowercase()
        val hasNextPage = document.select("#__next > main > div > div[class*=\"Animes_paginate\"] a:last-child svg").any()
        document.select("script").forEach { script ->
            if (script.data().contains("{\"props\":{\"pageProps\":")) {
                val jObject = json.decodeFromString<JsonObject>(script.data())
                val props = jObject["props"]!!.jsonObject
                val pageProps = props["pageProps"]!!.jsonObject
                val data = pageProps["data"]!!.jsonObject
                if (url.contains("status=1")) {
                    val latestData = data["data"]!!.jsonArray
                    latestData.forEach { item ->
                        val animeItem = item!!.jsonObject
                        val anime = SAnime.create()
                        anime.setUrlWithoutDomain(externalOrInternalImg("anime/${animeItem["slug"]!!.jsonPrimitive!!.content}"))
                        anime.thumbnail_url = "https://image.tmdb.org/t/p/w200${animeItem["poster"]!!.jsonPrimitive!!.content}"
                        anime.title = animeItem["name"]!!.jsonPrimitive!!.content
                        animeList.add(anime)
                    }
                } else {
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
        }
        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?page=$page&status=1")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

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

    private fun parseJsonArray(json: JsonElement?): List<JsonElement> {
        val list = mutableListOf<JsonElement>()
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
                                headers = headers.newBuilder()
                                    .add("Referer", "https://www.animelatinohd.com/")
                                    .add("authority", "api.animelatinohd.com")
                                    .add("upgrade-insecure-requests", "1")
                                    .build(),
                            ),
                        ).execute()
                        val locationsDdh = request!!.networkResponse.toString()
                        fetchUrls(locationsDdh).map { url ->
                            val language = if (item["languaje"]!!.jsonPrimitive!!.content == "1") "[LAT]" else "[SUB]"
                            val embedUrl = url.lowercase()
                            if (embedUrl.contains("filemoon")) {
                                val vidHeaders = headers.newBuilder()
                                    .add("Origin", "https://${url.toHttpUrl().host}")
                                    .add("Referer", "https://${url.toHttpUrl().host}/")
                                    .build()
                                FilemoonExtractor(client).videosFromUrl(url, prefix = "$language Filemoon:", headers = vidHeaders).also(videoList::addAll)
                            }
                            if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
                                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$language FileLions:$it" }).also(videoList::addAll)
                            }
                            if (embedUrl.contains("streamtape")) {
                                StreamTapeExtractor(client).videoFromUrl(url, "$language Streamtape")?.let { videoList.add(it) }
                            }
                            if (embedUrl.contains("dood")) {
                                DoodExtractor(client).videoFromUrl(url, "$language DoodStream")?.let { videoList.add(it) }
                            }
                            if (embedUrl.contains("okru") || embedUrl.contains("ok.ru")) {
                                OkruExtractor(client).videosFromUrl(url, language).also(videoList::addAll)
                            }
                            if (embedUrl.contains("solidfiles")) {
                                SolidFilesExtractor(client).videosFromUrl(url, language).also(videoList::addAll)
                            }
                            if (embedUrl.contains("od.lk")) {
                                videoList.add(Video(url, language + "Od.lk", url))
                            }
                            if (embedUrl.contains("cldup.com")) {
                                videoList.add(Video(url, language + "CldUp", url))
                            }
                        }
                    }
                }
            }
        }
        return videoList
    }

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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val stateFilter = filterList.find { it is StateFilter } as StateFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter

        val filterUrl = if (query.isBlank()) {
            "$baseUrl/animes?page=$page&genre=${genreFilter.toUriPart()}&status=${stateFilter.toUriPart()}&type=${typeFilter.toUriPart()}"
        } else {
            "$baseUrl/animes?page=$page&search=$query"
        }

        return GET(filterUrl)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora los filtros"),
        GenreFilter(),
        StateFilter(),
        TypeFilter(),
    )

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
            Pair("Yuri", "yuri"),
        ),
    )

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("Todos", ""),
            Pair("Finalizado", "0"),
            Pair("En emisión", "1"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("Todos", ""),
            Pair("Animes", "tv"),
            Pair("Películas", "movie"),
            Pair("Especiales", "special"),
            Pair("OVAS", "ova"),
            Pair("ONAS", "ona"),
        ),
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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
