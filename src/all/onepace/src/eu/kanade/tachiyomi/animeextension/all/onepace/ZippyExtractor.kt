package eu.kanade.tachiyomi.animeextension.all.onepace

import app.cash.quickjs.QuickJs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup

class ZippyExtractor {
    fun getVideoUrl(url: String, json: Json): String {
        val document = Jsoup.connect(url).get()
        val jscript = document.selectFirst("script:containsData(dlbutton)").data()
            .replace("document.getElementById('dlbutton').href", "a")
            .replace("document.getElementById('fimage').href", "b")
            .replace("document.getElementById('fimage')", "false")
        val quickjs = QuickJs.create()
        val objectA = quickjs.evaluate(objectScript(jscript)).toString()
        quickjs.close()
        return json.decodeFromString<JsonObject>(objectA)["a"]!!.jsonPrimitive.content
    }

    private fun objectScript(script: String) = """
            $script;
            let return_object = {a:a};
            JSON.stringify(return_object);
        """
}
