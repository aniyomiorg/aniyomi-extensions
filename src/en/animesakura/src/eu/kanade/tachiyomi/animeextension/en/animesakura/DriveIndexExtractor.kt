package eu.kanade.tachiyomi.animeextension.en.animesakura

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.network.POST
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder

class DriveIndexExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val json: Json by injectLazy()

    fun getEpisodesFromIndex(
        indexUrl: String,
        path: String,
        trimName: Boolean,
    ): List<SEpisode> {
        val episodeList = mutableListOf<SEpisode>()

        val basePathCounter = indexUrl.toHttpUrl().pathSegments.size

        var counter = 1

        fun traverseDirectory(url: String) {
            var newToken: String? = ""
            var newPageIndex = 0

            while (newToken != null) {
                val popHeaders = headers.newBuilder()
                    .add("Accept", "*/*")
                    .add("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .add("Host", url.toHttpUrl().host)
                    .add("Origin", "https://${url.toHttpUrl().host}")
                    .add("Referer", URLEncoder.encode(url, "UTF-8"))
                    .add("X-Requested-With", "XMLHttpRequest")
                    .build()

                val popBody = "password=&page_token=$newToken&page_index=$newPageIndex".toRequestBody("application/x-www-form-urlencoded".toMediaType())

                val parsedBody = client.newCall(
                    POST(url, body = popBody, headers = popHeaders),
                ).execute().body.string().decrypt()
                val parsed = json.decodeFromString<ResponseData>(parsedBody)

                parsed.data.files.forEach { item ->
                    if (item.mimeType.endsWith("folder")) {
                        val newUrl = joinUrl(url, item.name).addSuffix("/")
                        traverseDirectory(newUrl)
                    }
                    if (item.mimeType.startsWith("video/")) {
                        val epUrl = joinUrl(url, item.name)
                        val paths = epUrl.toHttpUrl().pathSegments

                        // Get other info
                        val season = if (paths.size == basePathCounter) {
                            ""
                        } else {
                            paths[basePathCounter - 1]
                        }
                        val seasonInfoRegex = """(\([\s\w-]+\))(?: ?\[[\s\w-]+\])?${'$'}""".toRegex()
                        val seasonInfo = if (seasonInfoRegex.containsMatchIn(season)) {
                            "${seasonInfoRegex.find(season)!!.groups[1]!!.value} • "
                        } else {
                            ""
                        }
                        val extraInfo = if (paths.size > basePathCounter) {
                            "/$path/" + paths.subList(basePathCounter - 1, paths.size - 1).joinToString("/") { it.trimInfo() }
                        } else {
                            "/$path"
                        }
                        val size = item.size?.toLongOrNull()?.let { formatFileSize(it) }

                        episodeList.add(
                            SEpisode.create().apply {
                                name = if (trimName) item.name.trimInfo() else item.name
                                this.url = epUrl
                                scanlator = "${if (size == null) "" else "$size"} • $seasonInfo$extraInfo"
                                date_upload = -1L
                                episode_number = counter.toFloat()
                            },
                        )
                        counter++
                    }
                }

                newToken = parsed.nextPageToken
                newPageIndex += 1
            }
        }

        traverseDirectory(indexUrl)

        return episodeList
    }

    @Serializable
    data class ResponseData(
        val nextPageToken: String? = null,
        val data: DataObject,
    ) {
        @Serializable
        data class DataObject(
            val files: List<FileObject>,
        ) {
            @Serializable
            data class FileObject(
                val mimeType: String,
                val id: String,
                val name: String,
                val modifiedTime: String? = null,
                val size: String? = null,
            )
        }
    }

    private fun String.trimInfo(): String {
        var newString = this.replaceFirst("""^\[\w+\] ?""".toRegex(), "")
        val regex = """( ?\[[\s\w-]+\]| ?\([\s\w-]+\))(\.mkv|\.mp4|\.avi)?${'$'}""".toRegex()

        while (regex.containsMatchIn(newString)) {
            newString = regex.replace(newString) { matchResult ->
                matchResult.groups[2]?.value ?: ""
            }
        }

        return newString.trim()
    }

    private fun String.addSuffix(suffix: String): String {
        return if (this.endsWith(suffix)) {
            this
        } else {
            this.plus(suffix)
        }
    }

    private fun String.decrypt(): String {
        return Base64.decode(this.reversed().substring(24, this.length - 20), Base64.DEFAULT).toString(Charsets.UTF_8)
    }

    private fun joinUrl(path1: String, path2: String): String {
        return path1.removeSuffix("/") + "/" + path2.removePrefix("/")
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1073741824 -> "%.2f GB".format(bytes / 1073741824.0)
            bytes >= 1048576 -> "%.2f MB".format(bytes / 1048576.0)
            bytes >= 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes > 1 -> "$bytes bytes"
            bytes == 1L -> "$bytes byte"
            else -> ""
        }
    }
}
