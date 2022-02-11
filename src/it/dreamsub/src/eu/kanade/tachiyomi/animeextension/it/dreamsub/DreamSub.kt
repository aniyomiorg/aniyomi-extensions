package eu.kanade.tachiyomi.animeextension.it.dreamsub

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
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
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception
import java.text.SimpleDateFormat

class DreamSub : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "DreamSub"

    override val baseUrl = "https://dreamsub.cc"

    override val lang = "it"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.goblock-content.go-full div.tvBlock"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/search?page=${page - 1}")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.url = element.selectFirst("div.showStreaming a").attr("href")
        anime.title = element.select("div.tvTitle").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        if (document.selectFirst(episodeListSelector()) == null) {
            return oneEpisodeParse(document)
        }
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    private fun formatDate(scrape_time: String): String = scrape_time.filter { !it.isWhitespace() }
        .replace("Gennaio", "-01-")
        .replace("Febbraio", "-02-")
        .replace("Marzo", "-03-")
        .replace("Aprile", "-04-")
        .replace("Maggio", "-05-")
        .replace("Giugno", "-06-")
        .replace("Luglio", "-07-")
        .replace("Agosto", "-08-")
        .replace("Settembre", "-09-")
        .replace("Ottobre", "-10-")
        .replace("Novembre", "-11-")
        .replace("Dicembre", "-12-")

    @SuppressLint("SimpleDateFormat")
    private fun parseDate(scrape_time: String): Long {
        return SimpleDateFormat("dd-MM-yyyy").parse(scrape_time).time
    }

    private fun oneEpisodeParse(document: Document): List<SEpisode> {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(document.location())
        episode.episode_number = 1F
        episode.name = document.selectFirst("ol.breadcrumb li a").text()
        episode.date_upload = parseDate(
            formatDate(
                document.select("div.dcis").text()
                    .substringBefore(", Conclusa").substringAfter("Data:")
            )
        )
        return listOf(episode)
    }

    override fun episodeListSelector() = "div.goblock.server-list li.ep-item:has(div.sli-btn)"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.selectFirst("div.sli-btn a").attr("href"))
        val epText = element.selectFirst("div.sli-name a").text()
        episode.episode_number = epText.substringAfter("Episodio ").substringBefore(":").toFloat()
        episode.name = epText.replace(": TBA", "")
        episode.date_upload = parseDate(formatDate(element.selectFirst("div.sli-name span").text()))
        return episode
    }

    override fun videoListSelector() = "div#main-content.onlyDesktop a.dwButton"

    override fun videoFromElement(element: Element): Video {
        val referer = element.ownerDocument().location()
        val url = element.attr("href")
        val quality = element.firstElementSibling().text() + element.text()
        return Video(url, quality, url, null, Headers.headersOf("Referer", referer))
    }

    override fun List<Video>.sort(): List<Video> {
        val subPreference = preferences.getString("preferred_sub", "SUB ITA")!!
        val quality = preferences.getString("preferred_quality", "1080")!!
        val qualityList = mutableListOf<Video>()
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (video.quality.contains(quality)) {
                qualityList.add(preferred, video)
                preferred++
            } else {
                qualityList.add(video)
            }
        }
        preferred = 0
        for (video in qualityList) {
            if (video.quality.startsWith(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else {
                newList.add(video)
            }
        }
        return newList
    }

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.url = element.selectFirst("div.showStreaming a").attr("href")
        anime.title = element.select("div.tvTitle").text()
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    override fun searchAnimeSelector(): String = "div.goblock-content.go-full div.tvBlock"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return GET("$baseUrl/search?$parameters&q=$query&page=${page - 1}")
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.dc-info h1.dc-title a").text()
        anime.genre = document.select("div.dc-info div.dci-spe div.dcis a").joinToString { it.text() }
        val descriptiontry = document.select("div.dc-info div.dci-desc span#tramaLong").firstOrNull()?.ownText()
        if (descriptiontry.isNullOrEmpty()) anime.description = document.select("div.dc-info div.dci-desc span").firstOrNull()?.ownText()
        else anime.description = descriptiontry
        anime.status = parseStatus(document.select("div.dcis:contains(Data:)").text())
        anime.thumbnail_url = "https:" + document.selectFirst("div.dc-thumb img").attr("src")
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("In Corso") -> {
                SAnime.ONGOING
            }
            statusString.contains("Conclusa") -> {
                SAnime.COMPLETED
            }
            else -> {
                SAnime.UNKNOWN
            }
        }
    }

    override fun latestUpdatesNextPageSelector(): String = "ul.pagination-list li:last-child:not(.selected)"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val href = element.select("a.thumb").attr("href")
        if (href.count { it == '/' } == 2) {
            anime.setUrlWithoutDomain(baseUrl + href)
        } else {
            anime.setUrlWithoutDomain(baseUrl + href.substringBeforeLast("/"))
        }
        anime.title = element.select("div.item-detail").text()
        return anime
    }

    override fun latestUpdatesRequest(page: Int): Request = GET(baseUrl)

    override fun latestUpdatesSelector(): String = "div.vistaGriglia ul.grid-item li"

    internal class Type(val id: String) : AnimeFilter.TriState(id)
    private class TypeList(types: List<Type>) : AnimeFilter.Group<Type>("Tipo", types)
    private fun getTypes() = listOf(
        Type("TV"),
        Type("Movie"),
        Type("OAV"),
        Type("Spinoff"),
        Type("Special"),
        Type("ONA")
    )

    internal class Genre(val id: String, name: String) : AnimeFilter.TriState(name)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("2|", "Arti Marziali"),
        Genre("4|", "Avventura"),
        Genre("5|", "Azione"),
        Genre("6|", "Bambini"),
        Genre("9|", "Commedia"),
        Genre("10|", "Crimine"),
        Genre("11|", "Demenziale"),
        Genre("12|", "Demoni"),
        Genre("14|", "Dramma"),
        Genre("15|", "Ecchi"),
        Genre("17|", "Fantascienza"),
        Genre("18|", "Fantasy"),
        Genre("19|", "Giallo"),
        Genre("22|", "Harem"),
        Genre("23|", "Hentai"),
        Genre("24|", "Horror"),
        Genre("25|", "Josei"),
        Genre("27|", "Magia"),
        Genre("28|", "Mecha"),
        Genre("20|", "Militare"),
        Genre("47|", "Mistero"),
        Genre("30|", "Musica"),
        Genre("32|", "Parodia"),
        Genre("33|", "Poliziesco"),
        Genre("35|", "Psicologico"),
        Genre("36|", "Samurai"),
        Genre("37|", "Scuola"),
        Genre("38|", "Seinen"),
        Genre("39|", "Sentimentale"),
        Genre("40|", "Shojo"),
        Genre("41|", "Shonen"),
        Genre("56|", "Shounen Ai"),
        Genre("52|", "Slice of Life"),
        Genre("42|", "Sovrannaturale"),
        Genre("21|", "Spazio"),
        Genre("43|", "Sport"),
        Genre("44|", "Storico"),
        Genre("45|", "Superpoteri"),
        Genre("48|", "Vampiri"),
        Genre("49|", "Videogame"),
        Genre("53|", "Yaoi"),
        Genre("54|", "Yuri")
    )

    internal class State(val id: String, name: String) : AnimeFilter.TriState(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("inCorso", "In Corso"),
        State("future", "Future"),
        State("concluse", "Concluse")
    )

    private val sortableList = listOf(
        Pair("Popolarità", "ppl-"),
        Pair("Alfabetico", "az-"),
        Pair("Punteggio", "score-"),
        Pair("Ultime Aggiunte", "lat-"),
        Pair("Anno di Uscita", "yea-"),
        Pair("Numero Episodi", "eps-"),
        Pair("Visualizzazioni", "view-")
    )
    class SortFilter(sortables: Array<String>) : AnimeFilter.Sort("Ordina Per", sortables, Selection(0, true))

    private class ScoreFilter :
        AnimeFilter.Select<String>(
            "Voto Minimo",
            arrayOf(
                "<Seleziona>",
                "0",
                "0.5",
                "1",
                "1.5",
                "2",
                "2.5",
                "3",
                "3.5",
                "4",
                "4.5",
                "5",
                "5.5",
                "6",
                "6.5",
                "7",
                "7.5",
                "8",
                "8.5",
                "9",
                "9.5",
                "10"
            ),
            0
        )

    internal class MinYear(val input: String, name: String) : AnimeFilter.Text(name)
    internal class MaxYear(val input: String, name: String) : AnimeFilter.Text(name)

    internal class MinEps(val input: String, name: String) : AnimeFilter.Text(name)
    internal class MaxEps(val input: String, name: String) : AnimeFilter.Text(name)

    internal class Length(val input: String, name: String) : AnimeFilter.Text(name)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeList(getTypes()),
        GenreList(getGenres()),
        AnimeFilter.Header("Anno di Uscita"),
        MinYear("1968", "Minimo Anno di Uscita(Default: 1968)"), // 1968 default
        MaxYear("2022", "Massimo Anno di Uscita(Default: 2022)"), // 2022 default
        ScoreFilter(),
        AnimeFilter.Header("Numero Episodi"),
        MinEps("1", "Minimo Episodi"), // 1 default
        MaxEps("1105", "Massimo Episodi(Default: 1105)"), // 1105 default
        AnimeFilter.Header("Durata Episodi(minuti)"),
        Length("216", "Massima Durata(Default: 216)"), // 216 default
        StateList(getStates()),
        SortFilter(sortableList.map { it.first }.toTypedArray()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var finalstring = ""
        var includedTypes = ""
        var blackListedTypes = ""

        var includedGenres = ""
        var blackListedGenres = ""

        var includedStates = ""
        var blackListedStates = ""

        var sortString = ""

        var minyear = ""
        var maxyear = ""
        var mineps = ""
        var maxeps = ""

        filters.forEach { filter ->
            when (filter) {
                is TypeList -> {
                    filter.state.forEach { type ->
                        if (type.isIncluded()) {
                            includedTypes += type.id.toLowerCase()
                        } else if (type.isExcluded()) {
                            blackListedTypes += type.id.toLowerCase()
                        }
                    }
                }

                is GenreList -> {
                    filter.state.forEach { genre ->
                        if (genre.isIncluded()) {
                            includedGenres += genre.id
                        } else if (genre.isExcluded()) {
                            blackListedGenres += genre.id
                        }
                    }
                }

                is StateList -> {
                    filter.state.forEach { state ->
                        if (state.isIncluded()) {
                            includedStates += state.id
                        } else if (state.isExcluded()) {
                            blackListedStates += state.id
                        }
                    }
                }
                is ScoreFilter -> {
                    if (filter.values[filter.state] != "<Seleziona>") finalstring += "voto=${filter.values[filter.state]}"
                }
                is SortFilter -> {
                    if (filter.state != null) {
                        when (sortableList[filter.state!!.index].second) {
                            "az-" -> sortString += "&order=A-Z"
                            "score-" -> sortString += "&order=rating"
                            "lat-" -> sortString += "&order=recenti"
                            "yea-" -> sortString += "&order=anno"
                            "eps-" -> sortString += "&order=episodi"
                            "view-" -> sortString += "&order=views"
                        }
                        when (filter.state!!.ascending) {
                            false -> sortString += "&not_order=true"
                        }
                    }
                }
                is MinYear -> {
                    minyear = if (filter.state.isEmpty()) "1968" // default value
                    else filter.state
                }
                is MaxYear -> {
                    maxyear = if (filter.state.isEmpty()) "2022" // default value
                    else filter.state
                }
                is MinEps -> {
                    mineps = if (filter.state.isEmpty()) "1" // default value
                    else filter.state
                }
                is MaxEps -> {
                    maxeps = if (filter.state.isEmpty()) "1105" // default value
                    else filter.state
                }
                is Length -> {
                    finalstring += if (filter.state.isEmpty()) "&maxEpLenght=216" // default value
                    else "&maxEpLenght=${filter.state}"
                }
                else -> {}
            }
        }
        if (includedTypes.isNotEmpty()) finalstring += "&typeY=|$includedTypes"
        if (blackListedTypes.isNotEmpty()) finalstring += "&typeN=|$blackListedTypes"
        if (includedGenres.isNotEmpty()) finalstring += "&genereY=|$includedGenres"
        if (blackListedGenres.isNotEmpty()) finalstring += "&genereN=|$blackListedGenres"
        if (includedStates.isNotEmpty()) finalstring += "&statesY=|$includedStates"
        if (blackListedStates.isNotEmpty()) finalstring += "&statesN=|$blackListedStates"
        finalstring += "&year=$minyear-$maxyear" // year release
        finalstring += "&episodes=$mineps-$maxeps" // amount episodes
        finalstring += sortString
        return finalstring
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualità preferita"
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
        val subPref = ListPreference(screen.context).apply {
            key = "preferred_sub"
            title = "Preferisci sub o dub?"
            entries = arrayOf("sub", "dub")
            entryValues = arrayOf("SUB ITA", "ITA")
            setDefaultValue("SUB ITA")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(subPref)
    }
}
