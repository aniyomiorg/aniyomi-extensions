package eu.kanade.tachiyomi.animeextension.en.zoro.extractors

import eu.kanade.tachiyomi.lib.cryptoaes.CryptoAES
import eu.kanade.tachiyomi.network.GET
import okhttp3.CacheControl
import okhttp3.OkHttpClient

class AniWatchExtractor(private val client: OkHttpClient) {

    // Prevent (automatic) caching the .JS file for different episodes, because it
    // changes everytime, and a cached old .js will have a invalid AES password,
    // invalidating the decryption algorithm.
    // We cache it manually when initializing the class.
    private val cacheControl = CacheControl.Builder().noStore().build()
    private val newClient = client.newBuilder()
        .cache(null)
        .build()

    companion object {
        private val SERVER_URL = arrayOf("https://megacloud.tv", "https://rapid-cloud.co")
        private val SOURCES_URL = arrayOf("/embed-2/ajax/e-1/getSources?id=", "/ajax/embed-6/getSources?id=")
        private val SOURCES_SPLITTER = arrayOf("/e-1/", "/embed-6/")
        private val SOURCES_KEY = arrayOf("6", "0")
    }

    fun getSourcesJson(url: String): String? {
        val type = if (url.startsWith("https://megacloud.tv")) 0 else 1
        val keyType = SOURCES_KEY[type]

        val id = url.substringAfter(SOURCES_SPLITTER[type], "")
            .substringBefore("?", "").ifEmpty { return null }
        val srcRes = newClient.newCall(GET(SERVER_URL[type] + SOURCES_URL[type] + id, cache = cacheControl))
            .execute()
            .body.string()

        val key = newClient.newCall(GET("https://raw.githubusercontent.com/enimax-anime/key/e$keyType/key.txt"))
            .execute()
            .body.string()

        if ("\"encrypted\":false" in srcRes) return srcRes
        if (!srcRes.contains("{\"sources\":")) return null
        val encrypted = srcRes.substringAfter("sources\":\"").substringBefore("\"")
        val decrypted = CryptoAES.decrypt(encrypted, key).ifEmpty { return null }
        val end = srcRes.replace("\"$encrypted\"", decrypted)
        return end
    }
}
