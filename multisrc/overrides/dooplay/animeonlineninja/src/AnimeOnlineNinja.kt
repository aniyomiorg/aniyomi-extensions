package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.streamtapeextractor.StreamTapeExtractor
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class AnimeOnlineNinja : DooPlay(
    "es",
    "AnimeOnline.Ninja",
    "https://ww3.animeonline.ninja",
) {
    override val client by lazy {
        if (preferences.getBoolean(PREF_VRF_INTERCEPT_KEY, PREF_VRF_INTERCEPT_DEFAULT)) {
            network.client.newBuilder()
                .addInterceptor(VrfInterceptor())
                .build()
        } else {
            network.client
        }
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/tendencias/$page")

    override fun popularAnimeSelector() = latestUpdatesSelector()

    override fun popularAnimeNextPageSelector() = latestUpdatesNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeOnlineNinjaFilters.getSearchParameters(filters)
        val path = when {
            params.genre.isNotBlank() -> {
                if (params.genre in listOf("tendencias", "ratings")) {
                    "/" + params.genre
                } else {
                    "/genero/${params.genre}"
                }
            }
            params.language.isNotBlank() -> "/genero/${params.language}"
            params.year.isNotBlank() -> "/release/${params.year}"
            params.movie.isNotBlank() -> {
                if (params.movie == "pelicula") {
                    "/pelicula"
                } else {
                    "/genero/${params.movie}"
                }
            }
            else -> buildString {
                append(
                    when {
                        query.isNotBlank() -> "/?s=$query"
                        params.letter.isNotBlank() -> "/letra/${params.letter}/?"
                        else -> "/tendencias/?"
                    },
                )

                append(
                    if (contains("tendencias")) {
                        "&get=${when (params.type){
                            "serie" -> "TV"
                            "pelicula" -> "movies"
                            else -> "todos"
                        }}"
                    } else {
                        "&tipo=${params.type}"
                    },
                )

                if (params.isInverted) append("&orden=asc")
            }
        }

        return if (path.startsWith("/?s=")) {
            GET("$baseUrl/page/$page$path")
        } else if (path.startsWith("/letra") || path.startsWith("/tendencias")) {
            val before = path.substringBeforeLast("/")
            val after = path.substringAfterLast("/")
            GET(baseUrl + before + "/page/$page/" + after)
        } else {
            GET("$baseUrl$path/page/$page")
        }
    }

    // ============================== Episodes ==============================
    override val episodeMovieText = "Película"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("ul#playeroptionsul li")
        return players.flatMap { player ->
            val name = player.selectFirst("span.title")!!.text()
            val url = getPlayerUrl(player)
            extractVideos(url, name)
        }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val uqloadExtractor by lazy { UqloadExtractor(client) }

    private fun extractVideos(url: String, lang: String): List<Video> {
        return when {
            "saidochesto.top" in url || "MULTISERVER" in lang.uppercase() ->
                extractFromMulti(url)
            "filemoon" in url ->
                filemoonExtractor.videosFromUrl(url, "$lang Filemoon - ", headers)
            "dood" in url ->
                doodExtractor.videoFromUrl(url, "$lang DoodStream", false)
                    ?.let(::listOf)
            "streamtape" in url ->
                streamTapeExtractor.videoFromUrl(url, "$lang StreamTape")
                    ?.let(::listOf)
            "mixdrop" in url ->
                mixDropExtractor.videoFromUrl(url, lang)
            "uqload" in url ->
                uqloadExtractor.videosFromUrl(url)
            "wolfstream" in url -> {
                client.newCall(GET(url, headers)).execute()
                    .asJsoup()
                    .selectFirst("script:containsData(sources)")
                    ?.data()
                    ?.let { jsData ->
                        val videoUrl = jsData.substringAfter("{file:\"").substringBefore("\"")
                        listOf(Video(videoUrl, "$lang WolfStream", videoUrl, headers = headers))
                    }
            }
            else -> null
        } ?: emptyList<Video>()
    }

    private fun extractFromMulti(url: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val prefLang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val langSelector = when {
            prefLang.isBlank() -> "div"
            else -> "div.OD_$prefLang"
        }
        return document.select("div.ODDIV $langSelector > li").flatMap {
            val hosterUrl = it.attr("onclick").toString()
                .substringAfter("('")
                .substringBefore("')")
            val lang = when (langSelector) {
                "div" -> {
                    it.parent()?.attr("class").toString()
                        .substringAfter("OD_", "")
                        .substringBefore(" ")
                }
                else -> prefLang
            }
            extractVideos(hosterUrl, lang)
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val type = player.attr("data-type")
        val id = player.attr("data-post")
        val num = player.attr("data-nume")
        return client.newCall(GET("$baseUrl/wp-json/dooplayer/v1/post/$id?type=$type&source=$num"))
            .execute()
            .let { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    // =========================== Anime Details ============================
    override fun Document.getDescription(): String {
        return select("$additionalInfoSelector div.wp-content p")
            .eachText()
            .joinToString("\n")
    }

    override val additionalInfoItems = listOf("Título", "Temporadas", "Episodios", "Duración media")

    // =============================== Latest ===============================
    override val latestUpdatesPath = "episodio"

    override fun latestUpdatesNextPageSelector() = "div.pagination > *:last-child:not(span):not(.current)"

    // ============================== Filters ===============================
    override val fetchGenres = false

    override fun getFilterList() = AnimeOnlineNinjaFilters.FILTER_LIST

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preference

        val langPref = ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = PREF_LANG_TITLE
            entries = PREF_LANG_ENTRIES
            entryValues = PREF_LANG_VALUES
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }

        val vrfIterceptPref = CheckBoxPreference(screen.context).apply {
            key = PREF_VRF_INTERCEPT_KEY
            title = PREF_VRF_INTERCEPT_TITLE
            summary = PREF_VRF_INTERCEPT_SUMMARY
            setDefaultValue(PREF_VRF_INTERCEPT_DEFAULT)
        }

        screen.addPreference(vrfIterceptPref)
        screen.addPreference(langPref)
    }

    // ============================= Utilities ==============================
    override fun String.toDate() = 0L

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(prefQualityKey, prefQualityDefault)!!
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(lang) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override val prefQualityValues = arrayOf("480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_TITLE = "Preferred language"
        private const val PREF_LANG_DEFAULT = "SUB"
        private val PREF_LANG_ENTRIES = arrayOf("SUB", "All", "ES", "LAT")
        private val PREF_LANG_VALUES = arrayOf("SUB", "", "ES", "LAT")

        private const val PREF_VRF_INTERCEPT_KEY = "vrf_intercept"
        private const val PREF_VRF_INTERCEPT_TITLE = "Intercept VRF links (Requiere Reiniciar)"
        private const val PREF_VRF_INTERCEPT_SUMMARY = "Intercept VRF links and open them in the browser"
        private const val PREF_VRF_INTERCEPT_DEFAULT = false
    }
}
