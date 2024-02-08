package eu.kanade.tachiyomi.animeextension.pt.animesdigital.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import okhttp3.Headers

object ScriptExtractor {
    fun videosFromScript(scriptData: String, headers: Headers): List<Video> {
        val script = when {
            "eval(function" in scriptData -> Unpacker.unpack(scriptData)
            else -> scriptData
        }.ifEmpty { null }?.replace("\\", "") ?: return emptyList()

        return script.substringAfter("sources:").substringAfter(".src(")
            .substringBefore(")")
            .substringAfter("[")
            .substringBefore("]")
            .split("{")
            .drop(1)
            .map {
                val quality = it.substringAfter("label", "")
                    .substringAfterKey()
                    .trim()
                    .ifEmpty { "Animes Digital" }
                val url = it.substringAfter("file").substringAfter("src")
                    .substringAfterKey()
                    .trim()
                Video(url, quality, url, headers)
            }
    }

    private fun String.substringAfterKey() = substringAfter(':')
        .substringAfter('"')
        .substringBefore('"')
        .substringAfter("'")
        .substringBefore("'")
}
