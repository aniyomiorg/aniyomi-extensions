package eu.kanade.tachiyomi.animeextension.de.animeshitai

import android.app.Application
import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.de.animeshitai.ASFilters.applyFilterParams
import eu.kanade.tachiyomi.animeextension.de.animeshitai.extractors.DoodExtractor
import eu.kanade.tachiyomi.animeextension.de.animeshitai.extractors.StreamTapeExtractor
import eu.kanade.tachiyomi.animeextension.de.animeshitai.model.ASAnime
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.collections.ArrayList

class AnimeShitai : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "Anime Shitai"

    override val baseUrl = "https://anime-shitai.com"

    override val lang = "de"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ===== POPULAR ANIME =====
    override fun popularAnimeSelector(): String = ".newanimes .newbox"

    override fun popularAnimeNextPageSelector(): String? = null

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val linkElement = element.selectFirst("a")
        anime.url = linkElement.attr("href")
        anime.thumbnail_url = linkElement.selectFirst("img").attr("src")
        anime.title = element.selectFirst(".ntitel").text()
        return anime
    }

    // ===== LATEST ANIME =====
    override fun latestUpdatesSelector(): String = "a"

    override fun latestUpdatesNextPageSelector(): String? = null

    override fun latestUpdatesRequest(page: Int): Request = POST("$baseUrl/ichigo/indexep.php?option=1")

    override fun latestUpdatesParse(response: Response): AnimesPage {
        val document = Jsoup.parseBodyFragment(response.body?.string())
        val animeList = mutableSetOf<String>()
        val animes = document.select(latestUpdatesSelector()).mapNotNull { element ->
            val animeName = element.selectFirst("span.t").text().substringBefore("...")
            // Check if any anime is multiple times in the list
            if (!animeList.contains(animeName)) {
                animeList.add(animeName)
                latestUpdatesFromElement(element)
            } else
                null
        }

        return AnimesPage(animes, false)
    }

    override fun latestUpdatesFromElement(element: Element): SAnime {
        val anime = SAnime.create()
        val animeId = element.attr("href").substringAfter("/anschauen/").substringBefore('/')
        anime.url = "/anime/$animeId/"
        anime.thumbnail_url = element.selectFirst("div.c").attr("style").substringAfter("background:url('").substringBefore("');")
        anime.title = anime.thumbnail_url!!.substringAfter("/cover/").substringBefore(".jpg").replace("__", ": ").replace('_', ' ')
        return anime
    }

    // ===== ANIME SEARCH =====
    override fun fetchSearchAnime(page: Int, query: String, filters: AnimeFilterList): Observable<AnimesPage> {
        val params = ASFilters.getSearchParameters(filters)
        return client.newCall(searchAnimeRequest(page, query, params))
            .asObservableSuccess()
            .map { response ->
                searchAnimeParse(response, params)
            }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException("Not used.")

    private fun searchAnimeRequest(page: Int, query: String, params: ASFilters.FilterSearchParams): Request {
        fun listToFormStr(list: ArrayList<String>): String {
            if (list.size > 0)
                return list.joinToString(" - ", " - ")
            return ""
        }
        val body = FormBody.Builder()
            .add("p", page.toString())
            .add("get_val", query)
            .add("genre", listToFormStr(params.includedGenres))
            .add("jahr", listToFormStr(params.includedYears))
            .add("format", listToFormStr(params.includedFormats))
            .add("omu", listToFormStr(params.includedLangs))
            .add("abc", listToFormStr(params.includedLetters))
            .build()

        return POST("$baseUrl/ichigo/get_anilist.php", headers, body)
    }

    override fun searchAnimeSelector(): String = ".listinganime"

    override fun searchAnimeNextPageSelector(): String? = ".nav"

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException("Not used.")

    private fun searchAnimeParse(response: Response, params: ASFilters.FilterSearchParams): AnimesPage {
        val document = Jsoup.parseBodyFragment(response.body?.string())

        val animeTrackList = mutableSetOf<String>()
        val animes = document.select(searchAnimeSelector()).mapNotNull { element ->
            val anime = searchAnimeFromElement(element)
            // Shows with sub and dub have two separate entries, exclude the second entry
            if (!animeTrackList.contains(anime.thumbnail_url)) {
                animeTrackList.add(anime.thumbnail_url!!)
                anime
            } else
                null
        }.toMutableList()

        // Apply client-side filters
        animes.applyFilterParams(params)

        val hasNextPage = searchAnimeNextPageSelector()?.let { selector ->
            document.select(selector).first()
        } != null

        return AnimesPage(animes, hasNextPage)
    }

    override fun searchAnimeFromElement(element: Element): ASAnime {
        val anime = ASAnime.create()
        anime.url = element.selectFirst("a").attr("href")
        anime.thumbnail_url = element.selectFirst(".listinganicover").attr("style").substringAfter("url('").substringBefore("')")
        anime.title = element.selectFirst(".listingtitle").text().replaceAfterLast(' ', "").trimStart()
        // Get genres for client-side genre filtering
        anime.genre = element.select(".listingdesc .alternativ").mapNotNull {
            if (it.text().startsWith("Hauptgenre") || it.text().startsWith("Genres"))
                it.text().substringAfter(':')
            else
                null
        }.joinToString(", ")

        anime.year = element.selectFirst(".listingtitle").text().substringAfterLast('(').substringBefore(')').toInt()
        return anime
    }

    override fun getFilterList(): AnimeFilterList = ASFilters.filterList

    // ===== ANIME DETAILS =====
    override fun animeDetailsParse(document: Document): SAnime {
        val anime = SAnime.create()
        val content = document.selectFirst("#ani .body")
        anime.title = content.selectFirst("animename").ownText().trim()
        anime.thumbnail_url = document.selectFirst("#ani .pic").attr("data-src")
        anime.genre = content.select("div:eq(1) .hg, a").joinToString(", ") { it.text() }
        anime.description = content.selectFirst(".br").ownText()
        anime.status = SAnime.UNKNOWN
        return anime
    }

    // ===== EPISODE =====
    override fun episodeListSelector(): String = ".ep_table tbody tr"

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        return document.select(episodeListSelector()).reversed().map { episodeFromElement(it) }
    }

    override fun episodeFromElement(element: Element): SEpisode {
        val episode = SEpisode.create()
        episode.setUrlWithoutDomain(baseUrl + element.attr("onclick").substringAfter("window.location.href='").substringBefore('#'))
        val ep = element.child(0).text()
        episode.episode_number = ep.toFloat()
        episode.name = "Episode $ep"
        episode.date_upload = System.currentTimeMillis()
        return episode
    }

    // ===== VIDEO SOURCES =====
    override fun videoListSelector(): String = "center a"

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        return document.select(videoListSelector()).mapNotNull { videoFromElement(it, document) }
    }

    private fun getVidLink(document: Document): String {
        val b64html = document.selectFirst(".c36 > script").data().substringAfter("atob('").substringBefore("');")
        val decodedHtml = Base64.decode(b64html, Base64.DEFAULT).decodeToString()
        return baseUrl + decodedHtml.substringAfter("src=\"").substringBefore('"')
    }

    override fun videoFromElement(element: Element): Video = throw UnsupportedOperationException("Not used.")

    private fun videoFromElement(element: Element, document: Document): Video? {
        val hosterLinkName = element.attr("href").substringAfter("/folge-").substringAfter('/')
        val hosterName = when (hosterLinkName) {
            "DODO" -> ASConstants.NAME_DOOD
            "Streamtape" -> ASConstants.NAME_STAPE
            else -> null
        } ?: return null

        val lang =
            when (element.selectFirst(".languagehoster img").attr("src").substringAfter("/img/").substringBefore(".")) {
                "de" -> ASConstants.LANG_DUB
                "jap" -> ASConstants.LANG_SUB
                "en" -> ASConstants.LANG_SUB
                else -> "Unknown"
            }

        val link: String = when {
            // If video link of loaded page is already the right one
            element.selectFirst(".host").hasClass("hoston") -> getVidLink(document)
            // If video link of loaded page is not the right one, get url for the right page and extract video link
            else -> {
                val response = client.newCall(GET("$baseUrl${element.attr("href")}")).execute()
                getVidLink(response.asJsoup())
            }
        }

        val quality = "$hosterName, $lang"
        val hosterSelection = preferences.getStringSet(ASConstants.HOSTER_SELECTION, null)
        when {
            hosterName == ASConstants.NAME_DOOD && hosterSelection?.contains(ASConstants.NAME_DOOD) == true -> {
                val video = try {
                    DoodExtractor(client).videoFromUrl(link, quality)
                } catch (e: Exception) {
                    null
                }
                if (video != null) {
                    return video
                }
            }
            hosterName == ASConstants.NAME_STAPE && hosterSelection?.contains(ASConstants.NAME_STAPE) == true -> {
                val video = StreamTapeExtractor(client).videoFromUrl(link, quality)
                if (video != null) {
                    return video
                }
            }
        }
        return null
    }

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(ASConstants.PREFERRED_HOSTER, null)
        val subPreference = preferences.getString(ASConstants.PREFERRED_LANG, "Sub")!!
        val hosterList = mutableListOf<Video>()
        val otherList = mutableListOf<Video>()
        if (hoster != null) {
            for (video in this) {
                if (video.url.contains(hoster)) {
                    hosterList.add(video)
                } else {
                    otherList.add(video)
                }
            }
        } else otherList += this
        val newList = mutableListOf<Video>()
        var preferred = 0
        for (video in hosterList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }
        for (video in otherList) {
            if (video.quality.contains(subPreference)) {
                newList.add(preferred, video)
                preferred++
            } else newList.add(video)
        }

        return newList
    }

    override fun videoUrlParse(document: Document): String = throw UnsupportedOperationException("Not used.")

    // ===== PREFERENCES ======
    @Suppress("UNCHECKED_CAST")
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val hosterPref = ListPreference(screen.context).apply {
            key = ASConstants.PREFERRED_HOSTER
            title = "Standard-Hoster"
            entries = ASConstants.HOSTER_NAMES
            entryValues = ASConstants.HOSTER_URLS
            setDefaultValue(ASConstants.URL_STAPE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subPref = ListPreference(screen.context).apply {
            key = ASConstants.PREFERRED_LANG
            title = "Standardmäßig Sub oder Dub?"
            entries = ASConstants.LANGS
            entryValues = ASConstants.LANGS
            setDefaultValue(ASConstants.LANG_SUB)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        val subSelection = MultiSelectListPreference(screen.context).apply {
            key = ASConstants.HOSTER_SELECTION
            title = "Hoster auswählen"
            entries = ASConstants.HOSTER_NAMES
            entryValues = ASConstants.HOSTER_NAMES
            setDefaultValue(ASConstants.HOSTER_NAMES.toSet())

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }
        screen.addPreference(subPref)
        screen.addPreference(hosterPref)
        screen.addPreference(subSelection)
    }
}
