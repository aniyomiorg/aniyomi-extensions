package eu.kanade.tachiyomi.animeextension.all.jellyfin

import android.content.SharedPreferences

object JFConstants {
    const val APIKEY_TITLE = "API Key"
    const val HOSTURL_TITLE = "Host URL"

    const val APIKEY_DEFAULT = ""
    const val HOSTURL_DEFAULT = "http://127.0.0.1:8096"

    fun getPrefApiKey(preferences: SharedPreferences): String = preferences.getString(
        APIKEY_TITLE, APIKEY_DEFAULT
    )!!
    fun getPrefHostUrl(preferences: SharedPreferences): String = preferences.getString(
        HOSTURL_TITLE, HOSTURL_DEFAULT
    )!!
}
