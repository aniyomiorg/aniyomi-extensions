package eu.kanade.tachiyomi.animeextension.en.nineanime

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
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

    override fun popularAnimeSelector(): String = "div.ani.items > div"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=trending&page=$page")

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a.name").attr("href").substringBefore("?"))
        thumbnail_url = element.select("div.poster img").attr("src")
        title = element.select("a.name").text()
    }

    override fun popularAnimeNextPageSelector(): String = "nav > ul.pagination > li > a[aria-label=pagination.next]"

    override fun episodeListRequest(anime: SAnime): Request {
        val id = client.newCall(GET(baseUrl + anime.url)).execute().asJsoup().selectFirst("div[data-id]").attr("data-id")
        val vrf = encodeVrf(id)
        return GET("$baseUrl/ajax/episode/list/$id?vrf=$vrf")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        return document.select(episodeListSelector()).map(::episodeFromElement).reversed()
    }

    override fun episodeListSelector() = "div.episodes ul > li > a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        val epNum = element.attr("data-num")
        val ids = element.attr("data-ids")
        val sub = element.attr("data-sub").toInt().toBoolean()
        val dub = element.attr("data-dub").toInt().toBoolean()
        val vrf = encodeVrf(ids)
        episode.url = "/ajax/server/list/$ids?vrf=$vrf"
        episode.episode_number = epNum.toFloat()
        val langPrefix = "[" + if (sub) { "Sub" } else { "" } + if (dub) { ",Dub" } else { "" } + "]"
        val name = element.parent()?.select("span.d-title")?.text().orEmpty()
        val namePrefix = "Episode $epNum"
        episode.name = "Episode $epNum" + if (sub || dub) {
            ": $langPrefix"
        } else { "" } + if (name.isNotEmpty() && name != namePrefix) {
            " $name"
        } else { "" }
        return episode
    }

    private fun Int.toBoolean() = this == 1

    override fun videoListParse(response: Response): List<Video> {
        val responseObject = json.decodeFromString<JsonObject>(response.body!!.string())
        val document = Jsoup.parse(JSONUtil.unescape(responseObject["result"]!!.jsonPrimitive.content))
        val videoList = mutableListOf<Video>()

        // Sub
        document.select("div[data-type=sub] > ul > li[data-sv-id=41]")
            .firstOrNull()?.attr("data-link-id")
            ?.let { videoList.addAll(extractVideo(it, "Sub")) }
        // Dub
        document.select("div[data-type=dub] > ul > li[data-sv-id=41]")
            .firstOrNull()?.attr("data-link-id")
            ?.let { videoList.addAll(extractVideo(it, "Dub")) }
        return videoList
    }

    private fun extractVideo(sourceId: String, lang: String): List<Video> {
        val vrf = encodeVrf(sourceId)
        val episodeBody = network.client.newCall(GET("$baseUrl/ajax/server/$sourceId?vrf=$vrf"))
            .execute().body!!.string()
        val encryptedSourceUrl = json.decodeFromString<JsonObject>(episodeBody)["result"]!!
            .jsonObject["url"]!!.jsonPrimitive.content
        val embedLink = decodeVrf(encryptedSourceUrl)
        val vizcloudClient = client.newBuilder().addInterceptor(VizcloudInterceptor()).build()
        val referer = Headers.headersOf("Referer", "$baseUrl/")
        val sourceObject = json.decodeFromString<JsonObject>(
            vizcloudClient.newCall(GET(embedLink, referer))
                .execute().body!!.string()
        )
        val mediaSources = sourceObject["data"]!!.jsonObject["media"]!!.jsonObject["sources"]!!.jsonArray
        val masterUrls = mediaSources.map { it.jsonObject["file"]!!.jsonPrimitive.content }
        val masterUrl = masterUrls.find { !it.contains("/simple/") } ?: masterUrls.first()
        val headers = Headers.headersOf("referer", embedLink, "origin", "https://" + masterUrl.toHttpUrl().topPrivateDomain())
        val result = client.newCall(GET(masterUrl, headers)).execute()
        val masterPlaylist = result.body!!.string()
        return masterPlaylist.substringAfter("#EXT-X-STREAM-INF:")
            .split("#EXT-X-STREAM-INF:").map {
                val quality = it.substringAfter("RESOLUTION=").substringAfter("x").substringBefore("\n") + "p $lang"
                val videoUrl = masterUrl.substringBeforeLast("/") + "/" + it.substringAfter("\n").substringBefore("\n")
                Video(videoUrl, quality, videoUrl, headers = headers)
            }
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        val lang = preferences.getString("preferred_language", "Sub")
        if (quality != null && lang != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality) && video.quality.contains(lang)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            // If dub is preferred language and anime do not have dub version, respect preferred quality
            if (lang == "Dub" && newList.first().quality.contains("Dub").not()) {
                newList.clear()
                for (video in this) {
                    if (video.quality.contains(quality)) {
                        newList.add(preferred, video)
                        preferred++
                    } else {
                        newList.add(video)
                    }
                }
            }
            return newList
        }
        return this
    }

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val vrf = encodeVrf(query)
        return GET("$baseUrl/filter?keyword=${encode(query)}&vrf=$vrf&page=$page")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("h1.title").text()
        anime.genre = document.select("div:contains(Genre) > span > a").joinToString { it.text() }
        anime.description = document.select("div.synopsis > div.shorting > div.content").text()
        anime.author = document.select("div:contains(Studios) > span > a").text()
        anime.status = parseStatus(document.select("div:contains(Status) > span").text())

        // add alternative name to anime description
        val altName = "Other name(s): "
        document.select("h1.title").attr("data-jp")?.let {
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
            "Releasing" -> SAnime.ONGOING
            "Completed" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/filter?sort=recently_updated&page=$page")

    override fun latestUpdatesSelector(): String = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("9anime.to", "9anime.id", "9anime.pl")
            entryValues = arrayOf("https://9anime.to", "https://9anime.id", "https://9anime.pl")
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
        val videoLanguagePref = ListPreference(screen.context).apply {
            key = "preferred_language"
            title = "Preferred language"
            entries = arrayOf("Sub", "Dub")
            entryValues = arrayOf("Sub", "Dub")
            setDefaultValue("Sub")
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
        screen.addPreference(videoLanguagePref)
    }

    private fun encodeVrf(id: String) = encode(encrypt(cipher(encode(id), cipherKey)))

    private fun decodeVrf(text: String) = decode(cipher(decrypt(text), decipherKey))

    private fun encrypt(input: String): String {
        if (input.any { it.code > 255 }) throw Exception("illegal characters!")
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
                    if (n in 0..63) output += nineAnimeKey[n]
                }
            }
        }
        return output
    }

    private fun cipher(input: String, cipherKey: String): String {
        val arr = IntArray(256) { it }

        var u = 0
        var r: Int
        arr.indices.forEach {
            u = (u + arr[it] + cipherKey[it % cipherKey.length].code) % 256
            r = arr[it]
            arr[it] = arr[u]
            arr[u] = r
        }
        u = 0
        var c = 0

        return input.indices.map { j ->
            c = (c + 1) % 256
            u = (u + arr[c]) % 256
            r = arr[c]
            arr[c] = arr[u]
            arr[u] = r
            (input[j].code xor arr[(arr[c] + arr[u]) % 256]).toChar()
        }.joinToString("")
    }

    private fun decrypt(input: String): String {
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
            i = nineAnimeKey.indexOf(t[o])
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

    private val keys by lazy {
        val allJsScript = runBlocking {
            client.newCall(
                GET(
                    url = "https://s2.bunnycdn.ru/assets/_9anime/min/all.js",
                    cache = CacheControl.FORCE_NETWORK
                )
            ).execute().body!!.string()
        }
        getKeys(allJsScript, json)
    }
    private val cipherKey by lazy {
        keys.first
    }
    private val decipherKey by lazy {
        keys.second
    }
}

private const val nineAnimeKey = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
