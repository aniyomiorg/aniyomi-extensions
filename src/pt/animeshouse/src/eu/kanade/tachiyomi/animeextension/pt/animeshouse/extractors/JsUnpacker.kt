package eu.kanade.tachiyomi.animeextension.pt.animeshouse.extractors

object JsUnpacker {

    private val REGEX_REPLACE = "\\b\\w+\\b".toRegex()
    private val REGEX_EVAL = """\}\('(.*)',(\d+),(\d+),'(.*)'\.split""".toRegex()

    private fun hasPacker(js: String): Boolean = REGEX_EVAL.containsMatchIn(js)

    private fun getPackerArgs(js: String): List<String> = REGEX_EVAL.findAll(js)
        .last().groupValues

    private fun convert(base: Int, num: Int): String {
        val firstPart = if (num < base) "" else (num / base).toString()
        val calc = num % base
        if (calc > 35)
            return firstPart + (calc + 29).toChar().toString()
        return firstPart + calc.toString(36)
    }

    fun unpack(js: String): String {
        if (!hasPacker(js)) return js
        val args = getPackerArgs(js)
        val origJS = args[1]
        val base = args[2].toInt()
        val count = args[3].toInt()
        val origList = args[4].split("|")

        val replaceMap = (0..(count - 1)).map {
            val key = convert(base, it)
            key to try { origList[it] } catch (e: Exception) { key }
        }.toMap()

        val result = origJS.replace(REGEX_REPLACE) {
            replaceMap.get(it.value) ?: it.value
        }.replace("\\", "")
        return unpack(result)
    }
}
