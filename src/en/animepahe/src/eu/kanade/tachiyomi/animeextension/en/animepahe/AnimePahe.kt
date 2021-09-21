package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.pow

class AnimePahe : ConfigurableAnimeSource, AnimeHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name = "AnimePahe"

    override val baseUrl = preferences.getString("preferred_domain", "https://animepahe.com")!!

    override val lang = "en"

    // Create bypass object
    private val ddgbypass = DdosGuardBypass("https://animepahe.com/")

    override val supportsLatest = false

    override fun headersBuilder(): Headers.Builder {
        try {
            // Bypass...
            // Only required once. Then you can browse any page on the domain.
            if (!ddgbypass.isBypassed) {
                ddgbypass.bypass()
            }
            // Set Cookie header
            if (ddgbypass.isBypassed) {
                return super.headersBuilder().add("cookie", ddgbypass.cookiesAsString)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return super.headersBuilder()
    }

    override val client: OkHttpClient = network.cloudflareClient

    override fun animeDetailsParse(response: Response): SAnime {
        val jsoup = response.asJsoup()
        val anime = SAnime.create()
        anime.title = jsoup.selectFirst("div.title-wrapper h1").text()
        anime.author = jsoup.selectFirst("div.col-sm-4.anime-info p:contains(Studio:)").text().replace("Studio: ", "")
        anime.status = parseStatus(jsoup.selectFirst("div.col-sm-4.anime-info p:contains(Status:) a").text())
        anime.thumbnail_url = jsoup.selectFirst("div.anime-poster a").attr("href")
        anime.genre = jsoup.select("div.anime-genre ul li").joinToString { it.text() }
        anime.description = jsoup.select("div.anime-summary").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int) = throw Exception("not supported")

    override fun latestUpdatesParse(response: Response) = throw Exception("not supported")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/api?m=search&l=8&q=$query")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parseSearchJson(responseString)
    }

    private fun parseSearchJson(jsonLine: String?): AnimesPage {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val data = jObject.get("data") ?: return AnimesPage(emptyList(), false)
        val array = data.asJsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.asJsonObject.get("title").asString
            val animeId = item.asJsonObject.get("id").asInt
            val session = item.asJsonObject.get("session").asString
            anime.setUrlWithoutDomain("$baseUrl/anime/$session?anime_id=$animeId")
            animeList.add(anime)
        }
        return AnimesPage(animeList, false)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api?m=airing&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseString = response.body!!.string()
        return parsePopularAnimeJson(responseString)
    }

    private fun parsePopularAnimeJson(jsonLine: String?): AnimesPage {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val lastPage = jObject.get("last_page").asInt
        val page = jObject.get("current_page").asInt
        val hasNextPage = page < lastPage
        val array = jObject.get("data").asJsonArray
        val animeList = mutableListOf<SAnime>()
        for (item in array) {
            val anime = SAnime.create()
            anime.title = item.asJsonObject.get("anime_title").asString
            val animeId = item.asJsonObject.get("anime_id").asInt
            val session = item.asJsonObject.get("anime_session").asString
            anime.setUrlWithoutDomain("$baseUrl/anime/$session?anime_id=$animeId")
            anime.artist = item.asJsonObject.get("fansub").asString
            animeList.add(anime)
        }
        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Currently Airing" -> SAnime.ONGOING
            "Finished Airing" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = anime.url.substringAfterLast("?anime_id=")
        return GET("$baseUrl/api?m=release&id=$animeId&sort=episode_desc&page=1")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return recursivePages(response)
    }

    private fun parseEpisodePage(jsonLine: String?): MutableList<SEpisode> {
        val jElement: JsonElement = JsonParser.parseString(jsonLine)
        val jObject: JsonObject = jElement.asJsonObject
        val array = jObject.get("data").asJsonArray
        val episodeList = mutableListOf<SEpisode>()
        for (item in array) {
            val itemO = item.asJsonObject
            val episode = SEpisode.create()
            episode.date_upload = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .parse(itemO.get("created_at").asString)!!.time
            val animeId = itemO.get("anime_id").asInt
            val session = itemO.get("session").asString
            episode.setUrlWithoutDomain("$baseUrl/api?m=links&id=$animeId&session=$session&p=kwik")
            val epNum = itemO.get("episode").asInt
            episode.episode_number = epNum.toFloat()
            episode.name = "Episode $epNum"
            episodeList.add(episode)
        }
        return episodeList
    }

    private fun recursivePages(response: Response): List<SEpisode> {
        val responseString = response.body!!.string()
        val jElement: JsonElement = JsonParser.parseString(responseString)
        val jObject: JsonObject = jElement.asJsonObject
        val lastPage = jObject.get("last_page").asInt
        val page = jObject.get("current_page").asInt
        val hasNextPage = page < lastPage
        val returnList = parseEpisodePage(responseString)
        if (hasNextPage) {
            val nextPage = nextPageRequest(response.request.url.toString(), page + 1)
            returnList += recursivePages(nextPage)
        }
        return returnList
    }

    private fun nextPageRequest(url: String, page: Int): Response {
        val request = GET(url.substringBeforeLast("&page=") + "&page=$page")
        return client.newCall(request).execute()
    }

    override fun videoListParse(response: Response): List<Video> {
        val array = JsonParser.parseString(response.body!!.string())
            .asJsonObject.get("data").asJsonArray
        val videos = mutableListOf<Video>()
        for (item in array.reversed()) {
            val quality = item.asJsonObject.keySet().first()
            val adflyLink = item.asJsonObject.get(quality)
                .asJsonObject.get("kwik_adfly").asString
            videos.add(getVideo(adflyLink, quality))
        }
        return videos
    }

    private fun getVideo(adflyUrl: String, quality: String): Video {
        val videoUrl = KwikExtractor().getStreamUrlFromKwik(adflyUrl)
        return Video(videoUrl, "${quality}p", videoUrl, null)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)
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

    inner class KwikExtractor {
        private var cookies: String = ""

        private val ytsm = "ysmm = '([^']+)".toRegex()
        private val kwikParamsRegex = Regex("""\("(\w+)",\d+,"(\w+)",(\d+),(\d+),\d+\)""")
        private val kwikDUrl = Regex("action=\"([^\"]+)\"")
        private val kwikDToken = Regex("value=\"([^\"]+)\"")

        private fun bypassAdfly(adflyUri: String): String {
            var responseCode = 302
            var adflyContent: Response? = null
            var tries = 0
            val noRedirectClient = OkHttpClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            while (responseCode != 200 && tries < 20) {
                val nextUrl = noRedirectClient.newCall(GET(adflyUri)).execute().header("location")!!
                adflyContent = noRedirectClient.newCall(GET(nextUrl)).execute()
                cookies += adflyContent.header("set-cookie") ?: ""
                responseCode = adflyContent.code
                ++tries
            }
            if (tries > 19) {
                throw Exception("Failed to bypass adfly.")
            }
            return decodeAdfly(ytsm.find(adflyContent?.body!!.string())!!.destructured.component1())
        }

        private fun decodeAdfly(codedKey: String): String {
            var r = ""
            var j = ""

            for ((n, l) in codedKey.withIndex()) {
                if (n % 2 != 0) {
                    j = l + j
                } else {
                    r += l
                }
            }

            val encodedUri = ((r + j).toCharArray().map { it.toString() }).toMutableList()
            val numbers = sequence {
                for ((i, n) in encodedUri.withIndex()) {
                    if (isNumber(n)) {
                        yield(Pair(i, n.toInt()))
                    }
                }
            }

            for ((first, second) in zipGen(numbers)) {
                val xor = first.second.xor(second.second)
                if (xor < 10) {
                    encodedUri[first.first] = xor.toString()
                }
            }
            var returnValue = String(encodedUri.joinToString("").toByteArray(), Charsets.UTF_8)
            returnValue = String(android.util.Base64.decode(returnValue, android.util.Base64.DEFAULT), Charsets.ISO_8859_1)
            return returnValue.slice(16..returnValue.length - 17)
        }

        private fun isNumber(s: String?): Boolean {
            return s?.toIntOrNull() != null
        }

        private fun zipGen(gen: Sequence<Pair<Int, Int>>): ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>> {
            val allItems = gen.toList().toMutableList()
            val newList = ArrayList<Pair<Pair<Int, Int>, Pair<Int, Int>>>()

            while (allItems.size > 1) {
                newList.add(Pair(allItems[0], allItems[1]))
                allItems.removeAt(0)
                allItems.removeAt(0)
            }
            return newList
        }

        fun getStreamUrlFromKwik(adflyUri: String): String {
            val fContent =
                client.newCall(GET(bypassAdfly(adflyUri), Headers.headersOf("referer", "https://kwik.cx/"))).execute()
            cookies += (fContent.header("set-cookie")!!)
            val fContentString = fContent.body!!.string()

            val (fullString, key, v1, v2) = kwikParamsRegex.find(fContentString)!!.destructured
            val decrypted = decrypt(fullString, key, v1.toInt(), v2.toInt())
            val uri = kwikDUrl.find(decrypted)!!.destructured.component1()
            val tok = kwikDToken.find(decrypted)!!.destructured.component1()
            var content: Response? = null

            var code = 419
            var tries = 0

            val noRedirectClient = OkHttpClient().newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .cookieJar(client.cookieJar)
                .build()

            while (code != 302 && tries < 20) {

                content = noRedirectClient.newCall(
                    POST(
                        uri,
                        Headers.headersOf(
                            "referer", fContent.request.url.toString(),
                            "cookie", fContent.header("set-cookie")!!.replace("path=/;", "")
                        ),
                        FormBody.Builder().add("_token", tok).build()
                    )
                ).execute()
                code = content.code
                ++tries
            }
            if (tries > 19) {
                throw Exception("Failed to extract the stream uri from kwik.")
            }
            val location = content?.header("location").toString()
            content?.close()
            return location
        }

        private fun decrypt(fullString: String, key: String, v1: Int, v2: Int): String {
            var r = ""
            var i = 0

            while (i < fullString.length) {
                var s = ""

                while (fullString[i] != key[v2]) {
                    s += fullString[i]
                    ++i
                }
                var j = 0

                while (j < key.length) {
                    s = s.replace(key[j].toString(), j.toString())
                    ++j
                }
                r += (getString(s, v2).toInt() - v1).toChar()
                ++i
            }
            return r
        }

        private fun getString(content: String, s1: Int): String {
            val s2 = 10
            val characterMap = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"

            val slice2 = characterMap.slice(0 until s2)
            var acc: Long = 0

            for ((n, i) in content.reversed().withIndex()) {
                acc += (
                    when (isNumber("$i")) {
                        true -> "$i".toLong()
                        false -> "0".toLong()
                    }
                    ) * s1.toDouble().pow(n.toDouble()).toInt()
            }

            var k = ""

            while (acc > 0) {
                k = slice2[(acc % s2).toInt()] + k
                acc = (acc - (acc % s2)) / s2
            }

            return when (k != "") {
                true -> k
                false -> "0"
            }
        }
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
        val domainPref = ListPreference(screen.context).apply {
            key = "preferred_domain"
            title = "Preferred domain (requires app restart)"
            entries = arrayOf("animepahe.com", "animepahe.ru", "animepahe.org")
            entryValues = arrayOf("https://animepahe.com", "https://animepahe.ru", "https://animepahe.org")
            setDefaultValue("https://animepahe.com")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(domainPref)
    }
}
