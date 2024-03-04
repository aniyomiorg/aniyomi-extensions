package eu.kanade.tachiyomi.animeextension.es.doramasflix

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
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
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Date

class Doramasflix : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Doramasflix"

    override val baseUrl = "https://doramasflix.in"

    private val apiUrl = "https://sv1.fluxcedene.net/api/gql"

    // The token is made through a type of milliseconds encryption in combination
    // with other calculated strings, the milliseconds indicate the expiration date
    // of the token, so it was calculated to expire in 100 years.
    private val accessPlatform = "RxARncfg1S_MdpSrCvreoLu_SikCGMzE1NzQzODc3NjE2MQ=="

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override val lang = "es"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf(
            "[ENG]", "[CAST]", "[LAT]", "[SUB]", "[POR]",
            "[COR]", "[JAP]", "[MAN]", "[TAI]", "[FIL]",
            "[IND]", "[VIET]",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "Uqload",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        }
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val popularRequestHeaders = Headers.headersOf(
        "authority", "sv1.fluxcedene.net",
        "accept", "application/json, text/plain, */*",
        "content-type", "application/json;charset=UTF-8",
        "origin", "https://doramasflix.in",
        "referer", "https://doramasflix.in/",
        "platform", "doramasflix",
        "authorization", "Bear",
        "x-access-jwt-token", "",
        "x-access-platform", accessPlatform,
    )

    private fun externalOrInternalImg(url: String, isThumb: Boolean = false): String {
        return if (url.contains("https")) {
            url
        } else if (isThumb) {
            "https://image.tmdb.org/t/p/w220_and_h330_face$url"
        } else {
            "https://image.tmdb.org/t/p/w500$url"
        }
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        document.select("script").map { el ->
            if (el.data().contains("{\"props\":{\"pageProps\":{")) {
                val apolloState = json.decodeFromString<JsonObject>(el.data())!!.jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["apolloState"]!!.jsonObject
                val dorama = apolloState!!.entries!!.firstOrNull { (key, _) -> Regex("\\b(?:Movie|Dorama):[a-zA-Z0-9]+").matches(key) }!!.value!!.jsonObject

                val genres = try { apolloState.entries.filter { x -> x.key.contains("genres") }.joinToString { it.value.jsonObject["name"]!!.jsonPrimitive.content } } catch (_: Exception) { "" }
                val network = try { apolloState.entries.firstOrNull { x -> x.key.contains("networks") }?.value?.jsonObject?.get("name")!!.jsonPrimitive.content } catch (_: Exception) { "" }
                val artist = try { dorama["cast"]?.jsonObject?.get("json")?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content } catch (_: Exception) { "" }
                val type = try { dorama["__typename"]!!.jsonPrimitive.content.lowercase() } catch (_: Exception) { "" }
                val poster = try { dorama["poster_path"]!!.jsonPrimitive.content } catch (_: Exception) { "" }
                val urlImg = try { poster.ifEmpty { dorama["poster"]!!.jsonPrimitive.content } } catch (_: Exception) { "" }

                val id = dorama["_id"]!!.jsonPrimitive.content
                anime.title = "${dorama["name"]?.jsonPrimitive?.content} (${dorama["name_es"]?.jsonPrimitive?.content})"
                anime.description = dorama["overview"]?.jsonPrimitive?.content?.trim() ?: ""
                if (genres.isNotEmpty()) anime.genre = genres
                if (network.isNotEmpty()) anime.author = network
                if (artist != null) anime.artist = artist
                if (type.isNotEmpty()) anime.status = if (type == "movie") SAnime.COMPLETED else SAnime.UNKNOWN
                if (urlImg.isNotEmpty()) anime.thumbnail_url = externalOrInternalImg(urlImg)
                anime.setUrlWithoutDomain(urlSolverByType(dorama["__typename"]!!.jsonPrimitive!!.content, dorama["slug"]!!.jsonPrimitive!!.content, id))
            }
        }
        return anime
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val id = anime.url.substringAfter("?id=")
        return if (anime.url.contains("peliculas-online")) {
            GET(baseUrl + anime.url)
        } else {
            val body = (
                "{\"operationName\":\"listSeasons\",\"variables\":{\"serie_id\":\"$id\"},\"query\":\"query listSeasons(\$serie_id: MongoID!) " +
                    "{\\n  listSeasons(sort: NUMBER_ASC, filter: {serie_id: \$serie_id}) {\\n    slug\\n    season_number\\n    poster_path\\n    air_date\\n    " +
                    "serie_name\\n    poster\\n    backdrop\\n    __typename\\n  }\\n}\\n\"}"
                ).toRequestBody(mediaType)
            POST("$apiUrl?id=$id", popularRequestHeaders, body)
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return if (response.request.url.toString().contains("peliculas-online")) {
            listOf(
                SEpisode.create().apply {
                    episode_number = 1F
                    name = "Película"
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            val id = response.request.url.toString().substringAfter("?id=")
            val responseString = response.body.string()
            val data = json.decodeFromString<SeasonModel>(responseString).data

            data.listSeasons.parallelCatchingFlatMapBlocking {
                val season = it.seasonNumber
                val body = (
                    "{\"operationName\":\"listEpisodes\",\"variables\":{\"serie_id\":\"$id\",\"season_number\":$season},\"query\":\"query " +
                        "listEpisodes(\$season_number: Float!, \$serie_id: MongoID!) {\\n  listEpisodes(sort: NUMBER_ASC, filter: {type_serie: \\\"dorama\\\", " +
                        "serie_id: \$serie_id, season_number: \$season_number}) {\\n    _id\\n    name\\n    slug\\n    serie_name\\n    serie_name_es\\n    " +
                        "serie_id\\n    still_path\\n    air_date\\n    season_number\\n    episode_number\\n    languages\\n    poster\\n    backdrop\\n    __typename\\n  }\\n}\\n\"}"
                    ).toRequestBody(mediaType)

                val episodes = client.newCall(POST(apiUrl, popularRequestHeaders, body)).execute().let { resp ->
                    json.decodeFromString<EpisodeModel>(resp.body.string())
                }
                parseEpisodeListJson(episodes)
            }
        }.reversed()
    }

    private fun parseEpisodeListJson(episodes: EpisodeModel): List<SEpisode> {
        var isUpcoming = false
        val currentDate = Date().time
        return episodes.data.listEpisodes.mapIndexed { idx, episodeObject ->
            val dateEp = episodeObject.airDate
            val nameEp = if (episodeObject.name.isNullOrEmpty()) "- Capítulo ${episodeObject.episodeNumber}" else "- ${episodeObject.name}"
            if (dateEp != null && dateEp.toDate() > currentDate && !isUpcoming) isUpcoming = true

            SEpisode.create().apply {
                name = "T${episodeObject.seasonNumber} - E${episodeObject.episodeNumber} $nameEp"
                episode_number = episodeObject.episodeNumber?.toFloat() ?: idx.toFloat()
                date_upload = dateEp?.toDate() ?: 0L
                scanlator = if (isUpcoming) "Próximamente..." else null
                setUrlWithoutDomain(urlSolverByType("episode", episodeObject.slug))
            }
        }
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return when {
            responseString.contains("paginationMovie") -> parsePopularJson(responseString, "movie")
            else -> parsePopularJson(responseString, "dorama")
        }
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = (
            "{\"operationName\":\"listDoramas\",\"variables\":{\"page\":$page,\"sort\":\"CREATEDAT_DESC\",\"perPage\":32,\"filter\":{\"isTVShow\":false}}," +
                "\"query\":\"query listDoramas(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  " +
                "paginationDorama(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      " +
                "hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      " +
                "cast\\n      names\\n      overview\\n      languages\\n      created_by\\n      popularity\\n      poster_path\\n      vote_average\\n      " +
                "backdrop_path\\n      first_air_date\\n      episode_run_time\\n      isTVShow\\n      poster\\n      backdrop\\n      genres {\\n        " +
                "name\\n        slug\\n        __typename\\n      }\\n      networks {\\n        name\\n        slug\\n        __typename\\n      }\\n      " +
                "__typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
            ).toRequestBody(mediaType)
        return POST(apiUrl, popularRequestHeaders, body)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return when {
            responseString.contains("paginationMovie") -> parsePopularJson(responseString, "movie")
            else -> parsePopularJson(responseString, "dorama")
        }
    }

    private val languages = arrayOf(
        Pair("36", "[ENG]"),
        Pair("37", "[CAST]"),
        Pair("38", "[LAT]"),
        Pair("192", "[SUB]"),
        Pair("1327", "[POR]"),
        Pair("13109", "[COR]"),
        Pair("13110", "[JAP]"),
        Pair("13111", "[MAN]"),
        Pair("13112", "[TAI]"),
        Pair("13113", "[FIL]"),
        Pair("13114", "[IND]"),
        Pair("343422", "[VIET]"),
    )

    private fun String.getLang(): String {
        return languages.firstOrNull { it.first == this }?.second ?: ""
    }

    private fun parsePopularJson(jsonLine: String?, type: String): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val data = json.decodeFromString<PaginationModel>(jsonData).data

        val pagination = when (type) {
            "dorama" -> data.paginationDorama
            "movie" -> data.paginationMovie
            else -> throw IllegalArgumentException("Tipo de dato no válido: $type")
        }

        val hasNextPage = pagination?.pageInfo?.hasNextPage ?: false
        val animeList = pagination?.items?.map { animeObject ->
            val urlImg = when {
                !animeObject.posterPath.isNullOrEmpty() -> animeObject.posterPath.toString()
                !animeObject.poster.isNullOrEmpty() -> animeObject.poster.toString()
                else -> ""
            }

            SAnime.create().apply {
                title = "${animeObject.name} (${animeObject.nameEs})"
                description = animeObject.overview
                genre = animeObject.genres.joinToString { it.name ?: "" }
                thumbnail_url = externalOrInternalImg(urlImg, true)
                setUrlWithoutDomain(urlSolverByType(animeObject.typename, animeObject.slug, animeObject.id))
            }
        }
        return AnimesPage(animeList ?: emptyList(), hasNextPage)
    }

    private fun urlSolverByType(type: String, slug: String, id: String? = ""): String {
        return when (type.lowercase()) {
            "dorama" -> "$baseUrl/doramas-online/$slug?id=$id"
            "episode" -> "$baseUrl/episodios/$slug"
            "movie" -> "$baseUrl/peliculas-online/$slug?id=$id"
            else -> ""
        }
    }

    override fun popularAnimeRequest(page: Int): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = (
            "{\"operationName\":\"listDoramas\",\"variables\":{\"page\":$page,\"sort\":\"POPULARITY_DESC\",\"perPage\":32,\"filter\":{\"isTVShow\":false}}," +
                "\"query\":\"query listDoramas(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  " +
                "paginationDorama(page: \$page, perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      " +
                "hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      " +
                "cast\\n      names\\n      overview\\n      languages\\n      created_by\\n      popularity\\n      poster_path\\n      vote_average\\n      " +
                "backdrop_path\\n      first_air_date\\n      episode_run_time\\n      isTVShow\\n      poster\\n      backdrop\\n      genres {\\n        " +
                "name\\n        slug\\n        __typename\\n      }\\n      networks {\\n        name\\n        slug\\n        __typename\\n      }\\n      " +
                "__typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
            ).toRequestBody(mediaType)
        return POST(apiUrl, popularRequestHeaders, body)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return when {
            responseString.contains("searchDorama") -> parseSearchAnimeJson(responseString)
            responseString.contains("paginationMovie") -> parsePopularJson(responseString, "movie")
            else -> parsePopularJson(responseString, "dorama")
        }
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val jsonObject = json.decodeFromString<SearchModel>(jsonData).data

        val animeList = mutableListOf<SAnime>()
        jsonObject.searchDorama.map { castToSAnime(it) }.also(animeList::addAll)
        jsonObject.searchMovie.map { castToSAnime(it) }.also(animeList::addAll)

        return AnimesPage(animeList, false)
    }

    private fun castToSAnime(animeObject: SearchDorama): SAnime {
        val urlImg = when {
            !animeObject.posterPath.isNullOrEmpty() -> animeObject.posterPath.toString()
            !animeObject.poster.isNullOrEmpty() -> animeObject.poster.toString()
            else -> ""
        }
        return SAnime.create().apply {
            title = "${animeObject.name} (${animeObject.nameEs})"
            thumbnail_url = externalOrInternalImg(urlImg, true)
            setUrlWithoutDomain(urlSolverByType(animeObject.typename, animeObject.slug, animeObject.id))
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> searchQueryRequest(query)
            "peliculas" in genreFilter.toUriPart() -> popularMovieRequest(page)
            "variedades" in genreFilter.toUriPart() -> popularVarietiesRequest(page)
            else -> popularAnimeRequest(page)
        }
    }

    private fun searchQueryRequest(query: String): Request {
        val fxQuery = query.replace("+", " ")
        val body = (
            "{\"operationName\":\"searchAll\",\"variables\":{\"input\":\"$fxQuery\"},\"query\":\"query searchAll(\$input: String!) {\\n  " +
                "searchDorama(input: \$input, limit: 32) {\\n    _id\\n    slug\\n    name\\n    name_es\\n    poster_path\\n    poster\\n    " +
                "__typename\\n  }\\n  searchMovie(input: \$input, limit: 32) {\\n    _id\\n    name\\n    name_es\\n    slug\\n    poster_path\\n    " +
                "poster\\n    __typename\\n  }\\n}\\n\"}"
            ).toRequestBody(mediaType)
        return POST(apiUrl, popularRequestHeaders, body)
    }

    private fun popularMovieRequest(page: Int): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = (
            "{\"operationName\":\"listMovies\",\"variables\":{\"perPage\":32,\"sort\":\"CREATEDAT_DESC\",\"filter\":{},\"page\":$page},\"query\":\"query " +
                "listMovies(\$page: Int, \$perPage: Int, \$sort: SortFindManyMovieInput, \$filter: FilterFindManyMovieInput) {\\n  paginationMovie(page: \$page" +
                ", perPage: \$perPage, sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n" +
                "      __typename\\n    }\\n    items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      cast\\n      names\\n      overview\\n      " +
                "languages\\n      popularity\\n      poster_path\\n      vote_average\\n      backdrop_path\\n      release_date\\n      runtime\\n      poster\\n      " +
                "backdrop\\n      genres {\\n        name\\n        __typename\\n      }\\n      networks {\\n        name\\n        __typename\\n      }\\n      " +
                "__typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
            ).toRequestBody(mediaType)

        return POST(apiUrl, popularRequestHeaders, body)
    }

    private fun popularVarietiesRequest(page: Int): Request {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = (
            "{\"operationName\":\"listDoramas\",\"variables\":{\"page\":$page,\"sort\":\"CREATEDAT_DESC\",\"perPage\":32,\"filter\":{\"isTVShow\":true}},\"query\":\"query " +
                "listDoramas(\$page: Int, \$perPage: Int, \$sort: SortFindManyDoramaInput, \$filter: FilterFindManyDoramaInput) {\\n  paginationDorama(page: \$page, perPage: \$perPage, " +
                "sort: \$sort, filter: \$filter) {\\n    count\\n    pageInfo {\\n      currentPage\\n      hasNextPage\\n      hasPreviousPage\\n      __typename\\n    }\\n    " +
                "items {\\n      _id\\n      name\\n      name_es\\n      slug\\n      cast\\n      names\\n      overview\\n      languages\\n      created_by\\n      " +
                "popularity\\n      poster_path\\n      vote_average\\n      backdrop_path\\n      first_air_date\\n      episode_run_time\\n      isTVShow\\n      poster\\n      " +
                "backdrop\\n      genres {\\n        name\\n        slug\\n        __typename\\n      }\\n      networks {\\n        name\\n        slug\\n        " +
                "__typename\\n      }\\n      __typename\\n    }\\n    __typename\\n  }\\n}\\n\"}"
            ).toRequestBody(mediaType)

        return POST(apiUrl, popularRequestHeaders, body)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("Doramas", "doramas"),
            Pair("Películas", "peliculas"),
            Pair("Variedades", "variedades"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }.getOrNull() ?: 0L
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val jsonData = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        val apolloState = json.decodeFromString<JsonObject>(jsonData).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["apolloState"]!!.jsonObject
        val episodeItem = apolloState.entries.firstOrNull { x -> x.key.contains("Episode:") }

        val episode = episodeItem?.value?.jsonObject
            ?: apolloState.entries.firstOrNull { (key, _) -> Regex("\\b(?:Movie|Dorama):[a-zA-Z0-9]+").matches(key) }?.value?.jsonObject

        var linksOnline = episode?.get("links_online")?.jsonObject?.get("json")?.jsonArray
        val bMovies = apolloState.entries.any { x -> x.key.contains("ROOT_QUERY.getMovieLinks(") }

        if (bMovies && linksOnline == null) {
            linksOnline = apolloState.entries.firstOrNull { x -> x.key.contains("ROOT_QUERY.getMovieLinks(") }
                ?.value?.jsonObject?.get("links_online")?.jsonObject?.get("json")?.jsonArray
        }

        return linksOnline?.parallelCatchingFlatMapBlocking {
            val link = it.jsonObject["link"]!!.jsonPrimitive.content
            val lang = it.jsonObject["lang"]?.jsonPrimitive?.content?.getLang() ?: ""
            serverVideoResolver(link, lang)
        } ?: apolloState.entries.filter { x -> x.key.contains("ROOT_QUERY.listProblems(") }
            .mapNotNull { entry ->
                val server = entry.value.jsonObject["server"]?.jsonObject?.get("json")?.jsonObject
                val link = server?.get("link")?.jsonPrimitive?.content
                val lang = server?.get("lang")?.jsonPrimitive?.content?.getLang() ?: ""
                link?.let { it to lang }
            }.distinctBy { it.first }
            .parallelCatchingFlatMapBlocking { (link, lang) ->
                val finalLink = getRealLink(link)
                serverVideoResolver(finalLink, lang)
            }
    }

    private fun getRealLink(link: String): String {
        if (!link.contains("fkplayer.xyz")) return link

        val token = client.newCall(GET(link)).execute()
            .asJsoup().selectFirst("script:containsData({\"props\":{\"pageProps\":{)")?.data()
            ?.parseTo<TokenModel>()

        val mediaType = "application/json".toMediaType()
        val requestBody = "{\"token\":\"${token?.props?.pageProps?.token ?: token?.query?.token}\"}".toRequestBody(mediaType)

        val headersVideo = headers.newBuilder()
            .add("origin", "https://${link.toHttpUrl().host}")
            .add("Content-Type", "application/json")
            .build()

        val json = client.newCall(POST("https://fkplayer.xyz/api/decoding", headersVideo, requestBody))
            .execute().body.string().parseTo<VideoToken>()

        return String(Base64.decode(json.link, Base64.DEFAULT))
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val embedUrl = url.lowercase()
        return when {
            "voe" in embedUrl -> VoeExtractor(client).videosFromUrl(url, " $prefix")
            "ok.ru" in embedUrl || "okru" in embedUrl -> OkruExtractor(client).videosFromUrl(url, prefix = "$prefix ")
            "filemoon" in embedUrl || "moonplayer" in embedUrl -> {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders)
            }
            "uqload" in embedUrl -> UqloadExtractor(client).videosFromUrl(url, prefix = prefix)
            "mp4upload" in embedUrl -> Mp4uploadExtractor(client).videosFromUrl(url, prefix = "$prefix ", headers = headers)
            "doodstream" in embedUrl || "dood." in embedUrl ->
                listOf(DoodExtractor(client).videoFromUrl(url.replace("https://doodstream.com/e/", "https://dood.to/e/"), "$prefix DoodStream", false)!!)
            "streamlare" in embedUrl -> StreamlareExtractor(client).videosFromUrl(url, prefix = prefix)
            "yourupload" in embedUrl || "upload" in embedUrl -> YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = "$prefix ")
            "wishembed" in embedUrl || "streamwish" in embedUrl || "strwish" in embedUrl || "wish" in embedUrl -> {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
            }
            "burstcloud" in embedUrl || "burst" in embedUrl -> BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = "$prefix ")
            "fastream" in embedUrl -> FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:")
            "upstream" in embedUrl -> UpstreamExtractor(client).videosFromUrl(url, prefix = "$prefix ")
            "streamtape" in embedUrl || "stp" in embedUrl || "stape" in embedUrl -> listOf(StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")!!)
            "ahvsh" in embedUrl || "streamhide" in embedUrl -> StreamHideVidExtractor(client).videosFromUrl(url, "$prefix ")
            "filelions" in embedUrl || "lion" in embedUrl -> StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" })
            "vudeo" in embedUrl || "vudea" in embedUrl -> VudeoExtractor(client).videosFromUrl(url, "$prefix ")
            else -> emptyList()
        }
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
    private inline fun <reified T> String.parseTo(): T {
        return json.decodeFromString<T>(this)
    }

    private inline fun <reified T> Response.parseTo(): T {
        return json.decodeFromString<T>(this.body.string())
    }
}
