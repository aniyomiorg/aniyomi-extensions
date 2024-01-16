package eu.kanade.tachiyomi.animeextension.en.bestdubbedanime

import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class BestDubbedAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "BestDubbedAnime"

    override val baseUrl = "https://bestdubbedanime.com"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/xz/trending.php?_=${System.currentTimeMillis() / 1000}")

    override fun popularAnimeSelector(): String = "li"

    override fun popularAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        thumbnail_url = element.select("img").attr("abs:src")
        title = element.select("div.cittx").text()
    }

    override fun popularAnimeNextPageSelector(): String? = null

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/xz/gridgrabrecent.php?p=$page&limit=12&_=${System.currentTimeMillis() / 1000}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val (animes, hasNextPage) = getAnimesFromLatest(document)
        return AnimesPage(animes, hasNextPage)
    }

    private fun getAnimesFromLatest(document: Document): Pair<List<SAnime>, Boolean> {
        val animeList = document.select("div.grid > div.grid__item").map { item ->
            latestUpdatesFromElement(item)
        }
        return Pair(animeList, animeList.size == 12)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime = SAnime.create().apply {
        setUrlWithoutDomain(element.select("a").attr("abs:href"))
        thumbnail_url = element.select("img").attr("abs:src")
        title = element.select("div.tixtlis").text()
    }

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            GET("$baseUrl/xz/searchgrid.php?p=$page&limit=12&s=$query&_=${System.currentTimeMillis() / 1000}", headers)
        } else {
            val genreFilter = (filters.find { it is TagFilter } as TagFilter).state.filter { it.state }
            val categories = genreFilter.map { it.name }

            GET("$baseUrl/xz/v3/taglist.php?tags=${categories.joinToString(separator = ",,")}&_=${System.currentTimeMillis() / 1000}", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()

        return if (response.request.url.encodedPath.startsWith("/xz/searchgrid")) {
            getAnimesPageFromSearch(document)
        } else {
            getAnimesPageFromTags(document)
        }
    }

    private fun getAnimesPageFromSearch(document: Document): AnimesPage {
        val animeList = document.select("div.grid > div.grid__item").map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.select("a").attr("abs:href"))
                thumbnail_url = item.select("img").attr("abs:src")
                title = item.select("div.tixtlis").text()
            }
        }
        return AnimesPage(animeList, animeList.size == 12)
    }

    private fun getAnimesPageFromTags(document: Document): AnimesPage {
        val animeList = document.select("div.itemdtagk").map { item ->
            SAnime.create().apply {
                setUrlWithoutDomain(item.select("a").attr("abs:href"))
                thumbnail_url = item.select("img").attr("abs:src")
                title = item.select("div.titlekf").text()
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    override fun animeDetailsParse(response: Response): SAnime {
        return if (response.request.url.encodedPath.startsWith("/movies/")) {
            val slug = response.request.url.toString().split(".com/movies/")[1]

            val apiResp = client.newCall(
                GET(baseUrl + "/movies/jsonMovie.php?slug=" + slug + "&_=${System.currentTimeMillis() / 1000}"),
            ).execute()

            val apiJson = apiResp.body.let { Json.decodeFromString<JsonObject>(it.string()) }
            val animeJson = apiJson["result"]!!
                .jsonObject["anime"]!!
                .jsonArray[0]
                .jsonObject

            SAnime.create().apply {
                title = animeJson["title"]!!.jsonPrimitive.content
                description = animeJson["desc"]!!.jsonPrimitive.content
                status = animeJson["status"]?.jsonPrimitive?.let { parseStatus(it.content) } ?: SAnime.UNKNOWN
                genre = Jsoup.parse(animeJson["tags"]!!.jsonPrimitive.content).select("a").eachText().joinToString(separator = ", ")
            }
        } else {
            val document = response.asJsoup()
            val info = document.select("div.animeDescript")

            SAnime.create().apply {
                genre = document.select("div[itemprop=keywords] > a").eachText().joinToString(separator = ", ")
                description = info.select("p").text()
                status = info.select("div > div").firstOrNull {
                    it.text().contains("Status")
                }?.let { parseStatus(it.text()) } ?: SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    // Episodes
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val episodeList = mutableListOf<SEpisode>()

        if (response.request.url.encodedPath.startsWith("/movies/")) {
            episodeList.add(
                SEpisode.create().apply {
                    name = document.select("div.tinywells > div > h4").text()
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            var counter = 1
            for (ep in document.select("div.eplistz > div > div > a")) {
                episodeList.add(
                    SEpisode.create().apply {
                        name = ep.select("div.inwel > span").text()
                        episode_number = counter.toFloat()
                        setUrlWithoutDomain(ep.attr("abs:href"))
                    },
                )
                counter++
            }

            if (document.select("div.eplistz > div > div > a").isEmpty()) {
                val cacheUrlRegex = Regex("""url: '(.*?)'(?:.*?)episodesListxf""", RegexOption.DOT_MATCHES_ALL)

                val jsText = document.selectFirst("script:containsData(episodesListxf)")!!.data()
                cacheUrlRegex.find(jsText)?.groupValues?.get(1)?.let {
                    episodeList.addAll(extractFromCache(it))
                }
            }
        }

        return episodeList.reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val videoList = mutableListOf<Video>()

        val slug = response.request.url.encodedPath.substringAfter("/")
        val serverHeaders = headers.newBuilder()
            .add("Accept", "application/json, text/javascript, */*; q=0.01")
            .add("Host", baseUrl.toHttpUrl().host)
            .add("Referer", response.request.url.toString())
            .add("X-Requested-With", "XMLHttpRequest")
            .build()

        val serversResponse = client.newCall(
            GET("$baseUrl/xz/v3/jsonEpi.php?slug=$slug&_=${System.currentTimeMillis() / 1000}", headers = serverHeaders),
        ).execute().body.string()
        val parsed = json.decodeFromString<ServerResponse>(serversResponse)
        Jsoup.parse(parsed.result.anime.first().serversHTML).select("div.serversks").parallelMapBlocking { player ->
            val playerHeaders = headers.newBuilder()
                .add("Accept", "*/*")
                .add("Host", baseUrl.toHttpUrl().host)
                .add("Referer", response.request.url.toString())
                .add("X-Requested-With", "XMLHttpRequest")
                .build()
            runCatching {
                val playerResponse = client.newCall(
                    GET("$baseUrl/xz/api/playeri.php?url=${player.attr("hl")}&_=${System.currentTimeMillis() / 1000}", headers = playerHeaders),
                ).execute().asJsoup()
                playerResponse.select("source").forEach { source ->
                    val url = source.attr("src")
                    if (url.isNotBlank()) {
                        videoList.add(
                            Video(url, "${source.attr("label")}p ${player.text()}", url),
                        )
                    }
                }
            }
        }

        require(videoList.isNotEmpty()) { "Failed to fetch videos" }

        return videoList.sort()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun extractFromCache(url: String): List<SEpisode> {
        val cacheHeaders = headers.newBuilder()
            .add("Accept", "*/*")
            .add("Origin", baseUrl)
            .add("Referer", "$baseUrl/")
            .build()

        val soup = client.newCall(GET(url, headers = cacheHeaders)).execute().asJsoup()
        return soup.select("a").mapIndexed { index, ep ->
            SEpisode.create().apply {
                name = ep.select("div.inwel > span").text()
                episode_number = (index + 1).toFloat()
                setUrlWithoutDomain(ep.attr("abs:href"))
            }
        }
    }

    @Serializable
    data class ServerResponse(
        val result: ResultObject,
    ) {
        @Serializable
        data class ResultObject(
            val anime: List<AnimeObject>,
        ) {
            @Serializable
            data class AnimeObject(
                val serversHTML: String,
            )
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Ongoing") -> SAnime.ONGOING
            statusString.contains("Completed") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p")
            entryValues = arrayOf("1080", "720", "480", "360", "240")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================== Filters ===============================

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("NOTE: Ignored if using text search!"),
        AnimeFilter.Separator(),
        TagFilter("Tags", checkboxesFrom(tagsList)),
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
        Pair("Zombies", "Zombies"),
    )
}
