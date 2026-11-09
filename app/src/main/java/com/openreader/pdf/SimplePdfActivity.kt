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
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import android.widget.Toast
import android.util.LruCache
import java.util.concurrent.Executors
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
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
    private val pageCache = object : LruCache<Int, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount
    }

    @Volatile private var destroyed = false
    @Volatile private var requestedPage = 0
    private var renderer: PdfRenderer? = null
    private var fileDescriptor: android.os.ParcelFileDescriptor? = null
    private var cachedPdfFile: File? = null
    private var pageCount = 0
    private var currentPage = 0
    private var renderGeneration = 0
    private var zoom = 1f
    private val pageIndexState = mutableIntStateOf(0)
    private val pageCountState = mutableIntStateOf(0)

    private lateinit var pageView: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var bottomBar: View
    private lateinit var topBar: View
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    private var pageBackground = Color.rgb(250, 247, 240)
    // Shared page inset: 30 dp on the sides, 32 dp at the top, 8 dp at the bottom.
    private val horizontalPaddingDp = 30
    private val topPaddingDp = 32
    private val bottomPaddingDp = 8
    private val readerThemeState = mutableStateOf("paper")
    private val progressPrefs by lazy { getSharedPreferences("pdf_progress", MODE_PRIVATE) }
    private val progressKey: String
        get() = "page_" + (intent.data?.toString() ?: "unknown").hashCode()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveMode()
        readerThemeState.value = getSharedPreferences("openreader", MODE_PRIVATE)
            .getString("reader_theme", "paper") ?: "paper"
        pageBackground = pdfBackgroundFor(readerThemeState.value)

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
        topBar = ComposeView(this).apply {
            setContent {
                var showAppearanceSettings by androidx.compose.runtime.remember { mutableStateOf(false) }
                val appearancePrefs = getSharedPreferences("openreader", MODE_PRIVATE)
                var fontSize by androidx.compose.runtime.remember { mutableFloatStateOf(appearancePrefs.getFloat("font_size", 19f)) }
                var readerSpacing by androidx.compose.runtime.remember { mutableFloatStateOf(appearancePrefs.getFloat("reader_spacing", 1.5f)) }
                var readerMargin by androidx.compose.runtime.remember { mutableFloatStateOf(appearancePrefs.getFloat("reader_margin", 24f)) }
                MaterialTheme(colorScheme = if (readerThemeState.value == "dark") darkColorScheme() else lightColorScheme()) {
                    com.openreader.ui.ReaderTopBar(
                        title = intent.getStringExtra("book_title") ?: "PDF",
                        fontSize = fontSize,
                        onBack = { finish() },
                        onBookmark = {
                            val key = "bookmarks_" + progressKey
                            val saved = progressPrefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
                            saved.add(currentPage.toString())
                            progressPrefs.edit().putStringSet(key, saved).apply()
                            Toast.makeText(this@SimplePdfActivity, "Закладка на странице " + (currentPage + 1), Toast.LENGTH_SHORT).show()
                        },
                        onQuotes = {},
                        onDecreaseText = {},
                        onSettings = { showAppearanceSettings = true },
                        onIncreaseText = {},
                        showTextSizeControls = false,
                        showQuotesAction = false,
                        onSearch = {
                            Toast.makeText(this@SimplePdfActivity, "Поиск по тексту PDF добавим следующим этапом", Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (showAppearanceSettings) {
                        AlertDialog(
                            onDismissRequest = { showAppearanceSettings = false },
                            title = { Text("Оформление книги") },
                            text = {
                                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                    com.openreader.ui.ReaderAppearanceSettings(
                                        readerTheme = readerThemeState.value,
                                        fontSize = fontSize,
                                        readerSpacing = readerSpacing,
                                        readerMargin = readerMargin,
                                        showTypography = false,
                                        onThemeChange = { value ->
                                            readerThemeState.value = value
                                            pageBackground = pdfBackgroundFor(value)
                                            pageView.setBackgroundColor(pageBackground)
                                            appearancePrefs.edit().putString("reader_theme", value).apply()
                                        },
                                        onFontSizeChange = { fontSize = it; appearancePrefs.edit().putFloat("font_size", it).apply() },
                                        onSpacingChange = { readerSpacing = it; appearancePrefs.edit().putFloat("reader_spacing", it).apply() },
                                        onMarginChange = { readerMargin = it; appearancePrefs.edit().putFloat("reader_margin", it).apply() }
                                    )
                                }
                            },
                            confirmButton = { TextButton(onClick = { showAppearanceSettings = false }) { Text("Готово") } }
                        )
                    }
                }
            }
        }

        bottomBar = ComposeView(this).apply {
            setContent {
                MaterialTheme(colorScheme = if (readerThemeState.value == "dark") darkColorScheme() else lightColorScheme()) {
                    com.openreader.ui.ReaderBottomBar(
                        pageIndex = pageIndexState.intValue,
                        pageCount = pageCountState.intValue,
                        onPrevious = { showPage(pageIndexState.intValue - 1) },
                        onNext = { showPage(pageIndexState.intValue + 1) },
                        onSelectPage = { showPage(it) }
                    )
                }
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(pageBackground)
            val horizontalPaddingPx = dp(horizontalPaddingDp)
            setPadding(horizontalPaddingPx, dp(topPaddingDp), horizontalPaddingPx, dp(bottomPaddingDp))
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
        // Keep reading area clear by default; a center tap reveals both bars.
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE

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


    private fun pdfBackgroundFor(theme: String): Int = when (theme) {
        "dark" -> Color.rgb(24, 24, 24)
        "sepia" -> Color.rgb(241, 227, 200)
        "light" -> Color.WHITE
        else -> Color.rgb(250, 247, 240)
    }

    private fun openPdf(uri: Uri) {
        progress.visibility = View.VISIBLE
        rendererExecutor.execute {
            try {
                // PdfRenderer requires a seekable file descriptor. Some document providers
                // expose cloud/virtual documents as non-seekable pipes, which fail immediately.
                // Copy the source into the app cache first, then open that local seekable file.
                val cacheDirectory = File(cacheDir, "pdf_cache").apply { mkdirs() }
                val localFile = File.createTempFile("openreader_", ".pdf", cacheDirectory)
                try {
                    val source = if (uri.scheme == "file") {
                        val path = uri.path ?: error("Некорректный путь к PDF-файлу.")
                        FileInputStream(File(path))
                    } else {
                        contentResolver.openInputStream(uri)
                    }
                    source?.use { input ->
                        FileOutputStream(localFile).use { output ->
                            input.copyTo(output, 64 * 1024)
                            output.fd.sync()
                        }
                    } ?: error("Не удалось прочитать PDF-файл.")
                } catch (copyError: Throwable) {
                    localFile.delete()
                    throw copyError
                }

                val descriptor = android.os.ParcelFileDescriptor.open(
                    localFile,
                    android.os.ParcelFileDescriptor.MODE_READ_ONLY
                )
                val opened = try {
                    PdfRenderer(descriptor)
                } catch (openError: Throwable) {
                    descriptor.close()
                    localFile.delete()
                    throw openError
                }
                cachedPdfFile = localFile
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
                    pageCountState.intValue = pageCount
                    showPage(saved)
                }
            } catch (error: Throwable) {
                android.util.Log.e("OpenReaderPDF", "PDF opening failed", error)
                mainHandler.post {
                    if (!destroyed) {
                        progress.visibility = View.GONE
                        pageView.visibility = View.GONE
                        val root = pageView.parent as? FrameLayout
                        val cause = generateSequence(error as Throwable?) { it.cause }.lastOrNull()
                        val details = buildString {
                            append(error.javaClass.simpleName)
                            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                            if (cause != null && cause !== error) {
                                append("\\nПричина: ").append(cause.javaClass.simpleName)
                                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                            }
                        }
                        val message = TextView(this).apply {
                            text = "Не удалось открыть PDF\\n\\n$details\\n\\nВернитесь назад и попробуйте другой файл. Сообщите нам этот текст."
                            textSize = 16f
                            gravity = Gravity.CENTER
                            setPadding(dp(28), dp(28), dp(28), dp(28))
                            setTextColor(if (readerThemeState.value == "dark") Color.WHITE else Color.DKGRAY)
                            setBackgroundColor(pageBackground)
                        }
                        root?.addView(message, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        ))
                        topBar.visibility = View.VISIBLE
                        topBar.bringToFront()
                    }
                }
            }
        }
    }

    private fun showPage(index: Int) {
        if (destroyed || pageCount <= 0) return
        val safeIndex = index.coerceIn(0, pageCount - 1)
        currentPage = safeIndex
        pageIndexState.intValue = safeIndex
        requestedPage = safeIndex
        renderGeneration++
        val generation = renderGeneration
        zoom = 1f
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
            } catch (error: Throwable) {
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
            // Render only to the PDF content width, excluding the 14 dp inset on both sides.
            var width = (display.widthPixels - dp(horizontalPaddingDp * 2)).coerceAtLeast(1)
            var height = (width.toDouble() * page.height / page.width).toInt().coerceAtLeast(1)
            val maxPixels = 1_800_000L
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
        // PDF pages are fitted to the available width (not contained by height).
        // Tall pages start at the top and can be zoomed; short pages stay vertically centered.
        val scale = viewWidth / bitmap.intrinsicWidth
        val dx = 0f
        val scaledHeight = bitmap.intrinsicHeight * scale
        val dy = ((viewHeight - scaledHeight) / 2f).coerceAtLeast(0f)
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
                cachedPdfFile?.let { file ->
                    try { file.delete() } catch (_: Exception) {}
                }
                cachedPdfFile = null
            }
        }
        rendererExecutor.shutdown()
        super.onDestroy()
    }
}
