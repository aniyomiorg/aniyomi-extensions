package eu.kanade.tachiyomi.animeextension.en.putlocker

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.en.putlocker.extractors.PutServerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

@ExperimentalSerializationApi
class PutLocker : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "PutLocker"

    override val baseUrl = "https://ww7.putlocker.vip"

    override val lang = "en"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val putServerExtractor by lazy { PutServerExtractor(client) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/putlocker/")

    override fun popularAnimeSelector(): String =
        "div#tab-movie > div.ml-item, div#tab-tv-show > div.ml-item"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeFromElement(element: Element): SAnime {
        return SAnime.create().apply {
            setUrlWithoutDomain(
                element.select("div.mli-poster > a")
                    .attr("abs:href"),
            )
            title = element.select("div.mli-info h3").text()
            thumbnail_url = element.select("div.mli-poster > a > img")
                .attr("abs:data-original")
        }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$baseUrl/filter/$page?genre=all&country=all&types=all&year=all&sort=updated")

    override fun latestUpdatesSelector(): String = "div.movies-list > div.ml-item"

    override fun latestUpdatesNextPageSelector(): String = "div#pagination li.active ~ li"

    override fun latestUpdatesFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return "[^A-Za-z0-9 ]".toRegex()
            .replace(query, "")
            .replace(" ", "+")
            .lowercase()
            .let {
                GET("$baseUrl/movie/search/$it/$page/")
            }
    }

    override fun searchAnimeSelector(): String = latestUpdatesSelector()

    override fun searchAnimeNextPageSelector(): String = latestUpdatesNextPageSelector()

    override fun searchAnimeFromElement(element: Element): SAnime = popularAnimeFromElement(element)

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime = SAnime.create().apply {
        document.select("div.mvic-desc").let { descElement ->
            val mLeft = descElement
                .select("div.mvic-info > div.mvici-left")
            val mRight = descElement
                .select("div.mvic-info > div.mvici-right")

            status = SAnime.COMPLETED
            genre = mLeft.select("p:contains(Genre) a").joinToString { it.text() }
            author = mLeft.select("p:contains(Production) a").first()?.text()
            description = buildString {
                appendLine(descElement.select("div.desc").text())
                appendLine()
                appendLine(mLeft.select("p:contains(Production) a").joinToString { it.text() })
                appendLine(mLeft.select("p:contains(Country)").text())
                mRight.select("p").mapNotNull { appendLine(it.text()) }
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request =
        GET("$baseUrl${anime.url}/watching.html")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val (type, mediaId) = doc.selectFirst("script:containsData(total_episode)")
            ?.data()
            ?.let {
                val t = it
                    .substringBefore("name:")
                    .substringAfter("type:")
                    .replace(",", "")
                    .trim()
                val mId = it
                    .substringAfter("id:")
                    .substringBefore(",")
                    .replace("\"", "")
                    .trim()
                Pair(t, mId)
            } ?: return emptyList()

        return when (type) {
            "1" -> {
                listOf(
                    SEpisode.create().apply {
                        url = EpLinks(
                            dataId = "1_full",
                            mediaId = mediaId,
                        ).toJson()
                        name = "Movie"
                        episode_number = 1F
                    },
                )
            }
            else -> {
                client.newCall(
                    GET("$baseUrl/ajax/movie/seasons/$mediaId"),
                ).execute()
                    .body.string()
                    .parseHtml()
                    .select("div.dropdown-menu > a")
                    .mapNotNull { it.attr("data-id") }
                    .sortedDescending()
                    .parallelCatchingFlatMapBlocking { season ->
                        client.newCall(
                            GET("$baseUrl/ajax/movie/season/episodes/${mediaId}_$season"),
                        ).execute()
                            .body.string()
                            .parseHtml()
                            .select("a")
                            .mapNotNull { elem ->
                                val dataId = elem.attr("data-id")
                                val epFloat = dataId
                                    .substringAfter("_")
                                    .toFloatOrNull()
                                    ?: 0F
                                SEpisode.create().apply {
                                    url = EpLinks(
                                        dataId = dataId,
                                        mediaId = mediaId,
                                    ).toJson()
                                    name = "Season $season ${elem.text()}"
                                    episode_number = epFloat
                                }
                            }.sortedByDescending { it.episode_number }
                    }
            }
        }
    }

    override fun episodeListSelector(): String = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val media = json.decodeFromString<EpLinks>(episode.url)
        return client.newCall(
            GET("$baseUrl/ajax/movie/episode/servers/${media.mediaId}_${media.dataId}"),
        ).execute()
            .body.string()
            .parseHtml()
            .select("a")
            .mapNotNull { elem ->
                Triple(
                    elem.attr("data-name"),
                    elem.attr("data-id"),
                    elem.text(),
                )
            }
            .parallelCatchingFlatMap { putServerExtractor.extractVideo(it, baseUrl) }
            .sort()
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================

    private fun EpLinks.toJson(): String = json.encodeToString(this)

    private fun String.parseHtml(): Document =
        json.decodeFromString<JsonObject>(this@parseHtml)["html"]!!
            .jsonPrimitive.content.run {
                Jsoup.parse(JSONUtil.unescape(this@run))
            }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", null)

        val newList = mutableListOf<Video>()
        if (quality != null) {
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }

            return newList
        }
        return this
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
        screen.addPreference(videoQualityPref)
    }
}
