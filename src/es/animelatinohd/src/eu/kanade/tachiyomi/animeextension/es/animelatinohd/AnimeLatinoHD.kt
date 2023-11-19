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
import okhttp3.OkHttpClient
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

    override val client: OkHttpClient = network.cloudflareClient

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

        document.select("div.list-animes > div.animeCard").forEach { animeCard ->
            val cover = animeCard.selectFirst("div.cover")
            val titleElement = animeCard.selectFirst("div.info h3 a span")
            val yearElement = animeCard.selectFirst("div.info p.year")
            val scoreElement = animeCard.selectFirst("span.score")

            val anime = SAnime.create()
            anime.title = titleElement?.text().orEmpty()
            anime.setUrlWithoutDomain(cover?.selectFirst("a")?.attr("href").orEmpty())
            anime.thumbnail_url = cover?.selectFirst("img")?.attr("src").orEmpty()

            animeList.add(anime)
        }

        return AnimesPage(animeList, hasNextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?page=$page&status=1")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val newAnime = SAnime.create()

        document.select("div.animePage").firstOrNull()?.let { animePage ->
            // Extracción de información del banner
            val banner = animePage.selectFirst("div.banner")
            if (banner != null) {
                newAnime.thumbnail_url = banner.attr("style").substringAfter("background-image: url(").substringBeforeLast(")")
            }

            // Extracción de información de la columna
            val column = animePage.selectFirst("div.column")
            if (column != null) {
                newAnime.title = column.selectFirst("h1")?.text() ?: ""
            }
            if (column != null) {
                newAnime.genre = column.select("div.genres a.genre").joinToString(",") { it.text() }
            }

            // Extracción de información detallada
            val detailed = animePage.selectFirst("div.detailed")
            if (detailed != null) {
                newAnime.status = parseStatus(
                    detailed.selectFirst("div.item:contains(Estado) span")
                        ?.text() ?: "",
                )
            }
            newAnime.description = animePage.selectFirst("div.overview p")?.text()
        }

        return newAnime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        document.select("div.listEpisodes a.episodeContainer").forEach { episodeContainer ->
            val episodeNumber =
                episodeContainer.selectFirst("div.episodeInfoContainer span.episodeNumber")?.text()
            val episodeUrl = episodeContainer.attr("href")

            val episode = SEpisode.create()
            if (episodeNumber != null) {
                episode.episode_number = episodeNumber.toFloatOrNull() ?: 0f
            }
            episode.setUrlWithoutDomain(externalOrInternalImg(episodeUrl))
            episode.name = "Episodio $episodeNumber"

            episodeList.add(episode)
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

        // Buscar el script que contiene la información necesaria
        val scriptWithProps = document.selectFirst("script:containsData({\"props\":{\"pageProps\":)")
            ?: return videoList

        // Extraer el token del script
        val tokenMatch = Regex(""""_token":"(.*?)"""").find(scriptWithProps.data())
        val token = tokenMatch?.groups?.get(1)?.value

        if (token != null) {
            // Obtener información de los videos usando el token
            val playersElement = document.selectFirst("div[id=selectServer]")!!.attr("data-playerdata")
            val players = json.decodeFromString<JsonObject>(playersElement)["1"]?.jsonArray

            players?.forEach { player ->
                val servers = player!!.jsonArray
                servers.forEach { server ->
                    val item = server!!.jsonObject
                    val videoUrl = "https://animelatinohd.com/video/${item["id"]!!.jsonPrimitive!!.content}/$token"
                    val request = client.newCall(
                        GET(
                            url = videoUrl,
                            headers = headers.newBuilder()
                                .add("Referer", "https://www.animelatinohd.com/")
                                .add("authority", "animelatinohd.com")
                                .add("upgrade-insecure-requests", "1")
                                .build(),
                        ),
                    ).execute()

                    if (request.isSuccessful) {
                        val locationsDdh = request.networkResponse.toString()
                        fetchUrls(locationsDdh).map { url ->
                            val language = if (item["languaje"]!!.jsonPrimitive!!.content == "1") "[LAT]" else "[SUB]"
                            val embedUrl = url.lowercase()
                            when {
                                embedUrl.contains("Delta") -> {
                                    val vidHeaders = headers.newBuilder()
                                        .add("Origin", "https://${url.toHttpUrl().host}")
                                        .add("Referer", "https://${url.toHttpUrl().host}/")
                                        .build()
                                    FilemoonExtractor(client).videosFromUrl(url, prefix = "$language Filemoon:", headers = vidHeaders).also(videoList::addAll)
                                }
                                embedUrl.contains("filelions") || embedUrl.contains("lion") -> {
                                    StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$language FileLions:$it" }).also(videoList::addAll)
                                }
                                embedUrl.contains("streamtape") -> {

                                    StreamTapeExtractor(client).videoFromUrl(url, "$language Streamtape")?.let { videoList.add(it) }
                                }
                                // Añadir más casos según sea necesario para otros extractores
                                else -> {

                                }

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
