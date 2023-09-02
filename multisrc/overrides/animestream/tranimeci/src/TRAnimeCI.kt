package eu.kanade.tachiyomi.animeextension.tr.tranimeci

import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.CountryFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.SeasonFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.StudioFilter
import eu.kanade.tachiyomi.animeextension.tr.tranimeci.TRAnimeCIFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class TRAnimeCI : AnimeStream(
    "tr",
    "TRAnimeCI",
    "https://tranimeci.com",
) {
    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyProtectionInterceptor(network.client))
            .build()
    }

    override val animeListUrl = "$baseUrl/search"

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl)

    override fun popularAnimeSelector() = "div.releases:contains(Populer) + div.listupd a.tip"

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/index?page=$page")

    override fun latestUpdatesSelector() = "div.releases:contains(Son Güncellenenler) ~ div.listupd a.tip"

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

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String) = apply {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
    }
}
