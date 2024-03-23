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
import okhttp3.Request
import okhttp3.internal.commonEmptyHeaders
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object JavCoverFetcher {

    private val CLIENT by lazy {
        Injekt.get<NetworkHelper>().client
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

        val request = GET(url, commonEmptyHeaders)

        val response = CLIENT.newCall(request).execute()

        var document = response.asJsoup()

        // possibly multiple results or none
        if (response.request.url.pathSegments.contains("vl_searchbyid.php")) {
            val targetUrl = document.selectFirst(".videos a[href*=\"?v=\"]")?.attr("abs:href")
                ?: return null

            document = CLIENT.newCall(GET(targetUrl, commonEmptyHeaders)).execute().asJsoup()
        }

        val dirtyTitle = document.selectFirst(".post-title")?.text()

        val id = document.select("#video_info tr > td:contains(品番) + td").text()

        return dirtyTitle?.substringAfter(id)?.trim()
    }

    private fun getDDGSearchResult(jpTitle: String): String? {
        val url = "https://lite.duckduckgo.com/lite/"

        val form = FormBody.Builder()
            .add("q", "site:amazon.co.jp inurl:/dp/$jpTitle")
            .build()

        val headers = commonEmptyHeaders.newBuilder().apply {
            add("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            add("Host", "lite.duckduckgo.com")
            add("Referer", "https://lite.duckduckgo.com/")
            add("Origin", "https://lite.duckduckgo.com")
            add("Accept-Language", "en-US,en;q=0.5")
            add("DNT", "1")
            add("Sec-Fetch-Dest", "document")
            add("Sec-Fetch-Mode", "navigate")
            add("Sec-Fetch-Site", "same-origin")
            add("Sec-Fetch-User", "?1")
            add("TE", "trailers")
        }.build()

        val request = POST(url, headers, form)

        val response = CLIENT.newCall(request).execute()

        val document = response.asJsoup()

        return document.selectFirst("a.result-link")?.attr("href")
    }

    private fun getHDCoverFromAmazonUrl(amazonUrl: String): String? {
        val basicCoverUrl = "https://m.media-amazon.com/images/P/%s.01.MAIN._SCRM_.jpg"
        val asinRegex = Regex("""/dp/(\w+)""")

        val asin = asinRegex.find(amazonUrl)?.groupValues?.get(1)
            ?: return null

        var cover = basicCoverUrl.replace("%s", asin)

        if (!checkCover(cover)) {
            cover = cover.replace(".01.", ".")
        }

        return cover
    }

    private fun checkCover(cover: String): Boolean {
        return getContentLength(cover) > 100
    }

    private fun getContentLength(url: String): Long {
        val request = Request.Builder()
            .head()
            .url(url)
            .build()

        val res = CLIENT.newCall(request).execute()

        return res.use { it.headers["content-length"] }?.toLongOrNull() ?: 0
    }

    fun addPreferenceToScreen(screen: PreferenceScreen) {
        SwitchPreferenceCompat(screen.context).apply {
            key = "JavCoverFetcherPref"
            title = "Fetch HD covers from Amazon"
            summary = "Attempts to fetch vertical HD covers from Amazon.\nMay result in incorrect cover."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    val SharedPreferences.fetchHDCovers
        get() = getBoolean("JavCoverFetcherPref", false)
}
