package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.JsUnpacker
import eu.kanade.tachiyomi.animeextension.es.animeonlineninja.extractors.UploadExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fembedextractor.FembedExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class AnimeonlineNinja : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeOnline.Ninja"

    override val baseUrl = "https://www1.animeonline.ninja"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.content.right div.items article"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/tendencias/page/$page/")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("div.data h3 a").attr("href"))
        anime.title = element.select("div.data h3 a").text()
        anime.thumbnail_url = element.select("div.poster img").attr("data-src").replace("-185x278", "")
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "a.arrow_pag i#nextpagination"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val url = response.request.url.toString()
        if (url.contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1.0F
                name = "Pelicula"
                setUrlWithoutDomain(url)
            }
            return listOf(episode)
        }
        val document = response.asJsoup()
        document.select("div#serie_contenido div#seasons div.se-c div.se-a ul.episodios li").forEach {
            val epTemp = it.select("div.numerando").text().substringBefore("-").replace(" ", "")
            val epNum = it.select("div.numerando").text().substringAfter("-").replace(" ", "").replace(".", "")
            val epName = it.select("div.episodiotitle a").text()
            if (epTemp.isNotBlank() && epNum.isNotBlank()) {
                val episode = SEpisode.create().apply {
                    episode_number = "$epTemp.$epNum".toFloat()
                    name = "T$epTemp $epName"
                }
                episode.setUrlWithoutDomain(it.select("div.episodiotitle a").attr("href"))
                episodes.add(episode)
            }
        }

        return episodes.reversed()
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        if (multiserverCheck(document)) {
            val datapost = document.selectFirst("#playeroptionsul li").attr("data-post")
            val datatype = document.selectFirst("#playeroptionsul li").attr("data-type")
            val apiCall = client.newCall(GET("https://www1.animeonline.ninja/wp-json/dooplayer/v1/post/$datapost?type=$datatype&source=1")).execute().asJsoup().body()
            val iframeLink = apiCall.toString().substringAfter("{\"embed_url\":\"").substringBefore("\"")
            val sDocument = client.newCall(GET(iframeLink)).execute().asJsoup()
            sDocument.select("div.ODDIV div").forEach {
                val lang = it.attr("class").toString().substringAfter("OD OD_").replace("REactiv", "").trim()
                it.select("li").forEach { source ->
                    val sourceUrl = source.attr("onclick").toString().substringAfter("go_to_player('").substringBefore("')")
                    serverslangParse(sourceUrl, lang).map { video -> videoList.add(video) }
                }
            }
        } else {
            val datapost = document.selectFirst("#playeroptionsul li").attr("data-post")
            val datatype = document.selectFirst("#playeroptionsul li").attr("data-type")
            document.select("#playeroptionsul li").forEach {
                val sourceId = it.attr("data-nume")
                val apiCall = client.newCall(GET("https://www1.animeonline.ninja/wp-json/dooplayer/v1/post/$datapost?type=$datatype&source=$sourceId")).execute().asJsoup().body()
                val sourceUrl = apiCall.toString().substringAfter("{\"embed_url\":\"").substringBefore("\"").replace("\\/", "/")

                val lang2 = preferences.getString("preferred_lang", "SUB").toString().trim()
                serverslangParse(sourceUrl, lang2).map { video -> videoList.add(video) }
            }
        }

        return videoList
    }

    private fun multiserverCheck(document: Document): Boolean {
        document.select("#playeroptionsul li").forEach {
            val title = it.select("span.title").text()
            val url = it.select("span.server").toString()
            if (title.lowercase() == "multiserver" || url.contains("saidochesto.top")) {
                return true
            }
        }
        return false
    }

    private fun serverslangParse(serverUrl: String, lang: String): List<Video> {
        val videos = mutableListOf<Video>()
        val langSelect = preferences.getString("preferred_lang", "SUB").toString()
        when {
            serverUrl.contains("fembed") && lang.contains(langSelect) -> {
                val video = FembedExtractor(client).videosFromUrl(serverUrl, lang)
                videos.addAll(video)
            }
            serverUrl.contains("streamtape") && lang.contains(langSelect) -> {
                StreamTapeExtractor(client).videoFromUrl(serverUrl, "$lang StreamTape")?.let { it1 -> videos.add(it1) }
            }
            serverUrl.contains("dood") && lang.contains(langSelect) -> {
                DoodExtractor(client).videoFromUrl(serverUrl, "$lang DoodStream", false)?.let { it1 -> videos.add(it1) }
            }
            serverUrl.contains("sb") && lang.contains(langSelect) -> {
                try {
                    val video = StreamSBExtractor(client).videosFromUrl(serverUrl, headers, lang)
                    videos.addAll(video)
                } catch (e: Exception) { }
            }
            serverUrl.contains("mixdrop") && lang.contains(langSelect) -> {
                try {
                    val jsE = client.newCall(GET(serverUrl)).execute().asJsoup().selectFirst("script:containsData(eval)").data()
                    if (jsE.contains("MDCore")) {
                        val url = "http:" + JsUnpacker(jsE).unpack().toString().substringAfter("MDCore.wurl=\"").substringBefore("\"")
                        if (!url.contains("\$(document).ready(function(){});")) {
                            videos.add(Video(url, "$lang MixDrop", url))
                        }
                    }
                } catch (e: Exception) { }
            }
            serverUrl.contains("wolfstream") && lang.contains(langSelect) -> {
                val jsE = client.newCall(GET(serverUrl)).execute().asJsoup().selectFirst("script:containsData(sources)").data()
                val url = jsE.substringAfter("{file:\"").substringBefore("\"")
                videos.add(Video(url, "$lang WolfStream", url))
            }
            serverUrl.contains("uqload") && lang.contains(langSelect) -> {
                val headers = headers.newBuilder().add("referer", "https://uqload.com/").build()
                val video = UploadExtractor(client).videoFromUrl(serverUrl, headers, lang)
                if (video != null) videos.add(video)
            }
        }

        return videos
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        return try {
            val videoSorted = this.sortedWith(
                compareBy<Video> { it.quality.replace("[0-9]".toRegex(), "") }.thenByDescending { getNumberFromString(it.quality) }
            ).toTypedArray()
            val userPreferredQuality = preferences.getString("preferred_quality", "SUB Fembed:1080p")
            val preferredIdx = videoSorted.indexOfFirst { x -> x.quality == userPreferredQuality }
            if (preferredIdx != -1) {
                videoSorted.drop(preferredIdx + 1)
                videoSorted[0] = videoSorted[preferredIdx]
            }
            videoSorted.toList()
        } catch (e: Exception) {
            this
        }
    }

    private fun getNumberFromString(epsStr: String): String {
        return epsStr.filter { it.isDigit() }.ifEmpty { "0" }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val otherOptionsGroup = filters.find { it is OtherOptionsGroup } as OtherOptionsGroup
        val typeFilter = filters.find { it is TypeFilter } as TypeFilter
        val letterFilter = filters.find { it is LetterFilter } as LetterFilter
        val invertedResultsFilter = filters.find { it is InvertedResultsFilter } as InvertedResultsFilter
        val genreFilter = otherOptionsGroup.state.find { it is GenreFilter } as GenreFilter
        val langFilter = otherOptionsGroup.state.find { it is LangFilter } as LangFilter
        val movieFilter = otherOptionsGroup.state.find { it is MovieFilter } as MovieFilter

        if (genreFilter.state != 0) {
            return if (genreFilter.toUriPart() != "tendencias" && genreFilter.toUriPart() != "ratings") {
                GET("$baseUrl/genero/${genreFilter.toUriPart()}/page/$page/")
            } else {
                GET("$baseUrl/${genreFilter.toUriPart()}")
            }
        }
        if (langFilter.state != 0) {
            return GET("$baseUrl/genero/${langFilter.toUriPart()}/page/$page/")
        }
        if (movieFilter.state != 0) {
            return if (movieFilter.toUriPart() == "pelicula") {
                GET("$baseUrl/pelicula/page/$page/")
            } else {
                GET("$baseUrl/genero/${movieFilter.toUriPart()}/page/$page/")
            }
        }

        var url = when {
            query.isNotBlank() -> "$baseUrl/?s=$query"
            else -> "$baseUrl/tendencias/?"
        }

        if (letterFilter.state.isNotBlank()) url = try {
            if (letterFilter.state.first().isLetter()) {
                "$baseUrl/letra/${letterFilter.state.first().uppercase()}/?"
            } else {
                "$baseUrl/letra/a/?"
            }
        } catch (e: Exception) {
            "$baseUrl/letra/a/?"
        }

        if (typeFilter.state != 0) url += if (url.contains("tendencias")) {
            "&get=${when (typeFilter.toUriPart()){
                "serie" -> "TV"
                "pelicula" -> "movies"
                else -> "todos"
            }}"
        } else {
            "&tipo=${typeFilter.toUriPart()}"
        }

        if (invertedResultsFilter.state) url += "&orden=asc"

        return GET(url)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.request.url.toString().contains("?s=")) {
            val document = response.asJsoup()
            val animes = document.select("div.search-page div.result-item").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("article div.details div.title a").attr("href"))
                    title = it.select("article div.details div.title a").text()
                    thumbnail_url = it.select("article div.image div.thumbnail.animation-2 a img").attr("data-src").replace("-150x150", "")
                }
            }
            AnimesPage(animes, false)
        } else if (response.request.url.toString().contains("letra")) {
            val document = response.asJsoup()
            val animes = document.select("div.content div#archive-content.animation-2.items article").map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.select("div.data h3 a").attr("href"))
                    title = it.select("div.data h3 a").text()
                    thumbnail_url = it.select("div.poster img").attr("data-src").replace("-500x750", "")
                }
            }
            AnimesPage(animes, true)
        } else {
            val document = response.asJsoup()
            val animes = document.select(popularAnimeSelector()).map { popularAnimeFromElement(it) }
            AnimesPage(animes, true)
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw Exception("not used")

    override fun searchAnimeNextPageSelector(): String = throw Exception("not used")

    override fun searchAnimeSelector(): String = "div.search-page div.result-item"

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.sheader div.data h1").text()
        val uselessTags = listOf("supergoku", "younime", "zonamixs", "monoschinos", "otakustv", "Hanaojara", "series flv", "zenkimex", "Crunchyroll")
        anime.genre = document.select("div.sheader div.data div.sgeneros a").joinToString("") {
            if (it.text() in uselessTags || it.text().lowercase().contains("anime")) {
                ""
            } else {
                it.text() + ", "
            }
        }
        anime.description = document.select("div.wp-content p").joinToString { it.text() }
        anime.author = document.select("div.sheader div.data div.extra span a").text()
        return anime
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("https://www1.animeonline.ninja/genero/en-emision/page/$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("SUB Fembed:480p", "SUB Fembed:720p", "SUB Fembed:1080p")
            entryValues = arrayOf("SUB Fembed:480p", "SUB Fembed:720p", "SUB Fembed:1080p")
            setDefaultValue("SUB Fembed:1080p")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val langPref = ListPreference(screen.context).apply {
            key = "preferred_lang"
            title = "Preferred language"
            entries = arrayOf("SUB", "All", "ES", "LAT")
            entryValues = arrayOf("SUB", "", "ES", "LAT")
            setDefaultValue("SUB")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(langPref)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        InvertedResultsFilter(),
        TypeFilter(),
        LetterFilter(),
        AnimeFilter.Separator(),
        AnimeFilter.Header("Estos filtros no afectan a la busqueda por texto"),
        OtherOptionsGroup(),
    )

    private class OtherOptionsGroup : AnimeFilter.Group<AnimeFilter<*>>(
        "Otros filtros",
        listOf(
            GenreFilter(),
            LangFilter(),
            MovieFilter(),
        )
    )

    private class LetterFilter : AnimeFilter.Text("Filtrar por letra", "")

    private class InvertedResultsFilter : AnimeFilter.CheckBox("Invertir resultados", false)

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("Todos", "todos"),
            Pair("Series", "serie"),
            Pair("Peliculas", "pelicula")
        )
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Sin Censura \uD83D\uDD1E", "sin-censura"),
            Pair("En emisión ⏩", "en-emision"),
            Pair("Blu-Ray / DVD \uD83D\uDCC0", "blu-ray-dvd"),
            Pair("Próximamente", "proximamente"),
            Pair("Live Action \uD83C\uDDEF\uD83C\uDDF5", "live-action"),
            Pair("Popular en la web \uD83D\uDCAB", "tendencias"),
            Pair("Mejores valorados ⭐", "ratings")
        )
    )

    private class LangFilter : UriPartFilter(
        "Idiomas",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Audio Latino \uD83C\uDDF2\uD83C\uDDFD", "audio-latino"),
            Pair("Audio Castellano \uD83C\uDDEA\uD83C\uDDF8", "anime-castellano")
        )
    )

    private class MovieFilter : UriPartFilter(
        "Peliculas",
        arrayOf(
            Pair("Seleccionar", ""),
            Pair("Anime ㊗️", "pelicula"),
            Pair("Live Action \uD83C\uDDEF\uD83C\uDDF5", "live-action")
        )
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
}
