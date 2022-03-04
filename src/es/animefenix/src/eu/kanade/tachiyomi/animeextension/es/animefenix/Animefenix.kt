package eu.kanade.tachiyomi.animeextension.es.animefenix

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.animefenix.extractors.FembedExtractor
import eu.kanade.tachiyomi.animeextension.es.animefenix.extractors.OkruExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.lang.Exception

class Animefenix : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeFenix"

    override val baseUrl = "https://www.animefenix.com"

    override val lang = "es"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.container div.container div.list-series article.serie-card"

    override fun popularAnimeRequest(page: Int): Request = GET("https://www.animefenix.com/animes?order=likes&page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("figure.image a").attr("href")
        )
        anime.title = element.select("div.title h3 a").text()
        anime.thumbnail_url = element.select("figure.image a img").attr("src")
        anime.description = element.select("div.serie-card__information p").text()
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.pagination.is-centered ul.pagination-list li a.pagination-link"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()

        jsoup.select("ul.anime-page__episode-list.is-size-6 li").forEach { it ->

            val epNum = it.select("a span").text().replace("Episodio", "")
            Log.i("bruh", "Episode-$epNum")
            val episode = SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                date_upload = System.currentTimeMillis()
            }
            episode.setUrlWithoutDomain(it.select("a").attr("href"))
            episodes.add(episode)
        }

        return episodes
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("ul.is-borderless.episode-page__servers-list li").forEach { it ->
            val server = it.select("a").attr("title")
            val serverId = it.select("a").attr("href").replace("#vid", "").toInt()
            val serverCode = document.select("div.player-container script").toString()
                .substringAfter("tabsArray['$serverId'] =")
                .substringBefore("&amp;thumbnail")
                .substringAfter("code=")
                .substringBefore("&amp")

            Log.i("bruh", "1Server:$server, ServerId:$serverId")

            if (server == "Fembed" || server == "fembed") {
                val fembedUrl = "https://www.fembed.com/v/$serverCode"
                val video = FembedExtractor().videosFromUrl(fembedUrl)
                videoList.addAll(video)
            }
            if (server == "RU" || server == "ru") {
                val okrUrl = "https://ok.ru/videoembed/$serverCode"
                val video = OkruExtractor(client).videosFromUrl(okrUrl)
                videoList.addAll(video)
            }
            if (server == "Amazon" || server == "AMAZON" || server == "amazon") {
                val amazonUrl = "https://www.animefenix.com/stream/amz.php?v=$serverCode"
                val video = amazonExtractor(amazonUrl)
                videoList.add(Video(video, "Amazon", video, null))
            }
            if (server == "AmazonEs" || server == "AmazonES" || server == "amazones") {
                val amazonUrl = "https://www.animefenix.com/stream/amz.php?v=$serverCode&ext=es"
                val video = amazonExtractor(amazonUrl)
                videoList.add(Video(video, "AmazonES", video, null))
            }
        }
        return videoList
    }

    private fun amazonExtractor(url: String): String {
        val jsoup = Jsoup.connect(url).get()
        val videoUrl = jsoup.select("body script").toString()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")
        Log.i("bruh", videoUrl)
        return videoUrl
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Amazon")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes?q=$query&page=$page", headers)
            genreFilter.state != 0 -> GET("$baseUrl/animes?genero[]=${genreFilter.toUriPart()}&order=default&page=$page")
            else -> GET("https://www.animefenix.com/animes?order=likes&page=$page ")
        }
    }
    override fun searchAnimeFromElement(element: Element): SAnime {
        return popularAnimeFromElement(element)
    }

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.column.is-12-mobile.is-8-tablet.is-10-desktop h1.title.has-text-orange").text()
        anime.genre = document.select("p.genres.buttons a.button.is-small.is-orange.is-outlined.is-roundedX").joinToString { it.text() }
        anime.status = parseStatus(document.select(" div.column.is-12-mobile.xis-3-tablet.xis-3-desktop.xhas-background-danger.is-narrow-tablet.is-narrow-desktop a").text())
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesRequest(page: Int) = GET("https://www.animefenix.com/animes?order=added&page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por texto ignora el filtro"),
        GenreFilter()
    )

    private class GenreFilter : UriPartFilter(
        "Generos",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Acción", "acción"),
            Pair("Aventura", "aventura"),
            Pair("Angeles", "angeles"),
            Pair("Artes Marciales", "artes-marciales"),
            Pair("Ciencia Ficcion", "ciencia-ficcion"),
            Pair("Comedia", "comedia"),
            Pair("Cyberpunk", "cyberpunk"),
            Pair("Demonios", "demonios"),
            Pair("Deportes", "deportes"),
            Pair("Dragones", "dragones"),
            Pair("Drama", "drama"),
            Pair("Ecchi", "ecchi"),
            Pair("Escolares", "escolares"),
            Pair("Fantasía", "fantasía"),
            Pair("Gore", "gore"),
            Pair("Harem", "harem"),
            Pair("Historico", "historico"),
            Pair("Horror", "horror"),
            Pair("Infantil", "infantil"),
            Pair("Isekai", "isekai"),
            Pair("Josei", "josei"),
            Pair("Juegos", "juegos"),
            Pair("Magia", "magia"),
            Pair("Mecha", "mecha"),
            Pair("Militar", "militar"),
            Pair("Misterio", "misterio"),
            Pair("Música", "música"),
            Pair("Ninjas", "ninjas"),
            Pair("Parodias", "parodias"),
            Pair("Policia", "policia"),
            Pair("Psicológico", "psicológico"),
            Pair("Recuerdos de la vida", "recuerdos-de-la-vida"),
            Pair("Romance", "romance"),
            Pair("Samurai", "samurai"),
            Pair("Sci-Fi", "sci-fi"),
            Pair("Seinen", "seinen"),
            Pair("Shoujo", "shoujo"),
            Pair("Shonen", "shonen"),
            Pair("Slice of life", "slice-of-life"),
            Pair("Sobrenatural", "sobrenatural"),
            Pair("Space", "space"),
            Pair("Spokon", "spokon"),
            Pair("SteamPunk", "steampunk"),
            Pair("SuperPoder", "superpoder"),
            Pair("Vampiros", "vampiros"),
            Pair("Yaoi", "yaoi"),
            Pair("Yuri", "yuri")
        )
    )
    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("Amazon", "Fembed:480p", "Fembed:720p", "Amazon", "AmazonES")
            entryValues = arrayOf("Amazon")
            setDefaultValue("Amazon")
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
