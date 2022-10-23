package eu.kanade.tachiyomi.animeextension.en.zoro.utils

import app.cash.quickjs.QuickJs
object FindPassword {

    fun getPassword(js: String): String {
        val passVar = js.substringAfter("CryptoJS[")
            .substringBefore("JSON")
            .substringBeforeLast(")")
            .substringAfterLast(",")
            .trim()

        val passValue = js.substringAfter("const $passVar=", "").substringBefore(";", "")
        if (passValue.isNotBlank()) {
            if (passValue.startsWith("'"))
                return passValue.trim('\'')
            return getPasswordFromJS(js, "(" + passValue.substringAfter("("))
        }
        val jsEnd = js.substringBefore("jwplayer(").substringBeforeLast("var")
        val suspiciousPass = jsEnd.substringBeforeLast("'").substringAfterLast("'")
        if (suspiciousPass.length < 8) {
            // something like (0x420,'NZsZ')
            val funcArgs = jsEnd.substringAfterLast("(0x").substringBefore(")")
            return getPasswordFromJS(js, "(0x" + funcArgs + ")")
        }
        return suspiciousPass
    }

    private fun getPasswordFromJS(js: String, getKeyArgs: String): String {
        var script = "(function" + js.substringBefore(",(!function")
            .substringAfter("(function") + ")"
        val decoderFunName = script.substringAfter("=").substringBefore(",")
        val decoderFunPrefix = "function " + decoderFunName
        var decoderFunBody = js.substringAfter(decoderFunPrefix)
        val decoderFunSuffix = decoderFunName + decoderFunBody.substringBefore("{") + ";}"
        decoderFunBody = (
            decoderFunPrefix +
                decoderFunBody.substringBefore(decoderFunSuffix) +
                decoderFunSuffix
            )

        if ("=[" !in js.substring(0, 20)) {
            val superArrayName = decoderFunBody.substringAfter("=")
                .substringBefore(";")
            val superArrayPrefix = "function " + superArrayName
            val superArraySuffix = "return " + superArrayName + ";}"
            val superArrayBody = (
                superArrayPrefix +
                    js.substringAfter(superArrayPrefix)
                        .substringBefore(superArraySuffix) +
                    superArraySuffix
                )
            script += "\n\n" + superArrayBody
        }
        script += "\n\n" + decoderFunBody
        script += "\n\n" + decoderFunName + getKeyArgs
        val qjs = QuickJs.create()
        // this part can be really slow, like 5s or even more >:(
        val result = qjs.evaluate(script).toString()
        qjs.close()
        return result
    }
}
