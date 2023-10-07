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
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class MetroSeries : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "MetroSeries"

    override val baseUrl = "https://metroseries.net"

    override val lang = "es"

    private val json: Json by injectLazy()

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
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
                thumbnail_url = element.selectFirst(".post-thumbnail figure img")?.attr("abs:src") ?: ""
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
            thumbnail_url = document.selectFirst("main .post-thumbnail img")?.attr("abs:src")
            genre = document.select("main .entry-content .tagcloud a").joinToString { it.text() }
            status = SAnime.UNKNOWN
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        val document = response.asJsoup()
        document.select(".season-list li a")
            .sortedByDescending { it.attr("data-season") }.map {
                val post = it.attr("data-post")
                val season = it.attr("data-season")
                val objectNumber = document.select("#aa-season").attr("data-object")

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
                    .header("Referer", response.request.url.toString())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()
                val docEpisodes = client.newCall(request).execute().asJsoup()

                docEpisodes.select(".episodes-list li a").reversed().map {
                    val epNumber = it.ownText().substringAfter("x").substringBefore("–").trim()
                    val episode = SEpisode.create().apply {
                        setUrlWithoutDomain(it.attr("abs:href"))
                        name = "T$season - E$epNumber - ${it.ownText().substringAfter("–").trim()}"
                        date_upload = try {
                            SimpleDateFormat("dd-MM-yyyy", Locale.ENGLISH).parse(it.select("span").text()).time
                        } catch (_: Exception) { System.currentTimeMillis() }
                    }
                    episodes.add(episode)
                }
            }
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
                        FastreamExtractor(client).videoFromUrl(src).let { videoList.addAll(it) }
                    }

                    if (src.contains("upstream")) {
                        UpstreamExtractor(client).videosFromUrl(src).let { videoList.addAll(it) }
                    }
                    if (src.contains("yourupload")) {
                        YourUploadExtractor(client).videoFromUrl(src, headers).let { videoList.addAll(it) }
                    }
                    if (src.contains("voe")) {
                        VoeExtractor(client).videoFromUrl(src)?.let { videoList.add(it) }
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
                        FilemoonExtractor(client).videosFromUrl(src, headers = headers).let { videoList.addAll(it) }
                    }
                } catch (_: Exception) {}
            }
        }
        return videoList
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "Fastream:1080p")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality == quality) {
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
        val qualities = arrayOf(
            "YourUpload",
            "BurstCloud",
            "Voe",
            "StreamWish",
            "Mp4Upload",
            "Fastream:1080p",
            "Fastream:720p",
            "Fastream:480p",
            "Fastream:360p",
        )
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = qualities
            entryValues = qualities
            setDefaultValue("Fastream:1080p")
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
