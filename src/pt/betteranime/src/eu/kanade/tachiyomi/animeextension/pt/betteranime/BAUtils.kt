package eu.kanade.tachiyomi.animeextension.pt.betteranime

// Terrible way to reinvent the wheel, i just didnt wanted to use apache commons.
fun String.unescape(): String {
    return UNICODE_REGEX.replace(this) {
        it.groupValues[1]
            .toInt(16)
            .toChar()
            .toString()
    }.replace("\\", "")
}
private val UNICODE_REGEX = "\\\\u(\\d+)".toRegex()
