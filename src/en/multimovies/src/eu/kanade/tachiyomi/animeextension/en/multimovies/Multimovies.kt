package eu.kanade.tachiyomi.animeextension.en.multimovies

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.multimovies.extractors.AutoEmbedExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class Multimovies : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Multimovies"

    override val baseUrl = "https://multimovies.fun"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div.content > div.items > article.item"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/genre/anime-series/page/$page/")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.selectFirst("div h3 a")!!.attr("href").toHttpUrl().encodedPath)
        anime.title = element.selectFirst("div h3 a")!!.text()
        anime.thumbnail_url = element.selectFirst("div.poster img")!!.attr("src")
        if (!anime.thumbnail_url.toString().startsWith("https://")) {
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        }

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination span.current ~ a"

    // =============================== Latest ===============================

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // =============================== Search ===============================

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = if (response.request.url.encodedPath.startsWith("/genre/")) {
            document.select(searchGenreAnimeSelector()).map { element ->
                searchGenreAnimeFromElement(element)
            }
        } else {
            document.select(searchAnimeSelector()).map { element ->
                searchAnimeFromElement(element)
            }
        }

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeSelector(): String = "div.search-page > div.result-item"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("div.details > div.title a").attr("href").toHttpUrl().encodedPath)
        anime.title = element.select("div.details > div.title a").text()
        anime.thumbnail_url = element.select("div.image img").attr("src")
        if (!anime.thumbnail_url.toString().startsWith("https://")) {
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        }

        return anime
    }

    private fun searchGenreAnimeSelector(): String = "div.items > article.item"

    private fun searchGenreAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(element.select("div.data h3 a").attr("href").toHttpUrl().encodedPath)
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("src")
        if (!anime.thumbnail_url.toString().startsWith("https://")) {
            anime.thumbnail_url = element.select("div.poster img").attr("data-src")
        }

        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.pagination span.current ~ a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotBlank()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            val filterList = if (filters.isEmpty()) getFilterList() else filters
            val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
            val streamingFilter = filterList.find { it is StreamingFilter } as StreamingFilter
            val ficUniFilter = filterList.find { it is FicUniFilter } as FicUniFilter
            val channelFilter = filterList.find { it is ChannelFilter } as ChannelFilter

            when {
                genreFilter.state != 0 -> GET("$baseUrl/genre/${genreFilter.toUriPart()}/page/$page", headers)
                streamingFilter.state != 0 -> GET("$baseUrl/genre/${streamingFilter.toUriPart()}/page/$page", headers)
                ficUniFilter.state != 0 -> GET("$baseUrl/genre/${ficUniFilter.toUriPart()}/page/$page", headers)
                channelFilter.state != 0 -> GET("$baseUrl/genre/${channelFilter.toUriPart()}/page/$page", headers)
                else -> popularAnimeRequest(page)
            }
        }
    }

    // ============================== Filters ===============================

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        GenreFilter(getGenreList()),
        StreamingFilter(getStreamingList()),
        FicUniFilter(getFictionalUniverseList()),
        ChannelFilter(getChannelList()),
    )

    private class GenreFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Genres", vals)
    private class StreamingFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Streaming service", vals)
    private class FicUniFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Fictional universe", vals)
    private class ChannelFilter(vals: Array<Pair<String, String>>) : UriPartFilter("Channel", vals)

    private fun getGenreList() = arrayOf(
        Pair("<select>", ""),
        Pair("Action", "action"),
        Pair("Adventure", "adventure"),
        Pair("Animation", "animation"),
        Pair("Anime Movies", "anime-movies"),
        Pair("Anime Series", "anime-series"),
        Pair("Crime", "crime"),
        Pair("Comedy", "comedy"),
        Pair("Drama", "drama"),
        Pair("Fantasy", "fantasy"),
        Pair("Horror", "horror"),
        Pair("Family", "family"),
        Pair("History", "history"),
        Pair("Romance", "romance"),
        Pair("Science Fiction", "science-fiction"),
        Pair("Thriller", "thriller"),
    )

    private fun getStreamingList() = arrayOf(
        Pair("<select>", ""),
        Pair("Amazon Prime", "amazone-prime"),
        Pair("Apple TV +", "apple-tv"),
        Pair("Disney+Hotstar", "disneyhotstar"),
        Pair("HBO MAX", "hbo-max"),
        Pair("Hulu", "hulu"),
        Pair("Netflix", "netflix"),
        Pair("Sony Liv", "sony-liv"),
        Pair("Voot", "voot"),
    )

    private fun getFictionalUniverseList() = arrayOf(
        Pair("<select>", ""),
        Pair("DC Universe", "dc-universe"),
        Pair("Fast and Furious", "fast-and-furious"),
        Pair("Harry Potter", "harry"),
        Pair("Jurassic Park", "jurassic-park"),
        Pair("Marvel Cinematic", "marvel"),
        Pair("Matrix", "matrix"),
        Pair("Mission Impossible", "mission-impossible"),
        Pair("Pirates of the Caribbean", "pirates"),
        Pair("Resident Evil", "resident-evil"),
        Pair("Star Wars", "star-wars"),
        Pair("Terminator", "terminator"),
        Pair("Transformers", "transformer"),
        Pair("Wrong Turn", "wrong-turn"),
        Pair("X-Men", "xmen"),
    )

    private fun getChannelList() = arrayOf(
        Pair("<select>", ""),
        Pair("Cartoon Network", "cartoon-network"),
        Pair("Disney Channel", "disney"),
        Pair("Disney XD", "disney-xd"),
        Pair("Hungama", "hungama"),
        Pair("Nick", "nick"),
        Pair("Pogo", "pogo"),
    )

    open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.sheader > div.data > h1").text()
        anime.genre = document.select("div.sgeneros a").eachText().joinToString(separator = ", ")
        anime.description = document.selectFirst("div#info p")!!.text()
        return anime
    }

    // ============================== Episodes ==============================

    override fun episodeListSelector() = throw Exception("Not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        if (response.request.url.encodedPath.startsWith("/movies/")) {
            val episode = SEpisode.create()

            episode.name = document.select("div.data > h1").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.encodedPath)
            episodeList.add(episode)
        } else {
            var counter = 1
            for (season in document.select("div#seasons > div")) {
                val seasonList = mutableListOf<SEpisode>()
                for (ep in season.select("ul > li")) {
                    if (ep.childrenSize() > 0) {
                        val episode = SEpisode.create()

                        episode.name = "Season ${ep.selectFirst("div.numerando")!!.ownText().substringAfter("E")} - ${ep.selectFirst("a[href]")!!.ownText()}"
                        episode.episode_number = counter.toFloat()
                        episode.setUrlWithoutDomain(ep.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath)

                        if (ep.selectFirst("p:contains(Filler)") != null) episode.scanlator = "Filler Episode"

                        seasonList.add(episode)

                        counter++
                    }
                }
                episodeList.addAll(seasonList)
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val reqUrL = response.request.url

        document.select("ul.ajax_mode > li").forEach {
            if (it.attr("data-nume") == "trailer") return@forEach

            val postHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Conent-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .add("Host", reqUrL.host)
                .add("Origin", "https://${reqUrL.host}")
                .add("Referer", reqUrL.toString())
                .add("X-Requested-With", "XMLHttpRequest")
                .build()

            val postData = "action=doo_player_ajax&post=${it.attr("data-post")}&nume=${it.attr("data-nume")}&type=${it.attr("data-type")}".toRequestBody("application/x-www-form-urlencoded".toMediaType())
            val embedded = client.newCall(
                POST("$baseUrl/wp-admin/admin-ajax.php", body = postData, headers = postHeaders),
            ).execute().body.string()

            val parsed = json.decodeFromString<Embed>(embedded)
            val url = if (parsed.embed_url.contains("<iframe", true)) {
                Jsoup.parse(parsed.embed_url).selectFirst("iframe")!!.attr("src")
            } else {
                parsed.embed_url
            }

            when {
                url.contains("sbembed.com") || url.contains("sbembed1.com") || url.contains("sbplay.org") ||
                    url.contains("sbvideo.net") || url.contains("streamsb.net") || url.contains("sbplay.one") ||
                    url.contains("cloudemb.com") || url.contains("playersb.com") || url.contains("tubesb.com") ||
                    url.contains("sbplay1.com") || url.contains("embedsb.com") || url.contains("watchsb.com") ||
                    url.contains("sbplay2.com") || url.contains("japopav.tv") || url.contains("viewsb.com") ||
                    url.contains("sbfast") || url.contains("sbfull.com") || url.contains("javplaya.com") ||
                    url.contains("ssbstream.net") || url.contains("p1ayerjavseen.com") || url.contains("sbthe.com") ||
                    url.contains("sbchill.com") || url.contains("sblongvu.com") || url.contains("sbanh.com") ||
                    url.contains("sblanh.com") || url.contains("sbhight.com") || url.contains("sbbrisk.com") ||
                    url.contains("sbspeed.com") || url.contains("multimovies.website") -> {
                    videoList.addAll(
                        StreamSBExtractor(client).videosFromUrl(url, headers = headers, prefix = "[multimovies]"),
                    )
                }
                url.contains("autoembed.to") || url.contains("2embed.to") -> {
                    val newHeaders = headers.newBuilder()
                        .add("Referer", url).build()
                    videoList.addAll(
                        AutoEmbedExtractor(client).videosFromUrl(url, headers = newHeaders),
                    )
                }
            }
        }

        return videoList.sort()
    }

    override fun videoListSelector() = throw Exception("Not used")

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    // ============================= Utilities ==============================

    @Serializable
    data class Embed(
        val embed_url: String,
    )

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "multimovies")!!
        val qualityRegex = """(\d+)p""".toRegex()

        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue("1080")
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
            title = "Preferred Server"
            entries = arrayOf(
                "multimovies",
                "2Embed Vidcloud",
                "2Embed Voe",
                "2Embed Streamlare",
                "2Embed MixDrop",
                "Gomo Dood",
            )
            entryValues = arrayOf(
                "multimovies",
                "[2embed] server vidcloud",
                "[2embed] server voe",
                "[2embed] server streamlare",
                "[2embed] server mixdrop",
                "[gomostream] dood",
            )
            setDefaultValue("multimovies")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(serverPref)
        screen.addPreference(videoQualityPref)
    }
}
