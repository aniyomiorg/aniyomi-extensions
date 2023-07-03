package eu.kanade.tachiyomi.animeextension.en.holamovies.extractors

import android.content.SharedPreferences
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class GDBotExtractor(private val client: OkHttpClient, private val headers: Headers, private val preferences: SharedPreferences) {

    private val prefBotUrlKey = "bot_url"

    private val defaultUrl = "https://gdtot.pro"

    fun videosFromUrl(serverUrl: String, maxTries: Int = 1): List<Video> {
        val botUrl = preferences.getString(prefBotUrlKey, defaultUrl)!!
        val videoList = mutableListOf<Video>()

        if (maxTries == 3) throw Exception("Video extraction catastrophically failed")

        val docHeaders = headers.newBuilder()
            .add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .add("Host", botUrl.toHttpUrl().host)
            .build()

        val fileId = serverUrl.substringAfter("/file/")
        val resp = try {
            client.newCall(
                GET("$botUrl/file/$fileId", headers = docHeaders),
            ).execute()
        } catch (a: Exception) {
            val newHost = OkHttpClient().newCall(GET(botUrl)).execute().request.url.host
            preferences.edit().putString(prefBotUrlKey, "https://$newHost").apply()
            return videosFromUrl(serverUrl, maxTries + 1)
        }

        if (resp.code == 421) {
            val newHost = OkHttpClient().newCall(GET(botUrl)).execute().request.url.host
            preferences.edit().putString(prefBotUrlKey, "https://$newHost").apply()
            return videosFromUrl(serverUrl, maxTries + 1)
        }

        val document = resp.asJsoup()

        videoList.addAll(
            document.select("li.py-6 > a[href]").parallelMap { server ->
                runCatching {
                    val url = server.attr("href")
                    when {
                        url.toHttpUrl().host.contains("gdflix") -> {
                            GDFlixExtractor(client, headers).videosFromUrl(url)
                        }
                        url.toHttpUrl().host.contains("gdtot") -> {
                            GDTotExtractor(client, headers).videosFromUrl(url)
                        }
                        else -> null
                    }
                }.getOrNull()
            }.filterNotNull().flatten(),
        )

        return videoList
    }

    // From Dopebox
    private fun <A, B> Iterable<A>.parallelMap(f: suspend (A) -> B): List<B> =
        runBlocking {
            map { async(Dispatchers.Default) { f(it) } }.awaitAll()
        }
}
