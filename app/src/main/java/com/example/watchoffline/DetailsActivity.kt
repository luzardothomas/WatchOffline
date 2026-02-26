package com.example.watchoffline

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.example.watchoffline.vid.PlaybackVideoFragment

class DetailsActivity : FragmentActivity() {

    private val TAG = "DetailsActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)

        if (savedInstanceState != null) return

        // ✅ Movie (key fija)
        val movie = intent.getSerializableExtra(MOVIE) as? Movie
        if (movie == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // ✅ Playlist + index (si vienen)
        val playlistName = intent.getStringExtra("EXTRA_PLAYLIST_NAME")
        val playlist = intent.getSerializableExtra(EXTRA_PLAYLIST) as? ArrayList<Movie>
        val index = intent.getIntExtra(EXTRA_INDEX, 0)

        Log.e(TAG, "INTENT movie=${movie.videoUrl} playlistSize=${playlist?.size} index=$index")

        val frag = PlaybackVideoFragment().apply {
            arguments = Bundle().apply {
                putSerializable("movie", movie)

                if (!playlistName.isNullOrEmpty()) {
                    putString("playlist_name", playlistName)
                    putInt("index", index)
                }
                else if (!playlist.isNullOrEmpty()) {
                    putSerializable("playlist", playlist)
                    putInt("index", index.coerceIn(0, playlist.lastIndex))
                }
            }
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.details_fragment, frag)
            .commit()
    }

    companion object {
        const val SHARED_ELEMENT_NAME = "hero"

        // ✅ key usada para el Movie
        const val MOVIE = "Movie"

        // ✅ keys para playlist
        const val EXTRA_PLAYLIST = "wo_playlist"
        const val EXTRA_INDEX = "wo_index"
    }
}
