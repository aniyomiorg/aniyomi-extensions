package eu.kanade.tachiyomi.animeextension.de.kinoking

import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
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
        private val PREF_HOSTER_ENTRIES = arrayOf("Doodstream", "StreamSB", "Voe")
        private val PREF_HOSTER_VALUES = arrayOf("https://dood", "https://watchsb.com", "https://voe.sx")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection"
        private const val PREF_HOSTER_SELECTION_TITLE = "Hoster auswählen"
        private val PREF_HOSTER_SELECTION_ENTRIES = PREF_HOSTER_ENTRIES
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("dood", "watchsb", "voe")
        private val PREF_HOSTER_SELECTION_DEFAULT = PREF_HOSTER_SELECTION_ENTRIES.toSet()
    }

    override val videoSortPrefKey = PREF_HOSTER_KEY
    override val videoSortPrefDefault = PREF_HOSTER_DEFAULT

    override val client = network.cloudflareClient

    // ============================== Popular ===============================
    override fun popularAnimeSelector(): String = "div#featured-titles div.poster"

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

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector(): String = "#nextpagination"

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val players = response.use { it.asJsoup().select("li.dooplay_player_option") }
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        return players.mapNotNull { player ->
            runCatching {
                val link = getPlayerUrl(player)
                getPlayerVideos(link, player, hosterSelection)
            }.getOrDefault(emptyList<Video>())
        }.flatten()
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
            .use { response ->
                response.body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\",")
                    .replace("\\", "")
            }
    }

    private fun getPlayerVideos(link: String, element: Element, hosterSelection: Set<String>): List<Video>? {
        return when {
            link.contains("https://watchsb") || link.contains("https://viewsb") && hosterSelection.contains("watchsb") -> {
                if (element.select("span.flag img").attr("data-src").contains("/en.")) {
                    val lang = "Englisch"
                    StreamSBExtractor(client).videosFromUrl(link, headers, suffix = lang)
                } else {
                    val lang = "Deutsch"
                    StreamSBExtractor(client).videosFromUrl(link, headers, lang)
                }
            }
            link.contains("https://dood.") || link.contains("https://doodstream.") && hosterSelection.contains("dood") -> {
                val quality = "Doodstream"
                val redirect = !link.contains("https://doodstream")
                DoodExtractor(client).videoFromUrl(link, quality, redirect)
                    ?.let(::listOf)
            }
            link.contains("https://voe.sx") && hosterSelection.contains("voe") == true -> {
                val quality = "Voe"
                VoeExtractor(client).videoFromUrl(link, quality)
                    ?.let(::listOf)
            }
            else -> null
        }
    }

    // ============================== Settings ==============================
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
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
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
