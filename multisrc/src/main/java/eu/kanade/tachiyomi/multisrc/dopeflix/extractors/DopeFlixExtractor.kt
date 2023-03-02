package eu.kanade.tachiyomi.multisrc.dopeflix.extractors

import eu.kanade.tachiyomi.multisrc.dopeflix.utils.Decryptor
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class DopeFlixExtractor(private val client: OkHttpClient) {
    companion object {
        private const val SOURCES_PATH = "/ajax/embed-4/getSources?id="
    }

    fun getSourcesJson(url: String): String? {
        val id = url.substringAfter("/embed-4/", "")
            .substringBefore("?", "").ifEmpty { return null }
        val serverUrl = url.substringBefore("/embed")
        val srcRes = client.newCall(
            GET(
                serverUrl + SOURCES_PATH + id,
                headers = Headers.headersOf("x-requested-with", "XMLHttpRequest"),
            ),
        )
            .execute()
            .body.string()

        val key = client.newCall(GET("https://raw.githubusercontent.com/enimax-anime/key/e4/key.txt"))
            .execute()
            .body.string()
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
