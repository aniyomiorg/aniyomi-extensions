package eu.kanade.tachiyomi.animeextension.en.zoro.extractors

import eu.kanade.tachiyomi.animeextension.en.zoro.utils.Decryptor
import eu.kanade.tachiyomi.network.GET
import okhttp3.CacheControl
import okhttp3.OkHttpClient

class ZoroExtractor(private val client: OkHttpClient) {

    // Prevent (automatic) caching the .JS file for different episodes, because it 
    // changes everytime, and a cached old .js will have a invalid AES password,
    // invalidating the decryption algorithm.
    // We cache it manually when initializing the class.
    private val cacheControl = CacheControl.Builder().noStore().build()
    private val newClient = client.newBuilder()
        .cache(null)
        .build()

    companion object {
        private const val SERVER_URL = "https://rapid-cloud.co"
        private const val JS_URL = SERVER_URL + "/js/player/prod/e6-player.min.js"
        private const val SOURCES_URL = SERVER_URL + "/ajax/embed-6/getSources?id="
    }

    // This will create a lag of 1~3s at the initialization of the class, but the 
    // speedup of the manual cache will be worth it.
    private val cachedJs by lazy {
        newClient.newCall(GET(JS_URL, cache = cacheControl)).execute()
            .body!!.string()
    }
    init { cachedJs }

    fun getSourcesJson(url: String): String? {
        val id = url.substringAfter("/embed-6/", "")
            .substringBefore("?", "").ifEmpty { return null }
        val srcRes = newClient.newCall(GET(SOURCES_URL + id, cache = cacheControl))
            .execute()
            .body!!.string()

        val key = newClient.newCall(GET("https://raw.githubusercontent.com/consumet/rapidclown/main/key.txt"))
            .execute()
            .body!!.string()

        if ("\"encrypted\":false" in srcRes) return srcRes
        if (!srcRes.contains("{\"sources\":")) return null
        val encrypted = srcRes.substringAfter("sources\":\"").substringBefore("\"")
        val decrypted = Decryptor.decrypt(encrypted, key) ?: return null
        val end = srcRes.replace("\"$encrypted\"", decrypted)
        return end
    }
}
