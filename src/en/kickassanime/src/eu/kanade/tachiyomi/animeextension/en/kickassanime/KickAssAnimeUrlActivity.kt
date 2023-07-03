package eu.kanade.tachiyomi.animeextension.en.kickassanime

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

/**
 * Springboard that accepts https://kaas.am/<item> intents
 * and redirects them to the main Aniyomi process.
 */
class KickAssAnimeUrlActivity : Activity() {

    private val tag = javaClass.simpleName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size >= 1) {
            val slug = pathSegments[0]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${KickAssAnime.PREFIX_SEARCH}$slug")
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
