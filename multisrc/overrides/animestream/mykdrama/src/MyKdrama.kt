package eu.kanade.tachiyomi.animeextension.fr.mykdrama

import eu.kanade.tachiyomi.animeextension.fr.mykdrama.MyKdramaFilters.CountryFilter
import eu.kanade.tachiyomi.animeextension.fr.mykdrama.MyKdramaFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.fr.mykdrama.MyKdramaFilters.OrderFilter
import eu.kanade.tachiyomi.animeextension.fr.mykdrama.MyKdramaFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.fr.mykdrama.MyKdramaFilters.TypeFilter
import eu.kanade.tachiyomi.animeextension.fr.mykdrama.MyKdramaFilters.getSearchParameters
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class MyKdrama : AnimeStream(
    "fr",
    "MyKdrama",
    "https://mykdrama.co",
) {
    override val animeListUrl = "$baseUrl/drama"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM dd, yyyy", Locale("fr"))
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val queryParams = params.run { listOf(genres, countries) }
                .filter(String::isNotBlank)
                .joinToString("&")
            val url = "$animeListUrl/?$queryParams".toHttpUrl().newBuilder()
                .addQueryParameter("page", "$page")
                .addIfNotBlank("status", params.status)
                .addIfNotBlank("type", params.type)
                .addIfNotBlank("order", params.order)
                .build()
            GET(url, headers)
        }
    }

    // ============================== Filters ===============================
    override val filtersSelector = "div.filter > ul"

    override fun getFilterList(): AnimeFilterList {
        return if (AnimeStreamFilters.filterInitialized()) {
            AnimeFilterList(
                GenresFilter("Genres"),
                CountryFilter("Pays"),
                AnimeFilter.Separator(),
                StatusFilter("Status"),
                TypeFilter("Type"),
                OrderFilter("Ordre"),
            )
        } else {
            AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
        }
    }

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.use { it.asJsoup() }
        return doc.select(videoListSelector()).parallelCatchingFlatMapBlocking { element ->
            val name = element.text()
            val url = getHosterUrl(element)
            getVideoList(url, name)
        }.ifEmpty {
            doc.select(".gov-the-embed").parallelCatchingFlatMapBlocking { element ->
                val name = element.text()
                val pageUrl = element.attr("onClick").substringAfter("'").substringBefore("'")
                val url = client.newCall(GET(pageUrl)).execute().use { it.asJsoup().select("#pembed iframe").attr("src") }
                getVideoList(url, name)
            }
        }
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            "ok.ru" in url -> okruExtractor.videosFromUrl(url)
            "uqload" in url -> uqloadExtractor.videosFromUrl(url)
            "dood" in url || "doodstream" in url -> doodExtractor.videosFromUrl(url)
            "vudeo" in url -> vudeoExtractor.videosFromUrl(url)
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String) = apply {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
    }
}
