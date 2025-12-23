package com.example.watchoffline.vid

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.example.watchoffline.Movie
import com.example.watchoffline.R

class PlaybackActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)

        if (savedInstanceState != null) return

        val movie = intent.getSerializableExtra("movie") as? Movie
        if (movie == null) {
            finish()
            return
        }

        val frag = PlaybackVideoFragment().apply {
            arguments = Bundle().apply {
                putSerializable("movie", movie)
            }
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.playback_container, frag)
            .commit()
    }
}
