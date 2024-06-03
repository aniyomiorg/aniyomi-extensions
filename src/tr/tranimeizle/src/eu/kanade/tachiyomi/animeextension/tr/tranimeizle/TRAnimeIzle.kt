package eu.kanade.tachiyomi.animeextension.tr.tranimeizle

import android.app.Application
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.mixdropextractor.MixDropExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.streamlareextractor.StreamlareExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.lib.youruploadextractor.YourUploadExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class TRAnimeIzle : ParsedAnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "TR Anime Izle"

    override val baseUrl = "https://www.tranimeizle.co"

    override val lang = "tr"

    override val supportsLatest = true

    override val client by lazy {
        network.client.newBuilder()
            .addInterceptor(ShittyCaptchaInterceptor(baseUrl, headers))
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/listeler/populer/sayfa-$page")

    override fun popularAnimeSelector() = "div.post-body div.flx-block"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("data-href"))
        thumbnail_url = element.selectFirst("img")!!.attr("src")
        title = element.selectFirst("div.bar > h4")!!.text().clearName()
    }

    override fun popularAnimeNextPageSelector() = "ul.pagination > li:has(.ti-angle-right):not(.disabled)"

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/listeler/yenibolum/sayfa-$page")

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) =
        popularAnimeFromElement(element).apply {
            // Convert episode url to anime url
            url = "/anime$url".substringBefore("-bolum").substringBeforeLast("-") + "-izle"
        }

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response.asJsoup()).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        GET("$baseUrl/arama/$query?page=$page")

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("div.playlist-title h1")!!.text().clearName()
        thumbnail_url = document.selectFirst("div.poster .social-icon img")!!.attr("src")

        val infosDiv = document.selectFirst("div.col-md-6 > div.row")!!
        genre = infosDiv.select("div > a.genre").eachText().joinToString()
        author = infosDiv.select("dd:contains(Fansublar) + dt a").eachText().joinToString()

        description = buildString {
            document.selectFirst("div.p-10 > p")?.text()?.also(::append)

            var dtCount = 0 // AAAAAAAA I HATE MUTABLE VALUES
            infosDiv.select("dd, dt").forEach {
                // Ignore non-wanted info
                it.selectFirst("dd:contains(Puanlama), dd:contains(Anime Türü), dt:has(i.fa-star), dt:has(a.genre)")
                    ?.let { return@forEach }

                val text = it.text()
                // yes
                when (it.tagName()) {
                    "dd" -> {
                        append("\n$text: ")
                        dtCount = 0
                    }
                    "dt" -> {
                        if (dtCount == 0) {
                            append(text)
                        } else {
                            append(", $text")
                        }
                        dtCount++
                    }
                }
            }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response) = super.episodeListParse(response).reversed()

    override fun episodeListSelector() = "div.animeDetail-items > ol a:has(div.episode-li)"

    override fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        val epNum = element.selectFirst(".etitle > span")!!.text()
            .substringBefore(". Bölüm", "")
            .substringAfterLast(" ", "")
            .toIntOrNull() ?: 1 // Int because of the episode name, a Float would render with more zeros.

        name = "Bölüm $epNum"
        episode_number = epNum.toFloat()

        date_upload = element.selectFirst(".etitle > small.author")?.text()?.toDate() ?: 0L
    }

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val episodeId = doc.selectFirst("input#EpisodeId")!!.attr("value")

        val allFansubs = PREF_FANSUB_SELECTION_ENTRIES
        val chosenFansubs = preferences.getStringSet(PREF_FANSUB_SELECTION_KEY, allFansubs.toSet())!!
        val chosenHosts = preferences.getStringSet(PREF_HOSTS_SELECTION_KEY, PREF_HOSTS_SELECTION_DEFAULT)!!

        return doc.select("div.fansubSelector").toList()
            // Filter-out non-chosen fansubs that were included in the fansub selection preference.
            // This way we prevent excluding unknown/non-added fansubs.
            .filter { it.text() in chosenFansubs || it.text() !in allFansubs }
            .flatMap { fansub ->
                val fansubId = fansub.attr("data-fid")
                val fansubName = fansub.text()

                val body = """{"EpisodeId":$episodeId,"FansubId":$fansubId}"""
                    .toRequestBody("application/json".toMediaType())

                client.newCall(POST("$baseUrl/api/fansubSources", headers, body))
                    .execute()
                    .asJsoup()
                    .select("li.sourceBtn")
                    .toList()
                    .filter { it.selectFirst("p")?.ownText().orEmpty() in chosenHosts }
                    .parallelCatchingFlatMapBlocking {
                        getVideosFromId(it.attr("data-id"))
                    }
                    .map {
                        Video(
                            it.url,
                            "[$fansubName] ${it.quality}",
                            it.videoUrl,
                            it.headers,
                            it.subtitleTracks,
                            it.audioTracks,
                        )
                    }
            }
    }

    private val filemoonExtractor by lazy { FilemoonExtractor(client) }
    private val mixDropExtractor by lazy { MixDropExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val sendvidExtractor by lazy { SendvidExtractor(client, headers) }
    private val sibnetExtractor by lazy { SibnetExtractor(client) }
    private val streamlareExtractor by lazy { StreamlareExtractor(client) }
    private val voeExtractor by lazy { VoeExtractor(client) }
    private val vudeoExtractor by lazy { VudeoExtractor(client) }
    private val yourUploadExtractor by lazy { YourUploadExtractor(client) }

    private fun getVideosFromId(id: String): List<Video> {
        val url = client.newCall(POST("$baseUrl/api/sourcePlayer/$id")).execute()
            .body.string()
            .substringAfter("src=")
            .substringAfter('"')
            .substringAfter("/embed2/?id=")
            .substringBefore('"')
            .replace("\\", "")
            .trim()
            .let {
                when {
                    it.startsWith("https") -> it
                    else -> "https:$it"
                }
            }

        // That's going to take an entire year to load, and I really don't care.
        return when {
            "filemoon.sx" in url -> filemoonExtractor.videosFromUrl(url, headers = headers)
            "mixdrop" in url -> mixDropExtractor.videoFromUrl(url)
            "mp4upload" in url -> mp4uploadExtractor.videosFromUrl(url, headers)
            "ok.ru" in url || "odnoklassniki.ru" in url -> okruExtractor.videosFromUrl(url)
            "sendvid.com" in url -> sendvidExtractor.videosFromUrl(url)
            "video.sibnet" in url -> sibnetExtractor.videosFromUrl(url)
            "streamlare.com" in url -> streamlareExtractor.videosFromUrl(url)
            "voe.sx" in url -> voeExtractor.videosFromUrl(url)
            "//vudeo." in url -> vudeoExtractor.videosFromUrl(url)
            "yourupload.com" in url -> {
                yourUploadExtractor.videoFromUrl(url, headers)
                    // ignore error links
                    .filterNot { it.url.contains("/novideo.mp4") }
            }
            else -> emptyList()
        }
    }

    override fun videoListSelector(): String {
        throw UnsupportedOperationException()
    }

    override fun videoFromElement(element: Element): Video {
        throw UnsupportedOperationException()
    }

    override fun videoUrlParse(document: Document): String {
        throw UnsupportedOperationException()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_FANSUB_SELECTION_KEY
            title = PREF_FANSUB_SELECTION_TITLE
            PREF_FANSUB_SELECTION_ENTRIES.let {
                entries = it
                entryValues = it
                setDefaultValue(it.toSet())
            }

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_ADDITIONAL_FANSUBS_KEY
            title = PREF_ADDITIONAL_FANSUBS_TITLE
            dialogTitle = PREF_ADDITIONAL_FANSUBS_DIALOG_TITLE
            dialogMessage = PREF_ADDITIONAL_FANSUBS_DIALOG_MESSAGE
            setDefaultValue(PREF_ADDITIONAL_FANSUBS_DEFAULT)
            summary = PREF_ADDITIONAL_FANSUBS_SUMMARY

            setOnPreferenceChangeListener { _, newValue ->
                runCatching {
                    val value = newValue as String
                    Toast.makeText(screen.context, PREF_ADDITIONAL_FANSUBS_TOAST, Toast.LENGTH_LONG).show()
                    preferences.edit().putString(key, value).commit()
                }.getOrDefault(false)
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTS_SELECTION_KEY
            title = PREF_HOSTS_SELECTION_TITLE
            entries = PREF_HOSTS_SELECTION_ENTRIES
            entryValues = PREF_HOSTS_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTS_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun String.clearName() = removeSuffix(" İzle").removeSuffix(" Bölüm")

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy { it.quality.contains(quality) },
        ).reversed()
    }

    private val defaultSubs by lazy {
        setOf(
            "Adonis Fansub",
            "Aitr",
            "Akatsuki Fansub",
            "AniKeyf",
            "ANS Fansub",
            "AnimeMangaTR",
            "AnimeOu Fansub",
            "AniSekai Fansub",
            "AniTürk",
            "AoiSubs",
            "ARE-YOU-SURE (AYS)",
            "AnimeWho",
            "Chevirman",
            "Fatality",
            "HikiGayaFansub",
            "HolySubs",
            "Lawsonia Sub",
            "LowSubs",
            "Momo & Berhann",
            "NoaSubs",
            "OrigamiSubs",
            "Puzzle Fansub",
            "ShimazuSubs",
            "SoutenSubs",
            "TAÇE",
            "TRanimeizle",
            "TR Altyazılı",
            "Uragiri Fansub",
            "Varsayılan",
        )
    }

    private val PREF_FANSUB_SELECTION_ENTRIES: Array<String> get() {
        val additional = preferences.getString(PREF_ADDITIONAL_FANSUBS_KEY, "")!!
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

        return (defaultSubs + additional.sorted()).toTypedArray()
    }

    companion object {
        const val PREFIX_SEARCH = "id:"

        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("dd MMM yyyy", Locale("tr"))
        }

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_FANSUB_SELECTION_KEY = "pref_fansub_selection"
        private const val PREF_FANSUB_SELECTION_TITLE = "Enable/Disable Fansubs"

        private const val PREF_ADDITIONAL_FANSUBS_KEY = "pref_additional_fansubs_key"
        private const val PREF_ADDITIONAL_FANSUBS_TITLE = "Add custom fansubs to the selection preference"
        private const val PREF_ADDITIONAL_FANSUBS_DEFAULT = ""
        private const val PREF_ADDITIONAL_FANSUBS_DIALOG_TITLE = "Enter a list of additional fansubs, separated by a comma."
        private const val PREF_ADDITIONAL_FANSUBS_DIALOG_MESSAGE = "Example: AntichristHaters Fansub, 2cm erect subs"
        private const val PREF_ADDITIONAL_FANSUBS_SUMMARY = "You can add more fansubs to the previous preference from here."
        private const val PREF_ADDITIONAL_FANSUBS_TOAST = "Reopen the extension's preferences for it to take effect."

        private const val PREF_HOSTS_SELECTION_KEY = "pref_hosts_selection"
        private const val PREF_HOSTS_SELECTION_TITLE = "Enable/disable video hosts"
        private val PREF_HOSTS_SELECTION_ENTRIES = arrayOf(
            "Filemoon",
            "MixDrop",
            "Mp4upload",
            "Ok.RU",
            "SendVid",
            "Sibnet",
            "Streamlare",
            "Voe",
            "Vudeo",
            "Yourupload",
        )

        // XDDDDDDDDD
        private val PREF_HOSTS_SELECTION_DEFAULT by lazy { PREF_HOSTS_SELECTION_ENTRIES.toSet() }
    }
}
