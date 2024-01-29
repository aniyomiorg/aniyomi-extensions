package eu.kanade.tachiyomi.lib.javcoverfetcher

import android.content.SharedPreferences
import android.util.Log
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.internal.commonEmptyHeaders
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

object JavCoverFetcher {

    private val CLIENT by lazy {
        Injekt.get<NetworkHelper>().client.newBuilder()
            .addInterceptor(::amazonAgeVerifyIntercept)
            .build()
    }

    private val HEADERS by lazy {
        commonEmptyHeaders.newBuilder()
            .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/117.0.0.0 Safari/537.36")
            .build()
    }

    private fun amazonAgeVerifyIntercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!request.url.host.contains("amazon.co.jp") || !response.request.url.pathSegments.contains("black-curtain")) {
            return response
        }

        val document = response.asJsoup()
        val targetUrl = document.selectFirst("#black-curtain-yes-button a")?.attr("abs:href")
            ?: throw IOException("Failed to bypass Amazon Age Gate")

        val newRequest = request.newBuilder().apply {
            url(targetUrl)
        }.build()

        return chain.proceed(newRequest)
    }

    /**
     *  Get HD Jav Cover from Amazon
     *
     * @param jpTitle title of jav in japanese
     */
    fun getCoverByTitle(jpTitle: String): String? {
        return runCatching {
            val amazonUrl = getDDGSearchResult(jpTitle)
                ?: return@runCatching null

            getHDCoverFromAmazonUrl(amazonUrl)
        }.getOrElse {
            Log.e("JavCoverFetcher", it.stackTraceToString())
            null
        }
    }

    /**
     *  Get HD Jav Cover from Amazon
     *
     * @param javId standard JAV code e.g PRIN-006
     */
    fun getCoverById(javId: String): String? {
        return runCatching {
            val jpTitle = getJPTitleFromID(javId)
                ?: return@runCatching null

            val amazonUrl = getDDGSearchResult(jpTitle)
                ?: return@runCatching null

            getHDCoverFromAmazonUrl(amazonUrl)
        }.getOrElse {
            Log.e("JavCoverFetcher", it.stackTraceToString())
            null
        }
    }

    private fun getJPTitleFromID(javId: String): String? {
        val url = "https://www.javlibrary.com/ja/vl_searchbyid.php?keyword=$javId"

        val request = GET(url, HEADERS)

        val response = CLIENT.newCall(request).execute()

        var document = response.asJsoup()

        // possibly multiple results or none
        if (response.request.url.pathSegments.contains("vl_searchbyid.php")) {
            val targetUrl = document.selectFirst(".videos a[href*=\"?v=\"]")?.attr("abs:href")
                ?: return null

            document = CLIENT.newCall(GET(targetUrl, HEADERS)).execute().asJsoup()
        }

        val dirtyTitle = document.selectFirst(".post-title")?.text()

        val id = document.select("#video_info tr > td:contains(品番) + td").text()

        return dirtyTitle?.substringAfter(id)?.trim()
    }

    private fun getDDGSearchResult(jpTitle: String): String? {
        val url = "https://lite.duckduckgo.com/lite"

        val form = FormBody.Builder()
            .add("q", "site:amazon.co.jp inurl:/dp/$jpTitle")
            .build()

        val request = POST(url, HEADERS, form)

        val response = CLIENT.newCall(request).execute()

        val document = response.asJsoup()

        return document.selectFirst("a.result-link")?.attr("href")
    }

    private fun getHDCoverFromAmazonUrl(amazonUrl: String): String? {
        val request = GET(amazonUrl, HEADERS)

        val response = CLIENT.newCall(request).execute()

        val document = response.asJsoup()

        val smallImage = document.selectFirst("#landingImage")?.attr("src")

        return smallImage?.replace(Regex("""(\._\w+_\.jpg)"""), ".jpg")
    }

    fun addPreferenceToScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "JavCoverFetcherPref"
            title = "Fetch HD covers from Amazon"
            summary = "Attempts to fetch HD covers from Amazon.\nMay result in incorrect cover."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    val SharedPreferences.fetchHDCovers
        get() = getBoolean("JavCoverFetcherPref", false)
}
