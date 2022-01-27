package eu.kanade.tachiyomi.animeextension.en.wcofun

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
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
import java.lang.Exception

class Wcofun : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Wcofun"

    override val baseUrl = "https://www.wcofun.com/"

    override val lang = "en"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.sidebar-titles li a"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "ul.pagination li:last-child:not(.selected)"

    override fun episodeListSelector() = "div.cat-eps a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        val epName = element.ownText()
        val season = epName.substringAfter("Season ")
        val ep = epName.substringAfter("Episode ")
        val seasonNo = try {
            season.substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) {
            0.toFloat()
        }
        val epNo = try {
            ep.substringBefore(" ").toFloat()
        } catch (e: NumberFormatException) {
            0.toFloat()
        }
        var episodeName = if (ep == epName) epName else "Episode $ep"
        episodeName = if (season == epName) episodeName else "Season $season"
        episode.episode_number = epNo + (seasonNo * 100)
        episode.name = episodeName
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    private fun videosFromElement(document: Document): List<Video> {
        val scriptData = document.select("script:containsData( = \"\"; var )").first().data()

        val numberRegex = """(?<=\.replace\(/\\D/g,''\)\) - )\d+""".toRegex()
        val subtractionNumber = numberRegex.find(scriptData)!!.value.toInt()

        val htmlRegex = """(?<=\["|, ").+?(?=")""".toRegex()
        val html = htmlRegex.findAll(scriptData).map {
            val decoded = String(Base64.decode(it.value, Base64.DEFAULT))
            val number = decoded.replace("""\D""".toRegex(), "").toInt()
            (number - subtractionNumber).toChar()
        }.joinToString("")

        val iframeLink = Jsoup.parse(html).select("div.pcat-jwplayer iframe")
            .attr("src")
        val playerHtml = client.newCall(
            GET(
                url = baseUrl + iframeLink,
                headers = Headers.headersOf("Referer", document.location())
            )
        ).execute().body!!.string()

        val getVideoLink = playerHtml.substringAfter("\$.getJSON(\"").substringBefore("\"")
        val videoJson = json.decodeFromString<JsonObject>(
            client.newCall(
                GET(
                    url = baseUrl + getVideoLink,
                    headers = Headers.headersOf("x-requested-with", "XMLHttpRequest")
                )
            ).execute().body!!.string()
        )

        val server = videoJson["server"]!!.jsonPrimitive.content
        val hd = videoJson["hd"]?.jsonPrimitive?.content
        val sd = videoJson["enc"]?.jsonPrimitive?.content
        val videoList = mutableListOf<Video>()
        hd?.let {
            if (it.isNotEmpty()) {
                val videoUrl = "$server/getvid?evid=$it"
                videoList.add(Video(videoUrl, "HD", videoUrl, null))
            }
        }
        sd?.let {
            if (it.isNotEmpty()) {
                val videoUrl = "$server/getvid?evid=$it"
                videoList.add(Video(videoUrl, "SD", videoUrl, null))
            }
        }
        return videoList
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "HD")
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
        anime.setUrlWithoutDomain(element.attr("href"))
        anime.title = element.text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun searchAnimeSelector(): String = "div#sidebar_right2 li div.recent-release-episodes a, div.ddmcc li a"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val formBody = FormBody.Builder()
            .add("catara", query)
            .add("konuara", "series")
            .build()
        return when {
            query.isNotBlank() -> POST("$baseUrl/search", headers, body = formBody)
            genreFilter.state != 0 -> GET("$baseUrl/search-by-genre/page/${genreFilter.toUriPart()}")
            else -> GET("$baseUrl/")
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.video-title a").first().text()
        anime.description = document.select("div#sidebar_cat p")?.first()?.text()
        anime.thumbnail_url = "https:${document.select("div#sidebar_cat img").first().attr("src")}"
        anime.genre = document.select("div#sidebar_cat > a").joinToString { it.text() }
        return anime
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("HD", "SD")
            entryValues = arrayOf("HD", "SD")
            setDefaultValue("HD")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
    }

    // Filters
    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Genres",
        arrayOf(
            Pair("<select>", ""),
            Pair("Adventure", "4"),
            Pair("Comedy", "6"),
            Pair("Fantasy", "8"),
            Pair("Science Fiction", "10"),
            Pair("Mystery", "12"),
            Pair("Action", "14"),
            Pair("Drama", "16"),
            Pair("Surrealist Comedy", "18"),
            Pair("Horror", "20"),
            Pair("Romance", "22"),
            Pair("Animated", "23"),
            Pair("Thriller", "24"),
            Pair("Slice Of Life", "25"),
            Pair("Supernatural", "26"),
            Pair("Romantic Comedy", "27"),
            Pair("Harem", "28"),
            Pair("Sports", "29"),
            Pair("Psychological", "30"),
            Pair("Magical Girl", "31"),
            Pair("Mecha", "35"),
            Pair("Role-playing", "33"),
            Pair("Military", "34"),
            Pair("Science Fantasy", "36"),
            Pair("Martial Arts", "37"),
            Pair("High School", "38"),
            Pair("Parody", "39"),
            Pair("Virtual Reality", "40"),
            Pair("Space Opera", "41"),
            Pair("Post-Apocalyptic", "42"),
            Pair("Music", "43"),
            Pair("Dark Fantasy", "44"),
            Pair("Historical Fantasy", "45"),
            Pair("Yuri", "46"),
            Pair("Superpower", "47"),
            Pair("Magic", "48"),
            Pair("Alternate History", "49"),
            Pair("Sci-fi", "50"),
            Pair("Girls With Guns", "51"),
            Pair("Tournament", "52"),
            Pair("School", "53"),
            Pair("Romantic Drama", "54"),
            Pair("Crime", "55"),
            Pair("Historical", "56"),
            Pair("Dystopian", "57"),
            Pair("Steampunk", "58"),
            Pair("Metafiction", "59"),
            Pair("Police", "60"),
            Pair("Detective Fiction", "61"),
            Pair("Occult", "62"),
            Pair("Tragedy", "63"),
            Pair("Fighter", "65"),
            Pair("Animated television series", "66"),
            Pair("Children's television series", "67"),
            Pair("Family television series", "68"),
            Pair("Absurdist humor", "69"),
            Pair("Dark humor", "70"),
            Pair("Surreal humor", "71"),
            Pair("Adult animation", "72"),
            Pair("Teen Drama", "73"),
            Pair("Superhero", "74"),
            Pair("Cyberpunk", "75"),
            Pair("Suspense", "76"),
            Pair("Sitcom", "77"),
            Pair("Satire", "78"),
            Pair("Anthology series", "79"),
            Pair("Edutainment", "80"),
            Pair("Espionage", "81"),
            Pair("Surrealism", "82"),
            Pair("Teen Animation", "83"),
            Pair("Toilet humour", "84"),
            Pair("Cutaway gag humor", "85"),
            Pair("Splatter", "86"),
            Pair("Deadpan", "87"),
            Pair("Car racing", "88"),
            Pair("Chanbara", "89"),
            Pair("Goth", "90"),
            Pair("Game", "93"),
            Pair("Magical boy", "94"),
            Pair("Shounen", "95"),
            Pair("Kids", "96"),
            Pair("shoujo", "97"),
            Pair("Baseball", "98"),
            Pair("Manga", "99"),
            Pair("Friendship", "100"),
            Pair("School Dormitory", "101"),
            Pair("Ecchi", "102"),
            Pair("Seinen", "103"),
            Pair("Coming-of-age story", "104"),
            Pair("Idol anime", "105"),
            Pair("Samurai", "106"),
            Pair("Reverse Harem", "107"),
            Pair("Urban", "108"),
            Pair("War", "109"),
            Pair("Vampire", "110"),
            Pair("Demons", "111"),
            Pair("Urban fantasy", "112"),
            Pair("Mythic fiction", "113"),
            Pair("Space", "114"),
            Pair("Shounen Ai", "115"),
            Pair("Shoujo Ai", "116"),
            Pair("Slapstick", "117"),
            Pair("Surreal", "118"),
            Pair("Chinese Cartoon", "119"),
            Pair("Short", "120"),
            Pair("Movie", "121"),
            Pair("Family", "123"),
            Pair("Animation", "125"),
            Pair("Educational", "128"),
            Pair("Musical", "129"),
            Pair("Yaoi", "132"),
            Pair("Documentary", "133"),
            Pair("Football", "134"),
            Pair("Learning", "135"),
            Pair("Pre-School", "136"),
            Pair("Graphic novel", "137"),
            Pair("Contemporary fantasy", "140"),
            Pair("Adult Cartoons", "141"),
            Pair("Cartoon series", "142"),
            Pair("Off-color humor", "143"),
            Pair("History", "147"),
            Pair("Superhero fiction", "149"),
            Pair("Sword and sorcery", "155"),
            Pair("Stop Motion", "157"),
            Pair("Horror comedy", "159"),
            Pair("Social satire", "163"),
            Pair("Black comedy", "166"),
            Pair("Animated sitcom", "168"),
            Pair("Game-Show", "170"),
            Pair("Western", "172"),
            Pair("Comic science fiction", "176"),
            Pair("Situation comedy", "178"),
            Pair("Adapted Literature", "181"),
            Pair("Harold and the Purple Crayon", "202"),
            Pair("Spy fiction", "203"),
            Pair("Children's fiction", "204"),
            Pair("Sketch comedy", "205"),
            Pair("Josei", "206"),
            Pair("Dementia", "208"),
            Pair("Cars", "209"),
            Pair("Isekai", "210"),
            Pair("Comedy-drama", "211"),
            Pair("Senryuu Girl", "214"),
            Pair("Musical comedy", "215"),
            Pair("Comedy horror", "216"),
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
