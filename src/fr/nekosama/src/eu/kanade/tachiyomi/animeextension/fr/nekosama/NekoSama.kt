package eu.kanade.tachiyomi.animeextension.fr.nekosama

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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class NekoSama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Neko-Sama"

    override val baseUrl = "https://neko-sama.fr"

    override val lang = "fr"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.anime"

    override fun popularAnimeRequest(page: Int): Request {
        return if (page > 1) {
            GET("$baseUrl/anime/$page")
        } else {
            GET("$baseUrl/anime/")
        }
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.info a").attr("href"),
        )
        anime.title = element.select("div.info a div").text()
        val thumb1 = element.select("div.cover a div img:not(.placeholder)").attr("data-src")
        val thumb2 = element.select("div.cover a div img:not(.placeholder)").attr("src")
        anime.thumbnail_url = thumb1.ifBlank { thumb2 }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nekosama.pagination a.active ~ a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageBody = response.asJsoup()
        val episodesJson = pageBody.selectFirst("script:containsData(var episodes =)")!!.data()
            .substringAfter("var episodes = ").substringBefore(";")
        val json = json.decodeFromString<List<EpisodesJson>>(episodesJson)

        return json.map {
            SEpisode.create().apply {
                name = try { it.episode!! } catch (e: Exception) { "episode" }
                url = it.url!!.replace("\\", "")

                episode_number = try { it.episode!!.substringAfter(". ").toFloat() } catch (e: Exception) { (0..10).random() }.toFloat()
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        // probably exists a better way to make this idk
        val script = document.selectFirst("script:containsData(var video = [];)")!!.data()

        val firstVideo = script.substringBefore("else {").substringAfter("video[0] = '").substringBefore("'").lowercase()
        val secondVideo = script.substringAfter("else {").substringAfter("video[0] = '").substringBefore("'").lowercase()

        when {
            firstVideo.contains("fusevideo") -> videoList.addAll(extractFuse(firstVideo))
            firstVideo.contains("streamtape") -> StreamTapeExtractor(client).videoFromUrl(firstVideo, "StreamTape")?.let { videoList.add(it) }
            firstVideo.contains("pstream") || firstVideo.contains("veestream") -> videoList.addAll(pstreamExtractor(firstVideo))
        }
        when {
            secondVideo.contains("fusevideo") -> videoList.addAll(extractFuse(secondVideo))
            secondVideo.contains("streamtape") -> StreamTapeExtractor(client).videoFromUrl(secondVideo, "StreamTape")?.let { videoList.add(it) }
            secondVideo.contains("pstream") || secondVideo.contains("veestream") -> videoList.addAll(pstreamExtractor(secondVideo))
        }

        return videoList.sort()
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "Pstream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val typeSearch = when (typeFilter.toUriPart()) {
            "anime" -> "vostfr"
            "anime-vf" -> "vf"
            else -> "vostfr"
        }

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes-search-$typeSearch.json?$query")
            typeFilter.state != 0 || query.isNotBlank() -> when (page) {
                1 -> GET("$baseUrl/${typeFilter.toUriPart()}")
                else -> GET("$baseUrl/${typeFilter.toUriPart()}/$page")
            }
            else -> when (page) {
                1 -> GET("$baseUrl/anime/")
                else -> GET("$baseUrl/anime/page/$page")
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val pageUrl = response.request.url.toString()
        val query = pageUrl.substringAfter("?").lowercase().replace("%20", " ")

        return when {
            pageUrl.contains("animes-search") -> {
                val jsonSearch = json.decodeFromString<List<SearchJson>>(response.asJsoup().body().text())
                val animes = mutableListOf<SAnime>()
                jsonSearch.map {
                    if (it.title!!.lowercase().contains(query)) {
                        val animeResult = SAnime.create().apply {
                            url = it.url!!
                            title = it.title!!
                            thumbnail_url = try {
                                it.url_image
                            } catch (e: Exception) {
                                "$baseUrl/images/default_poster.png"
                            }
                        }
                        animes.add(animeResult)
                    }
                }
                AnimesPage(
                    animes,
                    false,
                )
            }
            else -> {
                AnimesPage(
                    response.asJsoup().select(popularAnimeSelector()).map { popularAnimeFromElement(it) },
                    true,
                )
            }
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("not used")

    override fun searchAnimeSelector(): String = throw Exception("not used")

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.col.offset-lg-3.offset-md-4 h1")!!.ownText()
        var description = document.select("div.synopsis p").text() + "\n\n"

        val scoreElement = document.selectFirst("div#anime-info-list div.item:contains(Score)")!!
        if (scoreElement.ownText().isNotEmpty()) description += "Score moyen: ★${scoreElement.ownText().trim()}"

        val statusElement = document.selectFirst("div#anime-info-list div.item:contains(Status)")!!
        if (statusElement.ownText().isNotEmpty()) description += "\nStatus: ${statusElement.ownText().trim()}"

        val formatElement = document.selectFirst("div#anime-info-list div.item:contains(Format)")!!
        if (formatElement.ownText().isNotEmpty()) description += "\nFormat: ${formatElement.ownText().trim()}"

        val diffusionElement = document.selectFirst("div#anime-info-list div.item:contains(Diffusion)")!!
        if (diffusionElement.ownText().isNotEmpty()) description += "\nDiffusion: ${diffusionElement.ownText().trim()}"

        anime.status = parseStatus(statusElement.ownText().trim())
        anime.description = description
        anime.thumbnail_url = document.select("div.cover img").attr("src")
        anime.genre = document.select("div.col.offset-lg-3.offset-md-4 div.list a").eachText().joinToString(separator = ", ")
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "En cours" -> SAnime.ONGOING
            "Terminé" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animeList = mutableListOf<SAnime>()

        val jsonLatest = json.decodeFromString<List<SearchJson>>(
            response.body.string().substringAfter("var lastEpisodes = ").substringBefore(";\n"),
        )

        for (item in jsonLatest) {
            val animeResult = SAnime.create().apply {
                val type = item.url!!.substringAfterLast("-")
                url = item.url!!.replace("episode", "info").substringBeforeLast("-").substringBeforeLast("-") + "-$type"
                title = item.title!!
                thumbnail_url = try {
                    item.url_image
                } catch (e: Exception) {
                    "$baseUrl/images/default_poster.png"
                }
            }
            animeList.add(animeResult)
        }

        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesSelector() = throw Exception("Not used")

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Utilisez ce filtre pour affiner votre recherche"),
        TypeFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "VOSTFR or VF",
        arrayOf(
            Pair("<sélectionner>", "none"),
            Pair("VOSTFR", "anime"),
            Pair("VF", "anime-vf"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
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
            title = "Preferred server"
            entries = arrayOf("Pstream/Veestream", "Streamtape")
            entryValues = arrayOf("Pstream", "Streamtape")
            setDefaultValue("Pstream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(serverPref)
    }

    private fun pstreamExtractor(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val document = Jsoup.connect(url).headers(
            mapOf(
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Connection" to "keep-alive",
            ),
        ).get()
        document.select("script").forEach { Script ->
            if (Script.attr("src").contains("https://www.pstream.net/u/player-script") || Script.attr("src").contains("https://veestream.net/u/player-script")) {
                val playerScript = Jsoup.connect(Script.attr("src")).headers(
                    mapOf(
                        "Accept" to "*/*",
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Accept-Language" to "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7",
                        "Connection" to "keep-alive",
                    ),
                ).ignoreContentType(true).execute().body()

                val base64Data = playerScript.substringAfter("e.parseJSON(atob(t).slice(2))}(\"").substringBefore("\"")

                val base64Decoded = Base64.decode(base64Data, Base64.DEFAULT).toString(Charsets.UTF_8)

                val videoUrl = base64Decoded.substringAfter("mmmm\":\"").substringBefore("\"")

                val videoUrlDecoded = videoUrl.replace("\\", "")
                val headers = headers.newBuilder().apply {
                    add("Accept", "*/*")
                    add("Accept-Encoding", "gzip, deflate, br")
                    add("Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7")
                    add("Connection", "keep-alive")
                    add("Referer", url)
                }.build()

                val masterPlaylist = client.newCall(GET(videoUrlDecoded, headers))
                    .execute()
                    .body.string()

                val separator = "#EXT-X-STREAM-INF"
                masterPlaylist.substringAfter(separator).split(separator).map {
                    val resolution = it.substringAfter("NAME=\"")
                        .substringBefore("\"") + "p"
                    val videoUrl = it.substringAfter("\n").substringBefore("\n")
                    videoList.add(
                        Video(videoUrl, "$resolution (Pstream)", videoUrl, headers = headers),
                    )
                }
                return videoList
            }
        }
        return emptyList()
    }

    private fun extractFuse(videoUrl: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val iframeHeaders = Headers.headersOf(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language",
            "en-US,en;q=0.5",
            "Host",
            videoUrl.toHttpUrl().host,
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.150 Safari/537.36 Edg/88.0.705.63",
        )

        val soup = client.newCall(
            GET(videoUrl, headers = iframeHeaders),
        ).execute().asJsoup()

        val jsUrl = soup.selectFirst("script[src~=player-script]")!!.attr("src")

        val jsHeaders = Headers.headersOf(
            "Accept", "*/*",
            "Accept-Language", "en-US,en;q=0.5",
            "Host", videoUrl.toHttpUrl().host,
            "Referer", videoUrl,
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.150 Safari/537.36 Edg/88.0.705.63",
        )
        val jsString = client.newCall(
            GET(jsUrl, headers = jsHeaders),
        ).execute().body.string()
        val base64Data = jsString.substringAfter("e.parseJSON(atob(t).slice(2))}(\"").substringBefore("\"")
        val base64Decoded = Base64.decode(base64Data, Base64.DEFAULT).toString(Charsets.UTF_8)
        val playlistUrl = "https:" + base64Decoded.substringAfter("https:").substringBefore("\"}").replace("\\", "")

        val masterPlaylist = client.newCall(
            GET(playlistUrl, headers = jsHeaders),
        ).execute().body.string()

        masterPlaylist.substringAfter("#EXT-X-STREAM-INF").split("#EXT-X-STREAM-INF").map {
            val resolution = it.substringAfter("NAME=\"")
                .substringBefore("\"") + "p"
            val newUrl = it.substringAfter("\n").substringBefore("\n")
            val videoHeaders = Headers.headersOf(
                "Accept",
                "*/*",
                "Accept-Language",
                "en-US,en;q=0.5",
                "Host",
                videoUrl.toHttpUrl().host,
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/88.0.4324.150 Safari/537.36 Edg/88.0.705.63",
            )
            videoList.add(
                Video(videoUrl, "$resolution (fusevideo)", newUrl, headers = videoHeaders),
            )
        }

        return videoList.sort()
    }

    @Serializable
    data class EpisodesJson(
        var time: String? = null,
        var episode: String? = null,
        var title: String? = null,
        var url: String? = null,
        var url_image: String? = null,

    )

    @Serializable
    data class SearchJson(
        var id: Int? = null,
        var title: String? = null,
        var titleEnglish: String? = null,
        var titleRomanji: String? = null,
        var titleFrench: String? = null,
        var others: String? = null,
        var type: String? = null,
        var status: String? = null,
        var popularity: Double? = null,
        var url: String? = null,
        var genres: ArrayList<String> = arrayListOf(),
        var url_image: String? = null,
        var score: String? = null,
        var startDateYear: String? = null,
        var nbEps: String? = null,

    )
}
