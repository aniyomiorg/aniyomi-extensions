package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.StreamlareExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.injectLazy

class Pelisplusph(override val name: String, override val baseUrl: String) : Pelisplushd(name, baseUrl) {

    private val json: Json by injectLazy()

    override val supportsLatest = false

    override fun popularAnimeSelector(): String = ".items-peliculas .item-pelicula"

    override fun popularAnimeNextPageSelector(): String = ".items-peliculas > a"

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/peliculas?page=$page")

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(baseUrl + element.selectFirst("a")?.attr("href"))
        anime.title = element.select("a .item-detail > p").text()
        anime.thumbnail_url = baseUrl + element.select("a .item-picture img").attr("src")
        return anime
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst(".info-content h1")!!.text()
        document.select(".info-content p").map { p ->
            if (p.select(".content-type").text().contains("Sinópsis:")) {
                anime.description = p.select(".sinopsis")!!.text()
            }
            if (p.select(".content-type").text().contains("Géneros:")) {
                anime.genre = p.select(".content-type-a a").joinToString { it.text() }
            }
            if (p.select(".content-type").text().contains("Reparto:")) {
                anime.artist = p.select(".content-type ~ span").text().substringBefore(",")
            }
        }
        anime.status = if (document.location().contains("/serie/")) SAnime.UNKNOWN else SAnime.COMPLETED

        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
            }
            episode.setUrlWithoutDomain(response.request.url.toString())
            episodes.add(episode)
        } else {
            var index = 0
            jsoup.select(".item-season").reversed().mapIndexed { idxSeas, season ->
                val seasonNumber = try {
                    getNumberFromString(season.selectFirst(".item-season-title")!!.ownText())
                } catch (_: Exception) { idxSeas + 1 }
                season.select(".item-season-episodes a").reversed().mapIndexed { idx, ep ->
                    index += 1
                    val noEp = try {
                        getNumberFromString(ep.ownText())
                    } catch (_: Exception) { idx + 1 }

                    val episode = SEpisode.create()
                    episode.episode_number = index.toFloat()
                    episode.name = "T$seasonNumber - E$noEp - ${ep.ownText()}"
                    episode.setUrlWithoutDomain(baseUrl + ep.attr("href"))
                    episodes.add(episode)
                }
            }
        }
        return episodes.reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        return when {
            query.isNotBlank() -> GET("$baseUrl/search/$query", headers)
            genreFilter.state != 0 -> GET("$baseUrl/${genreFilter.toUriPart()}?page=$page")
            else -> popularAnimeRequest(page)
        }
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        document.select("[class*=server-item-]").map {
            val langIdx = getNumberFromString(it.attr("class").substringAfter("server-item-"))
            val langItem = document.select("li[data-id=\"$langIdx\"] a").text()
            val lang = if (langItem.contains("Subtitulado")) "[Sub]" else if (langItem.contains("Latino")) "[Lat]" else "[Cast]"
            it.select("li.tab-video").map { servers ->
                val url = servers.attr("data-video")
                loadExtractor(url, lang).let { videos ->
                    videoList.addAll(videos)
                }
            }
        }
        return videoList
    }

    private fun loadExtractor(url: String, prefix: String = ""): List<Video> {
        val videoList = mutableListOf<Video>()
        val embedUrl = url.lowercase()
        if (embedUrl.contains("tomatomatela")) {
            try {
                val mainUrl = url.substringBefore("/embed.html#").substringAfter("https://")
                val headers = headers.newBuilder()
                    .set("authority", mainUrl)
                    .set("accept", "application/json, text/javascript, */*; q=0.01")
                    .set("accept-language", "es-MX,es-419;q=0.9,es;q=0.8,en;q=0.7")
                    .set("sec-ch-ua", "\"Chromium\";v=\"106\", \"Google Chrome\";v=\"106\", \"Not;A=Brand\";v=\"99\"")
                    .set("sec-ch-ua-mobile", "?0")
                    .set("sec-ch-ua-platform", "Windows")
                    .set("sec-fetch-dest", "empty")
                    .set("sec-fetch-mode", "cors")
                    .set("sec-fetch-site", "same-origin")
                    .set("x-requested-with", "XMLHttpRequest")
                    .build()
                val token = url.substringAfter("/embed.html#")
                val urlRequest = "https://$mainUrl/details.php?v=$token"
                val response = client.newCall(GET(urlRequest, headers = headers)).execute().asJsoup()
                val bodyText = response.select("body").text()
                val json = json.decodeFromString<JsonObject>(bodyText)
                val status = json["status"]!!.jsonPrimitive!!.content
                val file = json["file"]!!.jsonPrimitive!!.content
                if (status == "200") { videoList.add(Video(file, "$prefix Tomatomatela", file, headers = null)) }
            } catch (e: Exception) { }
        }
        if (embedUrl.contains("yourupload")) {
            val videos = YourUploadExtractor(client).videoFromUrl(url, headers = headers)
            videoList.addAll(videos)
        }
        if (embedUrl.contains("doodstream") || embedUrl.contains("dood.")) {
            DoodExtractor(client).videoFromUrl(url, "$prefix DoodStream", false)
                ?.let { videoList.add(it) }
        }
        if (embedUrl.contains("sbembed.com") || embedUrl.contains("sbembed1.com") || embedUrl.contains("sbplay.org") ||
            embedUrl.contains("sbvideo.net") || embedUrl.contains("streamsb.net") || embedUrl.contains("sbplay.one") ||
            embedUrl.contains("cloudemb.com") || embedUrl.contains("playersb.com") || embedUrl.contains("tubesb.com") ||
            embedUrl.contains("sbplay1.com") || embedUrl.contains("embedsb.com") || embedUrl.contains("watchsb.com") ||
            embedUrl.contains("sbplay2.com") || embedUrl.contains("japopav.tv") || embedUrl.contains("viewsb.com") ||
            embedUrl.contains("sbfast") || embedUrl.contains("sbfull.com") || embedUrl.contains("javplaya.com") ||
            embedUrl.contains("ssbstream.net") || embedUrl.contains("p1ayerjavseen.com") || embedUrl.contains("sbthe.com") ||
            embedUrl.contains("vidmovie.xyz") || embedUrl.contains("sbspeed.com") || embedUrl.contains("streamsss.net") ||
            embedUrl.contains("sblanh.com") || embedUrl.contains("sbbrisk.com") || embedUrl.contains("lvturbo.com")
        ) {
            runCatching {
                StreamSBExtractor(client).videosFromUrl(url, headers, prefix = prefix)
            }.getOrNull()?.let { videoList.addAll(it) }
        }
        if (embedUrl.contains("okru") || embedUrl.contains("ok.ru")) {
            videoList.addAll(
                OkruExtractor(client).videosFromUrl(url, prefix, true),
            )
        }
        if (embedUrl.contains("voe")) {
            VoeExtractor(client).videoFromUrl(url, "$prefix VoeCDN")?.let { videoList.add(it) }
        }
        if (embedUrl.contains("filemoon") || embedUrl.contains("moonplayer")) {
            FilemoonExtractor(client).videoFromUrl(url, prefix)?.let {
                videoList.addAll(it)
            }
        }
        if (embedUrl.contains("streamlare")) {
            StreamlareExtractor(client).videosFromUrl(url)?.let { videoList.add(it) }
        }
        return videoList
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La busqueda por genero ignora los otros filtros"),
        GenreFilter(),
    )

    private class GenreFilter : UriPartFilter(
        "Géneros",
        arrayOf(
            Pair("<selecionar>", ""),
            Pair("Peliculas", "peliculas"),
            Pair("Series", "series"),
            Pair("Estrenos", "estrenos"),
            Pair("Acción", "genero/accion"),
            Pair("Artes marciales", "genero/artes-marciales"),
            Pair("Asesinos en serie", "genero/asesinos-en-serie"),
            Pair("Aventura", "genero/aventura"),
            Pair("Baile", "genero/baile"),
            Pair("Bélico", "genero/belico"),
            Pair("Biografico", "genero/biografico"),
            Pair("Catástrofe", "genero/catastrofe"),
            Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
            Pair("Cine Adolescente", "genero/cine-adolescente"),
            Pair("Cine LGBT", "genero/cine-lgbt"),
            Pair("Cine Negro", "genero/cine-negro"),
            Pair("Cine Policiaco", "genero/cine-policiaco"),
            Pair("Clásicas", "genero/clasicas"),
            Pair("Comedia", "genero/comedia"),
            Pair("Comedia Negra", "genero/comedia-negra"),
            Pair("Crimen", "genero/crimen"),
            Pair("DC Comics", "genero/dc-comics"),
            Pair("Deportes", "genero/deportes"),
            Pair("Desapariciones", "genero/desapariciones"),
            Pair("Disney", "genero/disney"),
            Pair("Documental", "genero/documental"),
            Pair("Drama", "genero/drama"),
            Pair("Familiar", "genero/familiar"),
            Pair("Fantasía", "genero/fantasia"),
            Pair("Historia", "genero/historia"),
            Pair("Horror", "genero/horror"),
            Pair("Infantil", "genero/infantil"),
            Pair("Intriga", "genero/intriga"),
            Pair("live action", "genero/live-action"),
            Pair("Marvel Comics", "genero/marvel-comics"),
            Pair("Misterio", "genero/misterio"),
            Pair("Música", "genero/musica"),
            Pair("Musical", "genero/musical"),
            Pair("Policial", "genero/policial"),
            Pair("Político", "genero/politico"),
            Pair("Psicológico", "genero/psicologico"),
            Pair("Reality Tv", "genero/reality-tv"),
            Pair("Romance", "genero/romance"),
            Pair("Secuestro", "genero/secuestro"),
            Pair("Slasher", "genero/slasher"),
            Pair("Sobrenatural", "genero/sobrenatural"),
            Pair("Stand Up", "genero/stand-up"),
            Pair("Superhéroes", "genero/superheroes"),
            Pair("Suspenso", "genero/suspenso"),
            Pair("Terror", "genero/terror"),
            Pair("Thriller", "genero/thriller"),
            Pair("Tokusatsu", "genero/tokusatsu"),
            Pair("TV Series", "genero/tv-series"),
            Pair("Western", "genero/western"),
            Pair("Zombie", "genero/zombie"),
            Pair("Acción", "genero/accion"),
            Pair("Artes marciales", "genero/artes-marciales"),
            Pair("Asesinos en serie", "genero/asesinos-en-serie"),
            Pair("Aventura", "genero/aventura"),
            Pair("Baile", "genero/baile"),
            Pair("Bélico", "genero/belico"),
            Pair("Biografico", "genero/biografico"),
            Pair("Catástrofe", "genero/catastrofe"),
            Pair("Ciencia Ficción", "genero/ciencia-ficcion"),
            Pair("Cine Adolescente", "genero/cine-adolescente"),
            Pair("Cine LGBT", "genero/cine-lgbt"),
            Pair("Cine Negro", "genero/cine-negro"),
            Pair("Cine Policiaco", "genero/cine-policiaco"),
            Pair("Clásicas", "genero/clasicas"),
            Pair("Comedia", "genero/comedia"),
            Pair("Comedia Negra", "genero/comedia-negra"),
            Pair("Crimen", "genero/crimen"),
            Pair("DC Comics", "genero/dc-comics"),
            Pair("Deportes", "genero/deportes"),
            Pair("Desapariciones", "genero/desapariciones"),
            Pair("Disney", "genero/disney"),
            Pair("Documental", "genero/documental"),
            Pair("Drama", "genero/drama"),
            Pair("Familiar", "genero/familiar"),
            Pair("Fantasía", "genero/fantasia"),
            Pair("Historia", "genero/historia"),
            Pair("Horror", "genero/horror"),
            Pair("Infantil", "genero/infantil"),
            Pair("Intriga", "genero/intriga"),
            Pair("live action", "genero/live-action"),
            Pair("Marvel Comics", "genero/marvel-comics"),
            Pair("Misterio", "genero/misterio"),
            Pair("Música", "genero/musica"),
            Pair("Musical", "genero/musical"),
            Pair("Policial", "genero/policial"),
            Pair("Político", "genero/politico"),
            Pair("Psicológico", "genero/psicologico"),
            Pair("Reality Tv", "genero/reality-tv"),
            Pair("Romance", "genero/romance"),
            Pair("Secuestro", "genero/secuestro"),
            Pair("Slasher", "genero/slasher"),
            Pair("Sobrenatural", "genero/sobrenatural"),
            Pair("Stand Up", "genero/stand-up"),
            Pair("Superhéroes", "genero/superheroes"),
            Pair("Suspenso", "genero/suspenso"),
            Pair("Terror", "genero/terror"),
            Pair("Thriller", "genero/thriller"),
            Pair("Tokusatsu", "genero/tokusatsu"),
            Pair("TV Series", "genero/tv-series"),
            Pair("Western", "genero/western"),
            Pair("Zombie", "genero/zombie"),
        ),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val qualities = arrayOf(
            "StreamSB:1080p", "StreamSB:720p", "StreamSB:480p", "StreamSB:360p", "StreamSB:240p", "StreamSB:144p", // StreamSB
            "Fembed:1080p", "Fembed:720p", "Fembed:480p", "Fembed:360p", "Fembed:240p", "Fembed:144p", // Fembed
            "DoodStream",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Voex")
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
