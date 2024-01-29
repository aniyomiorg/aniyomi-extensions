package eu.kanade.tachiyomi.animeextension.it.vvvvid

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
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import kotlin.text.isLetter

class VVVVID : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "VVVVID"

    override val baseUrl = "https://www.vvvvid.it"

    private var connId = ""
    private var sessionId = ""

    private var currentPrimaryPage = "anime"
    private var currentChannelId = ""

    override val lang = "it"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun getConnId() {
        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Content-Type", "application/json")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/channel/0/you")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val body = """
            {
              "action": "login",
              "email": "",
              "password": "",
              "facebookParams": "",
              "isIframe": false,
              "mobile": false,
              "hls": true,
              "dash": true,
              "flash": false,
              "webm": true,
              "wv+mp4": true,
              "wv+webm": true,
              "pr+mp4": false,
              "pr+webm": false,
              "fp+mp4": false,
              "device_id_seed": "${getRandomIntString()}"
            }
        """.trimIndent().toRequestBody("application/json".toMediaType())

        val response = client.newCall(
            POST("$baseUrl/user/login", body = body, headers = headers),
        ).execute()
        if (response.code != 200) error("Failed to log in")
        val parsed = json.decodeFromString<LoginResponse>(response.body.string())

        connId = parsed.data.conn_id
        sessionId = parsed.data.sessionId
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        if (connId.isEmpty()) {
            getConnId()
        }

        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Cookie", "JSESSIONID=$sessionId")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        if (page == 1) {
            updateFilters("anime", "Popolari")
        }

        return GET("$baseUrl/vvvvid/ondemand/anime/channel/${currentChannelId}${if (page == 1) "/last" else ""}?conn_id=$connId", headers = headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<AnimesResponse>(response.body.string())

        val animesList = parsed.data.map { ani ->
            SAnime.create().apply {
                title = ani.title
                thumbnail_url = ani.thumbnail
                url = ani.show_id.toString()
            }
        }

        return AnimesPage(animesList, animesList.size == 15)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        if (connId.isEmpty()) {
            getConnId()
        }

        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Cookie", "JSESSIONID=$sessionId")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        if (page == 1) {
            updateFilters("anime", "Nuove")
        }

        return GET("$baseUrl/vvvvid/ondemand/anime/channel/${currentChannelId}${if (page == 1) "/last" else ""}?conn_id=$connId", headers = headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (connId.isEmpty()) {
            getConnId()
        }

        if (query.isNotEmpty()) {
            error("Ricerca non disponibile")
        }

        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Cookie", "JSESSIONID=$sessionId")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        var filterStringFinal = ""
        var filterCounter = 0

        for (filter in filters) {
            when (filter) {
                is PrimaryPageFilter -> {
                    if (filter.selectedValue() != currentPrimaryPage) {
                        currentPrimaryPage = filter.selectedValue()
                        updateFilters(currentPrimaryPage)
                        throw Exception("Apri Filtri e premi reset per reimpostare i filtri")
                    }
                }

                is SubPageFilter -> {
                    var filterString = filter.selectedValue()
                    if (filterString.isNotEmpty()) {
                        filterStringFinal = "$filterString${if (page == 1) "/last" else ""}"
                        filterCounter++
                    }
                }

                is GenreFilter -> {
                    var filterString = filter.selectedValue()
                    if (filterString.isNotEmpty()) {
                        val (channelId, value) = filterString.split(",")
                        filterStringFinal = "$channelId${if (page == 1) "/last" else ""}?category=$value"
                        filterCounter++
                    }
                }

                is AZFilter -> {
                    var filterString = filter.selectedValue()
                    if (filterString.isNotEmpty()) {
                        val (channelId, value) = filterString.split(",")
                        filterStringFinal = "$channelId${if (page == 1) "/last" else ""}?filter=$value"
                        filterCounter++
                    }
                }
                else -> {}
            }
        }

        if (filterCounter != 1) {
            throw Exception("Seleziona solo un sottotipo")
        }

        val url = "$baseUrl/vvvvid/ondemand/$currentPrimaryPage/channel/$filterStringFinal".toHttpUrl()
            .newBuilder()
            .addQueryParameter("conn_id", connId)
            .build()
            .toString()

        return GET(url, headers = headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Filters ===============================

    private open class SelectFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
        defaultValue: String? = null,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
        vals.indexOfFirst { it.second == defaultValue }.takeIf { it != -1 } ?: 0,
    ) {
        fun selectedValue(): String = vals[state].second
    }

    private class PrimaryPageFilter(defaultOrder: String? = null) : SelectFilter(
        "Seleziona la pagina principale",
        arrayOf(
            Pair("Anime", "anime"),
            Pair("Film", "film"),
            Pair("Serie TV", "series"),
            Pair("Show", "show"),
            Pair("Kids", "kids"),
            // Pair("Sala VVVVID (Sperimentale)", "tvod"),
        ),
        defaultOrder,
    )

    override fun getFilterList(): AnimeFilterList {
        val filters = mutableListOf<AnimeFilter<*>>(
            AnimeFilter.Header("Dopo aver modificato la pagina principale,"),
            AnimeFilter.Header("premere filtro per aggiornare i filtri"),
            PrimaryPageFilter(currentPrimaryPage),
            AnimeFilter.Separator(),
            AnimeFilter.Header("Sottotipo (selezionane uno)"),
            SubPageFilter(getSubPageList()),
            GenreFilter(getGenreList()),
            AZFilter(getAZList()),
        )

        return AnimeFilterList(filters)
    }

    // Mutable filters

    private class SubPageFilter(values: Array<Pair<String, String>>, defaultOrder: String? = null) : SelectFilter(
        "Seleziona la sottopagina",
        values,
        defaultOrder,
    )

    private var subPageList: Array<Pair<String, String>>? = null

    private fun getSubPageList(): Array<Pair<String, String>> {
        return subPageList ?: arrayOf(
            Pair("Premere reset per aggiornare i filtri", ""),
        )
    }

    private class GenreFilter(values: Array<Pair<String, String>>) : SelectFilter(
        "Generi",
        values,
    )

    private var genreList: Array<Pair<String, String>>? = null

    private fun getGenreList(): Array<Pair<String, String>> {
        return genreList ?: arrayOf(
            Pair("Premere reset per aggiornare i filtri", ""),
        )
    }

    private class AZFilter(values: Array<Pair<String, String>>) : SelectFilter(
        "A - Z",
        values,
    )

    private var azList: Array<Pair<String, String>>? = null

    private fun getAZList(): Array<Pair<String, String>> {
        return azList ?: arrayOf(
            Pair("Premere reset per aggiornare i filtri", ""),
        )
    }

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        if (connId.isEmpty()) {
            getConnId()
        }

        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Cookie", "JSESSIONID=$sessionId")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return GET("$baseUrl/vvvvid/ondemand/${anime.url}/info/?conn_id=$connId", headers = headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val detailsJson = json.decodeFromString<InfoResponse>(response.body.string()).data

        return SAnime.create().apply {
            title = detailsJson.title
            status = SAnime.UNKNOWN
            genre = detailsJson.show_genres?.joinToString(", ") ?: ""
            description = buildString {
                append(detailsJson.description)
                append("\n\nAnno pubblicato: ${detailsJson.date_published}")
                append("\n${detailsJson.additional_info.split(" | ").joinToString("\n")}")
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        if (connId.isEmpty()) {
            getConnId()
        }

        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Cookie", "JSESSIONID=$sessionId")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return GET("$baseUrl/vvvvid/ondemand/${anime.url}/seasons/?conn_id=$connId", headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val animeJson = json.decodeFromString<SeasonsResponse>(response.body.string())
        val episodeList = mutableListOf<SEpisode>()
        val subDub = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

        var counter = 1
        animeJson.data.forEach {
            val prefix = if (it.name.lowercase().contains("in italiano")) {
                if (subDub == "sub") return@forEach
                "(Dub) Episodi "
            } else if (it.name.lowercase().contains("in giapponese")) {
                if (subDub == "dub") return@forEach
                "(Sub) Episodi "
            } else {
                "${it.name} "
            }

            it.episodes.forEach { ep ->
                episodeList.add(
                    SEpisode.create().apply {
                        name = "$prefix${ep.number} ${ep.title}"
                        episode_number = counter.toFloat()
                        url = LinkData(it.show_id, ep.season_id, ep.video_id).toJsonString()
                    },
                )
                counter++
            }
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> = throw UnsupportedOperationException()

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val (request, videoId) = videoListRequestPair(episode)
        return client.newCall(request)
            .awaitSuccess()
            .let { response ->
                videoListParse(response, videoId).sort()
            }
    }

    private fun videoListRequestPair(episode: SEpisode): Pair<Request, Int> {
        if (connId.isEmpty()) {
            getConnId()
        }

        val mediaId = json.decodeFromString<LinkData>(episode.url)

        val headers = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Cookie", "JSESSIONID=$sessionId")
            .add("Referer", "$baseUrl/")
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return Pair(
            GET(
                "$baseUrl/vvvvid/ondemand/${mediaId.show_id}/season/${mediaId.season_id}?video_id=${mediaId.video_id}&conn_id=$connId",
                headers = headers,
            ),
            mediaId.video_id,
        )
    }

    private fun videoListParse(response: Response, videoId: Int): List<Video> {
        val videoJson = json.decodeFromString<VideosResponse>(response.body.string())
        val videoList = mutableListOf<Video>()

        val video = videoJson.data.first {
            it.video_id == videoId
        }

        val realUrl = realUrl(video.embed_info)

        when {
            realUrl.endsWith(".mpd") -> {
                videoList.add(videoFromDash(realUrl, "HD"))

                if (video.embed_info_sd != null) {
                    val realUrl = realUrl(video.embed_info_sd)
                    videoList.add(videoFromDash(realUrl, "SD"))
                }
            }
        }

        return videoList.sort()
    }

    // ============================= Utilities ==============================

    private fun updateFilters(channelName: String, setId: String = "") {
        val channels = client.newCall(
            GET("$baseUrl/vvvvid/ondemand/$channelName/channels?conn_id=$connId"),
        ).execute()
        val channelsJson = json.decodeFromString<ChannelsResponse>(channels.body.string())

        val subPages = mutableListOf<Pair<String, String>>()
        subPages.add(Pair("Nessuno", ""))

        val genrePages = mutableListOf<Pair<String, String>>()
        genrePages.add(Pair("Nessuno", ""))

        val azPages = mutableListOf<Pair<String, String>>()
        azPages.add(Pair("Nessuno", ""))

        for (it in channelsJson.data) {
            when (it.name) {
                "In Evidenza" -> {
                    subPages.add(Pair(it.name, it.id.toString()))
                }
                "Popolari" -> {
                    if (setId == "Popolari") {
                        currentChannelId = it.id.toString()
                    }
                    subPages.add(Pair(it.name, it.id.toString()))
                }
                "Nuove uscite" -> {
                    if (setId == "Nuove") {
                        currentChannelId = it.id.toString()
                    }
                    subPages.add(Pair(it.name, it.id.toString()))
                }
                "Generi" -> {
                    genrePages.addAll(
                        it.category!!.map { t ->
                            Pair(t.name, "${it.id},${t.id}")
                        },
                    )
                }
                "A - Z" -> {
                    azPages.addAll(
                        it.filter!!.filter { s -> s[0].isLetter() }.map { t ->
                            Pair(t.uppercase(), "${it.id},$t")
                        },
                    )
                }
            }
        }

        subPageList = subPages.toTypedArray()
        genreList = genrePages.toTypedArray()
        azList = azPages.toTypedArray()
    }

    private fun videoFromDash(url: String, name: String): Video {
        val dashHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Accept-Language", "en-US,en;q=0.5")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
            .build()

        val dashContents = client.newCall(
            GET(url, headers = dashHeaders),
        ).execute().body.string()

        val baseVideoUrl = url.substringBeforeLast("/")
        val videoUrl = dashContents.substringAfter("mimeType=\"video").substringBefore("</BaseURL>").substringAfter("<BaseURL>")
        val audioUrl = dashContents.substringAfter("mimeType=\"audio").substringBefore("</BaseURL>").substringAfter("<BaseURL>")

        val audioTracks = mutableListOf<Track>()
        audioTracks.add(Track("$baseVideoUrl/$audioUrl", "Audio"))

        return Video(
            baseVideoUrl,
            name,
            "$baseVideoUrl/$videoUrl",
            audioTracks = audioTracks,
        )
    }

    private fun getRandomIntString(): String {
        val allowedChars = '0'..'9'
        return (1..16)
            .map { allowedChars.random() }
            .joinToString("")
    }

    private fun LinkData.toJsonString(): String {
        return json.encodeToString(this)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private fun f(m: List<Int>): List<Int> {
        val l = mutableListOf<Int>()
        var o = 0
        var b = false
        val mSize = m.size

        while (!b && o < mSize) {
            var n = m[o] shl 2
            o++
            var k = -1
            var j = -1

            if (o < mSize) {
                n += m[o] shr 4
                o++

                if (o < mSize) {
                    k = (m[o - 1] shl 4) and 255
                    k += m[o] shr 2
                    o++

                    if (o < mSize) {
                        j = (m[o - 1] shl 6) and 255
                        j += m[o]
                        o++
                    } else {
                        b = true
                    }
                } else {
                    b = true
                }
            } else {
                b = true
            }

            l.add(n)

            if (k != -1) {
                l.add(k)
            }

            if (j != -1) {
                l.add(j)
            }
        }

        return l
    }

    private fun realUrl(h: String): String {
        val g = "MNOPIJKL89+/4567UVWXQRSTEFGHABCDcdefYZabstuvopqr0123wxyzklmnghij"

        val c = mutableListOf<Int>()
        h.forEach {
            c.add(g.indexOf(it))
        }

        val cSize = c.size
        for (e in cSize * 2 - 1 downTo 0) {
            val a = c[e % cSize] xor c[(e + 1) % cSize]
            c[e % cSize] = a
        }

        val newC = f(c)
        var d = ""
        newC.forEach { e ->
            d += e.toChar()
        }

        return d
    }

    companion object {
        private const val PREF_SUB_KEY = "preferred_sub"
        private const val PREF_SUB_DEFAULT = "none"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "HD"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = "Preferenza sub/dub"
            entries = arrayOf("Nessuno", "Sub", "Dub")
            entryValues = arrayOf("none", "sub", "dub")
            setDefaultValue(PREF_SUB_DEFAULT)
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
            title = "Qualità preferita"
            entries = arrayOf("HD", "SD")
            entryValues = arrayOf("HD", "SD")
            setDefaultValue(PREF_QUALITY_DEFAULT)
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
