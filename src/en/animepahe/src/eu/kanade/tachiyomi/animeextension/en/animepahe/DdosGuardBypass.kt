package eu.kanade.tachiyomi.animeextension.en.animepahe

import android.util.Base64.DEFAULT
import android.util.Base64.encodeToString
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.UnsupportedEncodingException
import java.lang.Exception
import java.lang.StringBuilder
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.MalformedURLException
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.HashMap
import java.util.zip.GZIPInputStream

// stolen from https://github.com/RaghavJH/ddosguardbypass
class DdosGuardBypass(url: String?) {
    private var hg: HttpGet? = null
    private var proxyIp: String? = null
    private var proxyPort = 0
    var isBypassed = false
    private val url: URL = URL(url)

    fun bypass(): String? {
        hg = try {
            if (proxyIp != null) HttpGet(
                url.toString(), false,
                proxyIp,
                proxyPort
            ) else HttpGet(
                url.toString(),
                false
            )
        } catch (e1: MalformedURLException) {
            println("The URL you entered was not proper.")
            return null
        }
        // Send first get request
        hg!!.get()
        // Prepare POST request
        // Form parameters
        var h = encodeToString((url.protocol + "://" + url.host).toByteArray(), DEFAULT)
        var u = encodeToString("/".toByteArray(), DEFAULT)
        var p: String? = ""
        if (url.port != -1) p = encodeToString(
            url.port.toString().toByteArray(),
            DEFAULT
        )
        try {
            h = URLEncoder.encode(h, "UTF-8")
            u = URLEncoder.encode(u, "UTF-8")
            p = URLEncoder.encode(p, "UTF-8")
        } catch (e: UnsupportedEncodingException) {
            println("Internal error occured in bypass. (UTF-8 encoding not supported?)")
        }
        val postContent = String.format("u=%s&h=%s&p=%s", u, h, p)
        return try {
            val hp: HttpPost = if (proxyIp != null) {
                HttpPost(
                    url.protocol + "://ddgu.ddos-guard.net/ddgu/", postContent, false,
                    proxyIp,
                    proxyPort
                )
            } else {
                HttpPost(
                    url.protocol + "://ddgu.ddos-guard.net/ddgu/",
                    postContent,
                    false
                )
            }
            // Sleep 5 seconds
            try {
                Thread.sleep(5000)
            } catch (e: InterruptedException) {
                println("bypass was not given enough time to pause as thread was interrupted.")
            }
            // Get the redirect URL
            // hp.post(true) returns the HTML response, while hp.post(false) returns the location from a HTTP 301 code
            val redirUrl: String = hp.post(false)!!

            // The following request will require cookie storage, so use that.
            hg!!.setStoreCookies(true)
            try {
                hg!!.setUrl(redirUrl)
            } catch (e: NotSameHostException) {
                println(e.message)
            }
            hg!!.get()

            // AT THIS STAGE WE HAVE BYPASSED, return the cookie back to the user so they can use it
            isBypassed = true
            hg!!.cookiesAsString
        } catch (e: MalformedURLException) {
            println("The URL you entered was not proper.")
            null
        }
    }

    @Throws(
        MalformedURLException::class,
        NotSameHostException::class,
        NotYetBypassedException::class
    )
    operator fun get(page: String?): String {
        if (!isBypassed) throw NotYetBypassedException("You have to bypass before getting a page using the bypass() method.")
        hg!!.setUrl(page)
        return hg!!.get()!!
    }

    @get:Throws(NotYetBypassedException::class)
    val cookiesAsString: String
        get() {
            if (!isBypassed) throw NotYetBypassedException("You have to bypass before getting a page using the bypass() method.")
            return hg!!.cookiesAsString!!
        }
}

class NotYetBypassedException(exception: String?) : Exception(exception) {
    companion object {
        /**
         *
         */
        private const val serialVersionUID = 1L
    }
}

class NotSameHostException(exception: String?) : Exception(exception) {
    companion object {
        /**
         *
         */
        private const val serialVersionUID = 1L
    }
}

class HttpGet(private var url: String?, storeCookies: Boolean) {
    private val host: String = URL(url).host
    private var storeCookies: Boolean
    private var cookies: HashMap<String, String>? = null
    private var proxyIp: String? = null
    private var proxyPort = 0

    constructor(url: String?, storeCookies: Boolean, proxyIp: String?, proxyPort: Int) : this(
        url,
        storeCookies
    ) {
        this.proxyIp = proxyIp
        this.proxyPort = proxyPort
    }

    fun get(): String? {

        // Form URL
        val obj: URL = try {
            URL(url)
        } catch (e: MalformedURLException) {
            println("Bad URL in HttpGet")
            return null
        }
        var con: HttpURLConnection? = null
        // Open connection
        try {
            con = if (proxyIp != null) {
                val p = Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(
                        proxyIp,
                        proxyPort
                    )
                )
                obj.openConnection(p) as HttpURLConnection
            } else {
                obj.openConnection() as HttpURLConnection
            }
        } catch (e: IOException) {
            println("Could not establish connection to destination URL in HttpGet")
        }

        // If connection is open, proceed. Otherwise, return null.
        if (con != null) {
            try {
                // Set method as GET
                con.requestMethod = "GET"
                // 30s timeout for both connecting and reading.
                con.connectTimeout = 30000
                con.readTimeout = 30000
                con.instanceFollowRedirects = false
                // Set headers to represent a Chrome browser request
                setHeaders(con)
                // Return HTML content
                return readAndGetResponse(con, false)

                // Error is expected to occur due to 403, start receiving error stream.
            } catch (e: IOException) {
                try {
                    return readAndGetResponse(con, true)
                } catch (e1: IOException) {
                    println(e1.message)
                }
            }
        }
        // Return null. If there was valid content, it would have been returned in
        // return html.toString();
        return null
    }

    /*
	 *
	 * The error response is compressed using GZIP. It is originally Brotli, but the
	 * Accept-Encoding header has been altered to only accept gzip
	 *
	 */
    @Throws(IOException::class)
    fun readAndGetResponse(con: HttpURLConnection, errorStream: Boolean): String {
        BufferedReader(InputStreamReader(getValidStream(con, errorStream))).use { `in` ->
            // Read HTML line by line
            var inputLine: String?
            val html = StringBuilder()
            // Append htmlContent line by line
            while (`in`.readLine().also { inputLine = it } != null) {
                html.append(inputLine)
            }

            // Store cookies, if required.
            if (storeCookies) {
                val rp =
                    con.headerFields
                for (key in rp.keys) {
                    // If the header type is set cookie, set the cookie
                    if (key != null && key == "Set-Cookie") {
                        for (s in rp[key]!!) {
                            // Split at ';'
                            var content =
                                s.split(";".toRegex()).toTypedArray()
                            // Then, split the first bit at "=" to get key, value
                            content = content[0].split("=".toRegex()).toTypedArray()
                            // Set in hashmap
                            cookies!![content[0]] = content[1]
                        }
                    }
                }
            }
            return html.toString()
        }
    }

    private fun setHeaders(con: HttpURLConnection) {
        con.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8"
        )
        con.setRequestProperty("Accept-Encoding", "gzip, deflate")
        con.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        con.setRequestProperty("Cache-Control", "no-cache")
        con.setRequestProperty("Connection", "keep-alive")
        con.setRequestProperty("Pragma", "no-cache")
        con.setRequestProperty("Upgrade-Insecure-Requests", "1")
        con.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"
        )
        var cookies: String?
        if (cookiesAsString.also { cookies = it } != null) con.setRequestProperty("Cookie", cookies)
    }

    @Throws(MalformedURLException::class, NotSameHostException::class)
    fun setUrl(url: String?) {
        // Cannot change host.
        if (URL(url).host == host) {
            this.url = url
        } else {
            throw NotSameHostException("Cannot change the host in HttpGet class.")
        }
    }

    @Throws(IOException::class)
    private fun getValidStream(con: HttpURLConnection, error: Boolean): InputStream {
        return if (con.getHeaderField("content-encoding") != null && con.getHeaderField("content-encoding") == "gzip") {
            if (error) {
                GZIPInputStream(con.errorStream)
            } else {
                GZIPInputStream(con.inputStream)
            }
        } else {
            if (error) {
                con.errorStream
            } else {
                con.inputStream
            }
        }
    }

    fun setStoreCookies(storeCookies: Boolean) {
        this.storeCookies = storeCookies
        if (this.storeCookies) cookies = HashMap()
    }

    // Build cookies
    val cookiesAsString: String
    // Remove last character which is ';' as the last cookie does not have a ';'
    // Set the cookie property
    ?
        get() {
            if (storeCookies && cookies!!.isNotEmpty()) {
                // Build cookies
                val s = StringBuilder()
                for (key in cookies!!.keys) {
                    s.append(key + "=" + cookies!![key] + "; ")
                }
                var finalCookies = s.toString()
                // Remove last character which is ';' as the last cookie does not have a ';'
                finalCookies = finalCookies.substring(0, finalCookies.length - 2)
                // Set the cookie property
                return finalCookies
            }
            return null
        }

    init {
        this.storeCookies = storeCookies
        if (this.storeCookies) cookies = HashMap()
    }
}

open class HttpPost(
    private var url: String?,
    private var postContent: String,
    private val storeCookies: Boolean
) {
    private var cookies: HashMap<String, String>? = null
    private var proxyIp: String? = null
    private var proxyPort = 0

    constructor(
        url: String?,
        postContent: String,
        storeCookies: Boolean,
        proxyIp: String?,
        proxyPort: Int
    ) : this(url, postContent, storeCookies) {
        this.proxyIp = proxyIp
        this.proxyPort = proxyPort
    }

    /*
	 *
	 *
	 *
	 */
    fun post(getResponseString: Boolean): String? {

        // Form URL
        val obj: URL = try {
            URL(url)
        } catch (e: MalformedURLException) {
            println("Bad URL in HttpPost")
            return null
        }
        var con: HttpURLConnection? = null
        // Open connection
        try {
            // Only use proxy if its set.
            con = if (proxyIp != null) {
                val p = Proxy(
                    Proxy.Type.HTTP,
                    InetSocketAddress(
                        proxyIp,
                        proxyPort
                    )
                )
                obj.openConnection(p) as HttpURLConnection
            } else {
                obj.openConnection() as HttpURLConnection
            }
        } catch (e: IOException) {
            println("Could not establish connection to destination URL in HttpPost")
        }

        // If connection is open, proceed. Otherwise, return null.
        if (con != null) {
            try {
                // Set method as POST
                con.requestMethod = "POST"
                // 30s timeout for both connecting and reading.
                con.connectTimeout = 30000
                con.readTimeout = 30000
                // Get output too.
                con.doOutput = true
                // Don't redirect!
                con.instanceFollowRedirects = false
                // Set headers to represent a Chrome browser request
                setHeaders(con)

                // Write POST content
                try {
                    DataOutputStream(con.outputStream).use { out -> out.writeBytes(postContent) }
                } catch (e: IOException) {
                    println("Failed to write POST content in HttpPost")
                }
                // Receive HTML response
                return if (getResponseString) {
                    readAndGetResponse(con)
                } else {
                    con.getHeaderField("location")
                }
            } catch (e: IOException) {
                println(e.message)
            }
        }

        // Return null. If there was valid content, it would have been returned in
        // return html.toString();
        return null
    }

    /*
	 *
	 * The error response is compressed using GZIP. It is originally Brotli, but the
	 * Accept-Encoding header has been altered to only accept gzip
	 *
	 */
    @Throws(IOException::class)
    fun readAndGetResponse(con: HttpURLConnection): String {
        BufferedReader(InputStreamReader(getValidStream(con))).use { `in` ->
            // Read HTML line by line
            var inputLine: String?
            val html = StringBuilder()
            // Append htmlContent line by line
            while (`in`.readLine().also { inputLine = it } != null) {
                html.append(inputLine)
            }

            // Store cookies, if required.
            if (storeCookies) {
                val rp =
                    con.headerFields
                for (key in rp.keys) {
                    // If the header type is set cookie, set the cookie
                    if (key != null && key == "Set-Cookie") {
                        for (s in rp[key]!!) {
                            // Split at ';'
                            var content =
                                s.split(";".toRegex()).toTypedArray()
                            // Then, split the first bit at "=" to get key, value
                            content = content[0].split("=".toRegex()).toTypedArray()
                            // Set in hashmap
                            cookies!![content[0]] = content[1]
                        }
                    }
                }
            }
            return html.toString()
        }
    }

    private fun setHeaders(con: HttpURLConnection) {
        con.setRequestProperty(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8"
        )
        con.setRequestProperty("Accept-Encoding", "gzip, deflate")
        con.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        con.setRequestProperty("Cache-Control", "no-cache")
        con.setRequestProperty("Connection", "keep-alive")
        con.setRequestProperty("Content-Length", postContent.toByteArray().size.toString())
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        con.setRequestProperty("Pragma", "no-cache")
        con.setRequestProperty("Upgrade-Insecure-Requests", "1")
        con.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/71.0.3578.98 Safari/537.36"
        )
        var cookies: String?
        if (cookiesAsString.also { cookies = it } != null) con.setRequestProperty("Cookie", cookies)
    }

    @Throws(IOException::class)
    private fun getValidStream(con: HttpURLConnection): InputStream {
        return if (con.getHeaderField("content-encoding") != null && con.getHeaderField("content-encoding") == "gzip") {
            GZIPInputStream(con.inputStream)
        } else {
            con.inputStream
        }
    }

    // Build cookies
    private val cookiesAsString: String
    // Remove last character which is ';' as the last cookie does not have a ';'
    // Set the cookie property
    ?
        get() {
            if (storeCookies && cookies!!.isNotEmpty()) {
                // Build cookies
                val s = StringBuilder()
                for (key in cookies!!.keys) {
                    s.append(key + "=" + cookies!![key] + "; ")
                }
                var finalCookies = s.toString()
                // Remove last character which is ';' as the last cookie does not have a ';'
                finalCookies = finalCookies.substring(0, finalCookies.length - 2)
                // Set the cookie property
                return finalCookies
            }
            return null
        }

    init {
        if (this.storeCookies) cookies = HashMap()
    }
}
