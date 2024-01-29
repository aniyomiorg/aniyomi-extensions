package eu.kanade.tachiyomi.animeextension.tr.tranimeci

import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.CountryFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.SeasonFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.StudioFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class TRAnimeCI : AnimeStream(
    "tr",
    "TRAnimeCI",
    "https://tranimaci.com",
) {
    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyProtectionInterceptor(network.client))
            .build()
    }

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    override val animeListUrl = "$baseUrl/search"

    override val dateFormatter by lazy {
        SimpleDateFormat("dd MMMM yyyy", Locale("tr"))
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.releases:contains(Populer) + div.listupd a.tip"

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/index?page=$page")

    override fun latestUpdatesSelector() = "div.releases:contains(Son Güncellenenler) ~ div.listupd a.tip"

    override fun latestUpdatesFromElement(element: Element) =
        searchAnimeFromElement(element).apply {
            // Convert episode url to anime url
            url = "/series$url".replace("/video", "").substringBefore("-bolum").substringBeforeLast("-")
        }

    override fun latestUpdatesNextPageSelector() = "div.hpage > a:last-child[href]"

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = TRAnimeCIFilters.getSearchParameters(filters)
        val url = "$animeListUrl?${params.genres}".toHttpUrl().newBuilder()
            .addIfNotBlank("country[]", params.country)
            .addIfNotBlank("season[]", params.season)
            .addIfNotBlank("format[]", params.type)
            .addIfNotBlank("studio[]", params.studio)
            .build()

        return GET(url.toString(), headers)
    }

    override fun searchAnimeSelector() = "div.advancedsearch a.tip"

    override fun searchAnimeNextPageSelector() = null

    // ============================== Filters ===============================
    override val filtersSelector = "div.filter.dropdown > ul"

    override fun getFilterList(): AnimeFilterList {
        return if (AnimeStreamFilters.filterInitialized()) {
            AnimeFilterList(
                GenresFilter("Tür"),
                AnimeFilter.Separator(),
                CountryFilter("Ülke"),
                SeasonFilter("Mevsim"),
                TypeFilter("Tip"),
                StudioFilter("Studio"),
            )
        } else {
            AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
        }
    }

    // =========================== Anime Details ============================
    override val animeDetailsSelector = "div.infox"
    override val animeStatusText = "Durum"

    override fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "tamamlandı" -> SAnime.COMPLETED
            "devam ediyor" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        val epNum = element.selectFirst(".epl-title")!!.text()
            .substringBefore(".")
            .substringBefore(" ")
            .toIntOrNull() ?: 1 // Int because of the episode name, a Float would render with more zeros.

        name = "Bölüm $epNum"
        episode_number = epNum.toFloat()

        date_upload = element.selectFirst(".epl-date")?.text().toDate()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val script = doc.selectFirst("script:containsData(let video_source)")!!.data()
        return script.substringAfter("[").substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val quality = it.substringAfter("name\":\"").substringBefore('"')
                val url = it.substringAfter("url\":\"").substringBefore('"')
                Video(url, quality, url, headers)
            }
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String) = apply {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
    }
}
