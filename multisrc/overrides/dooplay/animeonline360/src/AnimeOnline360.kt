package eu.kanade.tachiyomi.animeextension.en.animeonline360

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element

class AnimeOnline360 : DooPlay(
    "en",
    "AnimeOnline360",
    "https://animeonline360.me",
) {
    // ============================== Popular ===============================

    override fun popularAnimeSelector(): String = "div.content article > div.poster"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/trending/page/$page/")

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    override fun popularAnimeFromElement(element: Element): SAnime {
        return super.popularAnimeFromElement(element).apply { title = title.addSubPrefix() }
    }

    // =============================== Latest ===============================

    override fun latestUpdatesSelector(): String = "div#archive-content > article"

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/anime-a/page/$page/")

    override fun latestUpdatesNextPageSelector(): String = "#nextpagination"

    override fun latestUpdatesFromElement(element: Element): SAnime {
        return super.latestUpdatesFromElement(element).apply { title = title.addSubPrefix() }
    }

    // =============================== Search ===============================

    override fun searchAnimeNextPageSelector(): String = "div.pagination > span.current + a"

    // ============================== Episodes ==============================

    override fun episodeListSelector() = "ul.episodios > li"

    override fun episodeListParse(response: Response): List<SEpisode> {
        return if (response.request.url.pathSegments.first().contains("movie", true)) {
            val document = response.use { getRealAnimeDoc(it.asJsoup()) }
            listOf(
                SEpisode.create().apply {
                    episode_number = 0F
                    date_upload = document.selectFirst("div.extra > span.date")
                        ?.text()
                        ?.toDate() ?: 0L
                    name = "Movie"
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        } else {
            response.use {
                getRealAnimeDoc(it.asJsoup())
            }.select(episodeListSelector()).map(::episodeFromElement)
        }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        return SEpisode.create().apply {
            val epNum = element.selectFirst("div.numerando")!!.text()
                .trim()
                .let(episodeNumberRegex::find)
                ?.groupValues
                ?.last() ?: "0"
            val href = element.selectFirst("a[href]")!!
            val episodeName = href.ownText()
            episode_number = epNum.toFloatOrNull() ?: 0F
            date_upload = element.selectFirst(episodeDateSelector)
                ?.text()
                ?.toDate() ?: 0L
            name = "Ep. $epNum - $episodeName"
            setUrlWithoutDomain(href.attr("href"))
        }
    }

    // ============================== Filters ===============================

    override val fetchGenres = false

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("Text search ignores filters"),
        SubPageFilter(),
    )

    private class SubPageFilter : UriPartFilter(
        "Sub-Page",
        arrayOf(
            Pair("<select>", ""),
            Pair("Dubbed", "genre-a/dubbed"),
            Pair("Movies", "movies-a"),
        ),
    )

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.use { it.asJsoup() }

        return document.select("iframe[src~=player]").mapNotNull {
            if (it.attr("src").toHttpUrl().queryParameter("source") != null) {
                runCatching {
                    val link = it.attr("src").toHttpUrl().queryParameter("source")!!
                    Video(link, "Video", link)
                }.getOrNull()
            } else {
                null
            }
        }
    }

    // ============================= Utilities ==============================
    private fun String.addSubPrefix(): String {
        return if (this.contains(" dubbed", true)) {
            "[DUB] ${this.substringBefore(" Dubbed")}"
        } else if (this.contains(" subbed", true)) {
            "[SUB] ${this.substringBefore(" Subbed")}"
        } else {
            this
        }
    }
}
