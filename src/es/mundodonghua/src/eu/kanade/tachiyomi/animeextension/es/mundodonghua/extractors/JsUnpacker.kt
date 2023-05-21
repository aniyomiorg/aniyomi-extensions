package eu.kanade.tachiyomi.animeextension.es.mundodonghua.extractors

import kotlin.math.pow

object JsUnpacker {

    /**
     * Regex to detect packed functions.
     */
    private val PACKED_REGEX = Regex("eval[(]function[(]p,a,c,k,e,[r|d]?", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * Regex to get and group the packed javascript.
     * Needed to get information and unpack the code.
     */
    private val PACKED_EXTRACT_REGEX = Regex("[}][(]'(.*)', *(\\d+), *(\\d+), *'(.*?)'[.]split[(]'[|]'[)]", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * Matches function names and variables to de-obfuscate the code.
     */
    private val UNPACK_REPLACE_REGEX = Regex("\\b\\w+\\b", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * Check if script is packed.
     *
     * @param scriptBlock the String to check if it is packed.
     *
     * @return whether the [scriptBlock] contains packed code or not.
     */
    fun detect(scriptBlock: String): Boolean {
        return scriptBlock.contains(PACKED_REGEX)
    }

    /**
     * Check if scripts are packed.
     *
     * @param scriptBlock (multiple) String(s) to check if it is packed.
     *
     * @return the packed scripts passed in [scriptBlock].
     */
    fun detect(vararg scriptBlock: String): List<String> {
        return scriptBlock.mapNotNull {
            if (it.contains(PACKED_REGEX)) {
                it
            } else {
                null
            }
        }
    }

    /**
     * Check if scripts are packed.
     *
     * @param scriptBlocks multiple Strings to check if it is packed.
     *
     * @return the packed scripts passed in [scriptBlocks].
     */
    fun detect(scriptBlocks: Collection<String>): List<String> {
        return detect(*scriptBlocks.toTypedArray())
    }

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return unpacked code in a list or an empty list if non is packed.
     */
    fun unpack(scriptBlock: String): Sequence<String> {
        return if (!detect(scriptBlock)) {
            emptySequence()
        } else {
            unpacking(scriptBlock)
        }
    }

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and combines them into a single String.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return unpacked code in a list combined by a whitespace to a single String.
     */
    fun unpackAndCombine(scriptBlock: String): String? {
        val unpacked = unpack(scriptBlock)
        return if (unpacked.toList().isEmpty()) {
            null
        } else {
            unpacked.joinToString(" ")
        }
    }

    /**
     * Unpack the passed [scriptBlock].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlock (multiple) String(s) to unpack.
     *
     * @return unpacked code in a flat list or an empty list if non is packed.
     */
    fun unpack(vararg scriptBlock: String): List<String> {
        val packedScripts = detect(*scriptBlock)
        return packedScripts.flatMap {
            unpacking(it)
        }
    }

    /**
     * Unpack the passed [scriptBlocks].
     * It matches all found occurrences and returns them as separate Strings in a list.
     *
     * @param scriptBlocks multiple Strings to unpack.
     *
     * @return unpacked code in a flat list or an empty list if non is packed.
     */
    fun unpack(scriptBlocks: Collection<String>): List<String> {
        return unpack(*scriptBlocks.toTypedArray())
    }

    /**
     * Unpacking functionality.
     * Match all found occurrences, get the information group and unbase it.
     * If found symtabs are more or less than the count provided in code, the occurrence will be ignored
     * because it cannot be unpacked correctly.
     *
     * @param scriptBlock the String to unpack.
     *
     * @return a list of all unpacked code from all found packed and unpackable occurrences found.
     */
    private fun unpacking(scriptBlock: String): Sequence<String> {
        val unpacked = PACKED_EXTRACT_REGEX.findAll(scriptBlock).mapNotNull { result ->

            val payload = result.groups[1]?.value
            val symtab = result.groups[4]?.value?.split('|')
            val radix = result.groups[2]?.value?.toIntOrNull() ?: 10
            val count = result.groups[3]?.value?.toIntOrNull()
            val unbaser = Unbaser(radix)

            if (symtab == null || count == null || symtab.size != count) {
                null
            } else {
                payload?.replace(UNPACK_REPLACE_REGEX) { match ->
                    val word = match.value
                    val unbased = symtab[unbaser.unbase(word)]
                    unbased.ifEmpty {
                        word
                    }
                }
            }
        }
        return unpacked
    }

    internal data class Unbaser(
        private val base: Int,
    ) {
        private val selector: Int = when {
            base > 62 -> 95
            base > 54 -> 62
            base > 52 -> 54
            else -> 52
        }

        fun unbase(value: String): Int {
            return if (base in 2..36) {
                value.toIntOrNull(base) ?: 0
            } else {
                val dict = ALPHABET[selector]?.toCharArray()?.mapIndexed { index, c ->
                    c to index
                }?.toMap()
                var returnVal = 0

                val valArray = value.toCharArray().reversed()
                for (i in valArray.indices) {
                    val cipher = valArray[i]
                    returnVal += (base.toFloat().pow(i) * (dict?.get(cipher) ?: 0)).toInt()
                }
                returnVal
            }
        }

        companion object {
            private val ALPHABET = mapOf<Int, String>(
                52 to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOP",
                54 to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQR",
                62 to "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
                95 to " !\"#\$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~",
            )
        }
    }
}
