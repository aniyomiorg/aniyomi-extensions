package eu.kanade.tachiyomi.animeextension.es.doramasflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
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
                val dorama = apolloState!!.entries!!.firstOrNull()!!.value!!.jsonObject
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
        val episodes = mutableListOf<SEpisode>()
        if (response.request.url.toString().contains("peliculas-online")) {
            val episode = SEpisode.create()
            episode.episode_number = 1F
            episode.name = "Película"
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        } else {
            val jsonEpisodes = mutableListOf<String>()
            val id = response.request.url.toString().substringAfter("?id=")
            val responseString = response.body.string()
            val data = json.decodeFromString<JsonObject>(responseString)!!.jsonObject["data"]!!.jsonObject
            data["listSeasons"]!!.jsonArray.forEach {
                val season = it.jsonObject["season_number"]!!.jsonPrimitive.content
                val body = (
                    "{\"operationName\":\"listEpisodes\",\"variables\":{\"serie_id\":\"$id\",\"season_number\":$season},\"query\":\"query " +
                        "listEpisodes(\$season_number: Float!, \$serie_id: MongoID!) {\\n  listEpisodes(sort: NUMBER_ASC, filter: {type_serie: \\\"dorama\\\", " +
                        "serie_id: \$serie_id, season_number: \$season_number}) {\\n    _id\\n    name\\n    slug\\n    serie_name\\n    serie_name_es\\n    " +
                        "serie_id\\n    still_path\\n    air_date\\n    season_number\\n    episode_number\\n    languages\\n    poster\\n    backdrop\\n    __typename\\n  }\\n}\\n\"}"
                    ).toRequestBody(mediaType)
                client.newCall(POST(apiUrl, popularRequestHeaders, body)).execute().let { resp -> jsonEpisodes.add(resp.body.string()) }
            }
            jsonEpisodes.forEach { json -> episodes.addAll(parseEpisodeListJson(json)) }
        }
        return episodes.reversed()
    }

    private fun parseEpisodeListJson(jsonLine: String?): List<SEpisode> {
        val jsonData = jsonLine ?: return emptyList()
        val episodes = mutableListOf<SEpisode>()
        val data = json.decodeFromString<JsonObject>(jsonData)!!.jsonObject["data"]!!.jsonObject
        data["listEpisodes"]!!.jsonArray!!.map {
            val noSeason = it.jsonObject["season_number"]!!.jsonPrimitive!!.content
            val noEpisode = it.jsonObject["episode_number"]!!.jsonPrimitive!!.content
            var nameEp = it.jsonObject["name"]!!.jsonPrimitive!!.content
            nameEp = if (nameEp == "null") "- Capítulo $noEpisode" else "- $nameEp"
            val slug = it.jsonObject["slug"]!!.jsonPrimitive!!.content
            val episode = SEpisode.create()
            episode.name = "T$noSeason - E$noEpisode $nameEp"
            episode.episode_number = noEpisode.toFloat()
            episode.setUrlWithoutDomain(urlSolverByType("episode", slug))
            episodes.add(episode)
        }
        return episodes
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val responseString = response.body.string()
        return parsePopularAnimeJson(responseString)
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
        return parsePopularAnimeJson(responseString)
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

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        val paginationDorama = json.decodeFromString<JsonObject>(jsonData)!!.jsonObject["data"]!!.jsonObject!!["paginationDorama"]!!.jsonObject
        val hasNextPage = paginationDorama["pageInfo"]!!.jsonObject["hasNextPage"]!!.jsonPrimitive!!.content!!.toBoolean()
        paginationDorama["items"]!!.jsonArray.map {
            val anime = SAnime.create()
            val genres = it.jsonObject!!["genres"]!!.jsonArray!!.joinToString { it.jsonObject["name"]!!.jsonPrimitive!!.content }
            val id = it.jsonObject!!["_id"]!!.jsonPrimitive!!.content
            val poster = it.jsonObject["poster_path"]!!.jsonPrimitive.content
            val urlImg = poster.ifEmpty { it.jsonObject!!["poster"]!!.jsonPrimitive.content }

            anime.title = "${it.jsonObject!!["name"]!!.jsonPrimitive!!.content} (${it.jsonObject!!["name_es"]!!.jsonPrimitive!!.content})"
            anime.description = it.jsonObject!!["overview"]!!.jsonPrimitive!!.content
            anime.genre = genres
            anime.thumbnail_url = externalOrInternalImg(urlImg, true)
            anime.setUrlWithoutDomain(urlSolverByType(it.jsonObject!!["__typename"]!!.jsonPrimitive!!.content, it.jsonObject!!["slug"]!!.jsonPrimitive!!.content, id))
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
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
        return if (responseString.contains("searchDorama")) {
            parseSearchAnimeJson(responseString)
        } else {
            parsePopularAnimeJson(responseString)
        }
    }

    private fun parseSearchAnimeJson(jsonLine: String?): AnimesPage {
        val jsonData = jsonLine ?: return AnimesPage(emptyList(), false)
        val animeList = mutableListOf<SAnime>()
        val paginationDorama = json.decodeFromString<JsonObject>(jsonData)!!.jsonObject["data"]!!.jsonObject
        paginationDorama["searchDorama"]!!.jsonArray.map {
            val anime = SAnime.create()
            val id = it.jsonObject!!["_id"]!!.jsonPrimitive!!.content
            val poster = it.jsonObject!!["poster_path"]!!.jsonPrimitive!!.content
            val urlImg = poster.ifEmpty { it.jsonObject!!["poster"]!!.jsonPrimitive!!.content }

            anime.title = "${it.jsonObject!!["name"]!!.jsonPrimitive!!.content} (${it.jsonObject!!["name_es"]!!.jsonPrimitive!!.content})"
            anime.thumbnail_url = externalOrInternalImg(urlImg, true)
            anime.setUrlWithoutDomain(urlSolverByType(it.jsonObject!!["__typename"]!!.jsonPrimitive!!.content, it.jsonObject!!["slug"]!!.jsonPrimitive!!.content, id))
            animeList.add(anime)
        }
        paginationDorama["searchMovie"]!!.jsonArray.map {
            val anime = SAnime.create()
            val id = it.jsonObject!!["_id"]!!.jsonPrimitive!!.content
            val poster = it.jsonObject!!["poster_path"]!!.jsonPrimitive!!.content
            val urlImg = poster.ifEmpty { it.jsonObject!!["poster"]!!.jsonPrimitive!!.content }

            anime.title = "${it.jsonObject!!["name"]!!.jsonPrimitive!!.content} (${it.jsonObject!!["name_es"]!!.jsonPrimitive!!.content})"
            anime.thumbnail_url = externalOrInternalImg(urlImg, true)
            anime.setUrlWithoutDomain(urlSolverByType(it.jsonObject!!["__typename"]!!.jsonPrimitive!!.content, it.jsonObject!!["slug"]!!.jsonPrimitive!!.content, id))
            animeList.add(anime)
        }
        return AnimesPage(animeList, false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return when {
            query.isNotBlank() -> searchQueryRequest(query)
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

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val jsonData = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        val apolloState = json.decodeFromString<JsonObject>(jsonData).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["apolloState"]!!.jsonObject
        val episode = apolloState.entries.firstOrNull { x -> x.key.contains("Episode:") }!!.value.jsonObject

        val linksOnline = episode["links_online"]!!.jsonObject["json"]!!.jsonArray

        linksOnline.map {
            val link = it.jsonObject["link"]!!.jsonPrimitive.content
            val lang = it.jsonObject["lang"]?.jsonPrimitive?.content?.getLang() ?: ""
            serverVideoResolver(link, lang).also(videoList::addAll)
        }
        return videoList
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videosFromUrl(url, prefix).also(videoList::addAll)
            }
            if ((embedUrl.contains("amazon") || embedUrl.contains("amz")) && !embedUrl.contains("disable")) {
                val body = client.newCall(GET(url)).execute().asJsoup()
                if (body.select("script:containsData(var shareId)").toString().isNotBlank()) {
                    val shareId = body.selectFirst("script:containsData(var shareId)")!!.data()
                        .substringAfter("shareId = \"").substringBefore("\"")
                    val amazonApiJson = client.newCall(GET("https://www.amazon.com/drive/v1/shares/$shareId?resourceVersion=V2&ContentType=JSON&asset=ALL"))
                        .execute().asJsoup()
                    val epId = amazonApiJson.toString().substringAfter("\"id\":\"").substringBefore("\"")
                    val amazonApi =
                        client.newCall(GET("https://www.amazon.com/drive/v1/nodes/$epId/children?resourceVersion=V2&ContentType=JSON&limit=200&sort=%5B%22kind+DESC%22%2C+%22modifiedDate+DESC%22%5D&asset=ALL&tempLink=true&shareId=$shareId"))
                            .execute().asJsoup()
                    val videoUrl = amazonApi.toString().substringAfter("\"FOLDER\":").substringAfter("tempLink\":\"").substringBefore("\"")
                    videoList.add(Video(videoUrl, "$prefix Amazon", videoUrl))
                }
            }
            if (embedUrl.contains("ok.ru") || embedUrl.contains("okru")) {
                OkruExtractor(client).videosFromUrl(url, prefix = "$prefix ").also(videoList::addAll)
            }
            if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders).also(videoList::addAll)
            }
            if (embedUrl.contains("uqload")) {
                UqloadExtractor(client).videosFromUrl(url, prefix = prefix).also(videoList::addAll)
            }
            if (embedUrl.contains("mp4upload")) {
                Mp4uploadExtractor(client).videosFromUrl(url, prefix = "$prefix ", headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish")) {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
                val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                DoodExtractor(client).videoFromUrl(url2, "$prefix DoodStream", false)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("streamlare")) {
                StreamlareExtractor(client).videosFromUrl(url, prefix = prefix).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("yourupload") || embedUrl.contains("upload")) {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = "$prefix ").let { videoList.addAll(it) }
            }
            if (embedUrl.contains("burstcloud") || embedUrl.contains("burst")) {
                BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = "$prefix ").let { videoList.addAll(it) }
            }
            if (embedUrl.contains("fastream")) {
                FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:").also(videoList::addAll)
            }
            if (embedUrl.contains("upstream")) {
                UpstreamExtractor(client).videosFromUrl(url, prefix = "$prefix ").let { videoList.addAll(it) }
            }
            if (embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape")) {
                StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")?.let { videoList.add(it) }
            }
            if (embedUrl.contains("ahvsh") || embedUrl.contains("streamhide")) {
                StreamHideVidExtractor(client).videosFromUrl(url, "$prefix ").let { videoList.addAll(it) }
            }
            if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "$prefix FileLions:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("tomatomatela")) {
                runCatching {
                    val mainUrl = url.substringBefore("/embed.html#").substringAfter("https://")
                    val headers = headers.newBuilder()
                        .set("authority", mainUrl)
                        .set("accept", "application/json, text/javascript, */*; q=0.01")
                        .set("accept-language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                        .set("sec-ch-ua", "\"Chromium\";v=\"106\", \"Google Chrome\";v=\"106\", \"Not;A=Brand\";v=\"99\"")
                        .set("sec-ch-ua-mobile", "?0")
                        .set("sec-ch-ua-platform", "Windows")
                        .set("sec-fetch-dest", "empty")
                        .set("sec-fetch-mode", "cors")
                        .set("sec-fetch-site", "same-origin")
                        .set("x-requested-with", "XMLHttpRequest")
                        .build()
                    val token = url.substringAfter("/embed.html#")
                    val urlRequest = "https://$mainUrl/details.php?v=$token"
                    val response = client.newCall(GET(urlRequest, headers = headers)).execute().asJsoup()
                    val bodyText = response.select("body").text()
                    val json = json.decodeFromString<JsonObject>(bodyText)
                    val status = json["status"]!!.jsonPrimitive!!.content
                    val file = json["file"]!!.jsonPrimitive!!.content
                    if (status == "200") { videoList.add(Video(file, "$prefix Tomatomatela", file, headers = null)) }
                }
            }
        } catch (_: Exception) { }
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
