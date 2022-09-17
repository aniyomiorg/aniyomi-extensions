package eu.kanade.tachiyomi.animeextension.pt.goyabu

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://goyabu.com/assistir/<slug> intents
 * and redirects them to the main Aniyomi process.
 */
class GYUrlActivity : Activity() {

    private val TAG = "GYUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val slug = pathSegments[1]
            val searchQuery = GYConstants.PREFIX_SEARCH_SLUG + slug
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", searchQuery)
                putExtra("filter", packageName)
            }

            try {
                startActivity(mainIntent)
            } catch (e: ActivityNotFoundException) {
                Log.e(TAG, e.toString())
            }
        } else {
            Log.e(TAG, "could not parse uri from intent $intent")
        }

        finish()
        exitProcess(0)
    }
}
