package eu.kanade.tachiyomi.animeextension.it.animeworld

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.it.animeworld.extractors.StreamHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class ANIMEWORLD : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "ANIMEWORLD.tv"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://www.animeworld.so"

    override val lang = "it"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Popular Anime - Same Format as Search

    override fun popularAnimeSelector(): String = searchAnimeSelector()
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/filter?sort=6&page=$page")
    override fun popularAnimeFromElement(element: Element): SAnime = searchAnimeFromElement(element)
    override fun popularAnimeNextPageSelector(): String = searchAnimeNextPageSelector()

    // Episodes

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    override fun episodeListSelector() = "div.server.active ul.episodes li.episode a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("abs:href"))
        episode.name = "Episode: " + element.text()
        val epNum = getNumberFromEpsString(element.text())
        episode.episode_number = when {
            epNum.isNotEmpty() -> epNum.toFloatOrNull() ?: 1F
            else -> 1F
        }
        return episode
    }

    private fun getNumberFromEpsString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }
    }

    // Video urls

    override fun videoListRequest(episode: SEpisode): Request {
        val iframe = baseUrl + episode.url
        return GET(iframe)
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return videosFromElement(document)
    }

    override fun videoListSelector() = "center a[href*=https://doo]," +
        "center a[href*=streamtape]," +
        "center a[href*=animeworld.biz]," +
        "center a[href*=streamingaw.online][id=alternativeDownloadLink]"

    private fun videosFromElement(document: Document): List<Video> {
        // afaik this element appears when videos are taken down, in this case instead of
        // displaying Videolist empty show the element's text
        val copyrightError = document.select("div.alert.alert-primary:contains(Copyright)")
        if (copyrightError.hasText()) throw Exception(copyrightError.text())

        val serverList = mutableListOf<Pair<String, String>>()

        val elements = document.select(videoListSelector())
        val epId = document.selectFirst("div#player[data-episode-id]")?.attr("data-episode-id")

        val altServers = mutableListOf<Pair<String, String>>()
        val altList = listOf("StreamHide", "FileMoon")
        document.select("div.servers > div.widget-title span.server-tab").forEach {
            val name = it.text()
            if (altList.any { t -> t.contains(name, true) }) {
                altServers.add(Pair(name, it.attr("data-name")))
            }
        }

        altServers.forEach { serverPair ->
            val dataId = document.selectFirst("div.server[data-name=${serverPair.second}] li.episode a[data-episode-id=$epId]")?.attr("data-id")
            dataId?.let {
                val apiUrl = "$baseUrl/api/episode/info?id=$it&alt=0"
                val apiHeaders = headers.newBuilder()
                    .add("Accept", "application/json, text/javascript, */*; q=0.01")
                    .add("Content-Type", "application/json")
                    .add("Host", baseUrl.toHttpUrl().host)
                    .add("Referer", document.location())
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()
                val target = json.decodeFromString<ServerResponse>(
                    client.newCall(GET(apiUrl, headers = apiHeaders)).execute().body.string(),
                ).target
                serverList.add(Pair(serverPair.first, target))
            }
        }

        for (element in elements) {
            val url = element.attr("href")
            val name = element.text().substringAfter("ownload ").substringBefore(" ")
            serverList.add(Pair(name, url))
        }

        val videoList = serverList.parallelCatchingFlatMapBlocking { server ->
            val url = server.second
            when {
                url.contains("streamingaw") -> {
                    listOf(Video(url, "AnimeWorld Server", url))
                }
                url.contains("https://doo") -> {
                    DoodExtractor(client).videoFromUrl(url, redirect = true)
                        ?.let(::listOf)
                }
                url.contains("streamtape") -> {
                    StreamTapeExtractor(client).videoFromUrl(url.replace("/v/", "/e/"))
                        ?.let(::listOf)
                }
                url.contains("filemoon") -> {
                    FilemoonExtractor(client).videosFromUrl(url, prefix = "${server.first} - ", headers = headers)
                }
                server.first.contains("streamhide", true) -> {
                    StreamHideExtractor(client).videosFromUrl(url, headers)
                }
                else -> null
            }.orEmpty()
        }

        return videoList
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val server = preferences.getString("preferred_server", "Animeworld server")!!

        return sortedWith(
            compareBy(
                { it.quality.lowercase().contains(server.lowercase()) },
                { it.quality.lowercase().contains(quality.lowercase()) },
            ),
        ).reversed()
    }

    // search

    override fun searchAnimeSelector(): String = "div.film-list div.item div.inner a.poster"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.attr("abs:href"))
        anime.thumbnail_url = element.select("img").attr("src")
        anime.title = element.select("img").attr("alt")
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "div.paging-wrapper a#go-next-page"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/filter?${getSearchParameters(filters)}&keyword=$query&page=$page")

    // Details

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.selectFirst("div.thumb img")!!.attr("src")
        anime.title = document.select("div.c1 h2.title").text()
        val dl = document.select("div.info dl")
        anime.genre = dl.select("dd:has(a[href*=language]) a, dd:has(a[href*=genre]) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.desc").text()
        anime.author = dl.select("dd:has(a[href*=studio]) a").joinToString(", ") { it.text() }
        anime.status = parseStatus(dl.select("dd:has(a[href*=status]) a").text().replace("Status: ", ""))
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "In corso" -> SAnime.ONGOING
            "Finito" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    // Latest - Same format as search

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/updated?page=$page")
    override fun latestUpdatesSelector(): String = searchAnimeSelector()
    override fun latestUpdatesNextPageSelector(): String = searchAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element): SAnime = searchAnimeFromElement(element)

    // Filters

    internal class Genre(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("and", "Mode: AND"),
        Genre("3", "Arti Marziali"),
        Genre("5", "Avanguardia"),
        Genre("2", "Avventura"),
        Genre("1", "Azione"),
        Genre("47", "Bambini"),
        Genre("4", "Commedia"),
        Genre("6", "Demoni"),
        Genre("7", "Drammatico"),
        Genre("8", "Ecchi"),
        Genre("9", "Fantasy"),
        Genre("10", "Gioco"),
        Genre("11", "Harem"),
        Genre("43", "Hentai"),
        Genre("13", "Horror"),
        Genre("14", "Josei"),
        Genre("16", "Magia"),
        Genre("18", "Mecha"),
        Genre("19", "Militari"),
        Genre("21", "Mistero"),
        Genre("20", "Musicale"),
        Genre("22", "Parodia"),
        Genre("23", "Polizia"),
        Genre("24", "Psicologico"),
        Genre("46", "Romantico"),
        Genre("26", "Samurai"),
        Genre("28", "Sci-Fi"),
        Genre("27", "Scolastico"),
        Genre("29", "Seinen"),
        Genre("25", "Sentimentale"),
        Genre("30", "Shoujo"),
        Genre("31", "Shoujo Ai"),
        Genre("32", "Shounen"),
        Genre("33", "Shounen Ai"),
        Genre("34", "Slice of Life"),
        Genre("35", "Spazio"),
        Genre("37", "Soprannaturale"),
        Genre("36", "Sport"),
        Genre("12", "Storico"),
        Genre("38", "Superpoteri"),
        Genre("39", "Thriller"),
        Genre("40", "Vampiri"),
        Genre("48", "Veicoli"),
        Genre("41", "Yaoi"),
        Genre("42", "Yuri"),
    )

    internal class Season(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class SeasonList(seasons: List<Season>) : AnimeFilter.Group<Season>("Stagioni", seasons)
    private fun getSeasons() = listOf(
        Season("winter", "Inverno"),
        Season("spring", "Primavera"),
        Season("summer", "Estate"),
        Season("fall", "Autunno"),
        Season("unknown", "Sconosciuto"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears() = listOf(
        Year("1966"),
        Year("1967"),
        Year("1969"),
        Year("1970"),
        Year("1973"),
        Year("1974"),
        Year("1975"),
        Year("1977"),
        Year("1978"),
        Year("1979"),
        Year("1980"),
        Year("1981"),
        Year("1982"),
        Year("1983"),
        Year("1984"),
        Year("1985"),
        Year("1986"),
        Year("1987"),
        Year("1988"),
        Year("1989"),
        Year("1990"),
        Year("1991"),
        Year("1992"),
        Year("1993"),
        Year("1994"),
        Year("1995"),
        Year("1996"),
        Year("1997"),
        Year("1998"),
        Year("1999"),
        Year("2000"),
        Year("2001"),
        Year("2002"),
        Year("2003"),
        Year("2004"),
        Year("2005"),
        Year("2006"),
        Year("2007"),
        Year("2008"),
        Year("2009"),
        Year("2010"),
        Year("2011"),
        Year("2012"),
        Year("2013"),
        Year("2014"),
        Year("2015"),
        Year("2016"),
        Year("2017"),
        Year("2018"),
        Year("2019"),
        Year("2020"),
        Year("2021"),
        Year("2022"),
    )

    internal class Type(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class TypeList(types: List<Type>) : AnimeFilter.Group<Type>("Tipo", types)
    private fun getTypes() = listOf(
        Type("0", "Anime"),
        Type("4", "Movie"),
        Type("1", "OVA"),
        Type("2", "ONA"),
        Type("3", "Special"),
        Type("5", "Music"),
    )

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Studio(val input: String, name: String) : AnimeFilter.Text(name)

    internal class Sub(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class SubList(subs: List<Sub>) : AnimeFilter.Group<Sub>("Sottotitoli", subs)
    private fun getSubs() = listOf(
        Sub("0", "Subbato"),
        Sub("1", "Doppiato"),
    )

    internal class Audio(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class AudioList(audios: List<Audio>) : AnimeFilter.Group<Audio>("Audio", audios)
    private fun getAudios() = listOf(
        Audio("jp", "Giapponese"),
        Audio("it", "Italiano"),
        Audio("ch", "Cinese"),
        Audio("kr", "Coreano"),
        Audio("en", "Inglese"),
    )

    private class OrderFilter :
        AnimeFilter.Select<String>(
            "Ordine",
            arrayOf(
                "Standard",
                "Ultime Aggiunte",
                "Lista A-Z",
                "Lista Z-A",
                "Più Vecchi",
                "Più Recenti",
                "Più Visti",
            ),
            0,
        )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""

        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring += if (Genre.id == "and") {
                                "&genre_mode=and"
                            } else {
                                "&genre=" + Genre.id
                            }
                        }
                    }
                }
                is SeasonList -> { // ---Season
                    filter.state.forEach { Season ->
                        if (Season.state) {
                            totalstring += "&season=" + Season.id
                        }
                    }
                }
                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring += "&year=" + Year.id
                        }
                    }
                }
                is TypeList -> { // ---Type
                    filter.state.forEach { Type ->
                        if (Type.state) {
                            totalstring += "&type=" + Type.id
                        }
                    }
                }
                is StateList -> { // ---State
                    filter.state.forEach { State ->
                        if (State.state) {
                            totalstring += "&status=" + State.id
                        }
                    }
                }
                is Studio -> {
                    if (filter.state.isNotEmpty()) {
                        val studios = filter.state.split(",").toTypedArray()
                        for (x in studios.indices) {
                            totalstring += "&studio=" + studios[x]
                        }
                    }
                }
                is SubList -> { // ---Subs
                    filter.state.forEach { Sub ->
                        if (Sub.state) {
                            totalstring += "&dub=" + Sub.id
                        }
                    }
                }
                is AudioList -> { // ---Audio
                    filter.state.forEach { Audio ->
                        if (Audio.state) {
                            totalstring += "&language=" + Audio.id
                        }
                    }
                }
                is OrderFilter -> {
                    if (filter.values[filter.state] == "Standard") totalstring += "&sort=0"
                    if (filter.values[filter.state] == "Ultime Aggiunte") totalstring += "&sort=1"
                    if (filter.values[filter.state] == "Lista A-Z") totalstring += "&sort=2"
                    if (filter.values[filter.state] == "Lista Z-A") totalstring += "&sort=3"
                    if (filter.values[filter.state] == "Più Vecchi") totalstring += "&sort=4"
                    if (filter.values[filter.state] == "Più Recenti") totalstring += "&sort=5"
                    if (filter.values[filter.state] == "Più Visti") totalstring += "&sort=6"
                }
                else -> {}
            }
        }
        return totalstring
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        GenreList(getGenres()),
        SeasonList(getSeasons()),
        YearList(getYears()),
        TypeList(getTypes()),
        StateList(getStates()),
        AnimeFilter.Header("Usa la virgola per separare i diversi studio"),
        Studio("", "Studio"),
        SubList(getSubs()),
        AudioList(getAudios()),
        OrderFilter(),
    )

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
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
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Animeworld server", "FileMoon", "StreamHide", "Doodstream", "StreamTape")
            entryValues = arrayOf("Animeworld server", "FileMoon", "StreamHide", "Doodstream", "StreamTape")
            setDefaultValue("Animeworld server")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // Utilities

    @Serializable
    data class ServerResponse(
        val target: String,
    )
}
