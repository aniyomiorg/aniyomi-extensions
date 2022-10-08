package eu.kanade.tachiyomi.animeextension.en.dopebox.utils

import app.cash.quickjs.QuickJs

// For e4.min.js
object FindPassword {

    fun getPassword(js: String): String {
        val funcName = js.substringBefore("CryptoJS[")
            .substringBeforeLast("document")
            .substringAfterLast(",")
            .substringBefore("=")

        val suspiciousPass = js.substringAfter(":" + funcName)
            .substringAfter(",")
            .substringBefore("||")
            .substringBeforeLast(")")

        if (suspiciousPass.startsWith("'"))
            return suspiciousPass.trim('\'')
        return getPasswordFromJS(js, "(" + suspiciousPass.substringAfter("("))
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

        if ("=[" !in js.substring(0, 30)) {
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
        script += "\n\n$decoderFunName$getKeyArgs"
        val qjs = QuickJs.create()
        // this part can be really slow, like 5s or even more >:(
        val result = qjs.evaluate(script).toString()
        qjs.close()
        return result
    }
}
