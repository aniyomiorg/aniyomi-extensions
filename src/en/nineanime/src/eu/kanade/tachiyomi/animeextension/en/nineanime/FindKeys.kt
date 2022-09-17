package eu.kanade.tachiyomi.animeextension.en.nineanime

import app.cash.quickjs.QuickJs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

const val fallbackcipherKey = "mcYrOtBqfRISotfM"
const val fallbackdecipherKey = "hlPeNwkncH0fq9so"

fun getKeys(allJsScript: String, json: Json): Pair<String, String> {
    val quickJs = QuickJs.create()
    val keys = try {
        val scriptResult = quickJs.evaluate(finderScript(allJsScript)).toString()
        val returnObject = json.decodeFromString<JsonObject>(scriptResult)
        val cipherKey = returnObject["cipher"]!!.jsonPrimitive.content
        val decipherKey = returnObject["decipher"]!!.jsonPrimitive.content
        Pair(cipherKey, decipherKey)
    } catch (t: Throwable) {
        Pair(fallbackcipherKey, fallbackdecipherKey)
    }
    quickJs.close()
    return keys
}

private fun finderScript(script: String) = """
let secret0 = "";
let secret1 = "";
const script = String.raw`$script`;
const prefix = `$prefix`;
var newscript = prefix + script;
const fn_regex = /or(?=:function\(.*?\) {var \w=.*?return .;})/gm
let fn_name = script.match(fn_regex);
const regex = RegExp(String.raw`(?<=this\["${'$'}{fn_name}"]\().+?(?=,)`, "gm");
let res = [...script.matchAll(regex)];
for (var index of [1,0]) {
    let match = res[index][0];
    let varnames = match.split("+");
    for (var varnameindex = 0; varnameindex < varnames.length; varnameindex++) {
        let varname = varnames[varnameindex];
        let search = `${'$'}{varname}=`;
        // variables are declared on line 2
        let line2index = script.indexOf("\n") + prefix.length;
        let line2 = newscript.substring(line2index + 1);
        let i = line2index + line2.indexOf(search) + search.length;
        let after = newscript.substring(i + 1);
        let j = after.indexOf(";") + i + 1;
        let before = newscript.substring(0, j + 1);
        let after_semicolon = newscript.substring(j + 1);
        newscript = before + `secret${'$'}{index}=${'$'}{res[index][0]};` + after_semicolon;
    }
};
try { eval(newscript); } catch(e) {}
let return_object = {cipher: secret0, decipher: secret1};
JSON.stringify(return_object);
"""

private const val prefix = """const document = { documentElement: {} };
const jQuery = function () { return { off: function () { return { on: function(e) { return { on: function() { return { on: function() { return { on: function() { return { on: function() { return {  }; } }; } }; } }; } }; } }; }, ready: function (e) {} } };
jQuery.fn = { dropdown: {}, extend: {} };
const window = { fn: { extend: {} } };
const navigator = {};
const setTimeout = {};
const clearTimeout = {};
const setInterval = {};
const clearInterval = {};

"""
