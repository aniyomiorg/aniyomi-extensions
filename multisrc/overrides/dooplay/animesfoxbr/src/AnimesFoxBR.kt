package eu.kanade.tachiyomi.animeextension.pt.animesfoxbr

import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesFoxBR : DooPlay(
    "pt-BR",
    "AnimesFox BR",
    "https://animesfoxbr.com",
) {
    // ============================== Popular ===============================
    // The site doesn't have a true popular anime tab,
    // so we use the latest added anime page instead.
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/animes/page/$page")

    override fun popularAnimeSelector() = "div.clw div.b_flex > div > a"

    override fun popularAnimeNextPageSelector() = "div.pagination i#nextpagination"

    // ============================== Episodes ==============================
    override fun episodeListSelector() = "div.se-a > div.anime_item > a"

    override fun episodeFromElement(element: Element, seasonName: String) =
        super.episodeFromElement(element, seasonName).apply {
            name = name.substringBefore("- ")
        }

    // =============================== Search ===============================
    override fun searchAnimeNextPageSelector() = "div.pagination > *:last-child:not(.current)"

    // ============================== Filters ===============================
    override fun genresListRequest() = GET("$baseUrl/categorias")

    override fun genresListSelector() = "div.box_category > a"

    override fun genresListParse(document: Document) =
        super.genresListParse(document).map {
            Pair(it.first.substringAfter(" "), it.second)
        }.toTypedArray()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val doc = getRealAnimeDoc(document)
        return SAnime.create().apply {
            setUrlWithoutDomain(doc.location())
            thumbnail_url = doc.selectFirst("div.capa_poster img")!!.attr("src")
            val container = doc.selectFirst("div.container_anime_r")!!
            title = container.selectFirst("div > h1")!!.text().let {
                when {
                    "email protected" in it -> {
                        val decoded = container.selectFirst("div > h1 > a")!!
                            .attr("data-cfemail")
                            .decodeEmail()
                        it.replace("[email protected]", decoded)
                    }
                    else -> it
                }
            }
            genre = container.select("div.btn_gen").eachText().joinToString()
            description = buildString {
                container.selectFirst("div.sinopse")?.let {
                    append(it.text() + "\n\n")
                }

                container.selectFirst("div.container_anime_nome > h2")?.let {
                    append("Nome alternativo: ${it.text()}\n")
                }

                container.select("div.container_anime_back").forEach {
                    val infoType = it.selectFirst("div.info-nome")?.text() ?: return@forEach
                    val infoData = it.selectFirst("span")?.text() ?: return@forEach
                    append("$infoType: $infoData\n")
                }
            }
        }
    }

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodios"

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.epsL i.material-icons:contains(library)"

    private fun String.decodeEmail(): String {
        val hex = chunked(2).map { it.toInt(16) }
        return hex.drop(1).joinToString("") {
            Char(it xor hex.first()).toString()
        }
    }
}
