package eu.kanade.tachiyomi.animeextension.pt.animesfoxbr

import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimesFoxBR : DooPlay(
    "pt-BR",
    "AnimesFox BR",
    "https://animesfox.net",
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

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("360p ~ SD", "720p ~ HD")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        return doc.select("ul#playeroptionsul li").mapNotNull {
            val url = getPlayerUrl(it)
            val language = it.selectFirst("span.title")?.text() ?: "Linguagem desconhecida"
            when {
                baseUrl in url -> extractVideos(url, language)
                else -> null
            }
        }.flatten()
    }

    private fun extractVideos(url: String, language: String): List<Video> {
        return client.newCall(GET(url, headers)).execute().let { response ->
            response.body.string()
                .substringAfter("sources:[")
                .substringBefore("]")
                .split("},")
                .mapNotNull {
                    val videoUrl = it.substringAfter("file: \"")
                        .substringBefore('"')
                        .ifBlank { return@mapNotNull null }
                    val quality = it.substringAfter("label:\"").substringBefore('"')
                    Video(videoUrl, "$language($quality)", videoUrl, headers = headers)
                }
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .let { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
                    .let { url ->
                        when {
                            url.contains("token=") -> {
                                url.substringAfter("token=")
                                    .substringBefore("' ")
                                    .let { Base64.decode(it, Base64.DEFAULT) }
                                    .let(::String)
                            }
                            url.contains("iframe") -> {
                                url.substringAfter("?link=").substringBefore("'")
                            }
                            else -> ""
                        }
                    }
            }
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

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoLanguagePref = ListPreference(screen.context).apply {
            key = PREF_LANGUAGE_KEY
            title = PREF_LANGUAGE_TITLE
            entries = PREF_LANGUAGE_ENTRIES
            entryValues = PREF_LANGUAGE_VALUES
            setDefaultValue(PREF_LANGUAGE_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        screen.addPreference(videoLanguagePref)
        super.setupPreferenceScreen(screen)
    }

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.epsL i.material-icons:contains(library)"

    private fun String.decodeEmail(): String {
        val hex = chunked(2).map { it.toInt(16) }
        return hex.drop(1).joinToString("") {
            Char(it xor hex.first()).toString()
        }
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        val language = preferences.getString(PREF_LANGUAGE_KEY, PREF_LANGUAGE_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.lowercase().contains(quality.lowercase()) },
                { it.quality.lowercase().contains(language.lowercase()) },
            ),
        ).reversed()
    }

    companion object {
        private const val PREF_LANGUAGE_KEY = "preferred_language"
        private const val PREF_LANGUAGE_DEFAULT = "Legendado"
        private const val PREF_LANGUAGE_TITLE = "Língua preferida"
        private val PREF_LANGUAGE_VALUES = arrayOf("Legendado", "Dublado")
        private val PREF_LANGUAGE_ENTRIES = PREF_LANGUAGE_VALUES
    }
}
