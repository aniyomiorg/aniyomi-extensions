package eu.kanade.tachiyomi.animeextension.de.kinoking

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element

class Kinoking : DooPlay(
    "de",
    "Kinoking",
    "https://kinoking.cc",
) {
    companion object {
        private const val PREF_HOSTER_KEY = "preferred_hoster"
        private const val PREF_HOSTER_TITLE = "Standard-Hoster"
        private const val PREF_HOSTER_DEFAULT = "https://dood"
        private val PREF_HOSTER_ENTRIES = arrayOf("Doodstream", "Voe", "Filehosted")
        private val PREF_HOSTER_VALUES = arrayOf("https://dood", "https://voe.sx", "https://fs1.filehosted")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = PREF_HOSTER_ENTRIES
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("dood", "voe", "filehosted")
        private val PREF_HOSTER_SELECTION_DEFAULT = PREF_HOSTER_SELECTION_VALUES.toSet()
    }

    override val videoSortPrefKey = PREF_HOSTER_KEY
    override val videoSortPrefDefault = PREF_HOSTER_DEFAULT

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "#nextpagination"

    // ============================== Episodes ==============================
    // Little workaround to show season episode names like the original extension
    // TODO: Create a "getEpisodeName(element, seasonName)" function in DooPlay class
    override fun episodeFromElement(element: Element, seasonName: String) =
        super.episodeFromElement(element, seasonName).apply {
            val substring = name.substringBefore(" -")
            val newString = substring.replace("Season", "Staffel")
                .replace("x", "Folge")
            name = name.replace("$substring -", "$newString :")
        }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val players = response.asJsoup().select("li.dooplay_player_option")
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return players.flatMap { player ->
            runCatching {
                val link = getPlayerUrl(player)
                getPlayerVideos(link, player, hosterSelection)
            }.getOrElse { emptyList() }
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
            }
    }

    private val doodExtractor by lazy { DoodExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }

    private fun getPlayerVideos(link: String, element: Element, hosterSelection: Set<String>): List<Video> {
        return when {
            link.contains("https://dood") && hosterSelection.contains("dood") -> {
                val quality = "Doodstream"
                val redirect = !link.contains("https://doodstream")
                doodExtractor.videosFromUrl(link, quality, redirect)
            }
            link.contains("https://voe.sx") && hosterSelection.contains("voe") -> {
                voeExtractor.videosFromUrl(link)
            }
            link.contains("filehosted") && hosterSelection.contains("filehosted") -> {
                listOf(Video(link, "Filehosted", link))
            }
            else -> null
        }.orEmpty()
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }
}
