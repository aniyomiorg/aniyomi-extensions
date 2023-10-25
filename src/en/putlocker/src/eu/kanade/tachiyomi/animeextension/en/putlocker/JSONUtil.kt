package eu.kanade.tachiyomi.animeextension.en.putlocker

object JSONUtil {
    fun escape(input: String): String {
        val output = StringBuilder()

        for (ch in input) {
            // let's not put any nulls in our strings
            val charInt = ch.code
            assert(charInt != 0)

            // 0x10000 = 65536 = 2^16 = u16 max value
            assert(charInt < 0x10000) { "Java stores as u16, so it should never give us a character that's bigger than 2 bytes. It literally can't." }

            val escapedChar = when (ch) {
                '\b' -> "\\b"
                '\u000C' -> "\\f" // '\u000C' == '\f', Kotlin doesnt support \f
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                '\\' -> "\\\\"
                '"' -> "\\\""
                else -> {
                    if (charInt > 127) {
                        String.format("\\u%04x", charInt)
                    } else {
                        ch
                    }
                }
            }
            output.append(escapedChar)
        }
        return output.toString()
    }

    fun unescape(input: String): String {
        val builder = StringBuilder()

        var index = 0
        while (index < input.length) {
            val delimiter = input.get(index) // consume letter or backslash
            index++

            if (delimiter == '\\' && index < input.length) {
                // consume first after backslash
                val ch = input.get(index)
                index++

                val unescaped = when (ch) {
                    '\\', '/', '"', '\'' -> ch // "
                    'b' -> '\b'
                    'f' -> '\u000C' // '\f' in java
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        if (index + 4 > input.length) {
                            throw RuntimeException("Not enough unicode digits!")
                        }
                        val hex = input.substring(index, index + 4)
                        if (hex.any { !it.isLetterOrDigit() }) {
                            throw RuntimeException("Bad character in unicode escape.")
                        }
                        hex.toInt(16).toChar()
                    }
                    else -> throw RuntimeException("Illegal escape sequence: \\" + ch)
                }
                builder.append(unescaped)
            } else {
                builder.append(delimiter)
            }
        }

        return builder.toString()
    }
}
