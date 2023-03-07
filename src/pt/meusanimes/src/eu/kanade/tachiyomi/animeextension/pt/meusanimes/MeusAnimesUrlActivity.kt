package eu.kanade.tachiyomi.animeextension.pt.meusanimes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://meusanimes.org/animes/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class MeusAnimesUrlActivity : Activity() {

    private val TAG = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 1) {
            // https://<host>/<segment 0>/<segment 1>...
            // ex: pattern "/animes/..*" -> pathSegments[1]
            // ex: pattern "/animes/info/..*" -> pathSegments[2]
            // etc..
            val item = pathSegments[1]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${MeusAnimes.PREFIX_SEARCH}$item")
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
