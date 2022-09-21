package eu.kanade.tachiyomi.animeextension.en.zoro.extractors

import eu.kanade.tachiyomi.animeextension.en.zoro.utils.Decryptor
import eu.kanade.tachiyomi.network.GET
import okhttp3.OkHttpClient

class ZoroExtractor(private val client: OkHttpClient) {

    companion object {
        private const val SERVER_URL = "https://rapid-cloud.co"
        private const val JS_URL = SERVER_URL + "/js/player/prod/e6-player.min.js"
        private const val SOURCES_URL = SERVER_URL + "/ajax/embed-6/getSources?id="
    }

    fun getSourcesJson(url: String): String? {
        val js = client.newCall(GET(JS_URL)).execute().body!!.string()
        val id = url.substringAfter("/embed-6/", "")
            .substringBefore("?", "").ifEmpty { return null }
        val srcRes = client.newCall(GET(SOURCES_URL + id)).execute().body!!.string()
        if ("\"encrypted\":false" in srcRes) return srcRes
        val encrypted = srcRes.substringAfter("sources\":\"").substringBefore("\"")
        val decrypted = Decryptor.decrypt(encrypted, js) ?: return null
        val end = srcRes.replace("\"$encrypted\"", decrypted)
        return end
    }
}
