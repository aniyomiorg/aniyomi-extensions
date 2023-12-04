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
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.gdriveplayerextractor.GdrivePlayerExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStreamFilters
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AsyaAnimeleri : AnimeStream(
    "tr",
    "AsyaAnimeleri",
    "https://asyaanimeleri.com",
) {
    override val animeListUrl = "$baseUrl/series"

    override val dateFormatter by lazy {
        SimpleDateFormat("MMMM dd, yyyy", Locale("tr"))
    }

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

    // ============================== Episodes ==============================
    override val episodePrefix = "Bölüm"

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("1080p", "720p", "480p", "360p", "240p", "144p")
    override val prefQualityEntries = prefQualityValues

    private val vkExtractor by lazy { VkExtractor(client, headers) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val gdrivePlayerExtractor by lazy { GdrivePlayerExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    // private val dailyExtractor by lazy { DailymotionExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when (name.lowercase().trim()) {
            "vk" -> vkExtractor.videosFromUrl(url)
            "ok.ru" -> okruExtractor.videosFromUrl(url)
            "sibnet" -> sibnetExtractor.videosFromUrl(url)
            // "daily" -> dailyExtractor.videosFromUrl(url)
            "dood", "doodstream" -> doodExtractor.videoFromUrl(url)?.let(::listOf) ?: emptyList()
            "gdrive" -> {
                val newUrl = "https://gdriveplayer.to/embed2.php?link=$url"
                gdrivePlayerExtractor.videosFromUrl(newUrl, "Gdrive", headers)
            }
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================
    private fun HttpUrl.Builder.addIfNotBlank(query: String, value: String) = apply {
        if (value.isNotBlank()) {
            addQueryParameter(query, value)
        }
    }

    // Overriding to prevent removing the ?resize part.
    // Without it, some images simply don't load (????)
    // Turkish source moment. That's why i prefer greeks.
    override fun Element.getImageUrl(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            else -> attr("abs:src")
        }
    }
}
