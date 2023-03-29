package eu.kanade.tachiyomi.animeextension.id.kuronime.extractors

import app.cash.quickjs.QuickJs
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

@Serializable
data class Source(
    val file: String,
    val label: String,
)

class AnimekuExtractor(private val client: OkHttpClient) {

    private val json: Json by injectLazy()

    fun getVideosFromUrl(url: String, name: String): List<Video> {
        val document = client.newCall(GET(url)).execute().asJsoup()
        val script = document.selectFirst("script:containsData(decodeURIComponent)") ?: return emptyList()

        val quickJs = QuickJs.create()
        val decryped = quickJs.evaluate(
            script.data().trim().split("\n")[0].replace("eval(function", "function a").replace("decodeURIComponent(escape(r))}(", "r};a(").substringBeforeLast(")"),
        ).toString()
        quickJs.close()

        val srcs = json.decodeFromString<List<Source>>(decryped.substringAfter("var srcs = ").substringBefore(";"))
        return srcs.map { src ->
            Video(
                src.file,
                "${src.label} - $name",
                src.file,
            )
        }
    }
}
