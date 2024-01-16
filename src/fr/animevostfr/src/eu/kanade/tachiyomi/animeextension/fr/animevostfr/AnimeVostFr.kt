package eu.kanade.tachiyomi.animeextension.fr.animevostfr

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.animevostfr.extractors.CdopeExtractor
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AnimeVostFr : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "AnimeVostFr"

    override val baseUrl = "https://animevostfr.tv"

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/filter-advance/page/$page/")

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/filter-advance/page/$page/?status=ongoing")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) {
            return GET("$baseUrl/?s=$query")
        } else {
            filters
        }
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val yearFilter = filterList.find { it is YearFilter } as YearFilter
        val statusFilter = filterList.find { it is StatusFilter } as StatusFilter
        val langFilter = filterList.find { it is LangFilter } as LangFilter

        val filterPath = if (query.isEmpty()) "/filter-advance" else ""

        var urlBuilder = "$baseUrl$filterPath/page/$page/".toHttpUrl().newBuilder()

        when {
            query.isNotEmpty() ->
                urlBuilder =
                    urlBuilder.addQueryParameter("s", query)
            typeFilter.state != 0 ->
                urlBuilder =
                    urlBuilder.addQueryParameter("topic", typeFilter.toUriPart())
            genreFilter.state != 0 ->
                urlBuilder =
                    urlBuilder.addQueryParameter("genre", genreFilter.toUriPart())
            yearFilter.state != 0 ->
                urlBuilder =
                    urlBuilder.addQueryParameter("years", yearFilter.toUriPart())
            statusFilter.state != 0 ->
                urlBuilder =
                    urlBuilder.addQueryParameter("status", statusFilter.toUriPart())
            langFilter.state != 0 ->
                urlBuilder =
                    urlBuilder.addQueryParameter("typesub", langFilter.toUriPart())
        }

        return GET(urlBuilder.build().toString())
    }

    override fun searchAnimeSelector() = "div.ml-item"

    override fun searchAnimeNextPageSelector() = "ul.pagination li:not(.active):last-child"

    override fun searchAnimeFromElement(element: Element): SAnime {
        val a = element.select("a:has(img)")
        val img = a.select("img")
        val h2 = a.select("span.mli-info > h2")
        return SAnime.create().apply {
            title = h2.text()
            setUrlWithoutDomain(a.attr("href"))
            thumbnail_url = img.attr("data-original")
        }
    }

    override fun popularAnimeSelector() = searchAnimeSelector()
    override fun latestUpdatesSelector() = searchAnimeSelector()
    override fun popularAnimeNextPageSelector() = searchAnimeNextPageSelector()
    override fun latestUpdatesNextPageSelector() = searchAnimeNextPageSelector()
    override fun popularAnimeFromElement(element: Element) = searchAnimeFromElement(element)
    override fun latestUpdatesFromElement(element: Element) = searchAnimeFromElement(element)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1[itemprop=name]").text()
            status = parseStatus(
                document.select(
                    "div.mvici-right > p:contains(Statut) > a:last-child",
                ).text(),
            )
            genre = document.select("div.mvici-left > p:contains(Genres)")
                .text().substringAfter("Genres: ")
            thumbnail_url = document.select("div.thumb > img")
                .firstOrNull()?.attr("data-lazy-src")
            description = document.select("div[itemprop=description]")
                .firstOrNull()?.wholeText()?.trim()
                ?.substringAfter("\n")
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val type = document
            .select("div.mvici-right > p:contains(Type) > a:last-child")
            .text()
        return if (type == "MOVIE") {
            return listOf(
                SEpisode.create().apply {
                    url = response.request.url.toString()
                    name = "Movie"
                },
            )
        } else {
            document.select(episodeListSelector()).map { episodeFromElement(it) }.reversed()
        }
    }

    override fun episodeListSelector() = "div#seasonss > div.les-title > a"

    override fun episodeFromElement(element: Element): SEpisode {
        val number = element.text()
            .substringAfterLast("-episode-")
            .substringBefore("-")
        return SEpisode.create().apply {
            setUrlWithoutDomain(element.attr("href"))
            name = "Épisode $number"
            episode_number = number.toFloat()
        }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val videoList = mutableListOf<Video>()

        val url = if (episode.url.startsWith("https:")) {
            episode.url
        } else {
            baseUrl + episode.url
        }

        val response = client.newCall(GET(url)).execute()
        val parsedResponse = response.asJsoup()
        if (parsedResponse.select("title").text().contains("Warning")) {
            throw Exception(parsedResponse.select("body").text())
        }
        val epId = parsedResponse.select("link[rel=shortlink]").attr("href")
            .substringAfter("?p=")

        parsedResponse.select("div.list-server > select > option").forEach { server ->
            videoList.addAll(
                extractVideos(
                    server.attr("value"),
                    server.text(),
                    epId,
                ),
            )
        }

        return videoList
    }

    private fun extractVideos(serverValue: String, serverName: String, epId: String): List<Video> {
        Log.i("bruh", "ID: $epId \nLink: $")
        val xhr = Headers.headersOf("x-requested-with", "XMLHttpRequest")
        val epLink = client.newCall(GET("$baseUrl/ajax-get-link-stream/?server=$serverValue&filmId=$epId", xhr))
            .execute().body.string()

        val playlist = mutableListOf<Video>()
        when {
            epLink.contains("comedyshow.to") -> {
                val playlistInterceptor = CloudFlareInterceptor()
                val cfClient = client.newBuilder().addInterceptor(playlistInterceptor).build()
                val headers = Headers.headersOf(
                    "referer",
                    "$baseUrl/",
                    "user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36",
                )
                val playlistResponse = cfClient.newCall(GET(epLink, headers)).execute().body.string()
                val headersVideo = Headers.headersOf(
                    "referer",
                    epLink,
                    "user-agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/105.0.0.0 Safari/537.36",
                )

                playlistResponse.substringAfter("#EXT-X-STREAM-INF:")
                    .split("#EXT-X-STREAM-INF:").map {
                        val quality = it.substringAfter("RESOLUTION=").split(",")[0].split("\n")[0].substringAfter("x") + "p ($serverName)"
                        val videoUrl = it.substringAfter("\n").substringBefore("\n")
                        playlist.add(Video(videoUrl, quality, videoUrl, headers = headersVideo))
                    }
            }
            epLink.contains("cdopetimes.xyz") -> {
                val extractor = CdopeExtractor(client)
                playlist.addAll(
                    extractor.videosFromUrl(epLink),
                )
            }
        }

        return playlist.sort()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720")
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

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.select("div.slide-middle h1").text()
        anime.description = document.selectFirst("div.slide-desc")!!.ownText()
        anime.genre = document.select("div.image-bg-content div.slide-block div.slide-middle ul.slide-top li.right a").joinToString { it.text() }
        return anime
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TypeFilter(),
        GenreFilter(),
        YearFilter(),
        StatusFilter(),
        LangFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "Type",
        arrayOf(
            Pair("-----", ""),
            Pair("Anime", "anime"),
            Pair("Cartoon", "cartoon"),
            Pair("MOVIE", "movie"),
            Pair("SERIES", "series"),
        ),
    )

    private class GenreFilter : UriPartFilterReverse(
        "Genre",
        arrayOf(
            Pair("", "-----"),
            Pair("action", "Action"),
            Pair("adventure", "Adventure"),
            Pair("animation", "Animation"),
            Pair("martial-arts", "Arts martiaux"),
            Pair("biography", "Biographie"),
            Pair("comedy", "Comédie"),
            Pair("crime", "Crime"),
            Pair("demence", "Démence"),
            Pair("demon", "Demons"),
            Pair("documentaire", "Documentaire"),
            Pair("drame", "Drama"),
            Pair("ecchi", "Ecchi"),
            Pair("enfants", "Enfants"),
            Pair("espace", "Espace"),
            Pair("famille", "Famille"),
            Pair("fantasy", "Fantastique"),
            Pair("game", "Game"),
            Pair("harem", "Harem"),
            Pair("historical", "Historique"),
            Pair("horror", "Horreur"),
            Pair("jeux", "Jeux"),
            Pair("josei", "Josei"),
            Pair("kids", "Kids"),
            Pair("magic", "Magie"),
            Pair("mecha", "Mecha"),
            Pair("military", "Militaire"),
            Pair("monster", "Monster"),
            Pair("music", "Musique"),
            Pair("mystere", "Mystère"),
            Pair("parody", "Parodie"),
            Pair("police", "Policier"),
            Pair("psychological", "Psychologique"),
            Pair("romance", "Romance"),
            Pair("samurai", "Samurai"),
            Pair("sci-fi", "Sci-Fi"),
            Pair("school", "Scolaire"),
            Pair("seinen", "Seinen"),
            Pair("short", "Short"),
            Pair("shoujo", "Shoujo"),
            Pair("shoujo-ai", "Shoujo Ai"),
            Pair("shounen", "Shounen"),
            Pair("shounen-ai", "Shounen Ai"),
            Pair("sport", "Sport"),
            Pair("super-power", "Super Pouvoir"),
            Pair("supernatural", "Surnaturel"),
            Pair("suspense", "Suspense"),
            Pair("thriller", "Thriller"),
            Pair("silce-of-life", "Tranche de vie"),
            Pair("vampire", "Vampire"),
            Pair("cars", "Voitures"),
            Pair("war", "War"),
            Pair("western", "Western"),
        ),
    )

    private class YearFilter : UriPartFilterYears(
        "Year",
        Array(62) {
            if (it == 0) {
                "-----"
            } else {
                (2022 - (it - 1)).toString()
            }
        },
    )

    private class StatusFilter : UriPartFilter(
        "Status",
        arrayOf(
            Pair("-----", ""),
            Pair("Fin", "completed"),
            Pair("En cours", "ongoing"),
        ),
    )

    private class LangFilter : UriPartFilter(
        "La langue",
        arrayOf(
            Pair("-----", ""),
            Pair("VO", "vo"),
            Pair("Animé Vostfr", "vostfr"),
            Pair("Animé VF", "vf"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    private open class UriPartFilterReverse(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.second }.toTypedArray()) {
        fun toUriPart() = vals[state].first
    }

    private open class UriPartFilterYears(displayName: String, val years: Array<String>) :
        AnimeFilter.Select<String>(displayName, years) {
        fun toUriPart() = years[state]
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Qualité préférée"
            entries = arrayOf("720p", "360p")
            entryValues = arrayOf("720", "360")
            setDefaultValue("720")
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

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "Fin" -> SAnime.COMPLETED
            "En cours" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }
}
