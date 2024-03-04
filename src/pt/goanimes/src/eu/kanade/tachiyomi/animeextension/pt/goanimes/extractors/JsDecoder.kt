package eu.kanade.tachiyomi.animeextension.pt.goanimes.extractors

import android.util.Base64
import kotlin.math.pow

object JsDecoder {
    private fun convertToNum(thing: String, limit: Float): Int {
        return thing.split("")
            .reversed()
            .map { it.toIntOrNull() ?: 0 }
            .reduceIndexed { index: Int, acc, num ->
                acc + (num * limit.pow(index - 1)).toInt()
            }
    }

    fun decodeScript(encodedString: String, magicStr: String, offset: Int, limit: Int): String {
        val regex = "\\w".toRegex()
        return encodedString
            .split(magicStr[limit])
            .dropLast(1)
            .map { str ->
                val replaced = regex.replace(str) { magicStr.indexOf(it.value).toString() }
                val charInt = convertToNum(replaced, limit.toFloat()) - offset
                Char(charInt)
            }.joinToString("")
    }

    fun decodeScript(html: String, isB64: Boolean = true): String {
        val script = if (isB64) {
            html.substringAfter(";base64,")
                .substringBefore('"')
                .let { String(Base64.decode(it, Base64.DEFAULT)) }
        } else {
            html
        }

        val regex = """\}\("(\w+)",.*?"(\w+)",(\d+),(\d+),.*?\)""".toRegex()
        return regex.find(script)
            ?.run {
                decodeScript(
                    groupValues[1], // encoded data
                    groupValues[2], // magic string
                    groupValues[3].toIntOrNull() ?: 0, // offset
                    groupValues[4].toIntOrNull() ?: 0, // limit
                )
            } ?: ""
    }
}
