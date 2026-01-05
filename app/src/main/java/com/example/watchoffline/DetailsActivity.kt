package com.example.watchoffline

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.example.watchoffline.vid.PlaybackVideoFragment

/**
 * Details activity class that loads [VideoDetailsFragment] class.
 */
class DetailsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        if (savedInstanceState != null) return

        // ✅ Tomar el Movie que ya mandás desde MainFragment
        val movie = intent.getSerializableExtra(MOVIE) as? Movie
        if (movie == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        // ✅ Cargar el PlaybackVideoFragment
        val frag = PlaybackVideoFragment().apply {
            arguments = Bundle().apply { putSerializable("movie", movie) }
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.details_fragment, frag)
            .commit()
    }

    companion object {
        const val SHARED_ELEMENT_NAME = "hero"
        const val MOVIE = "Movie"
    }
}