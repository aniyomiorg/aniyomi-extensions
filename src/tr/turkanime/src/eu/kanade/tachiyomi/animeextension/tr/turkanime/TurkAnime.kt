package eu.kanade.tachiyomi.animeextension.tr.turkanime

import android.app.Application
import android.util.Base64
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.AlucardExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.EmbedgramExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.MVidooExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.MailRuExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.StreamVidExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.VTubeExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.WolfstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.filemoonextractor.FilemoonExtractor
import eu.kanade.tachiyomi.lib.googledriveextractor.GoogleDriveExtractor
import eu.kanade.tachiyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.sendvidextractor.SendvidExtractor
import eu.kanade.tachiyomi.lib.sibnetextractor.SibnetExtractor
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.lib.uqloadextractor.UqloadExtractor
import eu.kanade.tachiyomi.lib.vkextractor.VkExtractor
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.lib.vudeoextractor.VudeoExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parallelMapBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class TurkAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Türk Anime TV"

    override val baseUrl = "https://www.turkanime.co"

    override val lang = "tr"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ajax/rankagore?sayfa=$page", xmlHeader)

    override fun popularAnimeSelector() = "div.panel-visible"

    override fun popularAnimeNextPageSelector() = "button.btn-default[data-loading-text*=Sonraki]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val animeTitle = element.selectFirst("div.panel-title > a")!!
        val name = animeTitle.attr("title")
            .substringBefore(" izle")
        val img = element.selectFirst("img.media-object")
        val animeId = element.selectFirst("a.reactions")!!.attr("data-unique-id")
        val animeUrl = animeTitle.attr("abs:href").toHttpUrl()
            .newBuilder()
            .addQueryParameter("animeId", animeId)
            .build().toString()
        return SAnime.create().apply {
            setUrlWithoutDomain(animeUrl)
            title = name
            thumbnail_url = img?.attr("abs:data-src")
        }
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ajax/yenieklenenseriler?sayfa=$page", xmlHeader)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        POST(
            "$baseUrl/arama?sayfa=$page",
            headers,
            FormBody.Builder().add("arama", query).build(),
        )

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val scriptElement = document.selectFirst("div.panel-body > script:containsData(window.location)")
        return if (scriptElement == null) {
            val animeList = document.select(searchAnimeSelector()).map(::searchAnimeFromElement)
            AnimesPage(animeList, document.selectFirst(searchAnimeSelector()) != null)
        } else {
            val location = scriptElement.data()
                .substringAfter("window.location")
                .substringAfter("\"")
                .substringBefore("\"")

            val slug = if (location.startsWith("/")) location else "/$location"

            val animeList = listOf(
                SAnime.create().apply {
                    setUrlWithoutDomain(slug)
                    thumbnail_url = ""
                    title = slug.substringAfter("anime/")
                },
            )

            AnimesPage(animeList, false)
        }
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document): SAnime {
        val img = document.selectFirst("div.imaj > img.media-object")
        val studio = document.selectFirst("div#animedetay > table tr:contains(Stüdyo) > td:last-child a")
        val desc = document.selectFirst("div#animedetay p.ozet")
        val genres = document.select("div#animedetay > table tr:contains(Anime Türü) > td:last-child a")
            .ifEmpty { null }
        return SAnime.create().apply {
            title = document.select("div#detayPaylas div.panel-title").text()
            thumbnail_url = img?.let { "https:" + it.attr("data-src") }
            author = studio?.text()
            description = desc?.text()
            genre = genres?.joinToString { it.text() }
        }
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime): Request {
        val animeId = (baseUrl + anime.url).toHttpUrl().queryParameter("animeId")
            ?: client.newCall(GET(baseUrl + anime.url)).execute().asJsoup()
                .selectFirst("a[data-unique-id]")!!.attr("data-unique-id")
        return GET("$baseUrl/ajax/bolumler?animeId=$animeId", xmlHeader)
    }

    override fun episodeListSelector() = "ul.menum li"

    override fun episodeFromElement(element: Element): SEpisode {
        val a = element.selectFirst("a:has(span.bolumAdi)")!!
        val title = a.attr("title")
        val substring = title.substringBefore(". Bölüm")
        val numIdx = substring.indexOfLast { !it.isDigit() } + 1
        val numbers = substring.slice(numIdx..substring.lastIndex)
        return SEpisode.create().apply {
            setUrlWithoutDomain(a.attr("abs:href"))
            name = title
            episode_number = numbers.toFloatOrNull() ?: 1F
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> =
        super.episodeListParse(response).reversed()

    // ============================ Video Links =============================
    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()

        val fansubbers = document.select("div#videodetay div.pull-right button")
        val videoList = if (fansubbers.size == 1) {
            getVideosFromHosters(document, fansubbers.first()!!.text().trim())
        } else {
            val allFansubs = PREF_FANSUB_SELECTION_ENTRIES
            val chosenFansubs = preferences.getStringSet(PREF_FANSUB_SELECTION_KEY, allFansubs.toSet())!!

            val filteredSubs = fansubbers.toList().filter {
                val subName = it.text().substringBeforeLast("BD").trim()
                chosenFansubs.any(subName::contains) || allFansubs.none(subName::contains)
            }

            filteredSubs.parallelCatchingFlatMapBlocking {
                val url = it.attr("onclick").trimOnClick()
                val subDoc = client.newCall(GET(url, xmlHeader)).await().asJsoup()
                getVideosFromHosters(subDoc, it.text().trim())
            }
        }

        require(videoList.isNotEmpty()) { "Failed to extract videos" }

        return videoList
    }

    private fun getVideosFromHosters(document: Document, subber: String): List<Video> {
        val selectedHoster = document.select("div#videodetay div.btn-group:not(.pull-right) > button.btn-danger")
        val hosters = document.select("div#videodetay div.btn-group:not(.pull-right) > button.btn-default[onclick*=videosec]")

        val hosterSelection = preferences.getStringSet(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!

        val videoList = buildList {
            val selectedHosterName = selectedHoster.text().trim()
            if (selectedHosterName in SUPPORTED_HOSTERS && selectedHosterName in hosterSelection) {
                document.selectFirst("iframe")?.attr("src")?.also { src ->
                    addAll(getVideosFromSource(src, selectedHosterName, subber))
                }
            }

            hosters.parallelMapBlocking {
                val hosterName = it.text().trim()
                if (hosterName !in SUPPORTED_HOSTERS) return@parallelMapBlocking
                if (hosterName !in hosterSelection) return@parallelMapBlocking
                val url = it.attr("onclick").trimOnClick()
                val videoDoc = client.newCall(GET(url, xmlHeader)).await().asJsoup()
                val src = videoDoc.selectFirst("iframe")?.attr("src")
                    ?.replace("^//".toRegex(), "https://")
                    ?: return@parallelMapBlocking
                addAll(getVideosFromSource(src, hosterName, subber))
            }
        }

        return videoList
    }

    private fun getVideosFromSource(src: String, hosterName: String, subber: String): List<Video> {
        val cipherParamsEncoded = src
            .substringAfter("/embed/#/url/")
            .substringBefore("?status")

        val cipherParams = json.decodeFromString<CipherParams>(
            String(
                Base64.decode(cipherParamsEncoded, Base64.DEFAULT),
            ),
        )

        val hosterLink = "https:" + decryptParams(cipherParams)

        val videoList = runCatching {
            when (hosterName) {
                "ALUCARD(BETA)" -> {
                    AlucardExtractor(client, json, baseUrl).extractVideos(hosterLink, subber)
                }
                "DOODSTREAM" -> {
                    DoodExtractor(client).videosFromUrl(hosterLink, "$subber: DOODSTREAM", redirect = false)
                }
                "EMBEDGRAM" -> {
                    EmbedgramExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                "FILEMOON" -> {
                    FilemoonExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: ", headers = headers)
                }
                "GDRIVE" -> {
                    Regex("""[\w-]{28,}""").find(hosterLink)?.groupValues?.get(0)?.let {
                        GoogleDriveExtractor(client, headers).videosFromUrl("https://drive.google.com/uc?id=$it", "$subber: Gdrive")
                    }
                }
                "MAIL" -> {
                    MailRuExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                "MP4UPLOAD" -> {
                    Mp4uploadExtractor(client).videosFromUrl(hosterLink, headers, prefix = "$subber: ")
                }
                "MVIDOO" -> {
                    MVidooExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                "ODNOKLASSNIKI" -> {
                    OkruExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                "SENDVID" -> {
                    SendvidExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                "SIBNET" -> {
                    SibnetExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: ")
                }

                "STREAMVID" -> {
                    StreamVidExtractor(client).videosFromUrl(hosterLink, headers, prefix = "$subber: ")
                }
                "UQLOAD" -> {
                    UqloadExtractor(client).videosFromUrl(hosterLink, "$subber:")
                }
                "VK" -> {
                    val vkUrl = "https://vk.com" + hosterLink.substringAfter("vk.com")
                    VkExtractor(client, headers).videosFromUrl(vkUrl, prefix = "$subber: ")
                }
                "VOE" -> {
                    VoeExtractor(client).videosFromUrl(hosterLink, "($subber) ")
                }
                "VTUBE" -> {
                    VTubeExtractor(client, headers).videosFromUrl(hosterLink, baseUrl, prefix = "$subber: ")
                }
                "VUDEA" -> {
                    VudeoExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                "WOLFSTREAM" -> {
                    WolfstreamExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: ")
                }
                else -> null
            }
        }.getOrNull() ?: emptyList()

        return videoList
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException()

    override fun videoListSelector(): String = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException()

    // ============================= Utilities ==============================
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) }, // preferred quality first
                { it.quality.substringBefore(":") }, // then group by fansub
                // then group by quality
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    @Serializable
    private data class CipherParams(
        val ct: String,
        val s: String,
    )

    private fun String.trimOnClick() = baseUrl + "/" + this.substringAfter("IndexIcerik('").substringBefore("'")

    private val xmlHeader = Headers.headersOf("X-Requested-With", "XMLHttpRequest")
    private val refererHeader = Headers.headersOf("Referer", baseUrl)

    private val mutex = Mutex()
    private var shouldUpdateKey = false

    private val key: String
        get() {
            return runBlocking(Dispatchers.IO) {
                mutex.withLock {
                    if (shouldUpdateKey) {
                        updateKey()
                        shouldUpdateKey = false
                    }
                    preferences.getString(PREF_KEY_KEY, DEFAULT_KEY)!!
                }
            }
        }

    private fun decryptParams(params: CipherParams, tried: Boolean = false): String {
        val decrypted = CryptoAES.decryptWithSalt(
            params.ct,
            params.s,
            key,
        ).ifEmpty {
            if (tried) {
                ""
            } else {
                shouldUpdateKey = true
                decryptParams(params, true)
            }
        }

        return json.decodeFromString<String>(decrypted)
    }

    private fun updateKey() {
        val script4 = client.newCall(GET("$baseUrl/embed/#/")).execute().asJsoup()
            .select("script[defer]").getOrNull(1)
            ?.attr("src") ?: return
        val embeds4 = client.newCall(GET(baseUrl + script4)).execute().body.string()
        val name = JS_NAME_REGEX.findAll(embeds4).toList().firstOrNull()?.value

        val file5 = client.newCall(GET("$baseUrl/embed/js/embeds.$name.js")).execute().body.string()
        val embeds5 = Deobfuscator.deobfuscateScript(file5) ?: return
        val key = KEY_REGEX.find(embeds5)?.value ?: return
        preferences.edit().putString(PREF_KEY_KEY, key).apply()
    }

    companion object {
        private val JS_NAME_REGEX by lazy { "(?<=')[0-9a-f]{16}(?=')".toRegex() }
        private val KEY_REGEX by lazy { "(?<=')\\S{100}(?=')".toRegex() }

        private val SUPPORTED_HOSTERS = listOf(
            // TODO: Fix Alucard
            // "ALUCARD(BETA)",
            "DOODSTREAM",
            "EMBEDGRAM",
            "FILEMOON",
            "GDRIVE",
            "MAIL",
            "MP4UPLOAD",
            "MVIDOO",
            "ODNOKLASSNIKI",
            "SENDVID",
            "SIBNET",
            "STREAMVID",
            "UQLOAD",
            "VK",
            "VOE",
            "VTUBE",
            "VUDEA",
            "WOLFSTREAM",
        )

        private val DEFAULT_SUBS by lazy {
            setOf(
                "Adonis",
                "Aitr",
                "Akatsuki",
                "AkiraSubs",
                "AniKeyf",
                "ANS",
                "AnimeMangaTR",
                "AnimeOU",
                "AniSekai",
                "AniTürk",
                "AoiSubs",
                "ARE-YOU-SURE",
                "AnimeWho",
                "Benihime",
                "Chevirman",
                "Fatality",
                "Hikigaya",
                "HolySubs",
                "Kirigana Fairies",
                "Lawsonia Sub",
                "LowSubs",
                "Magnus357",
                "Momo & Berhann",
                "NoaSubs",
                "OrigamiSubs",
                "Pijamalı Koi",
                "Puzzlesubs",
                "RaionSubs",
                "ShimazuSubs",
                "SoutenSubs",
                "TAÇE",
                "TRanimeizle",
                "TR Altyazılı",
                "Uragiri",
                "Varsayılan",
                "YukiSubs",
            )
        }

        private const val PREF_KEY_KEY = "key"
        private const val DEFAULT_KEY = "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = arrayOf("1080", "720", "480", "360")

        private const val PREF_HOSTER_KEY = "hoster_selection"
        private const val PREF_HOSTER_TITLE = "Enable/Disable Hosts"
        private val PREF_HOSTER_DEFAULT = setOf("GDRIVE", "VOE")

        // Copypasted from tr/tranimeizle.
        private const val PREF_FANSUB_SELECTION_KEY = "pref_fansub_selection"
        private const val PREF_FANSUB_SELECTION_TITLE = "Enable/Disable Fansubs"

        private const val PREF_ADDITIONAL_FANSUBS_KEY = "pref_additional_fansubs_key"
        private const val PREF_ADDITIONAL_FANSUBS_TITLE = "Add custom fansubs to the selection preference"
        private const val PREF_ADDITIONAL_FANSUBS_DEFAULT = ""
        private const val PREF_ADDITIONAL_FANSUBS_DIALOG_TITLE = "Enter a list of additional fansubs, separated by a comma."
        private const val PREF_ADDITIONAL_FANSUBS_DIALOG_MESSAGE = "Example: AntichristHaters Fansub, 2cm erect subs"
        private const val PREF_ADDITIONAL_FANSUBS_SUMMARY = "You can add more fansubs to the previous preference from here."
        private const val PREF_ADDITIONAL_FANSUBS_TOAST = "Reopen the extension's preferences for it to take effect."
    }

    private val PREF_FANSUB_SELECTION_ENTRIES: Array<String> get() {
        val additional = preferences.getString(PREF_ADDITIONAL_FANSUBS_KEY, "")!!
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

        return (DEFAULT_SUBS + additional).sorted().toTypedArray()
    }

    // =============================== Preferences ==============================
    @Suppress("UNCHECKED_CAST")
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
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = SUPPORTED_HOSTERS.toTypedArray()
            entryValues = SUPPORTED_HOSTERS.toTypedArray()
            setDefaultValue(PREF_HOSTER_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
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
    }
}
