package eu.kanade.tachiyomi.animeextension.pt.sukianimes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://sukianimes.com/anime/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class SKUrlActivity : Activity() {

    private val TAG = "SKUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            val item = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${SukiAnimes.PREFIX_SEARCH}$item")
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
