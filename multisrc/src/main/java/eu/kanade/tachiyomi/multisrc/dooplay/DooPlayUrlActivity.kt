package eu.kanade.tachiyomi.multisrc.dooplay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class DooPlayUrlActivity : Activity() {

    private val tag = "DooPlayUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val path = pathSegments[0]
            val slug = pathSegments[1]
            val searchQuery = "$path/$slug"
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${DooPlay.PREFIX_SEARCH}$searchQuery")
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(tag, e.toString())
            }
        } else {
            Log.e(tag, "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
