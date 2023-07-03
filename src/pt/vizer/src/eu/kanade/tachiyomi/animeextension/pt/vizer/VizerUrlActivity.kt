package eu.kanade.tachiyomi.animeextension.pt.vizer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://vizer.tv/[anime|filme|serie]/online/<slug> intents
 * and redirects them to the main Aniyomi process.
 */
class VizerUrlActivity : Activity() {

    private val tag = "VizerUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val query = "${pathSegments[0]}/${pathSegments[2]}"
            val searchQuery = Vizer.PREFIX_SEARCH + query
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", searchQuery)
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
