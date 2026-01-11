package com.example.watchoffline

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.watchoffline.databinding.ActivityImageSearchBinding
import kotlinx.coroutines.*

class ImageSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageSearchBinding
    private val imageAdapter = ImageAdapter()
    private var currentFirst = 1
    private var currentQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        hideSystemUI()
        super.onCreate(savedInstanceState)
        binding = ActivityImageSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (isTvDevice()) {
            setupTvMode(savedInstanceState)
        } else {
            setupMobileMode()
        }
    }

    private fun hideSystemUI() {
        // 1. Mazazo a nivel de ventana para eliminar la barra de estado (donde está la hora)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)

        // 2. Aplicar los flags de visibilidad tradicionales
        val immersiveFlags = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)

        window.decorView.systemUiVisibility = immersiveFlags

        // 3. Listener para re-ocultar (mantiene el bloqueo)
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                window.decorView.systemUiVisibility = immersiveFlags
            }
        }
    }

    private fun setupTvMode(savedInstanceState: Bundle?) {
        binding.mobileLayout.visibility = View.GONE
        binding.mobileLayout.isFocusable = false
        binding.loadingLayout.visibility = View.GONE
        binding.loadingLayout.isFocusable = false
        binding.tvFragmentContainer.visibility = View.VISIBLE

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_fragment_container, ImageSearchTvFragment(), "BUSCADOR_TAG")
                .commit()
        }
    }

    private fun setupMobileMode() {
        binding.tvFragmentContainer.visibility = View.GONE
        binding.mobileLayout.visibility = View.VISIBLE
        initViews()
    }

    private fun initViews() {
        // En móvil usamos 3 columnas para que las fotos sean grandes
        val manager = GridLayoutManager(this, 3)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (imageAdapter.getItemViewType(position) == 1) 3 else 1
            }
        }

        binding.recyclerImages.layoutManager = manager
        binding.recyclerImages.adapter = imageAdapter

        binding.btnBackMobile.visibility = View.VISIBLE

        binding.btnBackMobile.setOnClickListener { finish() }

        binding.btnSearch.setOnClickListener {
            val query = binding.etSearch.text.toString()
            if (query.isNotBlank()) performSearch(query)
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text.toString()
                if (query.isNotBlank()) performSearch(query)
                true
            } else false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTvDevice() && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            val fragment = supportFragmentManager.findFragmentByTag("BUSCADOR_TAG") as? ImageSearchTvFragment
            val gridView = fragment?.view?.findViewById<androidx.leanback.widget.VerticalGridView>(androidx.leanback.R.id.container_list)

            if (gridView == null || gridView.selectedPosition <= 0) {
                val searchOrb = fragment?.view?.findViewById<View>(androidx.leanback.R.id.lb_search_bar_speech_orb)
                    ?: fragment?.view?.findViewById<View>(androidx.leanback.R.id.lb_search_frame)

                if (searchOrb != null) {
                    searchOrb.isFocusable = true
                    searchOrb.requestFocus()
                    Log.d("FOCUS_DEBUG", "¡SALTO AL MICRO FORZADO!")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1000 && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0)
            setVoiceResults(data, spokenText)
        }
    }

    fun setVoiceResults(data: Intent?, text: String?) {
        if (text == null) return
        runOnUiThread {
            binding.etSearch.setText(text)
            if (isTvDevice()) {
                val fragment = supportFragmentManager.findFragmentByTag("BUSCADOR_TAG") as? ImageSearchTvFragment
                fragment?.setVoiceResults(data, text)
            } else {
                performSearch(text)
            }
        }
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) return
        currentQuery = query
        currentFirst = 1

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)

        setLoading(true, "Buscando '$query'...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val urls = ImageImporter.searchImages(query, first = currentFirst)

                // --- LÓGICA DE FILAS COMPLETAS (Múltiplo de 3) ---
                // Si llegan 10 items: 10/3 = 3 enteros. 3*3 = 9. Tomamos los primeros 9.
                // El item 10 se descarta para no dejar la fila incompleta.
                val validSize = (urls.size / 3) * 3
                val filteredUrls = urls.take(validSize)

                withContext(Dispatchers.Main) {
                    // Importante: Chequear si tras el filtro quedó vacía
                    if (urls.isNotEmpty() && filteredUrls.isEmpty()) {
                        Toast.makeText(this@ImageSearchActivity, "Resultados insuficientes para llenar una fila.", Toast.LENGTH_SHORT).show()
                        imageAdapter.updateList(emptyList()) // Opcional: limpiar si quieres
                    } else {
                        imageAdapter.updateList(filteredUrls)
                    }

                    setLoading(false)
                    binding.recyclerImages.scrollToPosition(0)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setLoading(false)
                    Toast.makeText(this@ImageSearchActivity, "Error de conexión", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadMoreMobile() {
        // Aumentamos el puntero para la siguiente página
        // Nota: Como descartamos visualmente algunos items, puede que veas un "salto" de imagen,
        // pero es necesario para mantener la estética estricta de 3 columnas.
        currentFirst += 10

        setLoading(true, "Cargando más...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val newUrls = ImageImporter.searchImages(currentQuery, first = currentFirst)

                // --- LÓGICA DE FILAS COMPLETAS (Múltiplo de 3) ---
                val validSize = (newUrls.size / 3) * 3
                val filteredNewUrls = newUrls.take(validSize)

                withContext(Dispatchers.Main) {
                    if (filteredNewUrls.isNotEmpty()) {
                        imageAdapter.addUrls(filteredNewUrls)
                    } else {
                        // Opcional: Si tras filtrar no queda nada (ej: llegaron 2 fotos),
                        // podrías intentar cargar la siguiente página automáticamente o avisar.
                        // Por ahora simplemente quitamos el loading.
                    }
                    setLoading(false)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }

    fun processImageSelection(imageUrl: String) {
        runOnUiThread {
            binding.loadingLayout.visibility = View.GONE

            // --- COLORES ---
            val colorBg = Color.parseColor("#303030")
            val colorTeal = Color.parseColor("#80CBC4")
            val colorHover = Color.parseColor("#505050") // Gris hover
            val tealStateList = android.content.res.ColorStateList.valueOf(colorTeal)

            // --- DRAWABLE PARA EL HOVER DE LA LISTA Y CHECKBOX ---
            val hoverDrawable = android.graphics.drawable.StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    android.graphics.drawable.ColorDrawable(colorHover)
                )
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    android.graphics.drawable.ColorDrawable(Color.parseColor("#606060"))
                )
                addState(
                    intArrayOf(),
                    android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                )
            }

            val allFiles = intent.getStringArrayListExtra("TARGET_JSONS") ?: arrayListOf()
            val itemsAsArray = allFiles.toTypedArray()

            // 1. Contenedor principal
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(60, 40, 60, 20)
            }

            // TÍTULO
            val customTitle = TextView(this).apply {
                text = "Importar portada"
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(60, 40, 60, 10)
            }

            // 2. Checkbox "Seleccionar todos"
            val cbSelectAll = CheckBox(this).apply {
                text = "Seleccionar todos"
                isChecked = true
                setTextColor(Color.WHITE)
                textSize = 20f
                buttonTintList = tealStateList
                setTypeface(null, android.graphics.Typeface.NORMAL)
                background = hoverDrawable.constantState?.newDrawable()
                setPadding(10, 10, 10, 10)
            }

            // 3. Adaptador
            val adapter = object : ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_multiple_choice,
                itemsAsArray
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val view = super.getView(position, convertView, parent)
                    val textView = view.findViewById<TextView>(android.R.id.text1)
                    textView.setTextColor(Color.WHITE)
                    textView.textSize = 18f
                    if (view is android.widget.CheckedTextView) {
                        view.checkMarkTintList = tealStateList
                    }
                    return view
                }
            }

            // 4. ListView
            val listView = ListView(this).apply {
                choiceMode = ListView.CHOICE_MODE_MULTIPLE
                this.adapter = adapter
                isEnabled = false
                alpha = 0.5f
                isFocusable = false
                selector = hoverDrawable
                setDrawSelectorOnTop(false)
            }

            // LÓGICA DE SELECCIÓN
            cbSelectAll.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    for (i in itemsAsArray.indices) listView.setItemChecked(i, true)
                    listView.isEnabled = false
                    listView.alpha = 0.5f
                    listView.isFocusable = false
                } else {
                    for (i in itemsAsArray.indices) listView.setItemChecked(i, false)
                    listView.isEnabled = true
                    listView.alpha = 1.0f
                    listView.isFocusable = true
                }
            }

            root.addView(cbSelectAll)

            val listParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 300).toInt()
            ).apply { topMargin = 20 }
            root.addView(listView, listParams)

            // Estado inicial
            for (i in itemsAsArray.indices) listView.setItemChecked(i, true)

            // 5. Diálogo
            val dialog = AlertDialog.Builder(this)
                .setCustomTitle(customTitle)
                .setView(root)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("CONFIRMAR SELECCIÓN") { _, _ ->
                    try {
                        val selectAll = cbSelectAll.isChecked
                        val chosen = if (selectAll) {
                            allFiles
                        } else {
                            val picked = mutableListOf<String>()
                            for (i in itemsAsArray.indices) {
                                if (listView.isItemChecked(i)) picked.add(itemsAsArray[i])
                            }
                            picked
                        }

                        if (chosen.isEmpty()) {
                            Toast.makeText(this, "No seleccionaste ningún destino.", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }

                        setLoading(true, "Importando portada...")

                        val resultIntent = Intent().apply {
                            putExtra("SELECTED_IMAGE_URL", imageUrl)
                            putStringArrayListExtra("TARGET_JSONS", ArrayList(chosen))
                        }

                        setResult(RESULT_OK, resultIntent)
                        finish()

                        if (android.os.Build.VERSION.SDK_INT >= 34) {
                            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
                        } else {
                            @Suppress("DEPRECATION")
                            overridePendingTransition(0, 0)
                        }

                    } catch (e: Exception) {
                        Log.e("TV_DEBUG", "Error al confirmar: ${e.message}")
                        finish()
                    }
                }
                .create()

            // 7. APLICAR ESTILOS FINALES (Corrige el bug visual de colores negros/flash)
            dialog.setOnShowListener { d ->
                val alertDialog = d as AlertDialog

                // Fondo general del diálogo
                alertDialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(colorBg))

                // Función para limpiar estilos nativos y aplicar los nuestros
                fun styleButton(button: Button) {
                    // 1. Eliminar animaciones de estado (sombra/elevación) que causan glitches
                    button.stateListAnimator = null

                    // 2. Limpiar fondos nativos completamente
                    button.background = null
                    button.backgroundTintList = null
                    button.setBackgroundColor(Color.TRANSPARENT)

                    // 3. Forzar color de texto en TODOS los estados para evitar que salga negro al inicio
                    button.setTextColor(tealStateList)

                    // 4. Configuración de foco
                    button.isFocusable = true
                    button.isFocusableInTouchMode = true

                    button.setOnFocusChangeListener { _, hasFocus ->
                        if (hasFocus) {
                            button.setBackgroundColor(colorHover)
                        } else {
                            button.setBackgroundColor(Color.TRANSPARENT)
                        }
                    }
                }

                styleButton(alertDialog.getButton(AlertDialog.BUTTON_POSITIVE))
                styleButton(alertDialog.getButton(AlertDialog.BUTTON_NEGATIVE))

                // Ponemos el foco inicial en el checkbox para evitar saltos raros
                cbSelectAll.requestFocus()
            }

            dialog.show()
        }
    }


    fun setLoading(isLoading: Boolean, text: String = "Cargando...") {
        runOnUiThread {
            if (isLoading) {
                binding.tvStatus.text = text
                binding.loadingLayout.visibility = View.VISIBLE
            } else {
                binding.loadingLayout.visibility = View.GONE
            }
        }
    }

    private fun isTvDevice(): Boolean {
        val cfg = resources.configuration
        val pm = packageManager

        val uiModeType =
            cfg.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val isTelevision =
            uiModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION

        val hasLeanback =
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                    pm.hasSystemFeature("android.software.leanback")

        val hasTelevisionFeature =
            pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

        val noTouch =
            cfg.touchscreen == android.content.res.Configuration.TOUCHSCREEN_NOTOUCH
        val dpad =
            cfg.navigation == android.content.res.Configuration.NAVIGATION_DPAD

        return isTelevision || hasLeanback || hasTelevisionFeature || (noTouch && dpad)
    }

    // --- ADAPTER ---
    inner class ImageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val urls = mutableListOf<String>()

        fun updateList(newUrls: List<String>) {
            urls.clear()
            urls.addAll(newUrls)
            notifyDataSetChanged()
        }

        fun addUrls(newUrls: List<String>) {
            val lastPos = urls.size
            urls.addAll(newUrls)
            notifyItemRangeInserted(lastPos, newUrls.size)
        }

        override fun getItemCount() = if (urls.isEmpty()) 0 else urls.size + 1
        override fun getItemViewType(p: Int) = if (p < urls.size) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                val cardView = androidx.cardview.widget.CardView(parent.context).apply {
                    layoutParams = GridLayoutManager.LayoutParams(-1, (resources.displayMetrics.density * 200).toInt()).apply { setMargins(12, 12, 12, 12) }
                    radius = 24f
                    cardElevation = 8f
                    isClickable = true
                    isFocusable = true
                    val outValue = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    foreground = context.getDrawable(outValue.resourceId)
                }
                val img = ImageView(parent.context).apply {
                    layoutParams = FrameLayout.LayoutParams(-1, -1)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
                cardView.addView(img)
                ImgViewHolder(cardView)
            } else {
                val btn = Button(parent.context).apply {
                    layoutParams = GridLayoutManager.LayoutParams(-1, -2).apply { setMargins(20, 20, 20, 20) }
                    text = "CARGAR MÁS"
                    setBackgroundColor(Color.parseColor("#00FBFF"))
                    setTextColor(Color.BLACK)
                }
                LoadMoreViewHolder(btn)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is ImgViewHolder) {
                val url = urls[position]
                val card = holder.itemView as androidx.cardview.widget.CardView
                val img = card.getChildAt(0) as ImageView
                com.bumptech.glide.Glide.with(img.context).load(url).centerCrop().into(img)
                card.setOnClickListener { processImageSelection(url) }
            } else if (holder is LoadMoreViewHolder) {
                holder.itemView.setOnClickListener { loadMoreMobile() }
            }
        }
    }

    inner class ImgViewHolder(v: View) : RecyclerView.ViewHolder(v)
    inner class LoadMoreViewHolder(v: View) : RecyclerView.ViewHolder(v)
}