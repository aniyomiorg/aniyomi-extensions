package eu.kanade.tachiyomi.animeextension.fr.frenchanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.SibnetExtractor
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.StreamHideExtractor
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.StreamVidExtractor
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.UpstreamExtractor
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.UqloadExtractor
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.VidoExtractor
import eu.kanade.tachiyomi.animeextension.fr.frenchanime.extractors.VudeoExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class FrenchAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "French Anime"

    override val baseUrl = "https://french-anime.com"

    override val lang = "fr"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes-vostfr/page/$page/")

    override fun popularAnimeSelector(): String = "div#dle-content > div.mov"

    override fun popularAnimeNextPageSelector(): String = "span.navigation > span:not(.nav_ext) + a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""
            title = "${element.selectFirst("a[href]")!!.text()} ${element.selectFirst("span.block-sai")?.text() ?: ""}"
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not Used")

    override fun latestUpdatesSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not Used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not Used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

        return when {
            query.isNotBlank() -> {
                if (query.length < 4) throw Exception("La recherche est suspendue! La chaîne de recherche est vide ou contient moins de 4 caractères.")

                val postHeaders = headers.newBuilder()
                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .add("Content-Type", "application/x-www-form-urlencoded")
                    .add("Host", baseUrl.toHttpUrl().host)
                    .add("Origin", baseUrl)
                    .add("Referer", "$baseUrl/")
                    .build()

                val cleanQuery = query.replace(" ", "+")
                if (page == 1) {
                    val postBody = "do=search&subaction=search&story=$cleanQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    POST("$baseUrl/", body = postBody, headers = postHeaders)
                } else {
                    val postBody = "do=search&subaction=search&search_start=$page&full_search=0&result_from=11&story=$cleanQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    POST("$baseUrl/index.php?do=search", body = postBody, headers = postHeaders)
                }
            }
            genreFilter.state != 0 -> {
                GET("$baseUrl${genreFilter.toUriPart()}page/$page/")
            }
            subPageFilter.state != 0 -> {
                GET("$baseUrl${subPageFilter.toUriPart()}page/$page/")
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La recherche de texte ignore les filtres"),
        SubPageFilter(),
        GenreFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Catégories",
        arrayOf(
            Pair("<Sélectionner>", ""),
            Pair("Animes VF", "/animes-vf/"),
            Pair("Animes VOSTFR", "/animes-vostfr/"),
            Pair("Films VF et VOSTFR", "/films-vf-vostfr/"),
        ),
    )

    private class GenreFilter : UriPartFilter(
        "Animes par genre",
        arrayOf(
            Pair("<Sélectionner>", ""),
            Pair("Action", "/genre/action/"),
            Pair("Aventure", "/genre/aventure/"),
            Pair("Arts martiaux", "/genre/arts-martiaux/"),
            Pair("Combat", "/genre/combat/"),
            Pair("Comédie", "/genre/comedie/"),
            Pair("Drame", "/genre/drame/"),
            Pair("Epouvante", "/genre/epouvante/"),
            Pair("Fantastique", "/genre/fantastique/"),
            Pair("Fantasy", "/genre/fantasy/"),
            Pair("Mystère", "/genre/mystere/"),
            Pair("Romance", "/genre/romance/"),
            Pair("Shonen", "/genre/shonen/"),
            Pair("Surnaturel", "/genre/surnaturel/"),
            Pair("Sci-Fi", "/genre/sci-fi/"),
            Pair("School life", "/genre/school-life/"),
            Pair("Ninja", "/genre/ninja/"),
            Pair("Seinen", "/genre/seinen/"),
            Pair("Horreur", "/genre/horreur/"),
            Pair("Tranche de vie", "/genre/tranchedevie/"),
            Pair("Psychologique", "/genre/psychologique/"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> {
        return client.newCall(animeDetailsRequest(anime))
            .asObservableSuccess()
            .map { response ->
                animeDetailsParse(response, anime).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    private fun animeDetailsParse(response: Response, baseAnime: SAnime): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()

        anime.title = baseAnime.title
        anime.thumbnail_url = baseAnime.thumbnail_url
        anime.description = document.selectFirst("div.mov-desc span[itemprop=description]")?.text() ?: ""
        anime.genre = document.select("div.mov-desc span[itemprop=genre] a").joinToString(", ") { it.text() }
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        val epsData = document.selectFirst("div.eps")?.text() ?: return emptyList()
        epsData.split(" ").filter { it.isNotBlank() }.forEach {
            val data = it.split("!", limit = 2)
            val episode = SEpisode.create()
            episode.episode_number = data[0].toFloatOrNull() ?: 0F
            episode.name = "Episode ${data[0]}"
            episode.url = data[1]
            episodeList.add(episode)
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector(): String = throw Exception("Not used")

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun fetchVideoList(episode: SEpisode): Observable<List<Video>> {
        val videoList = mutableListOf<Video>()

        episode.url.split(",").filter { it.isNotBlank() }.parallelMap { source ->
            runCatching {
                when {
                    source.contains("https://dood") -> {
                        videoList.addAll(
                            DoodExtractor(client).videosFromUrl(source),
                        )
                    }
                    source.contains("https://upstream") -> {
                        videoList.addAll(
                            UpstreamExtractor(client).videosFromUrl(source, headers),
                        )
                    }
                    source.contains("https://vudeo") -> {
                        videoList.addAll(
                            VudeoExtractor(client).videosFromUrl(source),
                        )
                    }
                    source.contains("https://uqload") -> {
                        videoList.addAll(
                            UqloadExtractor(client).videosFromUrl(source, headers),
                        )
                    }
                    source.contains("sbembed.com") || source.contains("sbembed1.com") || source.contains("sbplay.org") ||
                        source.contains("sbvideo.net") || source.contains("streamsb.net") || source.contains("sbplay.one") ||
                        source.contains("cloudemb.com") || source.contains("playersb.com") || source.contains("tubesb.com") ||
                        source.contains("sbplay1.com") || source.contains("embedsb.com") || source.contains("watchsb.com") ||
                        source.contains("sbplay2.com") || source.contains("japopav.tv") || source.contains("viewsb.com") ||
                        source.contains("sbfast") || source.contains("sbfull.com") || source.contains("javplaya.com") ||
                        source.contains("ssbstream.net") || source.contains("p1ayerjavseen.com") || source.contains("sbthe.com") ||
                        source.contains("lvturbo") || source.contains("sbface.com") || source.contains("sblongvu.com") -> {
                        videoList.addAll(
                            StreamSBExtractor(client).videosFromUrl(source, headers),
                        )
                    }
                    source.contains("https://guccihide") || source.contains("https://streamhide") -> {
                        videoList.addAll(
                            StreamHideExtractor(client).videosFromUrl(source, headers),
                        )
                    }
                    source.contains("https://streamvid") -> {
                        videoList.addAll(
                            StreamVidExtractor(client).videosFromUrl(source, headers),
                        )
                    }
                    source.contains("https://vido") -> {
                        videoList.addAll(
                            VidoExtractor(client).videosFromUrl(source, headers),
                        )
                    }
                    source.contains("sibnet") -> {
                        videoList.addAll(
                            SibnetExtractor(client).getVideosFromUrl(source),
                        )
                    }
                    source.contains("ok.ru") -> {
                        videoList.addAll(
                            OkruExtractor(client).videosFromUrl(source),
                        )
                    }
                    else -> {}
                }
            }
        }

        return Observable.just(videoList.sort())
    }

    override fun videoFromElement(element: Element): Video = throw Exception("Not Used")

    override fun videoListSelector(): String = throw Exception("Not Used")

    override fun videoUrlParse(document: Document): String = throw Exception("Not Used")

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720")!!
        val server = preferences.getString("preferred_server", "Upstream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Upstream", "Vido", "StreamVid", "StreamHide", "StreamSB", "Uqload", "Vudeo", "Doodstream", "Sibnet", "Okru")
            entryValues = arrayOf("Upstream", "Vido", "StreamVid", "StreamHide", "StreamSB", "Uqload", "Vudeo", "dood", "sibnet", "okru")
            setDefaultValue("Upstream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }
}
