package eu.kanade.tachiyomi.animeextension.en.uhdmovies

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

class RedirectorBypasser(private val client: OkHttpClient, private val headers: Headers) {
    fun bypass(url: String): String? {
        val lastDoc = client.newCall(GET(url, headers)).execute()
            .let { recursiveDoc(it.asJsoup()) }

        val script = lastDoc.selectFirst("script:containsData(/?go=):containsData(href)")
            ?.data()
            ?: return null

        val nextUrl = script.substringAfter("\"href\",\"").substringBefore('"')
        val httpUrl = nextUrl.toHttpUrlOrNull() ?: return null
        val cookieName = httpUrl.queryParameter("go") ?: return null
        val cookieValue = script.substringAfter("'$cookieName', '").substringBefore("'")
        val cookie = Cookie.parse(httpUrl, "$cookieName=$cookieValue")!!
        val headers = headers.newBuilder().set("referer", lastDoc.location()).build()

        val doc = runBlocking(Dispatchers.IO) {
            MUTEX.withLock { // Mutex to prevent overwriting cookies from parallel requests
                client.cookieJar.saveFromResponse(httpUrl, listOf(cookie))
                client.newCall(GET(nextUrl, headers)).execute().asJsoup()
            }
        }

        return doc.selectFirst("meta[http-equiv]")?.attr("content")
            ?.substringAfter("url=")
    }

    private fun recursiveDoc(doc: Document): Document {
        val form = doc.selectFirst("form#landing") ?: return doc
        val url = form.attr("action")
        val body = FormBody.Builder().apply {
            form.select("input").forEach {
                add(it.attr("name"), it.attr("value"))
            }
        }.build()

        val headers = headers.newBuilder()
            .set("referer", doc.location())
            .build()

        return client.newCall(POST(url, headers, body)).execute().let {
            recursiveDoc(it.asJsoup())
        }
    }

    companion object {
        private val MUTEX by lazy { Mutex() }
    }
}
