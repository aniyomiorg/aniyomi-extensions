package eu.kanade.tachiyomi.animeextension.en.rule34video

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class Rule34VideoUrlActivity : Activity() {

    private val tag = "Rule34VideoUrlActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.size > 0) {
            val slug = pathSegments[2]
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${Rule34Video.PREFIX_SEARCH}$slug")
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
