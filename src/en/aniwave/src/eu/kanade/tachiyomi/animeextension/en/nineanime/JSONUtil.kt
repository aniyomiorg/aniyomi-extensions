package eu.kanade.tachiyomi.animeextension.en.nineanime

object JSONUtil {
    fun escape(input: String): String {
        val output = StringBuilder()
        for (i in 0 until input.length) {
            val ch = input[i]
            val chx = ch.code
            assert(chx != 0)
            if (ch == '\n') {
                output.append("\\n")
            } else if (ch == '\t') {
                output.append("\\t")
            } else if (ch == '\r') {
                output.append("\\r")
            } else if (ch == '\\') {
                output.append("\\\\")
            } else if (ch == '"') {
                output.append("\\\"")
            } else if (ch == '\b') {
                output.append("\\b")
            } else if (ch == '\u000c') {
                output.append("\\u000c")
            } else if (chx >= 0x10000) {
                assert(false) { "Java stores as u16, so it should never give us a character that's bigger than 2 bytes. It literally can't." }
            } else if (chx > 127) {
                output.append(String.format("\\u%04x", chx))
            } else {
                output.append(ch)
            }
        }
        return output.toString()
    }

    fun unescape(input: String): String {
        val builder = StringBuilder()
        var i = 0
        while (i < input.length) {
            val delimiter = input[i]
            i++ // consume letter or backslash
            if (delimiter == '\\' && i < input.length) {
                // consume first after backslash
                val ch = input[i]
                i++
                if (ch == '\\' || ch == '/' || ch == '"' || ch == '\'') {
                    builder.append(ch)
                } else if (ch == 'n') {
                    builder.append('\n')
                } else if (ch == 'r') {
                    builder.append('\r')
                } else if (ch == 't') {
                    builder.append(
                        '\t',
                    )
                } else if (ch == 'b') {
                    builder.append('\b')
                } else if (ch == 'f') {
                    builder.append(
                        '\u000c',
                    )
                } else if (ch == 'u') {
                    val hex = StringBuilder()

                    // expect 4 digits
                    if (i + 4 > input.length) {
                        throw RuntimeException("Not enough unicode digits! ")
                    }
                    for (x in input.substring(i, i + 4).toCharArray()) {
                        if (!Character.isLetterOrDigit(x)) {
                            throw RuntimeException("Bad character in unicode escape.")
                        }
                        hex.append(x.lowercaseChar())
                    }
                    i += 4 // consume those four digits.
                    val code = hex.toString().toInt(16)
                    builder.append(code.toChar())
                } else {
                    throw RuntimeException("Illegal escape sequence: \\$ch")
                }
            } else { // it's not a backslash, or it's the last character.
                builder.append(delimiter)
            }
        }
        return builder.toString()
    }
}
