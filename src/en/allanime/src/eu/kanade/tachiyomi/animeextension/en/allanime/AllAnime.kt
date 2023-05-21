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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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

    // allanime.to
    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://api.allanime.to")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val popularQuery = """
        query(
                ${'$'}type: VaildPopularTypeEnumType!
                ${'$'}size: Int!
                ${'$'}page: Int
                ${'$'}dateRange: Int
            ) {
            queryPopular(
                type: ${'$'}type
                size: ${'$'}size
                dateRange: ${'$'}dateRange
                page: ${'$'}page
            ) {
                total
                recommendations {
                    anyCard {
                        _id
                        name
                        thumbnail
                        englishName
                        nativeName
                        slugTime
                    }
                }
            }
        }
    """.trimIndent().trim()

    private val searchQuery = """
        query(
                ${'$'}search: SearchInput
                ${'$'}limit: Int
                ${'$'}page: Int
                ${'$'}translationType: VaildTranslationTypeEnumType
                ${'$'}countryOrigin: VaildCountryOriginEnumType
            ) {
            shows(
                search: ${'$'}search
                limit: ${'$'}limit
                page: ${'$'}page
                translationType: ${'$'}translationType
                countryOrigin: ${'$'}countryOrigin
            ) {
                pageInfo {
                    total
                }
                edges {
                    _id
                    name
                    thumbnail
                    englishName
                    nativeName
                    slugTime
                }
            }
        }
    """.trimIndent().trim()

    private val detailsQuery = """
        query (${'$'}_id: String!) {
            show(
                _id: ${'$'}_id
            ) {
                thumbnail
                description
                type
                season
                score
                genres
                status
                studios
            }
        }
    """.trimIndent().trim()

    private val episodesQuery = """
        query (${'$'}_id: String!) {
            show(
                _id: ${'$'}_id
            ) {
                _id
                availableEpisodesDetail
            }
        }
    """.trimIndent().trim()

    private val streamQuery = """
        query(
                ${'$'}showId: String!,
                ${'$'}translationType: VaildTranslationTypeEnumType!,
                ${'$'}episodeString: String!
            ) {
            episode(
                showId: ${'$'}showId
                translationType: ${'$'}translationType
                episodeString: ${'$'}episodeString
            ) {
                sourceUrls
            }
        }
    """.trimIndent().trim()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val variables = """{"type":"anime","size":26,"dateRange":7,"page":$page}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$popularQuery", headers = headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val parsed = json.decodeFromString<PopularResult>(response.body.string())
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
                        url = "${it.anyCard._id}<&sep>${it.anyCard.slugTime ?: ""}<&sep>${it.anyCard.name.slugify()}"
                    },
                )
            }
        }

        return AnimesPage(animeList, animeList.size == 26)
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request {
        val variables = """{"search":{"allowAdult":false,"allowUnknown":false},"limit":26,"page":$page,"translationType":"${preferences.getString("preferred_sub", "sub")!!}","countryOrigin":"ALL"}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$searchQuery", headers = headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        return parseAnime(response)
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
            val headers = headers.newBuilder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                .build()
            GET("$baseUrl/allanimeapi?variables=$variables&query=$searchQuery", headers = headers)
        } else {
            val seasonString = if (filters.season == "all") "" else ""","season":"${filters.season}""""
            val yearString = if (filters.releaseYear == "all") "" else ""","year":${filters.releaseYear}"""
            val genresString = if (filters.genres == "all") "" else ""","genres":${filters.genres},"excludeGenres":[]"""
            val typesString = if (filters.types == "all") "" else ""","types":${filters.types}"""
            val sortByString = if (filters.sortBy == "update") "" else ""","sortBy":"${filters.sortBy}""""

            var variables = """{"search":{"allowAdult":false,"allowUnknown":false$seasonString$yearString$genresString$typesString$sortByString"""
            variables += """},"limit":26,"page":$page,"translationType":"${preferences.getString("preferred_sub", "sub")!!}","countryOrigin":"${filters.origin}"}"""

            val headers = headers.newBuilder()
                .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
                .build()
            GET("$baseUrl/allanimeapi?variables=$variables&query=$searchQuery", headers = headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return parseAnime(response)
    }

    override fun getFilterList(): AnimeFilterList = AllAnimeFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw Exception("Not used")

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequestInternal(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response, anime).apply { initialized = true }
            }
    }

    private fun animeDetailsRequestInternal(anime: SAnime): Request {
        val variables = """{"_id":"${anime.url.split("<&sep>").first()}"}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$detailsQuery", headers = headers)
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        val (id, time, slug) = anime.url.split("<&sep>")
        val slugTime = if (time.isNotEmpty()) {
            "-st-$time"
        } else {
            time
        }
        val siteUrl = preferences.getString("preferred_site_domain", "https://allanime.to")!!
        return GET("$siteUrl/anime/$id/$slug$slugTime")
    }

    private fun animeDetailsParse(response: Response, animeOld: SAnime): SAnime {
        val show = json.decodeFromString<DetailsResult>(response.body.string()).data.show
        val anime = SAnime.create()

        anime.title = animeOld.title

        anime.description = Jsoup.parse(
            show.description?.replace("<br>", "br2n") ?: "",
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
        val variables = """{"_id":"${anime.url.split("<&sep>").first()}"}"""
        val headers = headers.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0")
            .build()
        return GET("$baseUrl/allanimeapi?variables=$variables&query=$episodesQuery", headers = headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val medias = json.decodeFromString<SeriesResult>(response.body.string())
        val episodeList = mutableListOf<SEpisode>()

        val subOrDub = preferences.getString("preferred_sub", "sub")!!

        if (subOrDub == "sub") {
            for (ep in medias.data.show.availableEpisodesDetail.sub!!) {
                val episode = SEpisode.create()
                episode.episode_number = ep.toFloatOrNull() ?: 0F
                val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")
                episode.name = "Episode $numName (sub)"

                val variables = """{"showId":"${medias.data.show._id}","translationType":"sub","episodeString":"$ep"}"""
                episode.setUrlWithoutDomain("/allanimeapi?variables=$variables&query=$streamQuery")
                episodeList.add(episode)
            }
        } else {
            for (ep in medias.data.show.availableEpisodesDetail.dub!!) {
                val episode = SEpisode.create()
                episode.episode_number = ep.toFloatOrNull() ?: 0F
                val numName = ep.toIntOrNull() ?: (ep.toFloatOrNull() ?: "1")
                episode.name = "Episode $numName (dub)"

                val variables = """{"showId":"${medias.data.show._id}","translationType":"dub","episodeString":"$ep"}"""
                episode.setUrlWithoutDomain("/allanimeapi?variables=$variables&query=$streamQuery")
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
        val body = response.body.string()

        val videoJson = json.decodeFromString<EpisodeResult>(body)
        val videoList = mutableListOf<Pair<Video, Float>>()
        val serverList = mutableListOf<Server>()

        val altHosterSelection = preferences.getStringSet(
            "alt_hoster_selection",
            setOf("player", "vidstreaming", "okru", "mp4upload", "streamlare", "doodstream"),
        )!!

        val hosterSelection = preferences.getStringSet(
            "hoster_selection",
            setOf("default", "ac", "luf-mp4", "si-hls", "s-mp4", "ac-hls"),
        )!!

        for (video in videoJson.data.episode.sourceUrls) {
            when {
                video.sourceUrl.startsWith("/apivtwo/") && (
                    (hosterSelection.contains("default") && video.sourceName.lowercase().contains("default")) ||
                        (hosterSelection.contains("ac") && video.sourceName.lowercase().contains("ac")) ||
                        (hosterSelection.contains("ak") && video.sourceName.lowercase().contains("ak")) ||
                        (hosterSelection.contains("kir") && video.sourceName.lowercase().contains("kir")) ||
                        (hosterSelection.contains("luf-mp4") && video.sourceName.lowercase().contains("luf-mp4")) ||
                        (hosterSelection.contains("si-hls") && video.sourceName.lowercase().contains("si-hls")) ||
                        (hosterSelection.contains("s-mp4") && video.sourceName.lowercase().contains("s-mp4")) ||
                        (hosterSelection.contains("ac-hls") && video.sourceName.lowercase().contains("ac-hls")) ||
                        (hosterSelection.contains("uv-mp4") && video.sourceName.lowercase().contains("uv-mp4")) ||
                        (hosterSelection.contains("pn-hls") && video.sourceName.lowercase().contains("pn-hls"))
                    ) -> {
                    serverList.add(Server(video.sourceUrl, "internal ${video.sourceName}", video.priority))
                }
                altHosterSelection.contains("player") && video.type == "player" -> {
                    serverList.add(Server(video.sourceUrl, "player", video.priority))
                }
                altHosterSelection.contains("streamsb") && video.sourceUrl.contains("streamsb") -> {
                    serverList.add(Server(video.sourceUrl, "streamsb", video.priority))
                }
                altHosterSelection.contains("vidstreaming") && (
                    video.sourceUrl.contains("vidstreaming") || video.sourceUrl.contains("https://gogo") ||
                        video.sourceUrl.contains("playgo1.cc")
                    ) -> {
                    serverList.add(Server(video.sourceUrl, "gogo", video.priority))
                }
                altHosterSelection.contains("doodstream") && video.sourceUrl.contains("dood") -> {
                    serverList.add(Server(video.sourceUrl, "dood", video.priority))
                }
                altHosterSelection.contains("okru") && video.sourceUrl.contains("ok.ru") -> {
                    serverList.add(Server(video.sourceUrl, "okru", video.priority))
                }
                altHosterSelection.contains("mp4upload") && video.sourceUrl.contains("mp4upload.com") -> {
                    serverList.add(Server(video.sourceUrl, "mp4upload", video.priority))
                }
                altHosterSelection.contains("streamlare") && video.sourceUrl.contains("streamlare.com") -> {
                    serverList.add(Server(video.sourceUrl, "streamlare", video.priority))
                }
            }
        }

        videoList.addAll(
            serverList.parallelMap { server ->
                runCatching {
                    val sName = server.sourceName
                    when {
                        sName.startsWith("internal ") -> {
                            val extractor = AllAnimeExtractor(client)
                            val videos = runCatching {
                                extractor.videoFromUrl(server.sourceUrl, server.sourceName)
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        sName == "player" -> {
                            listOf(
                                Pair(
                                    Video(
                                        server.sourceUrl,
                                        "Original (player ${server.sourceName})",
                                        server.sourceUrl,
                                    ),
                                    server.priority,
                                ),
                            )
                        }
                        sName == "streamsb" -> {
                            val extractor = StreamSBExtractor(client)
                            val videos = runCatching {
                                extractor.videosFromUrl(server.sourceUrl, headers)
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        sName == "gogo" -> {
                            val extractor = VidstreamingExtractor(client, json)
                            val videos = runCatching {
                                extractor.videosFromUrl(server.sourceUrl.replace(Regex("^//"), "https://"))
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        sName == "dood" -> {
                            val extractor = DoodExtractor(client)
                            val videos = runCatching {
                                extractor.videosFromUrl(server.sourceUrl)
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        sName == "okru" -> {
                            val extractor = OkruExtractor(client)
                            val videos = runCatching {
                                extractor.videosFromUrl(server.sourceUrl)
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        sName == "mp4upload" -> {
                            val headers = headers.newBuilder().set("referer", "https://mp4upload.com/").build()
                            val videos = runCatching {
                                Mp4uploadExtractor(client).getVideoFromUrl(server.sourceUrl, headers)
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        sName == "streamlare" -> {
                            val extractor = StreamlareExtractor(client)
                            val videos = runCatching {
                                extractor.videosFromUrl(server.sourceUrl)
                            }.getOrNull() ?: emptyList()
                            videos.map {
                                Pair(it, server.priority)
                            }
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return prioritySort(videoList)
    }

    // ============================= Utilities ==============================

    private fun prioritySort(pList: List<Pair<Video, Float>>): List<Video> {
        val prefServer = preferences.getString("preferred_server", "site_default")!!
        val quality = preferences.getString("preferred_quality", "1080")!!
        val subOrDub = preferences.getString("preferred_sub", "sub")!!

        return pList.sortedWith(
            compareBy(
                { if (prefServer == "site_default") it.second else it.first.quality.contains(prefServer, true) },
                { it.first.quality.contains(quality, true) },
                { it.first.quality.contains(subOrDub, true) },
            ),
        ).reversed().map { t -> t.first }
    }

    data class Server(
        val sourceUrl: String,
        val sourceName: String,
        val priority: Float,
    )

    private fun parseStatus(string: String?): Int {
        return when (string) {
            "Releasing" -> SAnime.ONGOING
            "Finished" -> SAnime.COMPLETED
            "Not Yet Released" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    private fun String.slugify(): String {
        return this.replace("""[^a-zA-Z0-9]""".toRegex(), "-")
            .replace("""-{2,}""".toRegex(), "-")
            .lowercase()
    }

    private fun parseAnime(response: Response): AnimesPage {
        val parsed = json.decodeFromString<SearchResult>(response.body.string())
        val titleStyle = preferences.getString("preferred_title_style", "romaji")!!

        val animeList = parsed.data.shows.edges.map { ani ->
            SAnime.create().apply {
                title = when (titleStyle) {
                    "romaji" -> ani.name
                    "eng" -> ani.englishName ?: ani.name
                    else -> ani.nativeName ?: ani.name
                }
                thumbnail_url = ani.thumbnail
                url = "${ani._id}<&sep>${ani.slugTime ?: ""}<&sep>${ani.name.slugify()}"
            }
        }

        return AnimesPage(animeList, animeList.size == 26)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainSitePref = ListPreference(screen.context).apply {
            key = "preferred_site_domain"
            title = "Preferred domain for site (requires app restart)"
            entries = arrayOf("allanime.to", "allanime.co")
            entryValues = arrayOf("https://allanime.to", "https://allanime.co")
            setDefaultValue("https://allanime.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("api.allanime.to", "api.allanime.co")
            entryValues = arrayOf("https://api.allanime.to", "https://api.allanime.co")
            setDefaultValue("https://api.allanime.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

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
            entries = arrayOf("Default", "Ac", "Ak", "Kir", "Luf-mp4", "Si-Hls", "S-mp4", "Ac-Hls", "Uv-mp4", "Pn-Hls")
            entryValues = arrayOf("default", "ac", "ak", "kir", "luf-mp4", "si-hls", "s-mp4", "ac-hls", "uv-mp4", "pn-hls")
            setDefaultValue(setOf("default", "ac", "ak", "kir", "luf-mp4", "si-hls", "s-mp4", "ac-hls"))

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
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "80p", "1440p (okru only)", "2160p (okru only)")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "80", "1440", "2160")
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

        screen.addPreference(domainSitePref)
        screen.addPreference(domainPref)
        screen.addPreference(serverPref)
        screen.addPreference(hostSelection)
        screen.addPreference(altHostSelection)
        screen.addPreference(videoQualityPref)
        screen.addPreference(titleStylePref)
        screen.addPreference(subPref)
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
