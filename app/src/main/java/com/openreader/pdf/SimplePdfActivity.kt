package com.openreader.pdf

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.util.LruCache
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

/**
 * Lightweight paged PDF reader based on Android's PdfRenderer.
 * PdfRenderer is accessed only from one worker thread because it permits
 * only one open page at a time.
 */
class SimplePdfActivity : Activity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rendererExecutor = Executors.newSingleThreadExecutor()
    private val rendererLock = Any()
    private val pageCache = object : LruCache<Int, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    @Volatile private var destroyed = false
    @Volatile private var requestedPage = 0
    private var renderer: PdfRenderer? = null
    private var fileDescriptor: android.os.ParcelFileDescriptor? = null
    private var pageCount = 0
    private var currentPage = 0
    private var renderGeneration = 0
    private var zoom = 1f

    private lateinit var pageView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var bottomBar: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var titleLabel: TextView
    private lateinit var pageLabel: TextView
    private lateinit var pageSeek: SeekBar
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private val pageBackground = Color.rgb(245, 230, 200)
    private val progressPrefs by lazy { getSharedPreferences("pdf_progress", MODE_PRIVATE) }
    private val progressKey: String
        get() = "page_" + (intent.data?.toString() ?: "unknown").hashCode()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()

        val uri = intent.data ?: intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (uri == null) {
            Toast.makeText(this, "Не передан PDF-файл", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        pageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(pageBackground)
            setOnTouchListener { _, event -> handlePageTouch(event) }
        }
        progress = ProgressBar(this)
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xDD171717.toInt())
        }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(0xEE075B70.toInt())
        }
        titleLabel = TextView(this).apply {
            text = intent.getStringExtra("book_title") ?: "PDF"
            setTextColor(Color.WHITE)
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(makeButton("‹") { finish() }, LinearLayout.LayoutParams(dp(44), dp(52)))
        topBar.addView(titleLabel, LinearLayout.LayoutParams(0, dp(52), 1f))
        topBar.addView(makeButton("🔖") {
            val key = "bookmarks_" + progressKey
            val saved = progressPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            saved.add(currentPage.toString())
            progressPrefs.edit().putStringSet(key, saved).apply()
            Toast.makeText(this, "Закладка на странице " + (currentPage + 1), Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(44), dp(52)))
        topBar.addView(makeButton("⌕") {
            Toast.makeText(this, "Поиск по тексту PDF добавим следующим этапом", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(44), dp(52)))
        topBar.addView(makeButton("⚙") {
            Toast.makeText(this, "Настройки PDF появятся в общем меню чтения", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(dp(44), dp(52)))

        pageLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        pageSeek = SeekBar(this).apply {
            max = 0
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser && pageCount > 0) pageLabel.text = "${value + 1} / $pageCount"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    seekBar?.progress?.let { showPage(it) }
                }
            })
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val previous = makeButton("‹") { showPage(currentPage - 1) }
        val next = makeButton("›") { showPage(currentPage + 1) }
        controls.addView(previous, LinearLayout.LayoutParams(0, dp(44), 1f))
        controls.addView(pageLabel, LinearLayout.LayoutParams(0, dp(44), 2f))
        controls.addView(next, LinearLayout.LayoutParams(0, dp(44), 1f))
        bottomBar.addView(pageSeek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(28)
        ))
        bottomBar.addView(controls, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ))

        val root = FrameLayout(this).apply {
            setBackgroundColor(pageBackground)
            addView(pageView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(progress, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            addView(topBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ))
            addView(bottomBar, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))
        }
        setContentView(root)

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
                applyImageMatrix(detector.focusX, detector.focusY, detector.scaleFactor)
                return true
            }
        })
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (zoom > 1.02f) return false
                val width = pageView.width.toFloat().coerceAtLeast(1f)
                when {
                    e.x < width * 0.28f -> {
                        showPage(currentPage - 1)
                    }
                    e.x > width * 0.72f -> {
                        showPage(currentPage + 1)
                    }
                    else -> toggleReaderBars()
                }
                return true
            }
            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                val start = e1 ?: return false
                if (zoom > 1.02f) return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > dp(64) && abs(dx) > abs(dy) * 1.2f && abs(velocityX) > dp(250)) {
                    showPage(if (dx < 0) currentPage + 1 else currentPage - 1)
                    return true
                }
                return false
            }
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
                if (zoom > 1.02f) {
                    pageView.imageMatrix = Matrix(pageView.imageMatrix).apply {
                        postTranslate(-distanceX, -distanceY)
                    }
                    return true
                }
                return false
            }
        })

        openPdf(uri)
    }

    private fun openPdf(uri: Uri) {
        progress.visibility = View.VISIBLE
        rendererExecutor.execute {
            try {
                val descriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Не удалось получить доступ к PDF-файлу.")
                val opened = PdfRenderer(descriptor)
                if (opened.pageCount <= 0) {
                    opened.close()
                    descriptor.close()
                    error("В PDF нет страниц.")
                }
                synchronized(rendererLock) {
                    fileDescriptor = descriptor
                    renderer = opened
                    pageCount = opened.pageCount
                }
                val saved = progressPrefs.getInt(progressKey, 0).coerceIn(0, pageCount - 1)
                mainHandler.post {
                    if (destroyed) return@post
                    pageSeek.max = (pageCount - 1).coerceAtLeast(0)
                    showPage(saved)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (!destroyed) {
                        progress.visibility = View.GONE
                        Toast.makeText(this, "Не удалось открыть PDF: ${error.message ?: "ошибка файла"}", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (destroyed || pageCount <= 0) return
        val safeIndex = index.coerceIn(0, pageCount - 1)
        currentPage = safeIndex
        requestedPage = safeIndex
        renderGeneration++
        val generation = renderGeneration
        zoom = 1f
        pageLabel.text = "${safeIndex + 1} / $pageCount"
        pageSeek.progress = safeIndex
        progressPrefs.edit().putInt(progressKey, safeIndex).apply()
        progress.visibility = View.VISIBLE

        val cached = pageCache.get(safeIndex)
        if (cached != null && !cached.isRecycled) {
            displayBitmap(cached)
            progress.visibility = View.GONE
            rendererExecutor.execute { prefetchNeighbors(safeIndex) }
            return
        }

        rendererExecutor.execute {
            try {
                if (destroyed || requestedPage != safeIndex) return@execute
                val bitmap = renderPageBitmap(safeIndex)
                if (bitmap != null) {
                    pageCache.put(safeIndex, bitmap)
                    mainHandler.post {
                        if (!destroyed && generation == renderGeneration) {
                            displayBitmap(bitmap)
                            progress.visibility = View.GONE
                        }
                    }
                    prefetchNeighbors(safeIndex)
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (!destroyed && generation == renderGeneration) {
                        progress.visibility = View.GONE
                        Toast.makeText(this, "Ошибка страницы: ${error.message ?: "не удалось отрисовать"}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun renderPageBitmap(index: Int): Bitmap? = synchronized(rendererLock) {
        val activeRenderer = renderer ?: return@synchronized null
        if (index !in 0 until activeRenderer.pageCount) return@synchronized null
        activeRenderer.openPage(index).use { page ->
            val display = resources.displayMetrics
            var width = (display.widthPixels * 1.6f).toInt().coerceIn(720, 2400)
            var height = (width.toDouble() * page.height / page.width).toInt().coerceAtLeast(1)
            val maxPixels = 4_000_000L
            val pixels = width.toLong() * height
            if (pixels > maxPixels) {
                val factor = kotlin.math.sqrt(maxPixels.toDouble() / pixels)
                width = (width * factor).toInt().coerceAtLeast(1)
                height = (height * factor).toInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(pageBackground)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        }
    }

    private fun prefetchNeighbors(center: Int) {
        val candidates = listOf(center + 1, center - 1)
        for (index in candidates) {
            if (destroyed || index !in 0 until pageCount || pageCache.get(index) != null) continue
            try {
                if (requestedPage != center) return
                val bitmap = renderPageBitmap(index) ?: continue
                pageCache.put(index, bitmap)
            } catch (_: Exception) {
                // Prefetch is optional; the requested page remains usable.
            }
        }
    }

    private fun displayBitmap(bitmap: Bitmap) {
        pageView.setImageBitmap(bitmap)
        pageView.post {
            if (!destroyed) resetImageMatrix()
        }
    }

    private fun resetImageMatrix() {
        val bitmap = pageView.drawable ?: return
        val viewWidth = pageView.width.toFloat()
        val viewHeight = pageView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return
        val scale = min(viewWidth / bitmap.intrinsicWidth, viewHeight / bitmap.intrinsicHeight)
        val dx = (viewWidth - bitmap.intrinsicWidth * scale) / 2f
        val dy = (viewHeight - bitmap.intrinsicHeight * scale) / 2f
        pageView.imageMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    private fun toggleReaderBars() {
        val visible = topBar.visibility != View.VISIBLE
        topBar.visibility = if (visible) View.VISIBLE else View.GONE
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyImageMatrix(focusX: Float, focusY: Float, factor: Float) {
        pageView.imageMatrix = Matrix(pageView.imageMatrix).apply {
            postScale(factor, factor, focusX, focusY)
        }
    }

    private fun handlePageTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && zoom <= 1.02f) {
            // GestureDetector handles taps and horizontal flings.
        }
        return true
    }

    private fun makeButton(label: String, action: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFB74D.toInt())
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun enterImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onDestroy() {
        destroyed = true
        rendererExecutor.execute {
            synchronized(rendererLock) {
                try { renderer?.close() } catch (_: Exception) {}
                try { fileDescriptor?.close() } catch (_: Exception) {}
                renderer = null
                fileDescriptor = null
            }
        }
        rendererExecutor.shutdown()
        super.onDestroy()
    }
}
