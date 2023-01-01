package eu.kanade.tachiyomi.animeextension.en.bestdubbedanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.bestdubbedanime.extractors.DailyMotionExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class BestDubbedAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "BestDubbedAnime"

    override val baseUrl = "https://bestdubbedanime.com"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select(popularAnimeSelector()).map { element ->
            popularAnimeFromElement(element)
        }

        return AnimesPage(animes, false)
    }

    override fun popularAnimeSelector(): String = "li"

    override fun popularAnimeRequest(page: Int): Request {
        return GET("$baseUrl/xz/trending.php?_=${System.currentTimeMillis() / 1000}")
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()

        anime.setUrlWithoutDomain(("https:" + element.select("a").attr("href")).toHttpUrl().encodedPath)
        anime.title = element.select("div.cittx").text()
        anime.thumbnail_url = "https:" + element.select("img").attr("src")

        return anime
    }

    override fun popularAnimeNextPageSelector(): String = throw Exception("Not used")

    // Episodes

    override fun episodeListSelector() = throw Exception("Not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        if (response.request.url.encodedPath.startsWith("/movies/")) {
            val episode = SEpisode.create()

            episode.name = document.select("div.tinywells > div > h4").text()
            episode.episode_number = 1F
            episode.setUrlWithoutDomain(response.request.url.encodedPath)
            episodeList.add(episode)
        } else {
            var counter = 1
            for (ep in document.select("div.eplistz > div > div > a")) {

                val episode = SEpisode.create()

                episode.name = ep.select("div.inwel > span").text()
                episode.episode_number = counter.toFloat()
                episode.setUrlWithoutDomain(("https:" + ep.attr("href")).toHttpUrl().encodedPath)
                episodeList.add(episode)

                counter++
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw Exception("Not used")

    // Video urls

    private fun String.decodeHex(): String {
        require(length % 2 == 0) { "Must have an even length" }
        return chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
            .toString(Charsets.UTF_8)
    }

    private fun decodeAtob(inputStr: String): String {
        return String(Base64.decode(inputStr.replace("\\x", "").decodeHex(), Base64.DEFAULT))
    }

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()

        var slug = response.request.url.toString().split(".com/")[1]
        if (slug.startsWith("movies/")) {
            slug = slug.split("movies/")[1]
        }

        val jsString = client.newCall(
            GET("$baseUrl/xz/v3/js/index_beta.js?999995b")
        ).execute().body!!.string()

        val apiPath = if (response.request.url.encodedPath.startsWith("/movies/")) {
            "/movies/jsonMovie.php?slug="
        } else {
            decodeAtob(jsString.substringAfter("var Epinfri = window.atob('").substringBefore("');"))
        }
        val playerUrl = decodeAtob(jsString.substringAfter("var gkrrxx = '").substringBefore("';"))

        val apiResp = client.newCall(
            GET(baseUrl + apiPath + slug + "&_=${System.currentTimeMillis() / 1000}")
        ).execute()

        val apiJson = apiResp.body?.let { Json.decodeFromString<JsonObject>(it.string()) }

        val serversHtml = apiJson!!["result"]!!
            .jsonObject["anime"]!!
            .jsonArray[0]
            .jsonObject["serversHTML"]!!
            .jsonPrimitive.content
        val serversSoup = Jsoup.parse(serversHtml)

        for (server in serversSoup.select("body > div")) {
            if (server.attr("isembedurl") == "true") {
                val iframeUrl = String(Base64.decode(server.attr("hl"), Base64.DEFAULT))
                when {
                    iframeUrl.contains("dailymotion.com") -> {
                        val extractor = DailyMotionExtractor(client)

                        for (video in extractor.videoFromUrl(iframeUrl)) {
                            videoList.add(video)
                        }
                    }
                }
            } else {
                val sourceElement = client.newCall(
                    GET("https:" + playerUrl + server.attr("hl") + "&_=${System.currentTimeMillis() / 1000}")
                ).execute().asJsoup().selectFirst("source")

                val videoUrl = sourceElement.attr("src").replace("^//".toRegex(), "https://")

                videoList.add(
                    Video(
                        videoUrl,
                        "1080p (${server.select("small").text()})",
                        videoUrl
                    )
                )
            }
        }

        return videoList.sort()
    }

    override fun videoListSelector() = throw Exception("Not used")

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

    override fun videoFromElement(element: Element) = throw Exception("Not used")

    override fun videoUrlParse(document: Document) = throw Exception("Not used")

    // search

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        val (animes, hasNextPage) = if (response.request.url.encodedPath.startsWith("/xz/searchgrid")) {
            getAnimesFromSearch(document)
        } else {
            getAnimesFromTags(document)
        }
        return AnimesPage(animes, hasNextPage)
    }

    private fun getAnimesFromSearch(document: Document): Pair<List<SAnime>, Boolean> {
        val animeList = mutableListOf<SAnime>()
        for (item in document.select("div.grid > div.grid__item")) {
            val anime = SAnime.create()

            anime.title = item.select("div.tixtlis").text()
            anime.thumbnail_url = item.select("img").attr("src").replace("^//".toRegex(), "https://")
            anime.setUrlWithoutDomain(item.select("a").attr("href").toHttpUrl().encodedPath)

            animeList.add(anime)
        }

        return Pair(animeList, animeList.size == 12)
    }

    private fun getAnimesFromTags(document: Document): Pair<List<SAnime>, Boolean> {
        val animeList = mutableListOf<SAnime>()
        for (item in document.select("div.itemdtagk")) {
            val anime = SAnime.create()

            anime.title = item.select("div.titlekf").text()
            anime.thumbnail_url = item.select("img").attr("src").replace("^//".toRegex(), "https://")
            anime.setUrlWithoutDomain(("https:" + item.select("a").attr("href")).toHttpUrl().encodedPath)

            animeList.add(anime)
        }

        return Pair(animeList, false)
    }

    override fun searchAnimeSelector(): String = throw Exception("Not used")

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("Not used")

    // override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotEmpty()) {
            GET("$baseUrl/xz/searchgrid.php?p=$page&limit=12&s=$query&_=${System.currentTimeMillis() / 1000}", headers)
        } else {

            val genreFilter = (filters.find { it is TagFilter } as TagFilter).state.filter { it.state }

            var categories = mutableListOf<String>()

            genreFilter.forEach { categories.add(it.name) }

            GET("$baseUrl/xz/v3/taglist.php?tags=${categories.joinToString(separator = ",,")}&_=${System.currentTimeMillis() / 1000}", headers)
        }
        return url
    }

    // Filters

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        TagFilter("Tags", checkboxesFrom(tagsList))
    )

    private fun checkboxesFrom(tagArray: Array<Pair<String, String>>): List<TagCheckBox> = tagArray.map { TagCheckBox(it.second) }

    class TagCheckBox(tag: String) : AnimeFilter.CheckBox(tag, false)

    class TagFilter(name: String, checkBoxes: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, checkBoxes)

    val tagsList = arrayOf(
        Pair("1080p", "1080p"),
        Pair("Action", "Action"),
        Pair("Adventure", "Adventure"),
        Pair("Aliens", "Aliens"),
        Pair("Assassins", "Assassins"),
        Pair("Boku no Hero", "Boku no Hero"),
        Pair("Cg Animation", "Cg Animation"),
        Pair("Comedy", "Comedy"),
        Pair("Coming Of Age", "Coming Of Age"),
        Pair("Crossdressing", "Crossdressing"),
        Pair("Daily Life", "Daily Life"),
        Pair("Demons", "Demons"),
        Pair("Dragons", "Dragons"),
        Pair("Drama", "Drama"),
        Pair("Dystopia", "Dystopia"),
        Pair("Ecchi", "Ecchi"),
        Pair("Episodic", "Episodic"),
        Pair("Europe", "Europe"),
        Pair("Explicit Sex", "Explicit Sex"),
        Pair("Explicit Violence", "Explicit Violence"),
        Pair("Fantasy", "Fantasy"),
        Pair("Fate Stay Night", "Fate Stay Night"),
        Pair("Food And Beverage", "Food And Beverage"),
        Pair("Futuristic", "Futuristic"),
        Pair("Game", "Game"),
        Pair("Goku", "Goku"),
        Pair("Gore", "Gore"),
        Pair("Gunfights", "Gunfights"),
        Pair("Hand To Hand Combat", "Hand To Hand Combat"),
        Pair("Harem", "Harem"),
        Pair("High School", "High School"),
        Pair("High Stakes Games", "High Stakes Games"),
        Pair("Highschool dxd", "Highschool dxd"),
        Pair("Highschool", "Highschool"),
        Pair("Historical", "Historical"),
        Pair("Horror", "Horror"),
        Pair("Josei", "Josei"),
        Pair("Magic School", "Magic School"),
        Pair("Magic", "Magic"),
        Pair("Magical Girl", "Magical Girl"),
        Pair("Maids", "Maids"),
        Pair("Manga", "Manga"),
        Pair("Martial Arts", "Martial Arts"),
        Pair("Master-servant Relationship", "Master-servant Relationship"),
        Pair("Mature Themes", "Mature Themes"),
        Pair("Mecha", "Mecha"),
        Pair("Medieval", "Medieval"),
        Pair("Mercenaries", "Mercenaries"),
        Pair("Military", "Military"),
        Pair("Mmorpg", "Mmorpg"),
        Pair("Monsters", "Monsters"),
        Pair("Music", "Music"),
        Pair("Mystery", "Mystery"),
        Pair("Netflix", "Netflix"),
        Pair("Newly Co-ed School", "Newly Co-ed School"),
        Pair("Noitamina", "Noitamina"),
        Pair("Nudity", "Nudity"),
        Pair("Otaku Culture", "Otaku Culture"),
        Pair("Outer Space", "Outer Space"),
        Pair("OVA", "OVA"),
        Pair("Pandemic", "Pandemic"),
        Pair("Panty Shots", "Panty Shots"),
        Pair("Parody", "Parody"),
        Pair("Person In A Strange World", "Person In A Strange World"),
        Pair("Play Or Die", "Play Or Die"),
        Pair("Police", "Police"),
        Pair("Political", "Political"),
        Pair("Post-apocalyptic", "Post-apocalyptic"),
        Pair("Psychic Powers", "Psychic Powers"),
        Pair("Psychological", "Psychological"),
        Pair("Revenge", "Revenge"),
        Pair("Robots", "Robots"),
        Pair("Romance", "Romance"),
        Pair("Rpg", "Rpg"),
        Pair("Samurai", "Samurai"),
        Pair("School Club", "School Club"),
        Pair("School Life", "School Life"),
        Pair("School", "School"),
        Pair("Sci Fi", "Sci Fi"),
        Pair("Sci-Fi", "Sci-Fi"),
        Pair("Seinen", "Seinen"),
        Pair("Sexual Content", "Sexual Content"),
        Pair("Shingeki no Kyojin", "Shingeki no Kyojin"),
        Pair("Shoujo Ai", "Shoujo Ai"),
        Pair("Shoujo", "Shoujo"),
        Pair("Shoujo-ai", "Shoujo-ai"),
        Pair("Shounen Ai", "Shounen Ai"),
        Pair("Shounen", "Shounen"),
        Pair("Slice Of Life", "Slice Of Life"),
        Pair("Slice of Life", "Slice of Life"),
        Pair("Sports", "Sports"),
        Pair("Sudden Girlfriend Appearance", "Sudden Girlfriend Appearance"),
        Pair("Super Power", "Super Power"),
        Pair("Supernatural", "Supernatural"),
        Pair("Superpowers", "Superpowers"),
        Pair("Survival", "Survival"),
        Pair("Swordplay", "Swordplay"),
        Pair("Thriller", "Thriller"),
        Pair("Time Travel", "Time Travel"),
        Pair("Tournaments", "Tournaments"),
        Pair("Tsundere", "Tsundere"),
        Pair("Vampire", "Vampire"),
        Pair("Vampires", "Vampires"),
        Pair("Violence", "Violence"),
        Pair("Virtual Reality", "Virtual Reality"),
        Pair("War", "War"),
        Pair("Work Life", "Work Life"),
        Pair("Zombies", "Zombies")
    )

    // Details

    override fun animeDetailsParse(response: Response): SAnime {
        val anime = SAnime.create()
        if (response.request.url.encodedPath.startsWith("/movies/")) {
            val slug = response.request.url.toString().split(".com/movies/")[1]

            val apiResp = client.newCall(
                GET(baseUrl + "/movies/jsonMovie.php?slug=" + slug + "&_=${System.currentTimeMillis() / 1000}")
            ).execute()

            val apiJson = apiResp.body?.let { Json.decodeFromString<JsonObject>(it.string()) }
            val animeJson = apiJson!!["result"]!!
                .jsonObject["anime"]!!
                .jsonArray[0]
                .jsonObject

            anime.title = animeJson["title"]!!.jsonPrimitive.content
            anime.description = animeJson["desc"]!!.jsonPrimitive.content
            anime.status = animeJson["status"]?.jsonPrimitive?.let { parseStatus(it.content) } ?: SAnime.UNKNOWN
            anime.genre = Jsoup.parse(animeJson["tags"]!!.jsonPrimitive.content).select("a").eachText().joinToString(separator = ", ")
        } else {
            val document = response.asJsoup()
            val info = document.select("div.animeDescript")
            anime.description = info.select("p").text()

            for (header in info.select("div > div")) {
                if (header.text().contains("Status")) {
                    anime.status = parseStatus(header.text())
                }
            }

            anime.genre = document.select("div[itemprop=keywords] > a").eachText().joinToString(separator = ", ")
        }

        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Ongoing") -> SAnime.ONGOING
            statusString.contains("Completed") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun animeDetailsParse(document: Document): SAnime = throw Exception("Not used")

    // Latest

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val (animes, hasNextPage) = getAnimesFromLatest(document)
        return AnimesPage(animes, hasNextPage)
    }

    private fun getAnimesFromLatest(document: Document): Pair<List<SAnime>, Boolean> {
        val animeList = mutableListOf<SAnime>()
        for (item in document.select("div.grid > div.grid__item")) {
            val anime = SAnime.create()

            anime.title = item.select("div.tixtlis").text()
            anime.thumbnail_url = item.select("img").attr("src").replace("^//".toRegex(), "https://")
            anime.setUrlWithoutDomain(item.select("a").attr("href").toHttpUrl().encodedPath)

            animeList.add(anime)
        }

        return Pair(animeList, animeList.size == 12)
    }

    override fun latestUpdatesNextPageSelector(): String = throw Exception("Not used")

    override fun latestUpdatesFromElement(element: Element): SAnime = throw Exception("Not used")

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/xz/gridgrabrecent.php?p=$page&limit=12&_=${System.currentTimeMillis() / 1000}", headers)
    }

    override fun latestUpdatesSelector(): String = throw Exception("Not used")

    // settings

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
        screen.addPreference(videoQualityPref)
    }
}
