package eu.kanade.tachiyomi.animeextension.en.nineanime

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
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

@ExperimentalSerializationApi
class NineAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "9anime"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://9anime.to")!! }

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder(): Headers.Builder {
        return Headers.Builder().add("Referer", baseUrl)
    }

    override fun popularAnimeSelector(): String = "li"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/ajax/home/widget?name=trending&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["html"]!!.jsonPrimitive.content))

        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }

        val hasNextPage = popularAnimeNextPageSelector().let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.name").attr("href").substringBefore("?"))
        thumbnail_url = element.select("a.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String = "li"

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfterLast(".")
        val vrf = encode(getVrf(animeId))
        return GET("$baseUrl/ajax/anime/servers?id=$animeId&vrf=$vrf")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["html"]!!.jsonPrimitive.content))
        val animeId = response.request.url.queryParameter("id")!!
        val vrf = encode(response.request.url.queryParameter("vrf")!!)
        return document.select(episodeListSelector()).map { episodeFromElement(it, animeId, vrf) }.reversed()
    }

    override fun episodeListSelector() = "ul.episodes li a"

    private fun episodeFromElement(element: Element, animeId: String, vrf: String): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("data-base")
        episode.setUrlWithoutDomain("$baseUrl/ajax/anime/servers?id=$animeId&vrf=$vrf&episode=$epNum")
        episode.episode_number = epNum.toFloat()
        episode.name = "Episode $epNum"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["html"]!!.jsonPrimitive.content))
        val epNum = response.request.url.queryParameter("episode")
        val sources = document.select("ul.episodes li a[data-base=$epNum]").attr("data-sources")
        val sourceId = json.decodeFromString<JsonObject>(sources)["41"]!!.jsonPrimitive.content
        fun getEpisodeBody(): String? {
            val res = network.client
                .newCall(GET("$baseUrl/ajax/anime/episode?id=$sourceId"))
                .execute()
            return if (res.code == 200) res.body!!.string() else null
        }
        // sometimes I have to retry the request for some reason (???)
        val episodeBody = getEpisodeBody() ?: getEpisodeBody()!!
        val encryptedSourceUrl = json.decodeFromString<JsonObject>(episodeBody)["url"]!!.jsonPrimitive.content
        val embedLink = getLink(encryptedSourceUrl)
        val referer = Headers.headersOf("Referer", "$baseUrl/")
        val embed = client.newCall(GET(embedLink, referer)).execute().asJsoup()
        val skey = embed.selectFirst("script:containsData(window.skey = )")
            .data().substringAfter("window.skey = \'").substringBefore("\'")
        val sourceObject = json.decodeFromString<JsonObject>(
            client.newCall(GET(embedLink.replace("/embed/", "/info/") + "?skey=$skey", referer))
                .execute().body!!.string()
        )
        val masterUrl = sourceObject["media"]!!.jsonObject["sources"]!!.jsonArray
            .first().jsonObject["file"]!!.jsonPrimitive.content
        val masterPlaylist = client.newCall(GET(masterUrl)).execute().body!!.string()
        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p"
                val videoUrl = masterUrl.substringBeforeLast("/") + "/" + it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, null)
            }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.select("a.name").attr("href"))
        anime.thumbnail_url = element.select("a.poster img").attr("src")
        anime.title = element.select("a.name").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "a.btn-primary.next:not(.disabled)"

    override fun searchAnimeSelector(): String = "ul.anime-list li"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val vrf = encode(getVrf(query))
        return GET("$baseUrl/search?keyword=${encode(query)}&vrf=$vrf&page=$page")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.title").text()
        anime.genre = document.select("div:contains(Genre) > span > a[title]").joinToString { it.text() }
        anime.description = document.select("p[itemprop=description]").text()
        anime.status = parseStatus(document.select("div:contains(Status) > span").text())

        // add alternative name to anime description
        val altName = "Other name(s): "
        document.select("div.alias").firstOrNull()?.ownText()?.let {
            if (it.isBlank().not()) {
                anime.description = when {
                    anime.description.isNullOrBlank() -> altName + it
                    else -> anime.description + "\n\n$altName" + it
                }
            }
        }
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Airing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/ajax/home/widget?name=updated_all&page=$page")

    override fun latestUpdatesSelector(): String = throw Exception("not used")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("9anime.to", "9anime.id", "9anime.club", "9anime.center")
            entryValues = arrayOf("https://9anime.to", "https://9anime.id", "https://9anime.club", "https://9anime.center")
            setDefaultValue("https://9anime.to")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
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
        screen.addPreference(domainPref)
        screen.addPreference(videoQualityPref)
    }

    private fun getVrf(id: String): String {
        val reversed = ue(encode(id) + "0000000").slice(0..5).reversed()
        return reversed + ue(je(reversed, encode(id))).replace("""=+$""".toRegex(), "")
    }

    private fun getLink(url: String): String {
        val i = url.slice(0..5)
        val n = url.slice(6..url.lastIndex)
        return decode(je(i, ze(n)))
    }

    private fun ue(input: String): String {
        if (input.any { it.code >= 256 }) throw Exception("illegal characters!")
        var output = ""
        for (i in input.indices step 3) {
            val a = intArrayOf(-1, -1, -1, -1)
            a[0] = input[i].code shr 2
            a[1] = (3 and input[i].code) shl 4
            if (input.length > i + 1) {
                a[1] = a[1] or (input[i + 1].code shr 4)
                a[2] = (15 and input[i + 1].code) shl 2
            }
            if (input.length > i + 2) {
                a[2] = a[2] or (input[i + 2].code shr 6)
                a[3] = 63 and input[i + 2].code
            }
            for (n in a) {
                if (n == -1) output += "="
                else {
                    if (n in 0..63) output += key[n]
                }
            }
        }
        return output
    }

    private fun je(inputOne: String, inputTwo: String): String {
        val arr = IntArray(256) { it }
        var output = ""
        var u = 0
        var r: Int
        for (a in arr.indices) {
            u = (u + arr[a] + inputOne[a % inputOne.length].code) % 256
            r = arr[a]
            arr[a] = arr[u]
            arr[u] = r
        }
        u = 0
        var c = 0
        for (f in inputTwo.indices) {
            c = (c + f) % 256
            u = (u + arr[c]) % 256
            r = arr[c]
            arr[c] = arr[u]
            arr[u] = r
            output += (inputTwo[f].code xor arr[(arr[c] + arr[u]) % 256]).toChar()
        }
        return output
    }

    private fun ze(input: String): String {
        val t = if (input.replace("""[\t\n\f\r]""".toRegex(), "").length % 4 == 0) {
            input.replace("""==?$""".toRegex(), "")
        } else input
        if (t.length % 4 == 1 || t.contains("""[^+/0-9A-Za-z]""".toRegex())) throw Exception("bad input")
        var i: Int
        var r = ""
        var e = 0
        var u = 0
        for (o in t.indices) {
            e = e shl 6
            i = key.indexOf(t[o])
            e = e or i
            u += 6
            if (24 == u) {
                r += ((16711680 and e) shr 16).toChar()
                r += ((65280 and e) shr 8).toChar()
                r += (255 and e).toChar()
                e = 0
                u = 0
            }
        }
        return if (12 == u) {
            e = e shr 4
            r + e.toChar()
        } else {
            if (18 == u) {
                e = e shr 2
                r += ((65280 and e) shr 8).toChar()
                r += (255 and e).toChar()
            }
            r
        }
    }

    private fun encode(input: String): String = java.net.URLEncoder.encode(input, "utf-8").replace("+", "%20")

    private fun decode(input: String): String = java.net.URLDecoder.decode(input, "utf-8")
}

private const val key = "0wMrYU+ixjJ4QdzgfN2HlyIVAt3sBOZnCT9Lm7uFDovkb/EaKpRWhqXS5168ePcG"
