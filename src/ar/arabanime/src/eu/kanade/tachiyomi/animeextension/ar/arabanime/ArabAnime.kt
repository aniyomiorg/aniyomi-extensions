package eu.kanade.tachiyomi.animeextension.ar.arabanime

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.AnimeItem
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.Episode
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.PopularAnimeResponse
import eu.kanade.tachiyomi.animeextension.ar.arabanime.dto.ShowItem
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class ArabAnime : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "ArabAnime"

    override val baseUrl = "https://www.arabanime.net"

    override val lang = "ar"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/api?page=$page")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val responseJson = json.decodeFromString<PopularAnimeResponse>(response.body.string())
        val animeList = responseJson.Shows.mapNotNull {
            runCatching {
                val animeJson = json.decodeFromString<AnimeItem>(it.decodeBase64())
                SAnime.create().apply {
                    setUrlWithoutDomain(animeJson.info_src)
                    title = animeJson.anime_name
                    thumbnail_url = animeJson.anime_cover_image_url
                }
            }.getOrNull()
        }
        val hasNextPage = responseJson.current_page < responseJson.last_page
        return AnimesPage(animeList, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl)

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val latestEpisodes = response.asJsoup().select("div.as-episode")
        val animeList = latestEpisodes.map {
            SAnime.create().apply {
                val ahref = it.selectFirst("a.as-info")!!
                title = ahref.text()
                val url = ahref.attr("href").replace("watch", "show").substringBeforeLast("/")
                setUrlWithoutDomain(url)

                thumbnail_url = it.selectFirst("img")?.absUrl("src")
            }
        }
        return AnimesPage(animeList, false)
    }

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) {
            val body = FormBody.Builder().add("searchq", query).build()
            POST("$baseUrl/searchq", body = body)
        } else {
            val type = filters.asQueryPart<TypeFilter>()
            val status = filters.asQueryPart<StatusFilter>()
            val order = filters.asQueryPart<OrderFilter>()
            GET("$baseUrl/api?order=$order&type=$type&stat=$status&tags=&page=$page")
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        return if (response.body.contentType() == "application/json".toMediaType()) {
            popularAnimeParse(response)
        } else {
            val searchResult = response.asJsoup().select("div.show")
            val animeList = searchResult.map {
                SAnime.create().apply {
                    setUrlWithoutDomain(it.selectFirst("a")!!.attr("href"))
                    title = it.selectFirst("h3")!!.text()
                    thumbnail_url = it.selectFirst("img")?.absUrl("src")
                }
            }
            return AnimesPage(animeList, false)
        }
    }

    // ============================== filters ==============================
    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("فلترة الموقع"),
        OrderFilter(),
        TypeFilter(),
        StatusFilter(),
    )

    open class QueryPartFilter(
        displayName: String,
        val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(
        displayName,
        vals.map { it.first }.toTypedArray(),
    ) {
        fun toQueryPart() = vals[state].second
    }

    private inline fun <reified R> AnimeFilterList.asQueryPart(): String {
        return (firstOrNull { it is R } as? QueryPartFilter)?.toQueryPart() ?: ""
    }

    private class OrderFilter : QueryPartFilter("ترتيب", ORDER_LIST)
    private class TypeFilter : QueryPartFilter("النوع", TYPE_LIST)
    private class StatusFilter : QueryPartFilter("الحالة", STATUS_LIST)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response): SAnime {
        val showData = response.asJsoup().selectFirst("div#data")!!
            .text()
            .decodeBase64()

        val details = json.decodeFromString<ShowItem>(showData).show[0]
        return SAnime.create().apply {
            url = "/show-${details.anime_id}/${details.anime_slug}"
            title = details.anime_name
            status = when (details.anime_status) {
                "Ongoing" -> SAnime.ONGOING
                "Completed" -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
            genre = details.anime_genres
            description = details.anime_description
            thumbnail_url = details.anime_cover_image_url
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        val showData = response.asJsoup().selectFirst("div#data")
            ?.text()
            ?.decodeBase64()
            ?: return emptyList()

        val episodesJson = json.decodeFromString<ShowItem>(showData)
        return episodesJson.EPS.map {
            SEpisode.create().apply {
                name = it.episode_name
                episode_number = it.episode_number.toFloat()
                setUrlWithoutDomain(it.`info-src`)
            }
        }.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val watchData = response.asJsoup().selectFirst("div#datawatch")
            ?.text()
            ?.decodeBase64()
            ?: return emptyList()

        val serversJson = json.decodeFromString<Episode>(watchData)
        val selectServer = serversJson.ep_info[0].stream_servers[0].decodeBase64()

        val watchPage = client.newCall(GET(selectServer)).execute().asJsoup()
        return watchPage.select("option")
            .map { it.text() to it.attr("data-src").decodeBase64() } // server : url
            .filter { it.second.contains("$baseUrl/embed") } // filter urls
            .flatMap { (name, url) ->
                client.newCall(GET(url)).execute()
                    .asJsoup()
                    .select("source")
                    .mapNotNull { source ->
                        val videoUrl = source.attr("src")
                        if (!videoUrl.contains("static")) {
                            val quality = source.attr("label").let { q ->
                                if (q.contains("p")) q else q + "p"
                            }
                            Video(videoUrl, "$name: $quality", videoUrl)
                        } else {
                            null
                        }
                    }
            }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    // =============================== Preferences ===============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
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

    private fun String.decodeBase64() = String(Base64.decode(this, Base64.DEFAULT))

    companion object {
        private val ORDER_LIST = arrayOf(
            Pair("اختر", ""),
            Pair("التقييم", "2"),
            Pair("اخر الانميات المضافة", "1"),
            Pair("الابجدية", "0"),
        )

        private val TYPE_LIST = arrayOf(
            Pair("اختر", ""),
            Pair("الكل", ""),
            Pair("فيلم", "0"),
            Pair("انمى", "1"),
        )

        private val STATUS_LIST = arrayOf(
            Pair("اختر", ""),
            Pair("الكل", ""),
            Pair("مستمر", "1"),
            Pair("مكتمل", "0"),
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p", "240p")
        private val PREF_QUALITY_VALUES by lazy {
            PREF_QUALITY_ENTRIES.map { it.substringBefore("p") }.toTypedArray()
        }
    }
}
