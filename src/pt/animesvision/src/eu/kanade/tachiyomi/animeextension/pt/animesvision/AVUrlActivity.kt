package eu.kanade.tachiyomi.animeextension.pt.animesvision

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://animes.vision/<type>/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class AVUrlActivity : Activity() {

    private val TAG = "AVUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val type = pathSegments[0]
            val item = pathSegments[1]
            val searchQuery = "$type/$item"
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${AnimesVision.PREFIX_SEARCH}$searchQuery")
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
