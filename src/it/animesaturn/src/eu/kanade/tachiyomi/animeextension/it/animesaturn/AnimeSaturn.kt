package eu.kanade.tachiyomi.animeextension.it.animesaturn

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
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeSaturn : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeSaturn"

    override val baseUrl by lazy { preferences.getString("preferred_domain", "https://animesaturn.in")!! }

    override val lang = "it"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.sebox"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animeincorso?page=$page")

    private fun formatTitle(titlestring: String): String = titlestring.replace("(ITA) ITA", "Dub ITA").replace("(ITA)", "Dub ITA").replace("Sub ITA", "")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("div.msebox div.headsebox div.tisebox h2 a")!!.attr("href"))
        anime.title = formatTitle(element.selectFirst("div.msebox div.headsebox div.tisebox h2 a")!!.text())
        anime.thumbnail_url = element.selectFirst("div.msebox div.bigsebox div.l img.attachment-post-thumbnail.size-post-thumbnail.wp-post-image")!!.attr("src")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
    }

    override fun episodeListSelector() = "div.btn-group.episodes-button.episodi-link-button"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.selectFirst("a.btn.btn-dark.mb-1.bottone-ep")!!.attr("href"))
        val epText = element.selectFirst("a.btn.btn-dark.mb-1.bottone-ep")!!.text()
        val epNumber = epText.substringAfter("Episodio ")
        if (epNumber.contains("-", true)) {
            episode.episode_number = epNumber.substringBefore("-").toFloat()
        } else {
            episode.episode_number = epNumber.toFloat()
        }
        episode.name = epText
        return episode
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val standardVideos = videosFromElement(document)
        val videoList = mutableListOf<Video>()
        videoList.addAll(standardVideos)
        return videoList
    }

    override fun videoListRequest(episode: SEpisode): Request {
        val episodePage = client.newCall(GET(baseUrl + episode.url)).execute().asJsoup()
        val watchUrl = episodePage.select("a[href*=/watch]").attr("href")
        return GET("$watchUrl&s=alt")
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    private fun videosFromElement(document: Document): List<Video> {
        val url = if (document.html().contains("jwplayer(")) {
            document.html().substringAfter("file: \"").substringBefore("\"")
        } else {
            document.select("source").attr("src")
        }
        val referer = document.location()
        return if (url.endsWith("playlist.m3u8")) {
            val playlist = client.newCall(GET(url)).execute().body.string()
            val linkRegex = """(?<=\n)./.+""".toRegex()
            val qualityRegex = """(?<=RESOLUTION=)\d+x\d+""".toRegex()
            val qualities = qualityRegex.findAll(playlist).map {
                it.value.substringAfter('x') + "p"
            }.toList()
            val videoLinks = linkRegex.findAll(playlist).map {
                url.substringBefore("playlist.m3u8") + it.value.substringAfter("./")
            }.toList()
            videoLinks.mapIndexed { i, link ->
                Video(
                    link,
                    qualities[i],
                    link,
                )
            }
        } else {
            listOf(
                Video(
                    url,
                    "Qualità predefinita",
                    url,
                    headers = Headers.headersOf("Referer", referer),
                ),
            )
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!
        val qualityList = mutableListOf<Video>()
        var preferred = 0
        for (video in this) {
            if (video.quality.contains(quality)) {
                qualityList.add(preferred, video)
                preferred++
            } else {
                qualityList.add(video)
            }
        }
        return qualityList
    }

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        if (filterSearch) {
            // filter search
            anime.setUrlWithoutDomain(element.selectFirst("div.card.mb-4.shadow-sm a")!!.attr("href"))
            anime.title = formatTitle(element.selectFirst("div.card.mb-4.shadow-sm a")!!.attr("title"))
            anime.thumbnail_url = element.selectFirst("div.card.mb-4.shadow-sm a img.new-anime")!!.attr("src")
        } else {
            // word search
            anime.setUrlWithoutDomain(element.selectFirst("li.list-group-item.bg-dark-as-box-shadow div.item-archivio div.info-archivio h3 a.badge.badge-archivio.badge-light")!!.attr("href"))
            anime.title = formatTitle(element.selectFirst("li.list-group-item.bg-dark-as-box-shadow div.item-archivio div.info-archivio h3 a.badge.badge-archivio.badge-light")!!.text())
            anime.thumbnail_url = element.select("li.list-group-item.bg-dark-as-box-shadow div.item-archivio a.thumb.image-wrapper img.rounded.locandina-archivio").attr("src")
        }
        return anime
    }

    override fun searchAnimeNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    private var filterSearch = false

    override fun searchAnimeSelector(): String {
        return if (filterSearch) {
            "div.anime-card-newanime.main-anime-card" // filter search
        } else {
            "ul.list-group" // regular search
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val parameters = getSearchParameters(filters)
        return if (parameters.isEmpty()) {
            filterSearch = false
            GET("$baseUrl/animelist?search=$query") // regular search
        } else {
            filterSearch = true
            GET("$baseUrl/filter?$parameters&page=$page") // with filters
        }
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title =
            formatTitle(document.select("div.container.anime-title-as.mb-3.w-100 b").text())
        val tempDetails =
            document.select("div.container.shadow.rounded.bg-dark-as-box.mb-3.p-3.w-100.text-white")
                .text()
        val indexA = tempDetails.indexOf("Stato:")
        anime.author = tempDetails.substring(7, indexA).trim()
        val indexS1 = tempDetails.indexOf("Stato:") + 6
        val indexS2 = tempDetails.indexOf("Data di uscita:")
        anime.status = parseStatus(tempDetails.substring(indexS1, indexS2).trim())
        anime.genre =
            document.select("div.container.shadow.rounded.bg-dark-as-box.mb-3.p-3.w-100 a.badge.badge-dark.generi-as.mb-1")
                .joinToString { it.text() }
        anime.thumbnail_url = document.selectFirst("img.img-fluid.cover-anime.rounded")!!.attr("src")
        val alterTitle = formatTitle(
            document.selectFirst("div.box-trasparente-alternativo.rounded")!!.text(),
        ).replace("Dub ITA", "").trim()
        val description1 = document.selectFirst("div#trama div#shown-trama")?.ownText()
        val description2 = document.selectFirst("div#full-trama.d-none")?.ownText()
        when {
            description1 == null -> {
                anime.description = description2
            }
            description2 == null -> {
                anime.description = description1
            } description1.length > description2.length -> {
                anime.description = description1
            } else -> {
                anime.description = description2
            }
        }
        if (!anime.title.contains(alterTitle, true)) anime.description = anime.description + "\n\nTitolo Alternativo: " + alterTitle
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("In corso") -> {
                SAnime.ONGOING
            }
            statusString.contains("Finito") -> {
                SAnime.COMPLETED
            }
            else -> {
                SAnime.UNKNOWN
            }
        }
    }

    override fun latestUpdatesSelector(): String = "div.card.mb-4.shadow-sm"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        anime.title = formatTitle(element.selectFirst("a")!!.attr("title"))
        anime.thumbnail_url = element.selectFirst("a img.new-anime")!!.attr("src")
        return anime
    }
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/newest?page=$page")

    override fun latestUpdatesNextPageSelector(): String = "li.page-item.active:not(li:last-child)"

    // Filters
    internal class Genre(val id: String) : AnimeFilter.CheckBox(id)
    private class GenreList(genres: List<Genre>) : AnimeFilter.Group<Genre>("Generi", genres)
    private fun getGenres() = listOf(
        Genre("Arti Marziali"),
        Genre("Avventura"),
        Genre("Azione"),
        Genre("Bambini"),
        Genre("Commedia"),
        Genre("Demenziale"),
        Genre("Demoni"),
        Genre("Drammatico"),
        Genre("Ecchi"),
        Genre("Fantasy"),
        Genre("Gioco"),
        Genre("Harem"),
        Genre("Hentai"),
        Genre("Horror"),
        Genre("Josei"),
        Genre("Magia"),
        Genre("Mecha"),
        Genre("Militari"),
        Genre("Mistero"),
        Genre("Musicale"),
        Genre("Parodia"),
        Genre("Polizia"),
        Genre("Psicologico"),
        Genre("Romantico"),
        Genre("Samurai"),
        Genre("Sci-Fi"),
        Genre("Scolastico"),
        Genre("Seinen"),
        Genre("Sentimentale"),
        Genre("Shoujo Ai"),
        Genre("Shoujo"),
        Genre("Shounen Ai"),
        Genre("Shounen"),
        Genre("Slice of Life"),
        Genre("Soprannaturale"),
        Genre("Spazio"),
        Genre("Sport"),
        Genre("Storico"),
        Genre("Superpoteri"),
        Genre("Thriller"),
        Genre("Vampiri"),
        Genre("Veicoli"),
        Genre("Yaoi"),
        Genre("Yuri"),
    )

    internal class Year(val id: String) : AnimeFilter.CheckBox(id)
    private class YearList(years: List<Year>) : AnimeFilter.Group<Year>("Anno di Uscita", years)
    private fun getYears() = listOf(
        Year("1969"),
        Year("1970"),
        Year("1975"),
        Year("1978"),
        Year("1979"),
        Year("1981"),
        Year("1983"),
        Year("1984"),
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
        Year("2023"),
        Year("2024"),
    )

    internal class State(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class StateList(states: List<State>) : AnimeFilter.Group<State>("Stato", states)
    private fun getStates() = listOf(
        State("0", "In corso"),
        State("1", "Finito"),
        State("2", "Non rilasciato"),
        State("3", "Droppato"),
    )

    internal class Lang(val id: String, name: String) : AnimeFilter.CheckBox(name)
    private class LangList(langs: List<Lang>) : AnimeFilter.Group<Lang>("Lingua", langs)
    private fun getLangs() = listOf(
        Lang("0", "Subbato"),
        Lang("1", "Doppiato"),
    )

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Ricerca per titolo ignora i filtri e viceversa"),
        GenreList(getGenres()),
        YearList(getYears()),
        StateList(getStates()),
        LangList(getLangs()),
    )

    private fun getSearchParameters(filters: AnimeFilterList): String {
        var totalstring = ""
        var variantgenre = 0
        var variantstate = 0
        var variantyear = 0
        filters.forEach { filter ->
            when (filter) {
                is GenreList -> { // ---Genre
                    filter.state.forEach { Genre ->
                        if (Genre.state) {
                            totalstring = totalstring + "&categories%5B" + variantgenre.toString() + "%5D=" + Genre.id
                            variantgenre++
                        }
                    }
                }

                is YearList -> { // ---Year
                    filter.state.forEach { Year ->
                        if (Year.state) {
                            totalstring = totalstring + "&years%5B" + variantyear.toString() + "%5D=" + Year.id
                            variantyear++
                        }
                    }
                }

                is StateList -> { // ---State
                    filter.state.forEach { State ->
                        if (State.state) {
                            totalstring = totalstring + "&states%5B" + variantstate.toString() + "%5D=" + State.id
                            variantstate++
                        }
                    }
                }

                is LangList -> { // ---Lang
                    filter.state.forEach { Lang ->
                        if (Lang.state) {
                            totalstring = totalstring + "&language%5B0%5D=" + Lang.id
                        }
                    }
                }
                else -> {}
            }
        }
        return totalstring
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualità preferita"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "144")
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
            title = "Domain in uso (riavvio dell'app richiesto)"
            entries = arrayOf("animesaturn.in")
            entryValues = arrayOf("https://animesaturn.in")
            setDefaultValue("https://animesaturn.in")
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
