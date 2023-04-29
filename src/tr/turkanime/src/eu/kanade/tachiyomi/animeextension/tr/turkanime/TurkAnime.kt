package eu.kanade.tachiyomi.animeextension.tr.turkanime

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.AlucardExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.EmbedgramExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.FilemoonExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.GoogleDriveExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.MVidooExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.MailRuExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.Mp4uploadExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.MytvExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.SendvidExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.SibnetExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.StreamVidExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.UqloadExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.VTubeExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.VkExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.VudeoExtractor
import eu.kanade.tachiyomi.animeextension.tr.turkanime.extractors.WolfstreamExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.okruextractor.OkruExtractor
import eu.kanade.tachiyomi.lib.streamsbextractor.StreamSBExtractor
import eu.kanade.tachiyomi.lib.synchrony.Deobfuscator
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class TurkAnime : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Türk Anime TV"

    override val baseUrl = "https://www.turkanime.co"

    override val lang = "tr"

    override val supportsLatest = true

    override val client = network.cloudflareClient

    private val json = Json {
        ignoreUnknownKeys = true
    }

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val key: String
        get() = preferences.getString(PREF_KEY_KEY, DEFAULT_KEY)!!

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/ajax/rankagore?sayfa=$page", xmlHeader)

    override fun popularAnimeSelector() = "div.panel-visible"

    override fun popularAnimeNextPageSelector() = "button.btn-default[data-loading-text*=Sonraki]"

    override fun popularAnimeFromElement(element: Element): SAnime {
        val animeTitle = element.select("div.panel-title > a").first()!!
        val name = animeTitle.attr("title")
            .substringBefore(" izle")
        val img = element.select("img.media-object")
        val animeId = element.select("a.reactions").first()!!.attr("data-unique-id")
        val animeUrl = ("https:" + animeTitle.attr("href")).toHttpUrl()
            .newBuilder()
            .addQueryParameter("animeId", animeId)
            .build().toString()
        return SAnime.create().apply {
            setUrlWithoutDomain(animeUrl)
            title = name
            thumbnail_url = "https:" + img.attr("data-src")
        }
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(document: Document): SAnime {
        val img = document.select("div.imaj > img.media-object").ifEmpty { null }
        val studio = document.select("div#animedetay > table tr:contains(Stüdyo) > td:last-child a").ifEmpty { null }
        val desc = document.select("div#animedetay p.ozet").ifEmpty { null }
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
        val animeId = (baseUrl + anime.url).toHttpUrl().queryParameter("animeId")!!
        return GET("https://www.turkanime.co/ajax/bolumler?animeId=$animeId", xmlHeader)
    }

    override fun episodeListSelector() = "ul.menum li"

    override fun episodeFromElement(element: Element): SEpisode {
        val a = element.select("a:has(span.bolumAdi)")
        val title = a.attr("title")
        val substring = title.substringBefore(". Bölüm")
        val numIdx = substring.indexOfLast { !it.isDigit() } + 1
        val numbers = substring.slice(numIdx..substring.lastIndex)
        return SEpisode.create().apply {
            setUrlWithoutDomain("https:" + a.attr("href"))
            name = title
            episode_number = numbers.toFloatOrNull() ?: 1F
        }
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        return super.episodeListParse(response).reversed()
    }

    // ============================ Video Links =============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val fansubbers = document.select("div#videodetay div.pull-right button")
        return if (fansubbers.size == 1) {
            getVideosFromHosters(document, fansubbers.first()!!.text().trim())
        } else {
            val videoList = mutableListOf<Video>()
            fansubbers.parallelMap {
                val url = it.attr("onclick").trimOnClick()
                val subDoc = client.newCall(GET(url, xmlHeader)).execute().asJsoup()
                videoList.addAll(getVideosFromHosters(subDoc, it.text().trim()))
            }
            videoList
        }
    }

    private fun getVideosFromHosters(document: Document, subber: String): List<Video> {
        val selectedHoster = document.select("div#videodetay div.btn-group:not(.pull-right) > button.btn-danger")
        val hosters = document.select("div#videodetay div.btn-group:not(.pull-right) > button.btn-default[onclick*=videosec]")

        val hosterSelection = preferences.getStringSet(
            "hoster_selection",
            setOf("GDRIVE", "STREAMSB", "VOE"),
        )!!

        val videoList = mutableListOf<Video>()
        val selectedHosterName = selectedHoster.text().trim()
        if (selectedHosterName in SUPPORTED_HOSTERS && selectedHosterName in hosterSelection) {
            val src = document.select("iframe").attr("src")
            videoList.addAll(getVideosFromSource(src, selectedHosterName, subber))
        }
        hosters.parallelMap {
            val hosterName = it.text().trim()
            if (hosterName !in SUPPORTED_HOSTERS) return@parallelMap
            if (hosterName !in hosterSelection) return@parallelMap
            val url = it.attr("onclick").trimOnClick()
            val videoDoc = client.newCall(GET(url, xmlHeader)).execute().asJsoup()
            val src = videoDoc.select("iframe").attr("src").replace("^//".toRegex(), "https://")
            videoList.addAll(getVideosFromSource(src, hosterName, subber))
        }
        return videoList
    }

    private fun getVideosFromSource(src: String, hosterName: String, subber: String): List<Video> {
        val videoList = mutableListOf<Video>()
        val cipherParamsEncoded = src
            .substringAfter("/embed/#/url/")
            .substringBefore("?status")

        val cipherParams = json.decodeFromString<CipherParams>(
            String(
                Base64.decode(cipherParamsEncoded, Base64.DEFAULT),
            ),
        )

        val hosterLink = "https:" + json.decodeFromString<JsonPrimitive>(
            CryptoAES.decryptWithSalt(
                cipherParams.ct,
                cipherParams.s,
                key,
            ),
        ).content

        when (hosterName) {
            "ALUCARD(BETA)" -> {
                videoList.addAll(AlucardExtractor(client, json, baseUrl).extractVideos(hosterLink, subber))
            }
            "DOODSTREAM" -> {
                videoList.addAll(DoodExtractor(client).videosFromUrl(hosterLink, "$subber: DOODSTREAM", redirect = false))
            }
            "EMBEDGRAM" -> {
                videoList.addAll(EmbedgramExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "FILEMOON" -> {
                videoList.addAll(FilemoonExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "GDRIVE" -> {
                Regex("""[\w-]{28,}""").find(hosterLink)?.groupValues?.get(0)?.let {
                    videoList.addAll(GoogleDriveExtractor(client, headers).videosFromUrl("https://drive.google.com/uc?id=$it", "$subber: Gdrive"))
                }
            }
            "MAIL" -> {
                videoList.addAll(MailRuExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "MP4UPLOAD" -> {
                videoList.addAll(Mp4uploadExtractor(client).getVideoFromUrl(hosterLink, headers, prefix = "$subber: "))
            }
            "MYVI" -> {
                videoList.addAll(MytvExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "MVIDOO" -> {
                videoList.addAll(MVidooExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "ODNOKLASSNIKI" -> {
                videoList.addAll(OkruExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "SENDVID" -> {
                videoList.addAll(SendvidExtractor(client, headers).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "SIBNET" -> {
                videoList.addAll(SibnetExtractor(client).getVideosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "STREAMSB" -> {
                videoList.addAll(StreamSBExtractor(client).videosFromUrl(hosterLink, refererHeader, prefix = "$subber: "))
            }
            "STREAMVID" -> {
                videoList.addAll(StreamVidExtractor(client).videosFromUrl(hosterLink, headers, prefix = "$subber: "))
            }
            "UQLOAD" -> {
                videoList.addAll(UqloadExtractor(client).videosFromUrl(hosterLink, headers, "$subber: Uqload"))
            }
            "VK" -> {
                val vkUrl = "https://vk.com" + hosterLink.substringAfter("vk.com")
                videoList.addAll(VkExtractor(client).getVideosFromUrl(vkUrl, prefix = "$subber: "))
            }
            "VOE" -> {
                VoeExtractor(client).videoFromUrl(hosterLink, "$subber: VOE")?.let { video -> videoList.add(video) }
            }
            "VTUBE" -> {
                videoList.addAll(VTubeExtractor(client, headers).videosFromUrl(hosterLink, baseUrl, prefix = "$subber: "))
            }
            "VUDEA" -> {
                videoList.addAll(VudeoExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
            "WOLFSTREAM" -> {
                videoList.addAll(WolfstreamExtractor(client).videosFromUrl(hosterLink, prefix = "$subber: "))
            }
        }
        return videoList
    }

    @Serializable
    private data class CipherParams(
        @Serializable
        val ct: String,
        @Serializable
        val iv: String,
        @Serializable
        val s: String,
    )

    private fun String.trimOnClick() = baseUrl + "/" + this.substringAfter("IndexIcerik('").substringBefore("'")

    override fun videoFromElement(element: Element): Video = throw Exception("not used")
    override fun videoListSelector(): String = throw Exception("not used")
    override fun videoUrlParse(document: Document): String = throw Exception("not used")

    // =============================== Search ===============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        POST(
            "$baseUrl/arama?sayfa=$page",
            Headers.headersOf("content-type", "application/x-www-form-urlencoded"),
            FormBody.Builder().add("arama", query).build(),
        )

    override fun searchAnimeSelector() = popularAnimeSelector()
    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()
    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/ajax/yenieklenenseriler?sayfa=$page", xmlHeader)

    override fun latestUpdatesSelector() = popularAnimeSelector()
    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()
    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Preferences ===============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val videoQualityPref = ListPreference(screen.context).apply {
            key = "preferred_quality"
            title = "Preferred quality"
            entries = arrayOf("1080p", "720p", "480p", "360p")
            entryValues = arrayOf("1080", "720", "480", "360")
            setDefaultValue("1080")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val hostSelection = MultiSelectListPreference(screen.context).apply {
            key = "hoster_selection"
            title = "Enable/Disable Hosts"
            entries = SUPPORTED_HOSTERS.toTypedArray()
            entryValues = SUPPORTED_HOSTERS.toTypedArray()
            setDefaultValue(setOf("GDRIVE", "STREAMSB", "VOE"))

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(videoQualityPref)
        screen.addPreference(hostSelection)
    }

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString("preferred_quality", "1080")
        if (quality != null) {
            val newList = mutableListOf<Video>()
            var preferred = 0
            for (video in this) {
                if (video.quality.contains(quality)) {
                    newList.add(preferred, video)
                    preferred++
                } else {
                    newList.add(video)
                }
            }
            return newList
        }
        return this
    }

    // ============================= Utilities ==============================

    private val xmlHeader = Headers.headersOf("X-Requested-With", "XMLHttpRequest")
    private val refererHeader = Headers.headersOf("Referer", baseUrl)

    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking(Dispatchers.Default) {
            map { async { f(it) } }.awaitAll()
        }

    private fun getKey() {
        val script4 = client.newCall(GET("$baseUrl/embed/#/")).execute().asJsoup()
            .select("script[defer]").getOrNull(1)
            ?.attr("src") ?: return
        val embeds4 = client.newCall(GET(baseUrl + script4)).execute().body.string()
        val name = "(?<=')[0-9a-f]{16}(?=')".toRegex().findAll(embeds4).toList().firstOrNull()?.value

        val file5 = client.newCall(GET("$baseUrl/embed/js/embeds.$name.js")).execute().body.string()
        val embeds5 = Deobfuscator.deobfuscateScript(file5) ?: return
        val key = "(?<=')\\S{100}(?=')".toRegex().find(embeds5)?.value ?: return
        preferences.edit().putString(PREF_KEY_KEY, key).apply()
    }

    init {
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch {
            withContext(Dispatchers.IO) { getKey() }
        }
    }
}

private val SUPPORTED_HOSTERS = listOf(
    // TODO: Fix Alucard
    // "ALUCARD(BETA)",
    "DOODSTREAM",
    "EMBEDGRAM",
    "FILEMOON",
    "GDRIVE",
    "MAIL",
    "MP4UPLOAD",
    "MYVI",
    "MVIDOO",
    "ODNOKLASSNIKI",
    "SENDVID",
    "SIBNET",
    "STREAMSB",
    "STREAMVID",
    "UQLOAD",
    "VK",
    "VOE",
    "VTUBE",
    "VUDEA",
    "WOLFSTREAM",
)

private const val PREF_KEY_KEY = "key"
private const val DEFAULT_KEY = "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"
