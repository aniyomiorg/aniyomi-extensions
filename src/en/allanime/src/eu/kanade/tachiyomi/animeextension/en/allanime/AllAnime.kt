package eu.kanade.tachiyomi.animeextension.en.allanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.AllAnimeExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animeextension.en.allanime.extractors.VidstreamingExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class AllAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AllAnime"

    override val baseUrl = "https://allanime.site"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val popularHash = "6f6fe5663e3e9ea60bdfa693f878499badab83e7f18b56acdba5f8e8662002aa"
    private val searchHash = "9c7a8bc1e095a34f2972699e8105f7aaf9082c6e1ccd56eab99c2f1a971152c6"
    private val _idHash = "f73a8347df0e3e794f8955a18de6e85ac25dfc6b74af8ad613edf87bb446a854"
    private val episodeHash = "1f0a5d6c9ce6cd3127ee4efd304349345b0737fbf5ec33a60bbc3d18e3bb7c61"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val variables = """{"type":"anime","size":30,"dateRange":7,"page":$page,"allowAdult":false,"allowUnknown":false}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$popularHash"}}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&extensions=$extensions", headers = headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<PopularResult>(response.body!!.string())
        val animeList = mutableListOf<SAnime>()

        val titleStyle = preferences.getString("preferred_title_style", "romaji")!!

        parsed.data.queryPopular.recommendations.forEach {
            if (it.anyCard != null) {
                animeList.add(
                    SAnime.create().apply {
                        title = when (titleStyle) {
                            "romaji" -> it.anyCard.name
                            "eng" -> it.anyCard.englishName ?: it.anyCard.name
                            else -> it.anyCard.nativeName ?: it.anyCard.name
                        }
                        thumbnail_url = it.anyCard.thumbnail
                        url = it.anyCard._id
                    }
                )
            }
        }

        return AnimesPage(animeList, animeList.size == 30)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val variables = """{"search":{"allowAdult":false,"allowUnknown":false},"limit":26,"page":$page,"translationType":"${preferences.getString("preferred_sub", "sub")!!}","countryOrigin":"ALL"}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$searchHash"}}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&extensions=$extensions", headers = headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return ParseAnime(response)
    }

    // =============================== Search ===============================

    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = AllAnimeFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    private fun searchAnimeRequest(page: Int, query: String, filters: AllAnimeFilters.FilterSearchParams): Request {
        return if (query.isNotEmpty()) {
            val variables = """{"search":{"query":"$query","allowAdult":false,"allowUnknown":false},"limit":26,"page":$page,"translationType":"${preferences.getString("preferred_sub", "sub")!!}","countryOrigin":"ALL"}"""
            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$searchHash"}}"""
            val headers = headers.newBuilder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                .build()
            GET("$baseUrl/allanimeapi?variables=$variables&extensions=$extensions", headers = headers)
        } else {

            val seasonString = if (filters.season == "all") "" else ""","season":"${filters.season}""""
            val yearString = if (filters.releaseYear == "all") "" else ""","year":${filters.releaseYear}"""
            val genresString = if (filters.genres == "all") "" else ""","genres":${filters.genres},"excludeGenres":[]"""
            val typesString = if (filters.types == "all") "" else ""","types":${filters.types}"""
            val sortByString = if (filters.sortBy == "update") "" else ""","sortBy":"${filters.sortBy}""""

            var variables = """{"search":{"allowAdult":false,"allowUnknown":false$seasonString$yearString$genresString$typesString$sortByString"""
            variables += """},"limit":26,"page":$page,"translationType":"${preferences.getString("preferred_sub", "sub")!!}","countryOrigin":"${filters.origin}"}"""

            val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$searchHash"}}"""
            val headers = headers.newBuilder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                .build()
            GET("$baseUrl/allanimeapi?variables=$variables&extensions=$extensions", headers = headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return ParseAnime(response)
    }

    override fun getFilterList(): AnimeFilterList = AllAnimeFilters.filterList

    // =========================== Anime Details ============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val variables = """{"_id":"${anime.url}"}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$_idHash"}}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&extensions=$extensions", headers = headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val show = json.decodeFromString<SeriesResult>(response.body!!.string()).data.show
        val anime = SAnime.create()

        anime.title = show.name

        anime.description = Jsoup.parse(
            show.description?.replace("<br>", "br2n") ?: ""
        ).text().replace("br2n", "\n") + "\n\n"
        anime.description += "Type: ${show.type ?: "Unknown"}"
        anime.description += "\nAired: ${show.season?.quarter ?: "-"} ${show.season?.year ?: "-"}"
        anime.description += "\nScore: ${show.score ?: "-"}★"

        anime.genre = show.genres?.joinToString(separator = ", ") ?: ""
        anime.status = parseStatus(show.status)
        if (show.studios?.isNotEmpty() == true) {
            anime.author = show.studios.first()
        }

        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val variables = """{"_id":"${anime.url}"}"""
        val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$_idHash"}}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&extensions=$extensions", headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val medias = json.decodeFromString<SeriesResult>(response.body!!.string())
        val episodeList = mutableListOf<SEpisode>()

        val subOrDub = preferences.getString("preferred_sub", "sub")!!

        if (subOrDub == "sub") {
            for (ep in medias.data.show.availableEpisodesDetail.sub!!) {
                val episode = SEpisode.create()
                episode.episode_number = ep.toFloatOrNull() ?: 0F
                val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")
                episode.name = "Episode $numName (sub)"

                val variables = """{"showId":"${medias.data.show._id}","translationType":"sub","episodeString":"$ep"}"""
                val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$episodeHash"}}"""
                episode.setUrlWithoutDomain("/allanimeapi?variables=$variables&extensions=$extensions")
                episodeList.add(episode)
            }
        } else {
            for (ep in medias.data.show.availableEpisodesDetail.dub!!) {
                val episode = SEpisode.create()
                episode.episode_number = ep.toFloatOrNull() ?: 0F
                val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")
                episode.name = "Episode $numName (dub)"

                val variables = """{"showId":"${medias.data.show._id}","translationType":"dub","episodeString":"$ep"}"""
                val extensions = """{"persistedQuery":{"version":1,"sha256Hash":"$episodeHash"}}"""
                episode.setUrlWithoutDomain("/allanimeapi?variables=$variables&extensions=$extensions")
                episodeList.add(episode)
            }
        }

        return episodeList
    }

    // ============================ Video Links =============================

    override fun videoListRequest(episode: SEpisode): Request {
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET(baseUrl + episode.url, headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val body = response.body!!.string()

        val videoJson = json.decodeFromString<EpisodeResult>(body)
        val videoList = mutableListOf<Pair<Video, Float>>()

        val altHosterSelection = preferences.getStringSet(
            "alt_hoster_selection",
            setOf("player", "vidstreaming", "okru", "mp4upload", "streamlare", "doodstream")
        )!!

        val hosterSelection = preferences.getStringSet(
            "hoster_selection",
            setOf("default", "ac", "luf-mp4", "si-hls", "s-mp4", "ac-hls")
        )!!

        for (video in videoJson.data.episode.sourceUrls) {
            when {
                video.sourceUrl.startsWith("/apivtwo/") && (
                    (hosterSelection.contains("default") && video.sourceName.lowercase().contains("default")) ||
                        (hosterSelection.contains("ac") && video.sourceName.lowercase().contains("ac")) ||
                        (hosterSelection.contains("luf-mp4") && video.sourceName.lowercase().contains("luf-mp4")) ||
                        (hosterSelection.contains("si-hls") && video.sourceName.lowercase().contains("si-hls")) ||
                        (hosterSelection.contains("s-mp4") && video.sourceName.lowercase().contains("s-mp4")) ||
                        (hosterSelection.contains("ac-hls") && video.sourceName.lowercase().contains("ac-hls")) ||
                        (hosterSelection.contains("uv-mp4") && video.sourceName.lowercase().contains("uv-mp4")) ||
                        (hosterSelection.contains("pn-hls") && video.sourceName.lowercase().contains("pn-hls"))
                    ) -> {
                    val extractor = AllAnimeExtractor(client)
                    val videos = runCatching {
                        extractor.videoFromUrl(video.sourceUrl, video.sourceName)
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
                altHosterSelection.contains("player") && video.type == "player" -> {
                    videoList.add(
                        Pair(
                            Video(
                                video.sourceUrl,
                                "Original (player ${video.sourceName})",
                                video.sourceUrl
                            ),
                            video.priority
                        )

                    )
                }
                altHosterSelection.contains("streamsb") && video.sourceUrl.contains("streamsb") -> {
                    val extractor = StreamSBExtractor(client)
                    val videos = runCatching {
                        extractor.videosFromUrl(video.sourceUrl, headers)
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
                altHosterSelection.contains("vidstreaming") && (video.sourceUrl.contains("vidstreaming") || video.sourceUrl.contains("https://gogo")) -> {
                    val extractor = VidstreamingExtractor(client, json)
                    val videos = runCatching {
                        extractor.videosFromUrl(video.sourceUrl.replace(Regex("^//"), "https://"))
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
                altHosterSelection.contains("doodstream") && video.sourceUrl.contains("dood") -> {
                    val extractor = DoodExtractor(client)
                    val videos = runCatching {
                        extractor.videosFromUrl(video.sourceUrl)
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
                altHosterSelection.contains("okru") && video.sourceUrl.contains("ok.ru") -> {
                    val extractor = OkruExtractor(client)
                    val videos = runCatching {
                        extractor.videosFromUrl(video.sourceUrl)
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
                altHosterSelection.contains("mp4upload") && video.sourceUrl.contains("mp4upload.com") -> {
                    val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                    val videos = runCatching {
                        Mp4uploadExtractor(client).getVideoFromUrl(video.sourceUrl, headers)
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
                altHosterSelection.contains("streamlare") && video.sourceUrl.contains("streamlare.com") -> {
                    val extractor = StreamlareExtractor(client)
                    val videos = runCatching {
                        extractor.videosFromUrl(video.sourceUrl)
                    }.getOrNull() ?: emptyList()
                    for (v in videos) {
                        videoList.add(Pair(v, video.priority))
                    }
                }
            }
        }

        return prioritySort(videoList)
    }

    // ============================= Utilities ==============================

    private fun prioritySort(pList: List<Pair<Video, Float>>): List<Video> {
        val prefServer = preferences.getString("preferred_server", "site_default")!!
        val quality = preferences.getString("preferred_quality", "1080")!!
        val subOrDub = preferences.getString("preferred_sub", "sub")!!

        return pList.sortedWith(
            compareBy(
                { if (prefServer == "site_default") it.second else it.first.quality.lowercase().contains(prefServer) },
                { it.first.quality.lowercase().contains(quality) },
                { it.first.quality.lowercase().contains(subOrDub) }
            )
        ).reversed().map { t -> t.first }
    }

    private fun parseStatus(string: String?): Int {
        return when (string) {
            "Releasing" -> SAnime.ONGOING
            "Finished" -> SAnime.COMPLETED
            "Not Yet Released" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun ParseAnime(response: Response): AnimesPage {
        val parsed = json.decodeFromString<SearchResult>(response.body!!.string())
        val titleStyle = preferences.getString("preferred_title_style", "romaji")!!

        val animeList = parsed.data.shows.edges.map { ani ->
            SAnime.create().apply {
                title = when (titleStyle) {
                    "romaji" -> ani.name
                    "eng" -> ani.englishName ?: ani.name
                    else -> ani.nativeName ?: ani.name
                }
                thumbnail_url = ani.thumbnail
                url = ani._id
            }
        }

        return AnimesPage(animeList, animeList.size == 26)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val serverPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred Video Server"
            entries = arrayOf("Site Default", "Ac", "Luf-mp4", "Vid-mp4", "Yt-mp4", "Ok.ru", "Mp4upload", "Sl-mp4", "Uv-mp4", "S-mp4", "Ac-Hls", "Default")
            entryValues = arrayOf("site_default", "ac", "luf-mp4", "vid-mp4", "yt-mp4", "okru", "mp4upload", "sl-mp4", "uv-mp4", "s-mp4", "ac-hls", "default")
            setDefaultValue("site_default")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val hostSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Enable/Disable Hosts"
            entries = arrayOf("Default", "Ac", "Luf-mp4", "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls")
            entryValues = arrayOf("default", "ac", "luf-mp4", "si-hls", "s-mp4", "ac-hls", "uv-mp4", "pn-hls")
            setDefaultValue(setOf("default", "ac", "luf-mp4", "si-hls", "s-mp4", "ac-hls"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }

        val altHostSelection = MultiSelectListPreference(screen.context).apply {
            key = "alt_hoster_selection"
            title = "Enable/Disable Alternative Hosts"
            entries = arrayOf("Direct Player", "Vidstreaming/Gogo", "Ok.ru", "Mp4upload.com", "Streamlare.com", "StreamSB", "Doodstream")
            entryValues = arrayOf("player", "vidstreaming", "okru", "mp4upload", "streamlare", "streamsb", "doodstream")
            setDefaultValue(setOf("player", "vidstreaming", "okru", "mp4upload", "streamlare", "doodstream"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }

        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val titleStylePref = ListPreference(screen.context).apply {
            key = "preferred_title_style"
            title = "Preferred Title Style"
            entries = arrayOf("Romaji", "English", "Native")
            entryValues = arrayOf("romaji", "eng", "native")
            setDefaultValue("romaji")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Prefer subs or dubs?"
            entries = arrayOf("Subs", "Dubs")
            entryValues = arrayOf("sub", "dub")
            setDefaultValue("sub")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(serverPref)
        screen.addPreference(hostSelection)
        screen.addPreference(altHostSelection)
        screen.addPreference(videoQualityPref)
        screen.addPreference(titleStylePref)
        screen.addPreference(subPref)
    }
}
