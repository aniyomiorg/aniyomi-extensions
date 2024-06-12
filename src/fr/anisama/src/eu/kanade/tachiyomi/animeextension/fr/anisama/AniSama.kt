package eu.kanade.tachiyomi.animeextension.fr.anisama

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.anisama.extractors.VidCdnExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamhidevidextractor.StreamHideVidExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class AniSama : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "AniSama"

    override val baseUrl = "https://v1.anisama.net"

    override val lang = "fr"

    override val supportsLatest = true

    private val aniSamaFilters by lazy { AniSamaFilters(baseUrl, client) }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/most-popular/?page=$page")

    override fun popularAnimeSelector() = ".film_list article"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        title = element.select(".dynamic-name").text()
        thumbnail_url = element.select(".film-poster-img").attr("data-src")
        setUrlWithoutDomain(element.select(".film-poster-ahref").attr("href"))
    }

    override fun popularAnimeNextPageSelector() = ".ap__-btn-next a:not(.disabled)"

    override fun popularAnimeParse(response: Response): AnimesPage {
        aniSamaFilters.fetchFilters()
        return super.popularAnimeParse(response)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/recently-added/?page=$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesParse(response: Response): AnimesPage {
        aniSamaFilters.fetchFilters()
        return super.latestUpdatesParse(response)
    }

    // =============================== Search ===============================
    override fun getFilterList() = aniSamaFilters.getFilterList()

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id")).awaitSuccess().use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/filter".toHttpUrl().newBuilder()
        url.addQueryParameter("keyword", query)
        url.addQueryParameter("page", page.toString())
        filters.filterIsInstance<AniSamaFilters.QueryParameterFilter>().forEach {
            val (name, value) = it.toQueryParameter()
            if (value != null) url.addQueryParameter(name, value)
        }
        return GET(url.build())
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    private fun Elements.getMeta(name: String) = select(".item:has(.item-title:contains($name)) > .item-content").text()

    private fun Elements.parseStatus() = when (getMeta("Status")) {
        "Terminer" -> SAnime.COMPLETED
        "En cours" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    override fun animeDetailsParse(document: Document): SAnime {
        val details = document.select(".anime-detail")
        return SAnime.create().apply {
            title = details.select(".dynamic-name").text().substringBeforeLast(" ")
            thumbnail_url = details.select(".film-poster-img").attr("src")
            url = document.select("link[rel=canonical]").attr("href")
            artist = details.getMeta("Studio")
            status = details.parseStatus()
            description = details.select(".shorting").text()
            genre = details.getMeta("Genre")
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = ".ep-item"

    private fun SAnime.getId(): String = url.substringAfterLast("-")

    override fun episodeListRequest(anime: SAnime) = GET(
        "$baseUrl/ajax/episode/list/${anime.getId()}",
        headers.newBuilder().set("Referer", "$baseUrl${anime.url}").build(),
    )

    @Serializable
    data class HtmlResponseDTO(val html: String)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = Jsoup.parse(response.parseAs<HtmlResponseDTO>().html)
        return document.select(episodeListSelector()).parallelMapBlocking(::episodeFromElement).reversed()
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            episode_number = element.attr("data-number").toFloat()
            name = element.attr("title")
            val id = element.attr("href").substringAfterLast("=")
            url = "/ajax/episode/servers?episodeId=$id"
        }
    }

    // ============================ Video Links =============================
    @Serializable
    data class PlayerInfoDTO(val link: String)

    override fun videoListSelector() = ".server-item"

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vidCdnExtractor by lazy { VidCdnExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamHideVidExtractor by lazy { StreamHideVidExtractor(client) }

    override fun videoListRequest(episode: SEpisode) = GET(
        "$baseUrl${episode.url}",
        headers.newBuilder().set("Referer", "$baseUrl/").build(),
    )

    override fun videoListParse(response: Response): List<Video> {
        val document = Jsoup.parse(response.parseAs<HtmlResponseDTO>().html)
        val epid = response.request.url.toString().substringAfterLast("=")
        val serverBlacklist = preferences.serverBlacklist
        return document.select(videoListSelector()).parallelCatchingFlatMapBlocking {
            val playerUrl = client.newCall(
                GET("$baseUrl/ajax/episode/sources?id=${it.attr("data-id")}&epid=$epid"),
            ).execute().parseAs<PlayerInfoDTO>().link
            val prefix = "(${it.attr("data-type").uppercase()}) "
            if (serverBlacklist.fold(false) { acc, v -> acc || playerUrl.contains(Regex(v)) }) {
                emptyList()
            } else {
                with(playerUrl) {
                    when {
                        contains("toonanime.xyz") -> vidCdnExtractor.videosFromUrl(playerUrl, { "$prefix$it CDN" })
                        contains("filemoon.sx") -> filemoonExtractor.videosFromUrl(this, "$prefix Filemoon - ")
                        contains("sibnet.ru") -> sibnetExtractor.videosFromUrl(this, prefix)
                        contains("sendvid.com") -> sendvidExtractor.videosFromUrl(this, prefix)
                        contains("voe.sx") -> voeExtractor.videosFromUrl(this, prefix)
                        contains(Regex("(d000d|dood)")) -> doodExtractor.videosFromUrl(this, "${prefix}DoodStream")
                        contains("vidhide") -> streamHideVidExtractor.videosFromUrl(this, prefix)
                        else -> emptyList()
                    }
                }
            }
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val language = preferences.language
        val quality = preferences.quality
        val server = preferences.server
        return sortedWith(
            compareBy(
                { it.quality.contains(server) },
                { it.quality.contains(language) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================ Preferences =============================

    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES.map { it.first }.toTypedArray()
            entryValues = PREF_LANGUAGE_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, new ->
                val index = findIndexOfValue(new as String)
                preferences.edit().putString(key, entryValues[index] as String).commit()
            }
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES.map { it.first }.toTypedArray()
            entryValues = PREF_QUALITY_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, new ->
                val index = findIndexOfValue(new as String)
                preferences.edit().putString(key, entryValues[index] as String).commit()
            }
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = PREF_SERVER_TITLE
            entries = PREF_SERVER_ENTRIES.map { it.first }.toTypedArray()
            entryValues = PREF_SERVER_ENTRIES.map { it.second }.toTypedArray()
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, new ->
                val index = findIndexOfValue(new as String)
                preferences.edit().putString(key, entryValues[index] as String).commit()
            }
        }.also(screen::addPreference)
        MultiSelectListPreference(screen.context).apply {
            key = PREF_SERVER_BLACKLIST_KEY
            title = PREF_SERVER_BLACKLIST_TITLE
            entries = PREF_SERVER_ENTRIES.filter { it.second.isNotBlank() }.map { it.first }.toTypedArray()
            entryValues = PREF_SERVER_ENTRIES.filter { it.second.isNotBlank() }.map { it.third }.toTypedArray()
            setDefaultValue(PREF_SERVER_BLACKLIST_DEFAULT)
            setSummaryFromValues(preferences.serverBlacklist)

            setOnPreferenceChangeListener { _, new ->
                val newValue = new as Set<String>
                setSummaryFromValues(newValue)
                preferences.edit().putStringSet(key, newValue).commit()
            }
        }.also(screen::addPreference)
    }

    private fun MultiSelectListPreference.setSummaryFromValues(values: Set<String>) {
        summary = values.joinToString { entries[findIndexOfValue(it)] }.ifBlank { "Aucun" }
    }

    // ============================= Utilities ==============================

    private val SharedPreferences.language get() = getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
    private val SharedPreferences.quality get() = getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
    private val SharedPreferences.server get() = getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
    private val SharedPreferences.serverBlacklist get() = getStringSet(PREF_SERVER_BLACKLIST_KEY, PREF_SERVER_BLACKLIST_DEFAULT)!!

    companion object {
        const val PREFIX_SEARCH = "id:"

        private const val PREF_LANGUAGE_KEY = "preferred_sub"
        private const val PREF_LANGUAGE_TITLE = "Langue préférée"
        private const val PREF_LANGUAGE_DEFAULT = ""
        private val PREF_LANGUAGE_ENTRIES = arrayOf(
            Pair("VF", "VF"),
            Pair("VOSTFR", "VOSTFR"),
            Pair("Aucune", ""),
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualité préférée"
        private const val PREF_QUALITY_DEFAULT = ""
        private val PREF_QUALITY_ENTRIES = arrayOf(
            Pair("1080p", "1080"),
            Pair("720p", "720"),
            Pair("480p", "480"),
            Pair("360p", "360"),
            Pair("Aucune", ""),
        )

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_TITLE = "Lecteur préféré"
        private const val PREF_SERVER_DEFAULT = ""
        private const val PREF_SERVER_BLACKLIST_KEY = "blacklist_server"
        private const val PREF_SERVER_BLACKLIST_TITLE = "Lecteurs bloqués"
        private val PREF_SERVER_BLACKLIST_DEFAULT = emptySet<String>()

        private val PREF_SERVER_ENTRIES = arrayOf(
            Triple("Toonanime", "CDN", "toonanime\\.xyz"),
            Triple("Filemoon", "Filemoon", "filemoon\\.sx"),
            Triple("Sibnet", "Sibnet", "sibnet\\.ru"),
            Triple("Sendvid", "Sendvid", "sendvid\\.com"),
            Triple("Voe", "Voe", "voe\\.sx"),
            Triple("DoodStream", "DoodStream", "(dood|d000d)"),
            Triple("StreamHideVid", "StreamHideVid", "vidhide"),
            Triple("Aucun", "", "^$"),
        )
    }
}
