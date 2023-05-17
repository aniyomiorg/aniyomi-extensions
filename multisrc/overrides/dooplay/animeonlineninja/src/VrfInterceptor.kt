package eu.kanade.tachiyomi.animeextension.es.animeonlineninja

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.network.GET
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup

class VrfInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val respBody = response.body.string()
        if (response.headers["Content-Type"]?.contains("image") == true) {
            return chain.proceed(request)
        }
        val body = if (respBody.contains("One moment, please")) {
            val parsed = Jsoup.parse(respBody)
            val js = parsed.selectFirst("script:containsData(west=)")!!.data()
            val west = js.substringAfter("west=").substringBefore(",")
            val east = js.substringAfter("east=").substringBefore(",")
            val form = parsed.selectFirst("form#wsidchk-form")!!.attr("action")
            val eval = evalJs(west, east)
            val getLink = "https://" + request.url.host + form + "?wsidchk=$eval"
            chain.proceed(GET(getLink)).body
        } else {
            respBody.toResponseBody(response.body.contentType())
        }
        return response.newBuilder().body(body).build()
    }

    private fun evalJs(west: String, east: String): String {
        return QuickJs.create().use { qjs ->
            val jscript = """$west + $east;"""
            qjs.evaluate(jscript).toString()
        }
    }
}
