package eu.kanade.tachiyomi.animeextension.es.doramasflix

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.doramasflix.extractor.MixDropExtractor
import eu.kanade.tachiyomi.animeextension.es.doramasflix.extractor.UqloadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
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
                val genres = try { apolloState.entries.filter { x -> x.key.contains("genres") }.joinToString { it.value.jsonObject["name"]!!.jsonPrimitive.content } } catch (_: Exception) { null }
                val network = try { apolloState.entries.firstOrNull { x -> x.key.contains("networks") }?.value?.jsonObject?.get("name")!!.jsonPrimitive.content } catch (_: Exception) { null }
                val artist = try { dorama["cast"]?.jsonObject?.get("json")?.jsonArray?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.content } catch (_: Exception) { null }
                val type = dorama["__typename"]!!.jsonPrimitive.content.lowercase()
                val poster = dorama["poster_path"]!!.jsonPrimitive.content
                val urlImg = poster.ifEmpty { dorama["poster"]!!.jsonPrimitive.content }

                val id = dorama["_id"]!!.jsonPrimitive!!.content
                anime.title = "${dorama["name"]!!.jsonPrimitive!!.content} (${dorama["name_es"]!!.jsonPrimitive!!.content})"
                anime.description = dorama["overview"]!!.jsonPrimitive!!.content.trim()
                anime.genre = genres
                anime.author = network
                anime.artist = artist
                anime.status = if (type == "movie") SAnime.COMPLETED else SAnime.UNKNOWN
                anime.thumbnail_url = externalOrInternalImg(urlImg)
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
            // "https://image.tmdb.org/t/p/w220_and_h330_face${it.jsonObject!!["poster_path"]!!.jsonPrimitive!!.content}"
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

    private fun fetchUrls(text: String?): List<String> {
        if (text.isNullOrEmpty()) return listOf()
        val linkRegex = "(https?://(www\\.)?[-a-zA-Z0-9@:%._+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b([-a-zA-Z0-9()@:%_+.~#?&/=]*))".toRegex()
        return linkRegex.findAll(text).map { it.value.trim().removeSurrounding("\"") }.toList()
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videos = mutableListOf<Video>()
        val script = document.selectFirst("script:containsData({\"props\":{\"pageProps\":{)")!!.data()
        fetchUrls(script).map { link ->
            resolveVideoServer(link).let { videos.addAll(it) }
        }
        return videos
    }

    private fun resolveVideoServer(link: String): List<Video> {
        val videos = mutableListOf<Video>()
        if (link.contains("streamtape")) {
            try {
                StreamTapeExtractor(client).videoFromUrl(link)?.let { videos.add(it) }
            } catch (_: Exception) {}
        }
        if (link.contains("mixdrop")) {
            try {
                MixDropExtractor(client).videoFromUrl(link).let { videos.addAll(it) }
            } catch (_: Exception) {}
        }
        if (link.contains("uqload")) {
            try {
                val headers = headers.newBuilder()
                    .add("authority", "uqload.co")
                    .add("referer", "https://uqload.co/")
                    .build()
                UqloadExtractor(client).videoFromUrl(link, headers, "YourUpload").let { videos.addAll(it) }
            } catch (_: Exception) {}
        }
        if (link.contains("ok.ru")) {
            try {
                OkruExtractor(client).videosFromUrl(link, "", true).let { videos.addAll(it) }
            } catch (_: Exception) {}
        }
        if (link.contains("fembed") || link.contains("anime789.com") || link.contains("24hd.club") ||
            link.contains("fembad.org") || link.contains("vcdn.io") || link.contains("sharinglink.club") ||
            link.contains("moviemaniac.org") || link.contains("votrefiles.club") || link.contains("femoload.xyz") ||
            link.contains("albavido.xyz") || link.contains("feurl.com") || link.contains("dailyplanet.pw") ||
            link.contains("ncdnstm.com") || link.contains("jplayer.net") || link.contains("xstreamcdn.com") ||
            link.contains("fembed-hd.com") || link.contains("gcloud.live") || link.contains("vcdnplay.com") ||
            link.contains("superplayxyz.club") || link.contains("vidohd.com") || link.contains("vidsource.me") ||
            link.contains("cinegrabber.com") || link.contains("votrefile.xyz") || link.contains("zidiplay.com") ||
            link.contains("ndrama.xyz") || link.contains("fcdn.stream") || link.contains("mediashore.org") ||
            link.contains("suzihaza.com") || link.contains("there.to") || link.contains("femax20.com") ||
            link.contains("javstream.top") || link.contains("viplayer.cc") || link.contains("sexhd.co") ||
            link.contains("fembed.net") || link.contains("mrdhan.com") || link.contains("votrefilms.xyz") ||
            link.contains("embedsito.com") || link.contains("dutrag.com") || link.contains("youvideos.ru") ||
            link.contains("streamm4u.club") || link.contains("moviepl.xyz") || link.contains("asianclub.tv") ||
            link.contains("vidcloud.fun") || link.contains("fplayer.info") || link.contains("diasfem.com") ||
            link.contains("javpoll.com") || link.contains("reeoov.tube") || link.contains("suzihaza.com") ||
            link.contains("ezsubz.com") || link.contains("vidsrc.xyz") || link.contains("diampokusy.com") ||
            link.contains("diampokusy.com") || link.contains("i18n.pw") || link.contains("vanfem.com") ||
            link.contains("fembed9hd.com") || link.contains("votrefilms.xyz") || link.contains("watchjavnow.xyz")
        ) {
            try {
                FembedExtractor(client).videosFromUrl(link, redirect = !link.contains("fembed")).let { videos.addAll(it) }
            } catch (_: Exception) {}
        }
        if (link.contains("voe")) {
            try {
                VoeExtractor(client).videoFromUrl(link, "Voex")?.let { videos.add(it) }
            } catch (_: Exception) {}
        }
        if (link.contains("sbembed.com") || link.contains("sbembed1.com") || link.contains("sbplay.org") ||
            link.contains("sbvideo.net") || link.contains("streamsb.net") || link.contains("sbplay.one") ||
            link.contains("cloudemb.com") || link.contains("playersb.com") || link.contains("tubesb.com") ||
            link.contains("sbplay1.com") || link.contains("embedsb.com") || link.contains("watchsb.com") ||
            link.contains("sbplay2.com") || link.contains("japopav.tv") || link.contains("viewsb.com") ||
            link.contains("sbfast") || link.contains("sbfull.com") || link.contains("javplaya.com") ||
            link.contains("ssbstream.net") || link.contains("p1ayerjavseen.com") || link.contains("sbthe.com") ||
            link.contains("vidmovie.xyz") || link.contains("sbspeed.com") || link.contains("streamsss.net") ||
            link.contains("sblanh.com") || link.contains("tvmshow.com") || link.contains("sbanh.com") ||
            link.contains("streamovies.xyz")
        ) {
            try {
                StreamSBExtractor(client).videosFromUrl(link, headers).let { videos.addAll(it) }
            } catch (_: Exception) {}
        }
        return videos
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "Okru:1080p", "Okru:720p", "Okru:480p", "Okru:360p", "Okru:240p", "Okru:144p", // Okru
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
            "Streamlare:1080p", "Streamlare:720p", "Streamlare:480p", "Streamlare:360p", "Streamlare:240p", // Streamlare
            "StreamTape", "Voex", "DoodStream", "YourUpload", "MixDrop",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Voex")
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
