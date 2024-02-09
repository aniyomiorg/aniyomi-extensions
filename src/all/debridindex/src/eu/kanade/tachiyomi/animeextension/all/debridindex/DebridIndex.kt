package eu.kanade.tachiyomi.animeextension.all.debridindex

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.debridindex.dto.RootFiles
import eu.kanade.tachiyomi.animeextension.all.debridindex.dto.SubFiles
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.lang.Exception
import java.text.SimpleDateFormat
import java.util.Locale

class DebridIndex : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "Debrid Index"

    override val baseUrl = "https://torrentio.strem.fun"

    override val lang = "all"

    override val supportsLatest = false

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request {
        val tokenKey = preferences.getString(PREF_TOKEN_KEY, null)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "RealDebrid")
        when {
            tokenKey.isNullOrBlank() -> throw Exception("Please enter the token in extension settings.")
            else -> {
                return GET("$baseUrl/${debridProvider!!.lowercase()}=$tokenKey/catalog/other/torrentio-${debridProvider.lowercase()}.json")
            }
        }
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animeList = json.decodeFromString<RootFiles>(response.body.string()).metas?.map { meta ->
            SAnime.create().apply {
                title = meta.name
                url = meta.id
                thumbnail_url = if (meta.name == "Downloads") {
                    "https://i.ibb.co/MGmhmJg/download.png"
                } else {
                    "https://i.ibb.co/Q9GPtbC/default.png"
                }
            }
        } ?: emptyList()
        return AnimesPage(animeList, false)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesParse(response: Response): AnimesPage = throw Exception("Not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val tokenKey = preferences.getString(PREF_TOKEN_KEY, null)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "RealDebrid")
        when {
            tokenKey.isNullOrBlank() -> throw Exception("Please enter the token in extension settings.")
            else -> {
                // Used Debrid Search v0.1.8 https://68d69db7dc40-debrid-search.baby-beamup.club/configure
                return GET("https://68d69db7dc40-debrid-search.baby-beamup.club/%7B%22DebridProvider%22%3A%22$debridProvider%22%2C%22DebridApiKey%22%3A%22$tokenKey%22%7D/catalog/other/debridsearch/search=$query.json")
            }
        }
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)
    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime = throw UnsupportedOperationException()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return anime
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val tokenKey = preferences.getString(PREF_TOKEN_KEY, null)
        val debridProvider = preferences.getString(PREF_DEBRID_KEY, "RealDebrid")
        return GET("$baseUrl/${debridProvider!!.lowercase()}=$tokenKey/meta/other/${anime.url}.json")
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val jsonData = response.body.string()
        return json.decodeFromString<SubFiles>(jsonData).meta?.videos?.mapIndexed { index, video ->
            SEpisode.create().apply {
                episode_number = (index + 1).toFloat()
                name = video.title
                url = video.streams.firstOrNull()?.url.orEmpty()
                date_upload = parseDate(video.released)
            }
        }?.reversed() ?: emptyList()
    }

    private fun parseDate(dateStr: String): Long {
        return runCatching { DATE_FORMATTER.parse(dateStr)?.time }
            .getOrNull() ?: 0L
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, episode.name.split("/").last(), episode.url))
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        // Debrid provider
        ListPreference(screen.context).apply {
            key = PREF_DEBRID_KEY
            title = "Debird Provider"
            entries = PREF_DEBRID_ENTRIES
            entryValues = PREF_DEBRID_VALUES
            setDefaultValue("realdebrid")
            summary = "Don't forget to enter your token key."

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        // Token
        EditTextPreference(screen.context).apply {
            key = PREF_TOKEN_KEY
            title = "Real Debrid Token"
            setDefaultValue(PREF_TOKEN_DEFAULT)
            summary = PREF_TOKEN_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = (newValue as String).trim().ifBlank { PREF_TOKEN_DEFAULT }
                    Toast.makeText(screen.context, "Restart app to apply new setting.", Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        // Token
        private const val PREF_TOKEN_KEY = "token"
        private const val PREF_TOKEN_DEFAULT = "none"
        private const val PREF_TOKEN_SUMMARY = "For temporary uses. Updating the extension will erase this setting."

        // Debird
        private const val PREF_DEBRID_KEY = "debrid_provider"
        private val PREF_DEBRID_ENTRIES = arrayOf(
            "RealDebrid",
            "Premiumize",
            "AllDebrid",
            "DebridLink",
        )
        private val PREF_DEBRID_VALUES = arrayOf(
            "RealDebrid",
            "Premiumize",
            "AllDebrid",
            "DebridLink",
        )

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH)
        }
    }
}
