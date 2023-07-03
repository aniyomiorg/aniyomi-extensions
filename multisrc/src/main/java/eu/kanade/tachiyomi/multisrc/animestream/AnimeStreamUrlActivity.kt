package eu.kanade.tachiyomi.multisrc.animestream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlin.system.exitProcess

class AnimeStreamUrlActivity : Activity() {

    private val tag by lazy { javaClass.simpleName }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pathSegments = intent?.data?.pathSegments
        if (pathSegments != null && pathSegments.isNotEmpty()) {
            val path = pathSegments.joinToString("/")
            val mainIntent = Intent().apply {
                action = "eu.kanade.tachiyomi.ANIMESEARCH"
                putExtra("query", "${AnimeStream.PREFIX_SEARCH}$path")
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
