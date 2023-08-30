package eu.kanade.tachiyomi.animeextension.pl.desuonline

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pl.desuonline.extractors.CDAExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale

class DesuOnline : AnimeStream(
    "pl",
    "desu-online",
    "https://desu-online.pl",
) {
    override val dateFormatter by lazy {
        SimpleDateFormat("d MMMM, yyyy", Locale("pl", "PL"))
    }

    private val prefServerKey = "preferred_server"
    private val prefServerDefault = "CDA"

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> =
        super.videoListParse(response).ifEmpty { throw Exception("Failed to fetch videos") }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val cdaExtractor by lazy { CDAExtractor(client, headers, "$baseUrl/") }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val gdriveExtractor by lazy { GoogleDriveExtractor(client, headers) }

    override fun getVideoList(url: String, name: String): List<Video> {
        return when {
            url.contains("ok.ru") -> okruExtractor.videosFromUrl(url, name)
            url.contains("cda.pl") -> cdaExtractor.videosFromUrl(url, name)
            url.contains("sibnet") -> sibnetExtractor.videosFromUrl(url, prefix = "$name - ")
            url.contains("drive.google.com") -> {
                val id = Regex("[\\w-]{28,}").find(url)?.groupValues?.get(0) ?: return emptyList()
                gdriveExtractor.videosFromUrl("https://drive.google.com/uc?id=$id", videoName = name)
            }
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        val server = preferences.getString(prefServerKey, prefServerDefault)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(server, true) },
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen) // Quality preferences

        ListPreference(screen.context).apply {
            key = prefServerKey
            title = "Preferred server"
            entries = arrayOf("CDA", "Sibnet", "Google Drive", "ok.ru")
            entryValues = arrayOf("CDA", "sibnet", "gd", "okru")
            setDefaultValue(prefServerDefault)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }
}
