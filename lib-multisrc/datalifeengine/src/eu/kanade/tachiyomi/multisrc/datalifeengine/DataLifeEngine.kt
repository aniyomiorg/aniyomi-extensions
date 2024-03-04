package eu.kanade.tachiyomi.multisrc.datalifeengine

import android.app.Application
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
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

abstract class DataLifeEngine(
    override val name: String,
    override val baseUrl: String,
    override val lang: String,
) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val supportsLatest = false

    private val preferences by lazy { Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000) }

    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div#dle-content > div.mov"

    override fun popularAnimeNextPageSelector(): String = "span.navigation > span:not(.nav_ext) + a"

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(element.selectFirst("a[href]")!!.attr("href").toHttpUrl().encodedPath)
            thumbnail_url = element.selectFirst("img[src]")?.absUrl("src") ?: ""
            title = "${element.selectFirst("a[href]")!!.text()} ${element.selectFirst("span.block-sai")?.text() ?: ""}"
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException()

    override fun latestUpdatesSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesNextPageSelector(): String = throw UnsupportedOperationException()

    override fun latestUpdatesFromElement(element: Element): SAnime = throw UnsupportedOperationException()

    // =============================== Search ===============================

    // TODO: Implement the *actual* search filters from : https://${baseUrl}/index.php?do=search
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters
        val genreFilter = filterList.find { it is GenreFilter } as GenreFilter
        val subPageFilter = filterList.find { it is SubPageFilter } as SubPageFilter

        return when {
            query.isNotBlank() -> {
                if (query.length < 4) throw Exception("La recherche est suspendue! La chaîne de recherche est vide ou contient moins de 4 caractères.")

                val postHeaders = headers.newBuilder()
                    .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .add("Content-Type", "application/x-www-form-urlencoded")
                    .add("Host", baseUrl.toHttpUrl().host)
                    .add("Origin", baseUrl)
                    .add("Referer", "$baseUrl/")
                    .build()

                val cleanQuery = query.replace(" ", "+")
                if (page == 1) {
                    val postBody = "do=search&subaction=search&story=$cleanQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    POST("$baseUrl/", body = postBody, headers = postHeaders)
                } else {
                    val postBody = "do=search&subaction=search&search_start=$page&full_search=0&result_from=11&story=$cleanQuery".toRequestBody("application/x-www-form-urlencoded".toMediaType())
                    POST("$baseUrl/index.php?do=search", body = postBody, headers = postHeaders)
                }
            }
            genreFilter.state != 0 -> {
                GET("$baseUrl${genreFilter.toUriPart()}page/$page/")
            }
            subPageFilter.state != 0 -> {
                GET("$baseUrl${subPageFilter.toUriPart()}page/$page/")
            }
            else -> popularAnimeRequest(page)
        }
    }

    override fun searchAnimeSelector(): String = popularAnimeSelector()

    override fun searchAnimeNextPageSelector(): String = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // ============================== Filters ===============================

    abstract val categories: Array<Pair<String, String>>

    abstract val genres: Array<Pair<String, String>>

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("La recherche de texte ignore les filtres"),
        SubPageFilter(categories),
        GenreFilter(genres),
    )

    private class SubPageFilter(categories: Array<Pair<String, String>>) : UriPartFilter(
        "Catégories",
        categories,
    )

    private class GenreFilter(genres: Array<Pair<String, String>>) : UriPartFilter(
        "Genres",
        genres,
    )

    private open class UriPartFilter(displayName: String, val vals: Array<Pair<String, String>>) :
        AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }
    // =========================== Anime Details ============================

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return client.newCall(animeDetailsRequest(anime))
            .awaitSuccess()
            .let { response ->
                animeDetailsParse(response, anime).apply { initialized = true }
            }
    }

    override fun animeDetailsParse(document: Document): SAnime = throw UnsupportedOperationException()

    private fun animeDetailsParse(response: Response, baseAnime: SAnime): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = baseAnime.title
            thumbnail_url = baseAnime.thumbnail_url
            description = document.selectFirst("div.mov-desc span[itemprop=description]")?.text() ?: ""
            genre = document.select("div.mov-desc span[itemprop=genre] a").joinToString(", ") {
                it.text()
            }
        }
    }

    // ============================= Utilities ==============================

    @JvmName("sortSEpisode")
    fun List<SEpisode>.sort(): List<SEpisode> = this.sortedWith(
        compareBy(
            { it.scanlator },
            { it.episode_number },
        ),
    ).reversed()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "720")!!
        val server = preferences.getString("preferred_server", "Upstream")!!

        return this.sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { it.quality.contains(server, true) },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("720")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val videoServerPref = ListPreference(screen.context).apply {
            key = "preferred_server"
            title = "Preferred server"
            entries = arrayOf("Upstream", "Vido", "StreamVid", "StreamHide", "Uqload", "Vudeo", "Doodstream", "Sibnet", "Okru")
            entryValues = arrayOf("Upstream", "Vido", "StreamVid", "StreamHide", "Uqload", "Vudeo", "dood", "sibnet", "okru")
            setDefaultValue("Upstream")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoQualityPref)
        screen.addPreference(videoServerPref)
    }
}
