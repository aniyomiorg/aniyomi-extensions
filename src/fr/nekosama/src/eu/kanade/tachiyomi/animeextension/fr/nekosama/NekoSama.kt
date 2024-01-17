package eu.kanade.tachiyomi.animeextension.fr.nekosama

import android.app.Application
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
import eu.kanade.tachiyomi.lib.fusevideoextractor.FusevideoExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception

class NekoSama : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Neko-Sama"

    override val baseUrl by lazy { "https://" + preferences.getString(PREF_DOMAIN_KEY, PREF_DOMAIN_DEFAULT)!! }

    override val lang = "fr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun popularAnimeSelector(): String = "div.anime"

    override fun popularAnimeRequest(page: Int): Request {
        return if (page > 1) {
            GET("$baseUrl/anime/$page")
        } else {
            GET("$baseUrl/anime/")
        }
    }

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(
            element.select("div.info a").attr("href"),
        )
        anime.title = element.select("div.info a div").text()
        val thumb1 = element.select("div.cover a div img:not(.placeholder)").attr("data-src")
        val thumb2 = element.select("div.cover a div img:not(.placeholder)").attr("src")
        anime.thumbnail_url = thumb1.ifBlank { thumb2 }
        return anime
    }

    override fun popularAnimeNextPageSelector(): String = "div.nekosama.pagination a.active ~ a"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val pageBody = response.asJsoup()
        val episodesJson = pageBody.selectFirst("script:containsData(var episodes =)")!!.data()
            .substringAfter("var episodes = ").substringBefore(";")
        val json = json.decodeFromString<List<EpisodesJson>>(episodesJson)

        return json.map {
            SEpisode.create().apply {
                name = try { it.episode!! } catch (e: Exception) { "episode" }
                url = it.url!!.replace("\\", "")

                episode_number = try { it.episode!!.substringAfter(". ").toFloat() } catch (e: Exception) { (0..10).random() }.toFloat()
            }
        }.reversed()
    }

    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val script = document.selectFirst("script:containsData(var video = [];)")!!.data()
        val playersRegex = Regex("video\\s*\\[\\d*]\\s*=\\s*'(.*?)'")
        return playersRegex.findAll(script).flatMap {
            val url = it.groupValues[1]
            with(url) {
                when {
                    contains("fusevideo") -> FusevideoExtractor(client, headers).videosFromUrl(this)
                    contains("streamtape") -> listOfNotNull(StreamTapeExtractor(client).videoFromUrl(this))
                    else -> emptyList()
                }
            }
        }.toList()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")!!

        return this.sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val typeFilter = filterList.find { it is TypeFilter } as TypeFilter
        val typeSearch = when (typeFilter.toUriPart()) {
            "anime" -> "vostfr"
            "anime-vf" -> "vf"
            else -> "vostfr"
        }

        return when {
            query.isNotBlank() -> GET("$baseUrl/animes-search-$typeSearch.json?$query")
            typeFilter.state != 0 || query.isNotBlank() -> when (page) {
                1 -> GET("$baseUrl/${typeFilter.toUriPart()}")
                else -> GET("$baseUrl/${typeFilter.toUriPart()}/$page")
            }
            else -> when (page) {
                1 -> GET("$baseUrl/anime/")
                else -> GET("$baseUrl/anime/page/$page")
            }
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val pageUrl = response.request.url.toString()
        val query = pageUrl.substringAfter("?").lowercase().replace("%20", " ")

        return when {
            pageUrl.contains("animes-search") -> {
                val jsonSearch = json.decodeFromString<List<SearchJson>>(response.asJsoup().body().text())
                val animes = mutableListOf<SAnime>()
                jsonSearch.map {
                    if (it.title!!.lowercase().contains(query)) {
                        val animeResult = SAnime.create().apply {
                            url = it.url!!
                            title = it.title!!
                            thumbnail_url = try {
                                it.url_image
                            } catch (e: Exception) {
                                "$baseUrl/images/default_poster.png"
                            }
                        }
                        animes.add(animeResult)
                    }
                }
                AnimesPage(
                    animes,
                    false,
                )
            }
            else -> {
                AnimesPage(
                    response.asJsoup().select(popularAnimeSelector()).map { popularAnimeFromElement(it) },
                    true,
                )
            }
        }
    }

    override fun searchAnimeFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    override fun searchAnimeNextPageSelector(): String = throw UnsupportedOperationException()

    override fun searchAnimeSelector(): String = throw UnsupportedOperationException()

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.title = document.selectFirst("div.col.offset-lg-3.offset-md-4 h1")!!.ownText()
        var description = document.select("div.synopsis p").text() + "\n\n"

        val scoreElement = document.selectFirst("div#anime-info-list div.item:contains(Score)")!!
        if (scoreElement.ownText().isNotEmpty()) description += "Score moyen: ★${scoreElement.ownText().trim()}"

        val statusElement = document.selectFirst("div#anime-info-list div.item:contains(Status)")!!
        if (statusElement.ownText().isNotEmpty()) description += "\nStatus: ${statusElement.ownText().trim()}"

        val formatElement = document.selectFirst("div#anime-info-list div.item:contains(Format)")!!
        if (formatElement.ownText().isNotEmpty()) description += "\nFormat: ${formatElement.ownText().trim()}"

        val diffusionElement = document.selectFirst("div#anime-info-list div.item:contains(Diffusion)")!!
        if (diffusionElement.ownText().isNotEmpty()) description += "\nDiffusion: ${diffusionElement.ownText().trim()}"

        anime.status = parseStatus(statusElement.ownText().trim())
        anime.description = description
        anime.thumbnail_url = document.select("div.cover img").attr("src")
        anime.genre = document.select("div.col.offset-lg-3.offset-md-4 div.list a").eachText().joinToString(separator = ", ")
        return anime
    }

    private fun parseStatus(statusString: String): Int {
        return when (statusString) {
            "En cours" -> SAnime.ONGOING
            "Terminé" -> SAnime.COMPLETED
            else -> SAnime.UNKNOWN
        }
    }

    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()

    override fun latestUpdatesRequest(page: Int): Request {
        return GET(baseUrl)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val animeList = mutableListOf<SAnime>()

        val jsonLatest = json.decodeFromString<List<SearchJson>>(
            response.body.string().substringAfter("var lastEpisodes = ").substringBefore(";\n"),
        )

        for (item in jsonLatest) {
            val animeResult = SAnime.create().apply {
                val type = item.url!!.substringAfterLast("-")
                url = item.url!!.replace("episode", "info").substringBeforeLast("-").substringBeforeLast("-") + "-$type"
                title = item.title!!
                thumbnail_url = try {
                    item.url_image
                } catch (e: Exception) {
                    "$baseUrl/images/default_poster.png"
                }
            }
            animeList.add(animeResult)
        }

        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesSelector() = throw UnsupportedOperationException()

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Utilisez ce filtre pour affiner votre recherche"),
        TypeFilter(),
    )

    private class TypeFilter : UriPartFilter(
        "VOSTFR or VF",
        arrayOf(
            Pair("<sélectionner>", "none"),
            Pair("VOSTFR", "anime"),
            Pair("VF", "anime-vf"),
        ),
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
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
        }

        ListPreference(screen.context).apply {
            key = PREF_DOMAIN_KEY
            title = PREF_DOMAIN_TITLE
            entries = PREF_DOMAIN_ENTRIES
            entryValues = PREF_DOMAIN_ENTRIES
            setDefaultValue(PREF_DOMAIN_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
        screen.addPreference(videoQualityPref)
    }

    @Serializable
    data class EpisodesJson(
        var time: String? = null,
        var episode: String? = null,
        var title: String? = null,
        var url: String? = null,
        var url_image: String? = null,

    )

    @Serializable
    data class SearchJson(
        var id: Int? = null,
        var title: String? = null,
        var titleEnglish: String? = null,
        var titleRomanji: String? = null,
        var titleFrench: String? = null,
        var others: String? = null,
        var type: String? = null,
        var status: String? = null,
        var popularity: Double? = null,
        var url: String? = null,
        var genres: ArrayList<String> = arrayListOf(),
        var url_image: String? = null,
        var score: String? = null,
        var startDateYear: String? = null,
        var nbEps: String? = null,

    )

    companion object {
        private const val PREF_DOMAIN_KEY = "pref_domain_key"
        private const val PREF_DOMAIN_TITLE = "Preferred domain"
        private const val PREF_DOMAIN_DEFAULT = "animecat.net"
        private val PREF_DOMAIN_ENTRIES = arrayOf("animecat.net", "neko-sama.fr")
    }
}
