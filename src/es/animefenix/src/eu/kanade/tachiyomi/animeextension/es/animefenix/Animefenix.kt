package eu.kanade.tachiyomi.animeextension.es.animefenix

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
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder

class Animefenix : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "AnimeFenix"

    override val baseUrl = "https://www.animefenix.tv"

    override val lang = "es"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Amazon"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape",
            "Fastream", "Filemoon", "StreamWish", "Okru",
            "Amazon", "AmazonES", "Fireload", "FileLions",
        )
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/animes?order=likes&page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select("article.serie-card")
        val nextPage = document.select("ul.pagination-list li a.pagination-link:contains(Siguiente)").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.select("figure.image a").attr("abs:href"))
                title = element.select("div.title h3 a").text()
                thumbnail_url = element.select("figure.image a img").attr("abs:src")
                description = element.select("div.serie-card__information p").text()
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/animes?order=added&page=$page")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val yearFilter = filters.find { it is YearFilter } as YearFilter
        val stateFilter = filters.find { it is StateFilter } as StateFilter
        val typeFilter = filters.find { it is TypeFilter } as TypeFilter
        val orderByFilter = filters.find { it is OrderByFilter } as OrderByFilter

        val genreFilter = (filters.find { it is TagFilter } as TagFilter).state.filter { it.state }

        var filterUrl = "$baseUrl/animes?"
        if (query.isNotBlank()) {
            filterUrl += "&q=$query"
        } // search by name
        if (genreFilter.isNotEmpty()) {
            genreFilter.forEach {
                filterUrl += "&genero[]=${it.name}"
            }
        } // search by genre
        if (yearFilter.state.isNotBlank()) {
            filterUrl += "&year[]=${yearFilter.state}"
        } // search by year
        if (stateFilter.state != 0) {
            filterUrl += "&estado[]=${stateFilter.toUriPart()}"
        } // search by state
        if (typeFilter.state != 0) {
            filterUrl += "&type[]=${typeFilter.toUriPart()}"
        } // search by type
        filterUrl += "&order=${orderByFilter.toUriPart()}"
        filterUrl += "&page=$page" // add page

        return when {
            genreFilter.isEmpty() || yearFilter.state.isNotBlank() ||
                stateFilter.state != 0 || typeFilter.state != 0 || query.isNotBlank() -> GET(filterUrl, headers)
            else -> GET("$baseUrl/animes?order=likes&page=$page ")
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select("ul.anime-page__episode-list.is-size-6 li").map { it ->
            val epNum = it.select("a span").text().replace("Episodio", "")
            SEpisode.create().apply {
                episode_number = epNum.toFloat()
                name = "Episodio $epNum"
                setUrlWithoutDomain(it.select("a").attr("abs:href"))
            }
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val servers = document.selectFirst("script:containsData(var tabsArray)")!!.data()
            .split("tabsArray").map { it.substringAfter("src='").substringBefore("'").replace("amp;", "") }
            .filter { it.contains("https") }

        servers.forEach { server ->
            val decodedUrl = URLDecoder.decode(server, "UTF-8")
            val realUrl = try {
                client.newCall(GET(decodedUrl)).execute().asJsoup().selectFirst("script")!!
                    .data().substringAfter("src=\"").substringBefore("\"")
            } catch (e: Exception) { "" }

            try {
                serverVideoResolver(realUrl).let { videoList.addAll(it) }
            } catch (_: Exception) { }
        }
        return videoList.filter { it.url.contains("https") || it.url.contains("http") }
    }

    private fun serverVideoResolver(url: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        try {
            if (embedUrl.contains("voe")) {
                VoeExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if ((embedUrl.contains("amazon") || embedUrl.contains("amz")) && !embedUrl.contains("disable")) {
                val video = amazonExtractor(baseUrl + url.substringAfter(".."))
                if (video.isNotBlank()) {
                    if (url.contains("&ext=es")) {
                        videoList.add(Video(video, "AmazonES", video))
                    } else {
                        videoList.add(Video(video, "Amazon", video))
                    }
                }
            }
            if (embedUrl.contains("ok.ru") || embedUrl.contains("okru")) {
                OkruExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
                val vidHeaders = headers.newBuilder()
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", "https://${url.toHttpUrl().host}/")
                    .build()
                FilemoonExtractor(client).videosFromUrl(url, prefix = "Filemoon:", headers = vidHeaders).also(videoList::addAll)
            }
            if (embedUrl.contains("uqload")) {
                UqloadExtractor(client).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("mp4upload")) {
                Mp4uploadExtractor(client).videosFromUrl(url, headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("wishembed") || embedUrl.contains("embedwish") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish")) {
                val docHeaders = headers.newBuilder()
                    .add("Origin", "https://streamwish.to")
                    .add("Referer", "https://streamwish.to/")
                    .build()
                StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "StreamWish:$it" }).also(videoList::addAll)
            }
            if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
                DoodExtractor(client).videoFromUrl(url, "DoodStream", false)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("streamlare")) {
                StreamlareExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("yourupload") || embedUrl.contains("upload")) {
                YourUploadExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("burstcloud") || embedUrl.contains("burst")) {
                BurstCloudExtractor(client).videoFromUrl(url, headers = headers).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("fastream")) {
                FastreamExtractor(client, headers).videosFromUrl(url).also(videoList::addAll)
            }
            if (embedUrl.contains("upstream")) {
                UpstreamExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape")) {
                StreamTapeExtractor(client).videoFromUrl(url)?.let { videoList.add(it) }
            }
            if (embedUrl.contains("ahvsh") || embedUrl.contains("streamhide")) {
                StreamHideVidExtractor(client).videosFromUrl(url).let { videoList.addAll(it) }
            }
            if (embedUrl.contains("/stream/fl.php")) {
                val video = url.substringAfter("/stream/fl.php?v=")
                if (client.newCall(GET(video)).execute().code == 200) {
                    videoList.add(Video(video, "FireLoad", video))
                }
            }
            if (embedUrl.contains("filelions") || embedUrl.contains("lion")) {
                StreamWishExtractor(client, headers).videosFromUrl(url, videoNameGen = { "FileLions:$it" }).also(videoList::addAll)
            }
        } catch (_: Exception) { }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.select("h1.title.has-text-orange").text()
            genre = document.select("a.button.is-small.is-orange.is-outlined.is-roundedX").joinToString { it.text() }
            status = parseStatus(document.select("div.column.is-12-mobile.xis-3-tablet.xis-3-desktop.xhas-background-danger.is-narrow-tablet.is-narrow-desktop a").text())
        }
    }

    private fun parseStatus(statusString: String): Int {
        return when {
            statusString.contains("Emisión") -> SAnime.ONGOING
            statusString.contains("Finalizado") -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    private fun amazonExtractor(url: String): String {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val videoURl = document.selectFirst("script:containsData(sources: [)")!!.data()
            .substringAfter("[{\"file\":\"")
            .substringBefore("\",").replace("\\", "")

        return try {
            if (client.newCall(GET(videoURl)).execute().code == 200) videoURl else ""
        } catch (e: Exception) {
            ""
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagFilter("Generos", checkboxesFrom(genreList)),
        StateFilter(),
        TypeFilter(),
        OrderByFilter(),
        YearFilter(),
    )

    private val genreList = arrayOf(
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
        Pair("Yuri", "yuri"),
    )

    private fun checkboxesFrom(tagArray: Array<Pair<String, String>>): List<TagCheckBox> = tagArray.map { TagCheckBox(it.second) }

    class TagCheckBox(tag: String) : AnimeFilter.CheckBox(tag, false)

    class TagFilter(name: String, checkBoxes: List<TagCheckBox>) : AnimeFilter.Group<TagCheckBox>(name, checkBoxes)

    private class YearFilter : AnimeFilter.Text("Año")

    private class StateFilter : UriPartFilter(
        "Estado",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("Emision", "1"),
            Pair("Finalizado", "2"),
            Pair("Proximamente", "3"),
            Pair("En Cuarentena", "4"),
        ),
    )

    private class TypeFilter : UriPartFilter(
        "Tipo",
        arrayOf(
            Pair("<Seleccionar>", ""),
            Pair("TV", "tv"),
            Pair("Pelicula", "movie"),
            Pair("Especial", "special"),
            Pair("OVA", "ova"),
        ),
    )

    private class OrderByFilter : UriPartFilter(
        "Ordenar Por",
        arrayOf(
            Pair("Por defecto", "default"),
            Pair("Recientemente Actualizados", "updated"),
            Pair("Recientemente Agregados", "added"),
            Pair("Nombre A-Z", "title"),
            Pair("Calificación", "likes"),
            Pair("Más vistos", "visits"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
