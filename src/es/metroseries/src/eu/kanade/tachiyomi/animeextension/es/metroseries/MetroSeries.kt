package eu.kanade.tachiyomi.animeextension.es.metroseries

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.burstcloudextractor.BurstCloudExtractor
import eu.kanade.tachiyomi.lib.fastreamextractor.FastreamExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.lib.upstreamextractor.UpstreamExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapBlocking
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class MetroSeries : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MetroSeries"

    override val baseUrl = "https://metroseries.net"

    override val lang = "es"

    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "YourUpload"
        private val SERVER_LIST = arrayOf(
            "YourUpload",
            "BurstCloud",
            "Voe",
            "StreamWish",
            "Mp4Upload",
            "Fastream",
            "Upstream",
            "Filemoon",
        )
    }

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/series/page/$page", headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val elements = document.select(".post-list, .results-post > .post")
        val nextPage = document.select(".nav-links .current ~ a").any()
        val animeList = elements.map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.selectFirst(".lnk-blk")?.attr("abs:href") ?: "")
                title = element.selectFirst(".entry-header .entry-title")?.text() ?: ""
                description = element.select(".entry-content p").text() ?: ""
                thumbnail_url = element.selectFirst(".post-thumbnail figure img")?.let { getImageUrl(it) }
            }
        }
        return AnimesPage(animeList, nextPage)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = GET("$baseUrl/?s=$query", headers)

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            title = document.selectFirst("main .entry-header .entry-title")?.text() ?: ""
            description = document.select("main .entry-content p").joinToString { it.text() }
            thumbnail_url = document.selectFirst("main .post-thumbnail img")?.let { getImageUrl(it) }
            genre = document.select("main .entry-content .tagcloud a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    private fun getImageUrl(element: Element): String? {
        return when {
            element.hasAttr("data-src") -> element.attr("abs:data-src")
            element.hasAttr("src") -> element.attr("abs:src")
            else -> null
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val referer = response.request.url.toString()
        val chunkSize = Runtime.getRuntime().availableProcessors()
        val objectNumber = document.select("#aa-season").attr("data-object")
        val episodes = document.select(".season-list li a")
            .sortedByDescending { it.attr("data-season") }
            .chunked(chunkSize).flatMap { chunk ->
                chunk.parallelCatchingFlatMapBlocking { season ->
                    val pages = getDetailSeason(season, objectNumber, referer)
                    getPageEpisodeList(pages, referer, objectNumber, season.attr("data-season"))
                }
            }.sortedByDescending {
                it.name.substringBeforeLast("-")
            }
        return episodes
    }

    private fun getDetailSeason(element: org.jsoup.nodes.Element, objectNumber: String, referer: String): IntRange {
        try {
            val post = element.attr("data-post")
            val season = element.attr("data-season")
            val formBody = FormBody.Builder()
                .add("action", "action_select_season")
                .add("season", season)
                .add("post", post)
                .add("object", objectNumber)
                .build()

            val request = Request.Builder()
                .url("https://metroseries.net/wp-admin/admin-ajax.php")
                .post(formBody)
                .header("Origin", baseUrl)
                .header("Referer", referer)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()
            val detail = client.newCall(request).execute().asJsoup()

            val firstPage = try { detail.selectFirst("#aa-season > nav > span.page-numbers")?.text()?.toInt() ?: 1 } catch (_: Exception) { 1 }
            val lastPage = try { detail.select(".pagination a.page-numbers:not(.next)").last()?.text()?.toInt() ?: firstPage } catch (_: Exception) { firstPage }

            return firstPage.rangeTo(lastPage)
        } catch (_: Exception) {
            return 1..1
        }
    }

    private fun getPageEpisodeList(pages: IntRange, referer: String, objectNumber: String, season: String): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        try {
            pages.parallelMapBlocking {
                val formBody = FormBody.Builder()
                    .add("action", "action_pagination_ep")
                    .add("page", "$it")
                    .add("object", objectNumber)
                    .add("season", season)
                    .build()

                val requestPage = Request.Builder()
                    .url("https://metroseries.net/wp-admin/admin-ajax.php")
                    .post(formBody)
                    .header("authority", baseUrl.toHttpUrl().host)
                    .header("Origin", "https://${baseUrl.toHttpUrl().host}")
                    .header("Referer", referer)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()

                client.newCall(requestPage).await().parseAsEpisodeList().also(episodes::addAll)
            }
        } catch (_: Exception) { }
        return episodes
    }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val videoList = mutableListOf<Video>()
        val termId = document.select("#option-players").attr("data-term")
        document.select(".player-options-list li a").forEach {
            val ide = it.attr("data-opt")
            val formBody = FormBody.Builder()
                .add("action", "action_player_series")
                .add("ide", ide)
                .add("term_id", termId)
                .build()

            val postRequest = Request.Builder()
                .url("https://metroseries.net/wp-admin/admin-ajax.php")
                .post(formBody)
                .header("Origin", baseUrl)
                .header("Referer", response.request.url.toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val playerDocument = client.newCall(postRequest).execute().asJsoup()
            playerDocument.select("iframe").forEach {
                var src = it.attr("src").replace("#038;", "&").replace("&amp;", "")
                try {
                    if (src.contains("metroseries")) {
                        src = client.newCall(GET(src)).execute().asJsoup().selectFirst("iframe")?.attr("src") ?: ""
                    }

                    if (src.contains("fastream")) {
                        if (src.contains("emb.html")) {
                            val key = src.split("/").last()
                            src = "https://fastream.to/embed-$key.html"
                        }
                        FastreamExtractor(client, headers).videosFromUrl(src, needsSleep = false).also(videoList::addAll)
                    }

                    if (src.contains("upstream")) {
                        UpstreamExtractor(client).videosFromUrl(src).let { videoList.addAll(it) }
                    }
                    if (src.contains("yourupload")) {
                        YourUploadExtractor(client).videoFromUrl(src, headers).let { videoList.addAll(it) }
                    }
                    if (src.contains("voe")) {
                        VoeExtractor(client).videosFromUrl(src).also(videoList::addAll)
                    }
                    if (src.contains("wishembed") || src.contains("streamwish") || src.contains("wish")) {
                        StreamWishExtractor(client, headers).videosFromUrl(src) { "StreamWish:$it" }.also(videoList::addAll)
                    }
                    if (src.contains("mp4upload")) {
                        Mp4uploadExtractor(client).videosFromUrl(src, headers).let { videoList.addAll(it) }
                    }
                    if (src.contains("burst")) {
                        BurstCloudExtractor(client).videoFromUrl(src, headers = headers).let { videoList.addAll(it) }
                    }
                    if (src.contains("filemoon") || src.contains("moonplayer")) {
                        FilemoonExtractor(client).videosFromUrl(src, headers = headers, prefix = "Filemoon:").let { videoList.addAll(it) }
                    }
                } catch (_: Exception) {}
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val server = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)!!
        return this.sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Preferred quality"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_SERVER_KEY
            title = "Preferred server"
            entries = SERVER_LIST
            entryValues = SERVER_LIST
            setDefaultValue(PREF_SERVER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    private fun Response.parseAsEpisodeList(): List<SEpisode> {
        return asJsoup().select(".episodes-list li a").reversed().mapIndexed { idx, it ->
            val epNumber = try { it.ownText().substringAfter("x").substringBefore("–").trim() } catch (_: Exception) { "${idx + 1}" }
            val season = it.ownText().substringBefore("x").trim()
            SEpisode.create().apply {
                setUrlWithoutDomain(it.attr("abs:href"))
                name = "T$season - E$epNumber - ${it.ownText().substringAfter("–").trim()}"
                episode_number = epNumber.toFloat()
                date_upload = try {
                    SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).parse(it.select("span").text()).time
                } catch (_: Exception) {
                    System.currentTimeMillis()
                }
            }
        }
    }
}
