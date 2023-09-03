package eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri

import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.CountryFilter
import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.GenresFilter
import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.NetworkFilter
import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.OrderFilter
import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.StatusFilter
import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.StudioFilter
import eu.kanade.tachiyomi.animeextension.tr.asyaanimeleri.AsyaAnimeleriFilters.TypeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

class AsyaAnimeleri : AnimeStream(
    "tr",
    "AsyaAnimeleri",
    "https://asyaanimeleri.com",
) {
    override val animeListUrl = "$baseUrl/series"

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyProtectionInterceptor(network.client))
            .build()
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AsyaAnimeleriFilters.getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query")
        } else {
            val additional = params.run { listOf(genres, studios, countries, networks) }
                .filter(String::isNotBlank)
                .joinToString("&")

            val url = "$animeListUrl/?$additional".toHttpUrl().newBuilder()
                .addQueryParameter("page", "$page")
                .addIfNotBlank("status", params.status)
                .addIfNotBlank("type", params.type)
                .addIfNotBlank("order", params.order)
                .build()

            GET(url.toString(), headers)
        }
    }

    // ============================== Filters ===============================
    override val filtersSelector = "div.filter.dropdown > ul"

    override fun getFilterList(): AnimeFilterList {
        return if (AnimeStreamFilters.filterInitialized()) {
            AnimeFilterList(
                GenresFilter("Tür"),
                StudioFilter("Stüdyo"),
                CountryFilter("Ülke"),
                NetworkFilter("Ağ"),
                AnimeFilter.Separator(),
                StatusFilter("Durum"),
                TypeFilter("Tip"),
                OrderFilter("Sirala"),
            )
        } else {
            AnimeFilterList(AnimeFilter.Header(filtersMissingWarning))
        }
    }

    // =========================== Anime Details ============================
    override val animeStatusText = "Durum"

    override fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()?.lowercase()) {
            "tamamlandı" -> SAnime.COMPLETED
            "devam ediyor" -> SAnime.ONGOING
            else -> SAnime.UNKNOWN
        }
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String) = apply {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
    }
}
