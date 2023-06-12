package eu.kanade.tachiyomi.animeextension.pt.betteranime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://betteranime.net/<type>/<lang>/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class BAUrlActivity : Activity() {

    private val tag = "BAUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 2) {
            val type = pathSegments[0]
            val lang = pathSegments[1]
            val item = pathSegments[2]
            val searchQuery = "$type/$lang/$item"
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${BetterAnime.PREFIX_SEARCH_PATH}$searchQuery")
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
