package eu.kanade.tachiyomi.animeextension.ar.egydead

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class EgyDead : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Egy Dead"

    // TODO: Check frequency of url changes to potentially
    // add back overridable baseurl preference
    override val baseUrl = "https://egydead.space"

    override val lang = "ar"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ================================== popular ==================================

    override fun popularAnimeSelector(): String = "div.pin-posts-list li.movieItem"

    override fun popularAnimeNextPageSelector(): String = "div.whatever"

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // ================================== episodes ==================================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()
        fun episodeExtract(element: Element): SEpisode {
            val episode = SEpisode.create()
            episode.setUrlWithoutDomain(element.attr("href"))
            episode.name = element.attr("title")
            return episode
        }
        fun addEpisodes(res: Response, final: Boolean = false) {
            val document = res.asJsoup()
            val url = res.request.url.toString()
            if (final) {
                document.select(episodeListSelector()).map {
                    val episode = episodeFromElement(it)
                    val season = document.select("div.infoBox div.singleTitle").text()
                    val seasonTxt = season.substringAfter("الموسم ").substringBefore(" ")
                    episode.name = if (season.contains("موسم"))"الموسم $seasonTxt ${episode.name}" else episode.name
                    episodes.add(episode)
                }
            } else if (url.contains("assembly")) {
                document.select("div.salery-list li.movieItem a").map {
                    episodes.add(episodeExtract(it))
                }
            } else if (url.contains("serie") || url.contains("season")) {
                if (document.select("div.seasons-list li.movieItem a").isNullOrEmpty()) {
                    document.select(episodeListSelector()).map {
                        episodes.add(episodeFromElement(it))
                    }
                } else {
                    document.select("div.seasons-list li.movieItem a").map {
                        addEpisodes(client.newCall(GET(it.attr("href"))).execute(), true)
                    }
                }
            } else if (url.contains("episode")) {
                document.selectFirst("#breadcrumbs li a[itemprop=url]")?.let {
                    addEpisodes(client.newCall(GET(it.attr("href"))).execute())
                }
            } else {
                val episode = SEpisode.create()
                episode.name = "مشاهدة"
                episode.setUrlWithoutDomain(url)
                episodes.add(episode)
            }
            // document.select(episodeListSelector()).map { episodes.add(episodeFromElement(it)) }
        }
        addEpisodes(response)
        return episodes
    }

    override fun episodeListSelector() = "div.EpsList li a"

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(element.attr("href"))
        episode.name = element.select("a").text()
        episode.episode_number = element.select("a").text().filter { it.isDigit() }.toFloat()
        return episode
    }

    // ================================== video urls ==================================
    private val streamWishExtractor by lazy { StreamWishExtractor(client, headers) }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val requestBody = FormBody.Builder().add("View", "1").build()

        val document = client.newCall(POST(baseUrl + episode.url, body = requestBody))
            .await()
            .asJsoup()
        return document.select(videoListSelector()).parallelCatchingFlatMap {
            val url = it.attr("data-link")
            extractVideos(url)
        }
    }

    private fun extractVideos(url: String): List<Video> {
        return when {
            DOOD_REGEX.containsMatchIn(url) -> {
                DoodExtractor(client).videoFromUrl(url, "Dood mirror")?.let(::listOf)
            }
            url.contains("mdbekjwqa") -> {
                MixDropExtractor(client).videoFromUrl(url)
            }
            url.contains("ahvsh") -> {
                val request = client.newCall(GET(url, headers)).execute().asJsoup()
                val script = request.selectFirst("script:containsData(sources)")!!.data()
                val streamLink = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(script)!!.groupValues[1]
                val quality = Regex("'qualityLabels'\\s*:\\s*\\{\\s*\".*?\"\\s*:\\s*\"(.*?)\"").find(script)!!.groupValues[1]
                Video(streamLink, "StreamHide: $quality", streamLink).let(::listOf)
            }
            STREAMWISH_REGEX.containsMatchIn(url) -> {
                streamWishExtractor.videosFromUrl(url)
            }
            url.contains("fanakishtuna") -> {
                val request = client.newCall(GET(url, headers)).execute().asJsoup()
                val data = request.selectFirst("script:containsData(sources)")!!.data()
                val streamLink = Regex("sources:\\s*\\[\\{\\s*\\t*file:\\s*[\"']([^\"']+)").find(data)!!.groupValues[1]
                listOf(Video(streamLink, "Mirror: High Quality", streamLink))
            }
            url.contains("uqload") -> {
                val newURL = url.replace("https://uqload.co/", "https://www.uqload.co/")
                val request = client.newCall(GET(newURL, headers)).execute().asJsoup()
                val data = request.selectFirst("script:containsData(sources)")!!.data()
                val streamLink = data.substringAfter("sources: [\"").substringBefore("\"]")
                listOf(Video(streamLink, "Uqload: Mirror", streamLink))
            }

            else -> null
        } ?: emptyList()
    }

    override fun videoListSelector() = "ul.serversList li"

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080p")
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

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ================================== search ==================================

    override fun searchAnimeNextPageSelector(): String = "div.pagination-two a:contains(›)"

    override fun searchAnimeSelector(): String = "div.catHolder li.movieItem"

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = if (query.isNotBlank()) {
            "$baseUrl/page/$page/?s=$query"
        } else {
            val url = baseUrl
            (if (filters.isEmpty()) getFilterList() else filters).forEach { filter ->
                when (filter) {
                    is CategoryList -> {
                        if (filter.state > 0) {
                            val catQ = getCategoryList()[filter.state].query
                            val catUrl = "$baseUrl/$catQ/?page=$page/"
                            return GET(catUrl, headers)
                        }
                    }
                    else -> {}
                }
            }
            return GET(url, headers)
        }
        return GET(url, headers)
    }

    override fun searchAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    override fun getFilterList() = AnimeFilterList(
        CategoryList(categoriesName),
    )

    private class CategoryList(categories: Array<String>) : AnimeFilter.Select<String>("الأقسام", categories)

    private data class CatUnit(val name: String, val query: String)

    private val categoriesName = getCategoryList().map {
        it.name
    }.toTypedArray()

    private fun getCategoryList() = listOf(
        CatUnit("اختر القسم", ""),
        CatUnit("افلام اجنبى", "category/افلام-اجنبي"),
        CatUnit("افلام اسلام الجيزاوى", "category/ترجمات-اسلام-الجيزاوي"),
        CatUnit("افلام انمى", "category/افلام-كرتون"),
        CatUnit("افلام تركيه", "category/افلام-تركية"),
        CatUnit("افلام اسيويه", "category/افلام-اسيوية"),
        CatUnit("افلام مدبلجة", "category/افلام-اجنبية-مدبلجة"),
        CatUnit("سلاسل افلام", "assembly"),
        CatUnit("مسلسلات اجنبية", "series-category/مسلسلات-اجنبي"),
        CatUnit("مسلسلات انمى", "series-category/مسلسلات-انمي"),
        CatUnit("مسلسلات تركية", "series-category/مسلسلات-تركية"),
        CatUnit("مسلسلات اسيوىة", "series-category/مسلسلات-اسيوية"),
        CatUnit("مسلسلات لاتينية", "series-category/مسلسلات-لاتينية"),
        CatUnit("المسلسلات الكاملة", "serie"),
        CatUnit("المواسم الكاملة", "season"),
    )

    // ================================== details ==================================

    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        anime.thumbnail_url = document.select("div.single-thumbnail img").attr("src")
        anime.title = document.select("div.infoBox div.singleTitle").text()
        anime.author = document.select("div.LeftBox li:contains(البلد) a").text()
        anime.artist = document.select("div.LeftBox li:contains(القسم) a").text()
        anime.genre = document.select("div.LeftBox li:contains(النوع) a, div.LeftBox li:contains(اللغه) a, div.LeftBox li:contains(السنه) a").joinToString(", ") { it.text() }
        anime.description = document.select("div.infoBox div.extra-content p").text()
        anime.status = if (anime.title.contains("كامل") || anime.title.contains("فيلم")) SAnime.COMPLETED else SAnime.ONGOING
        return anime
    }

    // ================================== latest ==================================

    override fun latestUpdatesSelector(): String = "section.main-section li.movieItem"

    override fun latestUpdatesNextPageSelector(): String = "div.pagination ul.page-numbers li a.next"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/?page=$page/")

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        anime.setUrlWithoutDomain(element.select("a").attr("href"))
        anime.title = element.select("h1.BottomTitle").text()
        anime.thumbnail_url = element.select("a img").attr("src")
        return anime
    }

    // ================================== preferences ==================================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p", "240p", "DoodStream", "Uqload")
            entryValues = arrayOf("1080", "720", "480", "360", "240", "Dood", "Uqload")
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

    // like|kharabnahk
    companion object {
        private val DOOD_REGEX = Regex("(do*d(?:stream)?\\.(?:com?|watch|to|s[ho]|cx|la|w[sf]|pm|re|yt|stream))/[de]/([0-9a-zA-Z]+)|ds2play")
        private val STREAMWISH_REGEX = Regex("ajmidyad|alhayabambi|atabknh[ks]|https://.*\\.sbs/e/")
    }
}
