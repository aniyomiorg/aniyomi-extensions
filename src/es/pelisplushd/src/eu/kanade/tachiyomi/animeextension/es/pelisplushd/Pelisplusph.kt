package eu.kanade.tachiyomi.animeextension.es.pelisplushd

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.es.pelisplushd.extractors.StreamHideExtractor
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Pelisplusph(override val name: String, override val baseUrl: String) : Pelisplushd(name, baseUrl) {

    override val supportsLatest = false

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "[LAT]"
        private val LANGUAGE_LIST = arrayOf("[LAT]", "[SUB]", "[CAST]")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "Voe"
        private val SERVER_LIST = arrayOf(
            "YourUpload", "BurstCloud", "Voe", "Mp4Upload", "Doodstream",
            "Upload", "BurstCloud", "Upstream", "StreamTape", "Amazon",
            "Fastream", "Filemoon", "StreamWish", "Okru", "Streamlare",
            "StreamHide", "Tomatomatela",
        )
    }

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
        anime.status =
            if (document.location().contains("/serie/")) SAnime.UNKNOWN else SAnime.COMPLETED

        return anime
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val jsoup = response.asJsoup()
        if (response.request.url.toString().contains("/pelicula/")) {
            val episode = SEpisode.create().apply {
                episode_number = 1F
                name = "PELÍCULA"
                setUrlWithoutDomain(response.request.url.toString())
            }
            episodes.add(episode)
        } else {
            var index = 0
            jsoup.select(".item-season").reversed().mapIndexed { idxSeas, season ->
                val seasonNumber = runCatching {
                    getNumberFromString(season.selectFirst(".item-season-title")!!.ownText())
                }.getOrElse { idxSeas + 1 }
                season.select(".item-season-episodes a").reversed().mapIndexed { idx, ep ->
                    index += 1
                    val noEp = try {
                        getNumberFromString(ep.ownText())
                    } catch (_: Exception) {
                        idx + 1
                    }
                    val episode = SEpisode.create().apply {
                        episode_number = index.toFloat()
                        name = "T$seasonNumber - E$noEp - ${ep.ownText()}"
                        setUrlWithoutDomain(ep.attr("abs:href"))
                    }
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
        return document.select("[class*=server-item-]").parallelCatchingFlatMapBlocking { serverItem ->
            val langIdx = getNumberFromString(serverItem.attr("class").substringAfter("server-item-"))
            val langItem = document.select("li[data-id=\"$langIdx\"] a").text()
            val lang = if (langItem.contains("Subtitulado")) "[SUB]" else if (langItem.contains("Latino")) "[LAT]" else "[CAST]"

            serverItem.select("li.tab-video").map { videoItem ->
                val url = videoItem.attr("data-video")
                serverVideoResolver(url, lang)
            }.flatten()
        }
    }

    private fun serverVideoResolver(url: String, prefix: String = ""): List<Video> {
        val embedUrl = url.lowercase()
        return runCatching {
            when {
                embedUrl.contains("voe") -> VoeExtractor(client).videosFromUrl(url, prefix)
                embedUrl.contains("ok.ru") || embedUrl.contains("okru") -> OkruExtractor(client).videosFromUrl(url, prefix)
                embedUrl.contains("filemoon") || embedUrl.contains("moonplayer") -> {
                    val vidHeaders = headers.newBuilder()
                        .add("Origin", "https://${url.toHttpUrl().host}")
                        .add("Referer", "https://${url.toHttpUrl().host}/")
                        .build()
                    FilemoonExtractor(client).videosFromUrl(url, prefix = "$prefix Filemoon:", headers = vidHeaders)
                }
                embedUrl.contains("uqload") -> UqloadExtractor(client).videosFromUrl(url, prefix = prefix)
                embedUrl.contains("mp4upload") -> Mp4uploadExtractor(client).videosFromUrl(url, headers, prefix = prefix)
                embedUrl.contains("wishembed") || embedUrl.contains("streamwish") || embedUrl.contains("strwish") || embedUrl.contains("wish") -> {
                    val docHeaders = headers.newBuilder()
                        .add("Origin", "https://streamwish.to")
                        .add("Referer", "https://streamwish.to/")
                        .build()
                    StreamWishExtractor(client, docHeaders).videosFromUrl(url, videoNameGen = { "$prefix StreamWish:$it" })
                }
                embedUrl.contains("doodstream") || embedUrl.contains("dood.") || embedUrl.contains("ds2play") || embedUrl.contains("doods.") -> {
                    val url2 = url.replace("https://doodstream.com/e/", "https://dood.to/e/")
                    listOf(DoodExtractor(client).videoFromUrl(url2, "$prefix DoodStream", false)!!)
                }
                embedUrl.contains("streamlare") -> StreamlareExtractor(client).videosFromUrl(url, prefix = prefix)
                embedUrl.contains("yourupload") || embedUrl.contains("upload") -> YourUploadExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)
                embedUrl.contains("burstcloud") || embedUrl.contains("burst") -> BurstCloudExtractor(client).videoFromUrl(url, headers = headers, prefix = prefix)
                embedUrl.contains("fastream") -> FastreamExtractor(client, headers).videosFromUrl(url, prefix = "$prefix Fastream:")
                embedUrl.contains("upstream") -> UpstreamExtractor(client).videosFromUrl(url, prefix = prefix)
                embedUrl.contains("streamtape") || embedUrl.contains("stp") || embedUrl.contains("stape") -> listOf(StreamTapeExtractor(client).videoFromUrl(url, quality = "$prefix StreamTape")!!)
                embedUrl.contains("ahvsh") || embedUrl.contains("streamhide") || embedUrl.contains("guccihide") || embedUrl.contains("streamvid") -> StreamHideExtractor(client).videosFromUrl(url, "$prefix StreamHide")
                else -> emptyList()
            }
        }.getOrNull() ?: emptyList()
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        val lang = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
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
            key = PREF_LANGUAGE_KEY
            title = "Preferred language"
            entries = LANGUAGE_LIST
            entryValues = LANGUAGE_LIST
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
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
