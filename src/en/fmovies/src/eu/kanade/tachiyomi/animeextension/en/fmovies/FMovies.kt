package eu.kanade.tachiyomi.animeextension.en.fmovies

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.vidsrcextractor.VidsrcExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class FMovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "FMovies"

    override val baseUrl = "https://fmovies24.to"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val utils by lazy { FmoviesUtils(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/trending${page.toPageQuery()}", headers)

    override fun popularAnimeSelector(): String = "div.items > div.item"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        element.selectFirst("div.meta a")!!.let { a ->
            title = a.text()
            setUrlWithoutDomain(a.attr("abs:href"))
        }

        thumbnail_url = element.select("div.poster img").attr("data-src")
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination > li.active + li"

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/filter?keyword=&sort=recently_updated${page.toPageQuery(false)}", headers)

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = FMoviesFilters.getSearchParameters(filters)

        return GET("$baseUrl/filter?keyword=$query${params.filter}${page.toPageQuery(false)}", headers)
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = FMoviesFilters.FILTER_LIST

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val info = document.selectFirst("section#w-info > div.info")!!
        val detail = info.selectFirst("div.detail")

        val descElement = info.selectFirst("div.description")
        val desc = descElement?.selectFirst("div[data-name=full]")?.ownText() ?: descElement?.ownText() ?: ""
        val extraInfo = detail?.select("> div")?.joinToString("\n") { it.text() } ?: ""

        val mediaTitle = info.selectFirst("h1.name")!!.text()
        val mediaDetail = utils.getDetail(mediaTitle)

        return SAnime.create().apply {
            title = mediaTitle
            status = when (mediaDetail?.status) {
                "Ended", "Released" -> SAnime.COMPLETED
                "In Production" -> SAnime.LICENSED
                "Canceled" -> SAnime.CANCELLED
                "Returning Series" -> {
                    mediaDetail.nextEpisode?.let { SAnime.ONGOING } ?: SAnime.ON_HIATUS
                }
                else -> SAnime.UNKNOWN
            }
            thumbnail_url = document.selectFirst("section#w-info > div.poster img")!!.attr("src")
            description = buildString {
                appendLine(desc.ifBlank { mediaDetail?.overview })
                appendLine()
                mediaDetail?.nextEpisode?.let {
                    appendLine("Next: Ep ${it.epNumber} - ${it.name}")
                    appendLine("Air Date: ${it.airDate}")
                    appendLine()
                }
                appendLine(extraInfo)
            }
            genre = detail?.let { dtl ->
                dtl.select("> div:has(> div:contains(Genre:)) span").joinToString { it.text() }
            }
            author = detail?.let { dtl ->
                dtl.select("> div:has(> div:contains(Production:)) span").joinToString { it.text() }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val id = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
            .selectFirst("div[data-id]")!!.attr("data-id")

        val vrf = utils.vrfEncrypt(id)
        val vrfHeaders = headers.newBuilder().apply {
            add("Accept", "application/json, text/javascript, */*; q=0.01")
            add("Host", baseUrl.toHttpUrl().host)
            add("Referer", baseUrl + anime.url)
            add("X-Requested-With", "XMLHttpRequest")
        }.build()

        return GET("$baseUrl/ajax/episode/list/$id?vrf=$vrf", headers = vrfHeaders)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(
            response.parseAs<AjaxResponse>().result,
        )
        val episodeList = mutableListOf<SEpisode>()
        val seasons = document.select("div.body > ul.episodes")
        seasons.forEach { season ->
            val seasonPrefix = if (seasons.size > 1) {
                "Season ${season.attr("data-season")} "
            } else {
                ""
            }

            season.select("li").forEach { ep ->
                episodeList.add(
                    SEpisode.create().apply {
                        name = "$seasonPrefix${ep.text().trim()}".replace("Episode ", "Ep. ")

                        ep.selectFirst("a")!!.let { a ->
                            episode_number = a.attr("data-num").toFloatOrNull() ?: 0F
                            url = json.encodeToString(
                                EpisodeInfo(
                                    id = a.attr("data-id"),
                                    url = "$baseUrl${a.attr("href")}",
                                ),
                            )
                        }
                    },
                )
            }
        }

        return episodeList.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return client.newCall(videoListRequest(episode))
            .awaitSuccess()
            .let { response ->
                videoListParse(response, episode).sort()
            }
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val data = json.decodeFromString<EpisodeInfo>(episode.url)
        val vrf = utils.vrfEncrypt(data.id)

        val vrfHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Referer", data.url)
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        return GET("$baseUrl/ajax/server/list/${data.id}?vrf=$vrf", headers = vrfHeaders)
    }

    private val vidsrcExtractor by lazy { VidsrcExtractor(client, headers) }
    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val streamtapeExtractor by lazy { StreamTapeExtractor(client) }

    private suspend fun videoListParse(response: Response, episode: SEpisode): List<Video> {
        val data = json.decodeFromString<EpisodeInfo>(episode.url)
        val document = Jsoup.parse(
            response.parseAs<AjaxResponse>().result,
        )
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        return document.select("ul.servers > li.server").parallelCatchingFlatMap { server ->
            val name = server.text().trim()
            if (!hosterSelection.contains(name)) return@parallelCatchingFlatMap emptyList()

            // Get decrypted url
            val vrf = utils.vrfEncrypt(server.attr("data-link-id"))

            val vrfHeaders = headers.newBuilder()
                .add("Accept", "application/json, text/javascript, */*; q=0.01")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", data.url)
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            val encrypted = client.newCall(
                GET("$baseUrl/ajax/server/${server.attr("data-link-id")}?vrf=$vrf", headers = vrfHeaders),
            ).await().parseAs<AjaxServerResponse>().result.url

            val decrypted = utils.vrfDecrypt(encrypted)
            when (name) {
                "Vidplay", "MyCloud" -> {
                    val subs = client.newCall(
                        GET("$baseUrl/ajax/episode/subtitles/${data.id}"),
                    ).execute().toTracks()
                    vidsrcExtractor.videosFromUrl(decrypted, name, subtitleList = subs)
                }
                "Filemoon" -> filemoonExtractor.videosFromUrl(decrypted, headers = headers)
                "Streamtape" -> {
                    val subtitleList = decrypted.toHttpUrl().queryParameter("sub.info")?.let {
                        client.newCall(GET(it, headers)).await().toTracks()
                    } ?: emptyList()

                    streamtapeExtractor.videoFromUrl(decrypted, subtitleList = subtitleList)?.let(::listOf) ?: emptyList()
                }
                else -> emptyList()
            }
        }
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun Int.toPageQuery(first: Boolean = true): String {
        return if (this == 1) "" else "${if (first) "?" else "&"}page=$this"
    }

    private fun Response.toTracks(): List<Track> = parseAs<List<FMoviesSubs>>()
        .map { t ->
            Track(t.file, t.label)
        }

    companion object {
        private val HOSTERS = arrayOf(
            "Vidplay",
            "MyCloud",
            "Filemoon",
            "Streamtape",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Vidplay"

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private val PREF_HOSTER_DEFAULT = setOf("Vidplay", "Filemoon")
    }
    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = "Enable/Disable Hosts"
            entries = HOSTERS
            entryValues = HOSTERS
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
