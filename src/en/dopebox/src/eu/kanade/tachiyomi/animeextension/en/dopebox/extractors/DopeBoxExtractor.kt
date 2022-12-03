package eu.kanade.tachiyomi.animeextension.en.dopebox.extractors

import eu.kanade.tachiyomi.animeextension.en.dopebox.utils.Decryptor
import eu.kanade.tachiyomi.network.GET
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient

class DopeBoxExtractor(private val client: OkHttpClient) {

    // Prevent (automatic) caching the .JS file for different episodes, because it 
    // changes everytime, and a cached old .js will have a invalid AES password,
    // invalidating the decryption algorithm.
    // We cache it manually when initializing the class.
    private val cacheControl = CacheControl.Builder().noStore().build()
    private val newClient = client.newBuilder()
        .cache(null)
        .build()

    companion object {
        // its the same .js file for any server it may use, 
        // so we choose rabbitstream arbitrarily
        private const val JS_URL = "https://rabbitstream.net/js/player/prod/e4-player.min.js"
        // unlike the case of the .js file, here it is not possible to 
        // simply use the same host.
        private const val SOURCES_PATH = "/ajax/embed-4/getSources?id="
    }

    // This will create a lag of 1~3s at the initialization of the class, but the 
    // speedup of the manual cache will be worth it.
    private val cachedJs by lazy {
        newClient.newCall(GET(JS_URL, cache = cacheControl)).execute()
            .body!!.string()
    }
    init { cachedJs }

    fun getSourcesJson(url: String): String? {
        val id = url.substringAfter("/embed-4/", "")
            .substringBefore("?", "").ifEmpty { return null }
        val serverUrl = url.substringBefore("/embed")
        val srcRes = newClient.newCall(
            GET(
                serverUrl + SOURCES_PATH + id,
                headers = Headers.headersOf("x-requested-with", "XMLHttpRequest"),
                cache = cacheControl
            )
        )
            .execute()
            .body!!.string()

        val key = newClient.newCall(GET("https://raw.githubusercontent.com/consumet/rapidclown/rabbitstream/key.txt"))
            .execute()
            .body!!.string()
        // encrypted data will start with "U2Fsd..." because they put
        // "Salted__" at the start of encrypted data, thanks openssl
        // if its not encrypted, then return it
        if ("\"sources\":\"U2FsdGVk" !in srcRes) return srcRes
        if (!srcRes.contains("{\"sources\":")) return null
        val encrypted = srcRes.substringAfter("sources\":\"").substringBefore("\"")
        val decrypted = Decryptor.decrypt(encrypted, key) ?: return null
        val end = srcRes.replace("\"$encrypted\"", decrypted)
        return end
    }
}
