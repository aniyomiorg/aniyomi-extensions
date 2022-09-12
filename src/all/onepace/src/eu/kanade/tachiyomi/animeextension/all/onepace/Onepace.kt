package eu.kanade.tachiyomi.animeextension.all.onepace

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.Exception

open class Onepace(override val lang: String, override val name: String) : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val baseUrl = "https://www.zippyshare.com/rest/public/getTree?user=onepace&ident=kbvatgfc&id=%23"

    override val supportsLatest = false

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val thumPref by lazy { preferences.getString("thumpreference", "false")!! }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = client.newCall(GET(baseUrl)).execute().asJsoup()
        val responseJson = json.decodeFromString<JsonObject>(document.select("body").text().dropLast(1).drop(1))
        val childrenJson = responseJson["children"]?.jsonArray
        // 0 = eng, 1 = sp, 2 = fr
        val langId = when (lang) {
            "es" -> 1
            "en" -> 0
            "fr" -> 2
            else -> 0
        }
        val langAnJson = childrenJson!![langId].jsonObject["children"]!!.jsonArray
        val thumJson by lazy { client.newCall(GET("https://onepace.net/_next/data/RQ8jGOTF74G85UWtiDofs/es/watch.json")).execute().asJsoup() }
        return AnimesPage(
            langAnJson.map {
                val anName = it.jsonObject["text"].toString().replace("\"", "")
                val anId = it.jsonObject["li_attr"]!!.jsonObject["ident"].toString().replace("\"", "")
                val anStatus = if (anName.contains("Completo")) SAnime.COMPLETED else SAnime.ONGOING
                SAnime.create().apply {
                    title = anName
                    status = anStatus
                    url = "https://www.zippyshare.com/onepace/$anId/dir.html"
                    thumbnail_url = when (thumPref) {
                        "true" -> thumAnimeParser(anName, thumJson)
                        else -> ""
                    }
                }
            },
            false
        )
    }

    private fun thumAnimeParser(animeName: String, document: Document): String {
        val jsonResponse = json.decodeFromString<JsonObject>(document.body().text())["pageProps"]!!
        val arcsJson = jsonResponse.jsonObject["arcs"]!!.jsonArray
        arcsJson.forEach {
            val thumId = it.jsonObject["images"]!!.jsonArray[0].jsonObject["src"].toString().replace("\"", "")
            val langTitle = it.jsonObject["translations"]!!.jsonArray
            langTitle.forEach { j ->
                val langCode = j.jsonObject["language"]!!.jsonObject["code"]
                if (langCode.toString().replace("\"", "") == lang) {
                    val title = j.jsonObject["title"].toString().replace("\"", "")
                    if (animeName.lowercase().contains(title.lowercase())) return "https://onepace.net/_next/image?url=%2Fimages%2Farcs%2F$thumId&w=828&q=75"
                }
            }
        }
        return ""
    }

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl)

    override fun popularAnimeNextPageSelector() = throw Exception("not used")

    override fun popularAnimeSelector() = throw Exception("not used")

    override fun popularAnimeFromElement(element: Element) = throw Exception("not used")

    override fun episodeListParse(response: Response): List<SEpisode> {
        val realUrl = response.request.url.toString().substringAfter("%23")
        val jsoup = client.newCall(GET(realUrl)).execute().asJsoup()
        return jsoup.select("table.listingplikow tbody tr.filerow.even").map {
            val epName = it.select("td.cien a.name").text().replace(".mp4", "")
            val epNum = try {
                epName.substringAfter("][").substringBefore("]")
                    .replace("-", ".")
                    .replace(",", ".")
                    .replace("F", ".").replace("B", "0").toFloat()
            } catch (e: Exception) {
                // bruh
                (Math.random() * 100).toFloat()
            }
            val epUrl = it.select("td.cien a.name").attr("href")
            SEpisode.create().apply {
                name = epName
                url = epUrl
                episode_number = epNum
            }
        }
    }

    override fun episodeListSelector() = throw Exception("not used")

    override fun episodeFromElement(element: Element) = throw Exception("not used")

    override fun videoListParse(response: Response): List<Video> {
        val realUrl = "https:" + response.request.url.toString().substringAfter("%23")
        val hostUrl = realUrl.substringBefore("/v/")
        val videoUrlD = ZippyExtractor().getVideoUrl(realUrl, json)
        val videoUrl = hostUrl + videoUrlD
        return listOf(Video(videoUrl, "ZippyShare", videoUrl))
    }

    override fun videoListSelector() = throw Exception("not used")

    override fun videoUrlParse(document: Document) = throw Exception("not used")

    override fun videoFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw Exception("not used")

    override fun searchAnimeFromElement(element: Element) = throw Exception("not used")

    override fun searchAnimeNextPageSelector() = throw Exception("not used")

    override fun searchAnimeSelector() = throw Exception("not used")

    override fun latestUpdatesNextPageSelector() = throw Exception("not used")

    override fun latestUpdatesFromElement(element: Element) = throw Exception("not used")

    override fun latestUpdatesRequest(page: Int) = throw Exception("not used")

    override fun latestUpdatesSelector() = throw Exception("not used")

    override fun animeDetailsParse(document: Document): SAnime {
        return SAnime.create().apply { title = "OnePace" }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val thumPreference = ListPreference(screen.context).apply {
            key = "thumpreference"
            title = when (lang) {
                "es" -> "Habilita la carga de miniaturas (requiere reiniciar)"
                "en" -> "Enable load thumbnails (requires app restart)"
                "fr" -> "Activer le chargement des vignettes (nécessite un redémarrage)"
                else -> "Enable load thumbnails (requires app restart)"
            }
            entries = when (lang) {
                "es" -> arrayOf("Habilitar", "Deshabilitar")
                "en" -> arrayOf("Enable", "Unable")
                "fr" -> arrayOf("Activer", "désactiver")
                else -> arrayOf("Enable", "Unable")
            }
            entryValues = arrayOf("true", "false")
            setDefaultValue("false")
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }
        screen.addPreference(thumPreference)
    }
}
