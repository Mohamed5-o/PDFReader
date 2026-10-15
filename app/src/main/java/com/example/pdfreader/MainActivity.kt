package com.example.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.InputType
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    // ───────────── Colors ─────────────
    private val bgColor = Color.rgb(8, 12, 11)
    private val panel = Color.rgb(17, 27, 24)
    private val panel2 = Color.rgb(24, 38, 33)
    private val green = Color.rgb(32, 190, 120)
    private val darkGreen = Color.rgb(15, 70, 50)
    private val gold = Color.rgb(232, 190, 75)
    private val white = Color.rgb(245, 247, 245)
    private val muted = Color.rgb(150, 165, 158)
    private val red = Color.rgb(230, 85, 85)
    private val line = Color.argb(40, 255, 255, 255)

    // ───────────── Models ─────────────
    private data class Overlay(
        val pageIndex: Int,
        val bitmap: Bitmap,
        val nx: Float,
        val ny: Float,
        val widthPts: Float
    )

    private data class Pending(val bitmap: Bitmap, val widthPts: Float)

    private data class SearchHit(
        val page: Int,
        val snippet: String,
        val start: Int,
        val len: Int,
        val count: Int
    )

    // ───────────── State ─────────────
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val renderLock = Any()

    private var screen = "home"
    private var currentUri: Uri? = null
    private var currentName = "document.pdf"
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentPage = 0
    private var pageCount = 0
    private var editMode = false
    private var activeTool = 0
    private var currentBitmap: Bitmap? = null
    private var pending: Pending? = null

    private var selectedLanguage = "ar"
    private var renderQuality = 2
    private var nightMode = false
    private var autoScroll = false
    private var autoRotate = true
    private var fontIdx = 1
    private var autoSaveCopy = true
    private var defaultWatermark = false

    private var saveToOriginal = false
    private var pendingSaveName = "edited.pdf"
    private var lastQuery = ""

    private val overlays = mutableListOf<Overlay>()
    private val redoOverlays = mutableListOf<Overlay>()

    private var pageImage: ZoomImageView? = null
    private var pageLabel: TextView? = null
    private var zoomLabel: TextView? = null

    private val fontSizes = intArrayOf(18, 24, 32)

    private val requestOpenPdf = 1001
    private val requestSavePdf = 1002
    private val requestAddImage = 1003

    private val autoRunnable = object : Runnable {
        override fun run() {
            if (screen == "reader" && autoScroll && !editMode) {
                if (currentPage < pageCount - 1) changePage(1)
                handler.postDelayed(this, 8000)
            }
        }
    }

    private fun isArabic() = selectedLanguage == "ar"
    private fun tr(ar: String, en: String) = if (isArabic()) ar else en

    // ───────────── Lifecycle ─────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)

        selectedLanguage = prefs.getString("language", "ar") ?: "ar"
        renderQuality = prefs.getInt("quality", 2)
        nightMode = prefs.getBoolean("night", false)
        autoScroll = prefs.getBoolean("autoscroll", false)
        autoRotate = prefs.getBoolean("autorotate", true)
        fontIdx = prefs.getInt("fontIdx", 1)
        autoSaveCopy = prefs.getBoolean("autosave", true)
        defaultWatermark = prefs.getBoolean("watermark", false)

        applyOrientation()
        showHome()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when (screen) {
            "home" -> super.onBackPressed()
            "reader" -> {
                if (editMode) {
                    editMode = false
                    pending = null
                    showReader()
                } else showHome()
            }
            else -> if (renderer != null) showReader() else showHome()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closePdf()
        overlays.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        redoOverlays.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        overlays.clear()
        redoOverlays.clear()
        super.onDestroy()
    }

    private fun applyOrientation() {
        requestedOrientation = if (autoRotate) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun rebuild() {
        when (screen) {
            "reader" -> showReader()
            "settings" -> showSettings()
            "about" -> showAbout()
            "search" -> showSearch(lastQuery)
            "save" -> showSaveScreen()
            "files" -> showFiles()
            else -> showHome()
        }
    }

    private fun setScreen(name: String, view: View) {
        handler.removeCallbacksAndMessages(null)
        screen = name
        setContentView(view)
    }

    // ───────────── UI helpers ─────────────
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun dir() =
        if (isArabic()) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR

    private fun rounded(
        fill: Int,
        radius: Int = 14,
        stroke: Int = 0,
        strokeDp: Int = 1
    ) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(strokeDp), stroke)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private fun lpm(
        w: Int, h: Int, weight: Float = 0f,
        l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0
    ) = LinearLayout.LayoutParams(w, h, weight).apply { setMargins(l, t, r, b) }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun txt(
        text: String,
        size: Float = 14f,
        color: Int = white,
        bold: Boolean = false,
        center: Boolean = false
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = if (center) Gravity.CENTER else (Gravity.START or Gravity.CENTER_VERTICAL)
    }

    private fun pill(
        text: String,
        fill: Int,
        color: Int,
        stroke: Int = 0,
        onClick: () -> Unit
    ) = TextView(this).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
        gravity = Gravity.CENTER
        background = rounded(fill, 14, stroke)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { onClick() }
    }

    private fun icon(res: Int, tint: Int = white, sizeDp: Int = 22) =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            val p = dp(8)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp + 16), dp(sizeDp + 16))
        }

    private fun screenRoot() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(bgColor)
        setPadding(dp(10), dp(6), dp(10), dp(8))
        layoutDirection = dir()
        fitsSystemWindows = true
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun header(title: String, onBack: () -> Unit, trailing: View? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(4), 0, dp(8))
        }
        row.addView(
            txt("←", 26f, white, false, true).apply { setOnClickListener { onBack() } },
            lp(dp(48), dp(48))
        )
        row.addView(txt(title, 19f, white, true, true), lp(0, WRAP, 1f))
        row.addView(trailing ?: View(this), lp(dp(48), dp(48)))
        return row
    }

    private fun showCard(build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 20, green, 1)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutDirection = dir()
        }
        build(card, d)
        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WRAP)
        }
        d.show()
        return d
    }

    private fun field(hint: String, value: String = ""): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(white)
        setHintTextColor(muted)
        background = rounded(panel2, 12, line)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun showMenu(items: List<Pair<String, () -> Unit>>) {
        showCard { card, d ->
            items.forEachIndexed { i, (label, action) ->
                if (i > 0) card.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
                card.addView(
                    txt(label, 16f, white).apply {
                        setPadding(dp(8), dp(14), dp(8), dp(14))
                        setOnClickListener { d.dismiss(); action() }
                    },
                    lp(MATCH, WRAP)
                )
            }
        }
    }

    // ───────────── Home ─────────────
    private fun showHome() {
        val frame = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(12, 40, 30), bgColor, Color.rgb(6, 20, 15))
            )
            layoutDirection = dir()
            fitsSystemWindows = true
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }

        col.addView(CloverView(this), lp(dp(230), dp(230)))
        col.addView(
            txt("BLACK CLOVER", 34f, gold, true, true).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            }
        )
        col.addView(
            txt("PDF READER & EDITOR", 14f, green, true, true).apply { letterSpacing = 0.2f }
        )
        col.addView(
            txt(tr("اقرأ .. عدل .. احفظ", "Read .. Edit .. Save"), 18f, green, false, true),
            lpm(WRAP, WRAP, 0f, 0, dp(14), 0, dp(14))
        )
        col.addView(View(this).apply { setBackgroundColor(gold) }, lpm(dp(120), 2, 0f, 0, 0, 0, dp(14)))
        col.addView(txt(tr("المطور: Mikey", "Developer: Mikey"), 15f, gold, true, true))
        col.addView(
            txt(tr("(محمد محمود عامر)", "(Mohamed Mahmoud Amer)"), 15f, gold, false, true),
            lpm(WRAP, WRAP, 0f, 0, 0, 0, dp(26))
        )
        col.addView(
            pill("📁  " + tr("فتح ملف PDF", "Open PDF"), green, Color.BLACK) { openPdfPicker() },
            lp(dp(260), dp(52))
        )
        if (renderer != null) {
            col.addView(
                pill(
                    tr("متابعة: ", "Continue: ") + currentName,
                    panel, white, green
                ) { showReader() },
                lpm(dp(260), dp(48), 0f, 0, dp(10), 0, 0)
            )
        }

        frame.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        frame.addView(
            txt("⋮", 28f, gold, true, true).apply {
                setOnClickListener {
                    showMenu(
                        listOf(
                            tr("الإعدادات", "Settings") to { showSettings() },
                            tr("معلومات التطبيق", "About app") to { showAbout() }
                        )
                    )
                }
            },
            FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.LEFT)
        )
        setScreen("home", frame)
    }

    // ───────────── Reader ─────────────
    private fun showReader() {
        if (renderer == null) {
            showHome()
            return
        }
        val r = screenRoot()
        r.addView(if (editMode) editHeader() else readerHeader())
        if (editMode) r.addView(toolSegment())

        val frame = FrameLayout(this).apply { background = rounded(Color.rgb(24, 28, 26), 10) }
        val iv = ZoomImageView(this)
        pageImage = iv
        iv.onTap = { fx, fy -> if (editMode) placePending(fx, fy) }
        iv.onSwipe = { d -> changePage(d) }
        iv.onZoom = { s -> zoomLabel?.text = "${(s * 100).toInt()}%" }
        frame.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))

        if (renderQuality >= 2) {
            val badge = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(Color.argb(215, 10, 24, 20), 12, green)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutDirection = dir()
            }
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            c.addView(txt(tr("جودة عالية", "High quality"), 12f, green, true))
            c.addView(txt(tr("دقة عرض متميزة", "Premium display"), 10f, muted))
            badge.addView(c)
            badge.addView(
                txt("HD", 13f, green, true, true).apply {
                    background = rounded(Color.TRANSPARENT, 6, green)
                    setPadding(dp(5), dp(2), dp(5), dp(2))
                },
                lpm(WRAP, WRAP, 0f, dp(8), 0, 0, 0)
            )
            frame.addView(
                badge,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.RIGHT)
                    .apply { setMargins(dp(8), dp(8), dp(8), 0) }
            )
        }

        if (editMode) {
            val tools = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.argb(225, 10, 24, 20), 14, green)
                setPadding(dp(2), dp(4), dp(2), dp(4))
            }
            tools.addView(txt("T", 22f, green, true, true).apply { setOnClickListener { startTool(0) } }, lp(dp(46), dp(46)))
            tools.addView(icon(android.R.drawable.ic_menu_gallery, green).apply { setOnClickListener { startTool(1) } })
            tools.addView(icon(android.R.drawable.ic_menu_edit, green).apply { setOnClickListener { startTool(2) } })
            tools.addView(icon(android.R.drawable.ic_menu_preferences, green).apply { setOnClickListener { showWatermarkDialog() } })
            frame.addView(
                tools,
                FrameLayout.LayoutParams(WRAP, WRAP, Gravity.RIGHT or Gravity.CENTER_VERTICAL)
                    .apply { setMargins(0, 0, dp(6), 0) }
            )
        }

        r.addView(frame, lpm(MATCH, 0, 1f, 0, dp(6), 0, dp(6)))

        // Bottom bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(panel, 16, line)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutDirection = dir()
        }
        if (editMode) {
            bar.addView(tab(null, "T", tr("نص", "Text"), false) { startTool(0) }, lp(0, WRAP, 1f))
            bar.addView(tab(android.R.drawable.ic_menu_gallery, null, tr("إضافة صورة", "Image"), false) { startTool(1) }, lp(0, WRAP, 1f))
            bar.addView(tab(android.R.drawable.ic_menu_edit, null, tr("توقيع", "Signature"), false) { startTool(2) }, lp(0, WRAP, 1f))
            bar.addView(
                tab(android.R.drawable.ic_menu_delete, null, tr("حذف", "Delete"), false) { undoEdit() }
                    .apply { setOnLongClickListener { redoEdit(); true } },
                lp(0, WRAP, 1f)
            )
        } else {
            bar.addView(tab(android.R.drawable.ic_menu_edit, null, tr("تعديل", "Edit"), true) { toggleEdit() }, lp(0, WRAP, 1f))
            bar.addView(tab(null, "📋", tr("نسخ النص", "Copy text"), false) { showCopyDialog() }, lp(0, WRAP, 1f))
            bar.addView(tab(android.R.drawable.ic_menu_search, null, tr("بحث", "Search"), false) { showSearch(lastQuery) }, lp(0, WRAP, 1f))
            bar.addView(tab(android.R.drawable.ic_menu_more, null, tr("المزيد", "More"), false) { showMoreMenu() }, lp(0, WRAP, 1f))
        }
        r.addView(bar)

        // Navigation + zoom
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        nav.addView(circleBtn("←") { changePage(-1) }, lpm(dp(44), dp(44), 0f, 0, dp(8), 0, 0))
        val zoomBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(panel, 22, line)
        }
        zoomBox.addView(txt("−", 22f, white, true, true).apply { setOnClickListener { pageImage?.zoomBy(-0.25f) } }, lp(dp(48), dp(44)))
        zoomLabel = txt("100%", 15f, white, true, true)
        zoomBox.addView(zoomLabel, lp(dp(64), dp(44)))
        zoomBox.addView(txt("+", 22f, white, true, true).apply { setOnClickListener { pageImage?.zoomBy(0.25f) } }, lp(dp(48), dp(44)))
        nav.addView(zoomBox, lpm(WRAP, dp(44), 0f, dp(14), dp(8), dp(14), 0))
        nav.addView(circleBtn("→") { changePage(1) }, lpm(dp(44), dp(44), 0f, 0, dp(8), 0, 0))
        r.addView(nav)

        setScreen("reader", r)
        updateStatus()
        renderCurrentPage()
        if (autoScroll && !editMode) handler.postDelayed(autoRunnable, 8000)
    }

    private fun circleBtn(label: String, onClick: () -> Unit) =
        txt(label, 20f, white, true, true).apply {
            background = rounded(panel, 22, line)
            setOnClickListener { onClick() }
        }

    private fun tab(
        iconRes: Int?,
        emoji: String?,
        label: String,
        active: Boolean,
        onClick: () -> Unit
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
        if (active) background = rounded(darkGreen, 12, green)
        val tint = if (active) green else white
        if (iconRes != null) {
            addView(ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
            }, lp(dp(24), dp(24)))
        } else {
            addView(txt(emoji ?: "", 20f, tint, true, true), lp(dp(28), dp(26)))
        }
        addView(txt(label, 11f, tint, false, true))
        setOnClickListener { onClick() }
    }

    private fun readerHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(2), 0, dp(4))
        }
        row.addView(
            txt("←", 26f, white, false, true).apply { setOnClickListener { showHome() } },
            lp(dp(44), dp(48))
        )
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(txt(currentName, 16f, white, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        pageLabel = txt("", 12f, muted)
        titles.addView(pageLabel)
        row.addView(titles, lp(0, WRAP, 1f))
        row.addView(icon(android.R.drawable.ic_menu_search).apply { setOnClickListener { showSearch(lastQuery) } })
        row.addView(txt("🔖", 20f, gold, false, true).apply { setOnClickListener { saveBookmark() } }, lp(dp(44), dp(44)))
        row.addView(txt("↕", 22f, white, true, true).apply { setOnClickListener { showGoToDialog() } }, lp(dp(44), dp(44)))
        return row
    }

    private fun editHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(0, dp(2), 0, dp(6))
        }
        row.addView(pill(tr("حفظ", "Save"), green, Color.BLACK) { onSaveClicked() }, lp(dp(84), dp(42)))
        row.addView(txt(tr("وضع التعديل", "Edit mode"), 19f, white, true, true), lp(0, WRAP, 1f))
        row.addView(
            txt("→", 26f, white, false, true).apply {
                setOnClickListener {
                    editMode = false
                    pending = null
                    showReader()
                }
            },
            lp(dp(48), dp(48))
        )
        pageLabel = null
        return row
    }

    private fun toolSegment(): View {
        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(panel, 14, line)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutDirection = dir()
        }
        val names = listOf(tr("نص", "Text"), tr("صورة", "Image"), tr("توقيع", "Signature"))
        names.forEachIndexed { i, n ->
            val on = i == activeTool
            seg.addView(
                txt(n, 14f, if (on) green else white, on, true).apply {
                    background = if (on) rounded(darkGreen, 11, green) else null
                    setPadding(0, dp(9), 0, dp(9))
                    setOnClickListener { startTool(i) }
                },
                lp(0, WRAP, 1f)
            )
        }
        return seg
    }

    private fun showMoreMenu() {
        showMenu(
            listOf(
                tr("حفظ نسخة PDF", "Save PDF copy") to { onSaveClicked() },
                tr("الذهاب إلى صفحة", "Go to page") to { showGoToDialog() },
                tr("فتح ملف آخر", "Open another PDF") to { openPdfPicker() },
                tr("الإعدادات", "Settings") to { showSettings() },
                tr("معلومات التطبيق", "About app") to { showAbout() }
            )
        )
    }

    private fun toggleEdit() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }
        editMode = !editMode
        if (!editMode) pending = null
        showReader()
    }

    private fun updateStatus() {
        pageLabel?.text = tr(
            "الصفحة ${currentPage + 1} من $pageCount",
            "Page ${currentPage + 1} of $pageCount"
        )
    }

    private fun changePage(direction: Int) {
        if (renderer == null) return
        val next = currentPage + direction
        if (next !in 0 until pageCount) return
        currentPage = next
        pageImage?.resetZoom()
        renderCurrentPage()
        updateStatus()
    }

    private fun showGoToDialog() {
        showCard { card, d ->
            card.addView(txt(tr("الذهاب إلى صفحة", "Go to page"), 18f, gold, true, true))
            val f = field("1 - $pageCount").apply { inputType = InputType.TYPE_CLASS_NUMBER }
            card.addView(f, lpm(MATCH, WRAP, 0f, 0, dp(12), 0, dp(12)))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pill(tr("إلغاء", "Cancel"), panel2, white, line) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            row.addView(pill(tr("انتقال", "Go"), green, Color.BLACK) {
                val n = f.text.toString().toIntOrNull()
                if (n != null && n in 1..pageCount) {
                    currentPage = n - 1
                    d.dismiss()
                    pageImage?.resetZoom()
                    renderCurrentPage()
                    updateStatus()
                } else toast(tr("رقم صفحة غير صحيح", "Invalid page number"))
            }, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun bookmarkKey() = "bm_" + (currentUri?.toString()?.hashCode() ?: 0)

    private fun saveBookmark() {
        prefs.edit().putInt(bookmarkKey(), currentPage).apply()
        toast(tr("تم حفظ العلامة في الصفحة ${currentPage + 1}", "Bookmarked page ${currentPage + 1}"))
    }

    // ───────────── PDF open / render ─────────────
    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestOpenPdf)
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, requestAddImage)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!

        when (requestCode) {
            requestOpenPdf -> {
                try {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                        try {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {
                        }
                    }
                    openPdf(uri)
                    showReader()
                } catch (e: Exception) {
                    toast(tr("تعذر فتح الملف: ${e.message}", "Could not open file: ${e.message}"))
                }
            }
            requestSavePdf -> saveCopyTo(uri)
            requestAddImage -> addImageFrom(uri)
        }
    }

    private fun queryName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            } ?: "document.pdf"
        } catch (_: Exception) {
            "document.pdf"
        }
    }

    private fun openRenderer(uri: Uri) {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open PDF")
        fileDescriptor = descriptor
        renderer = PdfRenderer(descriptor)
        pageCount = renderer!!.pageCount
    }

    private fun openPdf(uri: Uri) {
        closePdf()
        openRenderer(uri)
        currentUri = uri
        currentName = queryName(uri)
        currentPage = prefs.getInt(bookmarkKey(), 0).coerceIn(0, max(0, pageCount - 1))

        overlays.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        redoOverlays.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        overlays.clear()
        redoOverlays.clear()
        pending = null
        editMode = false
        addRecent(uri, currentName)
    }

    private fun closePdf() {
        synchronized(renderLock) {
            try { renderer?.close() } catch (_: Exception) {}
            renderer = null
            try { fileDescriptor?.close() } catch (_: Exception) {}
            fileDescriptor = null
        }
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
    }

    private fun pageSize(index: Int): PointF? = synchronized(renderLock) {
        val rr = renderer ?: return@synchronized null
        try {
            val p = rr.openPage(index)
            val s = PointF(p.width.toFloat(), p.height.toFloat())
            p.close()
            s
        } catch (_: Exception) {
            null
        }
    }

    private fun drawOverlaysOn(bitmap: Bitmap, pageIndex: Int, k: Float) {
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        for (o in overlays) {
            if (o.pageIndex != pageIndex) continue
            val w = o.widthPts * k
            val h = w * o.bitmap.height / o.bitmap.width
            val left = o.nx * bitmap.width
            val top = o.ny * bitmap.height
            canvas.drawBitmap(o.bitmap, null, RectF(left, top, left + w, top + h), paint)
        }
    }

    private fun renderCurrentPage() {
        val pdf = renderer ?: return
        val iv = pageImage ?: return
        if (currentPage !in 0 until pageCount) return

        try {
            val bitmap = synchronized(renderLock) {
                val page = pdf.openPage(currentPage)
                try {
                    val pw = page.width
                    val ph = page.height
                    val screenW = resources.displayMetrics.widthPixels
                    val base = min(screenW.toFloat() / pw, 2.5f).coerceAtLeast(1f)
                    val k = base * renderQuality / 2f
                    val bmp = Bitmap.createBitmap(
                        max(1, (pw * k).toInt()),
                        max(1, (ph * k).toInt()),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    drawOverlaysOn(bmp, currentPage, k)
                    bmp
                } finally {
                    page.close()
                }
            }

            val previous = currentBitmap
            currentBitmap = bitmap
            iv.colorFilter = if (nightMode) {
                ColorMatrixColorFilter(
                    ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            } else null
            iv.setImageBitmap(bitmap)
            if (previous != null && previous !== bitmap && !previous.isRecycled) previous.recycle()
        } catch (e: Exception) {
            toast(tr("حدث خطأ أثناء عرض الصفحة", "Could not render page"))
        }
    }

    private fun renderThumb(index: Int): Bitmap? = synchronized(renderLock) {
        val rr = renderer ?: return@synchronized null
        try {
            val p = rr.openPage(index)
            val w = 140
            val h = max(1, (p.height * (w.toFloat() / p.width)).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            p.close()
            bmp
        } catch (_: Exception) {
            null
        }
    }

    // ───────────── Text extraction / copy ─────────────
    private fun extractText(): String {
        val uri = currentUri ?: return ""
        return try {
            val input = contentResolver.openInputStream(uri) ?: return ""
            input.use {
                val document = PDDocument.load(it)
                try {
                    val stripper = PDFTextStripper()
                    stripper.startPage = currentPage + 1
                    stripper.endPage = currentPage + 1
                    stripper.getText(document)
                } finally {
                    document.close()
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun showCopyDialog() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }
        val text = extractText().trim()
        if (text.isBlank()) {
            toast(tr("لم يتم العثور على نص قابل للاستخراج في الصفحة", "No extractable text found on this page"))
            return
        }
        showCard { card, d ->
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(txt(tr("نص الصفحة", "Page text"), 18f, white, true), lp(0, WRAP, 1f))
            top.addView(txt("✕", 20f, white, true, true).apply { setOnClickListener { d.dismiss() } }, lp(dp(40), dp(40)))
            card.addView(top)

            val et = EditText(this).apply {
                setText(text)
                setTextColor(white)
                textSize = 15f
                background = rounded(panel2, 12, line)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                maxLines = 10
                setTextIsSelectable(true)
                showSoftInputOnFocus = false
            }
            card.addView(et, lpm(MATCH, WRAP, 0f, 0, dp(10), 0, dp(12)))

            card.addView(
                pill("📋  " + tr("نسخ النص", "Copy text"), green, Color.BLACK) {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("PDF text", text))
                    toast(tr("تم نسخ النص", "Text copied"))
                },
                lp(MATCH, dp(50))
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pill(tr("إغلاق", "Close"), panel2, white, green) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            row.addView(pill(tr("تحديد الكل", "Select all"), panel2, white, green) {
                et.requestFocus()
                et.selectAll()
            }, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row, lpm(MATCH, WRAP, 0f, 0, dp(8), 0, 0))
        }
    }

    // ───────────── Search ─────────────
    private fun searchAll(query: String): List<SearchHit> {
        val uri = currentUri ?: return emptyList()
        val hits = mutableListOf<SearchHit>()
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val doc = PDDocument.load(input)
                try {
                    val stripper = PDFTextStripper()
                    for (p in 1..doc.numberOfPages) {
                        stripper.startPage = p
                        stripper.endPage = p
                        val text = stripper.getText(doc).replace(Regex("\\s+"), " ")
                        var idx = text.indexOf(query, ignoreCase = true)
                        if (idx < 0) continue
                        val first = idx
                        var count = 0
                        while (idx >= 0) {
                            count++
                            idx = text.indexOf(query, idx + query.length, ignoreCase = true)
                        }
                        val s = max(0, first - 20)
                        val e = min(text.length, first + query.length + 30)
                        val prefix = if (s > 0) "…" else ""
                        val snippet = prefix + text.substring(s, e) + if (e < text.length) "…" else ""
                        hits.add(SearchHit(p - 1, snippet, prefix.length + (first - s), query.length, count))
                        if (hits.size >= 100) break
                    }
                } finally {
                    doc.close()
                }
            }
        } catch (_: Exception) {
        }
        return hits
    }

    private fun showSearch(initial: String) {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }
        val r = screenRoot()
        r.addView(header(tr("نتائج البحث", "Search results"), { onBackPressedCompat() }))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(panel, 14, line)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        box.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            setColorFilter(green)
        }, lp(dp(24), dp(24)))
        val input = EditText(this).apply {
            setText(initial)
            hint = tr("ابحث داخل الملف", "Search in file")
            setTextColor(white)
            setHintTextColor(muted)
            background = null
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(input, lp(0, WRAP, 1f))
        box.addView(txt("✕", 18f, white, true, true).apply { setOnClickListener { input.setText("") } }, lp(dp(36), dp(36)))
        r.addView(box, lp(MATCH, WRAP))

        val count = txt("", 14f, green, true)
        r.addView(count, lpm(WRAP, WRAP, 0f, dp(4), dp(8), 0, dp(4)))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        r.addView(ScrollView(this).apply { addView(list) }, lp(MATCH, 0, 1f))

        fun doSearch() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            lastQuery = q
            count.text = tr("جارٍ البحث...", "Searching...")
            list.removeAllViews()
            Thread {
                val hits = searchAll(q)
                runOnUiThread {
                    if (screen == "search") showHits(hits, list, count)
                }
            }.start()
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch()
                true
            } else false
        }

        setScreen("search", r)
        if (initial.isNotBlank()) doSearch()
    }

    private fun showHits(hits: List<SearchHit>, list: LinearLayout, count: TextView) {
        count.text = tr("${hits.size} نتائج", "${hits.size} results")
        val thumbs = mutableListOf<Pair<Int, ImageView>>()
        for (h in hits) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(8), dp(6), dp(8))
                setOnClickListener {
                    currentPage = h.page
                    showReader()
                }
            }
            val iv = ImageView(this).apply {
                setBackgroundColor(Color.WHITE)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            thumbs.add(h.page to iv)
            row.addView(iv, lpm(dp(54), dp(72), 0f, 0, 0, dp(12), 0))

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(txt(tr("الصفحة ${h.page + 1}", "Page ${h.page + 1}"), 15f, white, true))
            val sp = SpannableString(h.snippet)
            sp.setSpan(
                ForegroundColorSpan(green), h.start,
                min(h.snippet.length, h.start + h.len), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            col.addView(txt("", 13f, muted).apply { text = sp; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            col.addView(txt(tr("(تطابق ${h.count})", "(${h.count} matches)"), 12f, muted))
            row.addView(col, lp(0, WRAP, 1f))
            row.addView(txt(if (isArabic()) "‹" else "›", 22f, muted, true, true))

            list.addView(row)
            list.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        }
        Thread {
            for ((page, iv) in thumbs) {
                val b = renderThumb(page) ?: continue
                runOnUiThread { iv.setImageBitmap(b) }
            }
        }.start()
    }

    private fun onBackPressedCompat() {
        @Suppress("DEPRECATION")
        onBackPressed()
    }

    // ───────────── Edit tools ─────────────
    private fun requireEdit(): Boolean {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return false
        }
        if (!editMode) {
            toast(tr("فعّل وضع التعديل أولًا", "Enable edit mode first"))
            return false
        }
        return true
    }

    private fun startTool(i: Int) {
        if (!requireEdit()) return
        activeTool = i
        when (i) {
            0 -> showAddTextDialog()
            1 -> openImagePicker()
            2 -> showSignatureDialog()
        }
    }

    private fun startPending(bitmap: Bitmap, widthPts: Float) {
        pending = Pending(bitmap, widthPts)
        toast(tr("اضغط على الصفحة لتحديد مكان العنصر", "Tap the page to place the item"))
    }

    private fun placePending(fx: Float, fy: Float) {
        val p = pending ?: run {
            toast(tr("اختر أداة أولًا (نص / صورة / توقيع)", "Pick a tool first"))
            return
        }
        val size = pageSize(currentPage) ?: return
        val wFrac = p.widthPts / size.x
        val hFrac = (p.widthPts * p.bitmap.height / p.bitmap.width) / size.y
        val nx = (fx - wFrac / 2f).coerceIn(0f, max(0f, 1f - wFrac))
        val ny = (fy - hFrac / 2f).coerceIn(0f, max(0f, 1f - hFrac))
        overlays.add(Overlay(currentPage, p.bitmap, nx, ny, p.widthPts))
        clearRedo()
        pending = null
        renderCurrentPage()
    }

    private fun clearRedo() {
        redoOverlays.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        redoOverlays.clear()
    }

    private fun undoEdit() {
        val i = overlays.indexOfLast { it.pageIndex == currentPage }
        if (i < 0) {
            toast(tr("لا توجد عناصر لحذفها في هذه الصفحة", "Nothing to delete on this page"))
            return
        }
        redoOverlays.add(overlays.removeAt(i))
        renderCurrentPage()
        toast(tr("تم الحذف (اضغط مطولًا لإعادة العنصر)", "Deleted (long-press to restore)"))
    }

    private fun redoEdit() {
        if (redoOverlays.isEmpty()) {
            toast(tr("لا توجد عناصر لإعادتها", "Nothing to restore"))
            return
        }
        overlays.add(redoOverlays.removeAt(redoOverlays.lastIndex))
        renderCurrentPage()
    }

    private fun showAddTextDialog() {
        showCard { card, d ->
            card.addView(txt(tr("إضافة نص جديد", "Add new text"), 18f, gold, true, true))
            val input = field(tr("اكتب النص الذي تريد إضافته", "Enter text to add")).apply { minLines = 2 }
            val size = field(tr("حجم الخط", "Font size"), fontSizes[fontIdx].toString())
                .apply { inputType = InputType.TYPE_CLASS_NUMBER }
            card.addView(input, lpm(MATCH, WRAP, 0f, 0, dp(12), 0, dp(8)))
            card.addView(size, lpm(MATCH, WRAP, 0f, 0, 0, 0, dp(12)))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pill(tr("إلغاء", "Cancel"), panel2, white, line) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            row.addView(pill(tr("إضافة", "Add"), green, Color.BLACK) {
                val t = input.text.toString()
                val s = (size.text.toString().toFloatOrNull() ?: 24f).coerceIn(8f, 100f)
                if (t.isNotBlank()) {
                    d.dismiss()
                    val bmp = makeTextBitmap(t, s)
                    startPending(bmp, bmp.width / 3f)
                }
            }, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun makeTextBitmap(text: String, sizePts: Float): Bitmap {
        val scale = 3f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = sizePts * scale
        }
        val lines = text.split("\n")
        val maxW = lines.map { paint.measureText(it) }.maxOrNull() ?: 1f
        val w = ceil(maxW).toInt() + 8
        val lh = paint.textSize * 1.3f
        val h = ceil(lh * lines.size + paint.textSize * 0.3f).toInt() + 4
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        var y = paint.textSize
        for (l in lines) {
            canvas.drawText(l, 4f, y, paint)
            y += lh
        }
        return bmp
    }

    private fun addImageFrom(uri: Uri) {
        if (!requireEdit()) return
        try {
            val input = contentResolver.openInputStream(uri) ?: throw IllegalStateException("read")
            var source = input.use { BitmapFactory.decodeStream(it) } ?: throw IllegalStateException("decode")
            if (source.width > 1600) {
                val f = 1600f / source.width
                val scaled = Bitmap.createScaledBitmap(source, 1600, max(1, (source.height * f).toInt()), true)
                if (scaled !== source) source.recycle()
                source = scaled
            }
            val size = pageSize(currentPage) ?: return
            startPending(source, size.x * 0.4f)
        } catch (e: Exception) {
            toast(tr("تعذر إضافة الصورة", "Could not add image"))
        }
    }

    private fun showSignatureDialog() {
        showCard { card, d ->
            card.addView(txt(tr("ارسم توقيعك", "Draw your signature"), 18f, gold, true, true))
            val sig = SignatureView(this)
            card.addView(sig, lpm(MATCH, dp(220), 0f, 0, dp(12), 0, dp(12)))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pill(tr("مسح", "Clear"), panel2, white, line) { sig.clear() }, lpm(0, WRAP, 1f, 0, 0, dp(4), 0))
            row.addView(pill(tr("إلغاء", "Cancel"), panel2, white, line) { d.dismiss() }, lpm(0, WRAP, 1f, dp(4), 0, dp(4), 0))
            row.addView(pill(tr("إضافة", "Add"), green, Color.BLACK) {
                val bmp = sig.exportBitmap()
                if (bmp == null) {
                    toast(tr("ارسم التوقيع أولًا", "Draw a signature first"))
                } else {
                    d.dismiss()
                    val size = pageSize(currentPage)
                    startPending(bmp, (size?.x ?: 595f) * 0.3f)
                }
            }, lpm(0, WRAP, 1f, dp(4), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun showWatermarkDialog() {
        if (!requireEdit()) return
        showCard { card, d ->
            card.addView(txt(tr("إضافة علامة مائية", "Add watermark"), 18f, gold, true, true))
            val input = field(tr("اكتب العلامة المائية", "Enter watermark"))
            card.addView(input, lpm(MATCH, WRAP, 0f, 0, dp(12), 0, dp(12)))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pill(tr("إلغاء", "Cancel"), panel2, white, line) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            row.addView(pill(tr("إضافة", "Add"), green, Color.BLACK) {
                val t = input.text.toString()
                if (t.isNotBlank()) {
                    d.dismiss()
                    val size = pageSize(currentPage) ?: return@pill
                    val bmp = makeWatermarkBitmap(t, size.x, size.y)
                    overlays.add(Overlay(currentPage, bmp, 0f, 0f, size.x))
                    clearRedo()
                    renderCurrentPage()
                }
            }, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun makeWatermarkBitmap(text: String, wPts: Float, hPts: Float): Bitmap {
        val bmp = Bitmap.createBitmap(
            max(1, (wPts * 2).toInt()), max(1, (hPts * 2).toInt()), Bitmap.Config.ARGB_8888
        )
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 20, 120, 75)
            textSize = bmp.width * 0.075f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.save()
        canvas.rotate(-35f, bmp.width / 2f, bmp.height / 2f)
        canvas.drawText(text, bmp.width / 2f, bmp.height / 2f, paint)
        canvas.restore()
        return bmp
    }

    // ───────────── Saving ─────────────
    private fun baseName(): String = currentName.removeSuffix(".pdf").removeSuffix(".PDF")

    private fun onSaveClicked() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }
        if (autoSaveCopy) {
            launchSaveCopy(baseName() + "_edited.pdf")
        } else {
            showSaveScreen()
        }
    }

    private fun launchSaveCopy(name: String) {
        pendingSaveName = name
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, requestSavePdf)
    }

    private fun showSaveScreen() {
        if (currentUri == null) {
            showHome()
            return
        }
        val r = screenRoot()
        r.addView(header(tr("تعديل وحفظ", "Edit & save"), { onBackPressedCompat() }))

        val seg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(panel, 14, line)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val nameField = field(tr("اسم الملف", "File name"), baseName() + "_edited.pdf")
        fun segBtn(label: String, on: Boolean, click: () -> Unit) =
            txt(label, 14f, if (on) green else white, on, true).apply {
                background = if (on) rounded(darkGreen, 11, green) else null
                setPadding(0, dp(11), 0, dp(11))
                setOnClickListener { click() }
            }
        seg.addView(segBtn(tr("حفظ كملف جديد", "Save as new file"), !saveToOriginal) {
            saveToOriginal = false; showSaveScreen()
        }, lp(0, WRAP, 1f))
        seg.addView(segBtn(tr("حفظ كملف الأصلي", "Overwrite original"), saveToOriginal) {
            saveToOriginal = true; showSaveScreen()
        }, lp(0, WRAP, 1f))
        r.addView(seg, lp(MATCH, WRAP))

        r.addView(txt(tr("اسم الملف", "File name"), 14f, muted), lpm(WRAP, WRAP, 0f, dp(4), dp(18), 0, dp(6)))
        if (saveToOriginal) {
            nameField.setText(currentName)
            nameField.isEnabled = false
        }
        r.addView(nameField, lp(MATCH, WRAP))
        r.addView(
            txt(
                if (saveToOriginal)
                    tr("سيتم استبدال الملف الأصلي. لا يمكن التراجع بعد الحفظ.", "The original file will be replaced. This cannot be undone.")
                else
                    tr("سيطلب منك النظام اختيار المجلد (مثل Documents).", "The system will ask you to pick the folder (e.g. Documents)."),
                13f, if (saveToOriginal) red else muted
            ),
            lpm(WRAP, WRAP, 0f, dp(4), dp(10), 0, dp(14))
        )
        r.addView(
            pill("💾  " + tr("حفظ", "Save"), green, Color.BLACK) {
                if (saveToOriginal) confirmOverwrite()
                else {
                    var n = nameField.text.toString().trim().ifEmpty { baseName() + "_edited.pdf" }
                    if (!n.lowercase().endsWith(".pdf")) n += ".pdf"
                    launchSaveCopy(n)
                }
            },
            lp(MATCH, dp(54))
        )
        setScreen("save", r)
    }

    private fun confirmOverwrite() {
        showCard { card, d ->
            card.addView(txt(tr("استبدال الملف الأصلي؟", "Replace the original?"), 18f, gold, true, true))
            card.addView(
                txt(tr("سيتم حفظ التعديلات داخل نفس الملف.", "Edits will be written into the same file."), 14f, white, false, true),
                lpm(MATCH, WRAP, 0f, 0, dp(10), 0, dp(14))
            )
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(pill(tr("إلغاء", "Cancel"), panel2, white, line) { d.dismiss() }, lpm(0, WRAP, 1f, 0, 0, dp(6), 0))
            row.addView(pill(tr("استبدال", "Replace"), red, Color.BLACK) {
                d.dismiss()
                overwriteOriginal()
            }, lpm(0, WRAP, 1f, dp(6), 0, 0, 0))
            card.addView(row)
        }
    }

    private fun buildEditedPdf(source: ByteArray): ByteArray {
        val doc = PDDocument.load(ByteArrayInputStream(source))
        try {
            if (defaultWatermark) {
                val cache = HashMap<String, PDImageXObject>()
                for (i in 0 until doc.numberOfPages) {
                    val page = doc.getPage(i)
                    val box = page.mediaBox
                    val key = "${box.width.toInt()}x${box.height.toInt()}"
                    val img = cache.getOrPut(key) {
                        val wb = makeWatermarkBitmap("BLACK CLOVER", box.width, box.height)
                        val x = LosslessFactory.createFromImage(doc, wb)
                        wb.recycle()
                        x
                    }
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                        it.drawImage(img, box.lowerLeftX, box.lowerLeftY, box.width, box.height)
                    }
                }
            }
            for (o in overlays) {
                if (o.pageIndex !in 0 until doc.numberOfPages) continue
                val page = doc.getPage(o.pageIndex)
                val box = page.mediaBox
                val image = LosslessFactory.createFromImage(doc, o.bitmap)
                val w = o.widthPts
                val h = w * o.bitmap.height / o.bitmap.width
                val x = box.lowerLeftX + o.nx * box.width
                val y = box.upperRightY - o.ny * box.height - h
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use {
                    it.drawImage(image, x, y, w, h)
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun saveCopyTo(destination: Uri) {
        val src = currentUri ?: return
        try {
            val bytes = contentResolver.openInputStream(src)!!.use { it.readBytes() }
            val edited = buildEditedPdf(bytes)
            contentResolver.openOutputStream(destination, "w")!!.use { it.write(edited) }
            toast(tr("تم حفظ نسخة PDF الجديدة", "New PDF copy saved"))
        } catch (e: Exception) {
            toast(tr("تعذر حفظ الملف: ${e.message}", "Could not save PDF: ${e.message}"))
        }
    }

    private fun overwriteOriginal() {
        val uri = currentUri ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val edited = buildEditedPdf(bytes)
            closePdf()
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(edited) }
                overlays.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                overlays.clear()
                clearRedo()
                toast(tr("تم حفظ التعديلات في الملف الأصلي", "Saved to the original file"))
            } catch (e: Exception) {
                toast(
                    tr(
                        "تعذر الكتابة على الملف الأصلي، احفظ نسخة جديدة بدلًا من ذلك",
                        "Could not write to the original, save a new copy instead"
                    )
                )
            } finally {
                openRenderer(uri)
                currentPage = currentPage.coerceIn(0, max(0, pageCount - 1))
            }
            editMode = false
            showReader()
        } catch (e: Exception) {
            toast(tr("تعذر الحفظ: ${e.message}", "Could not save: ${e.message}"))
        }
    }

    // ───────────── Settings ─────────────
    private fun chevron() = txt(if (isArabic()) "‹" else "›", 24f, muted, true, true)

    private fun toggle(checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(white, muted))
        trackTintList = ColorStateList(states, intArrayOf(green, Color.rgb(60, 66, 64)))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun settingRow(
        title: String,
        subtitle: String? = null,
        trailing: View? = null,
        onClick: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutDirection = dir()
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(title, 15f, white, true))
        if (subtitle != null) col.addView(txt(subtitle, 12f, muted))
        row.addView(col, lp(0, WRAP, 1f))
        if (trailing != null) row.addView(trailing)
        if (onClick != null) row.setOnClickListener { onClick() }
        return row
    }

    private fun section(parent: LinearLayout, title: String, rows: List<View>) {
        parent.addView(txt(title, 15f, green, true), lpm(WRAP, WRAP, 0f, dp(6), dp(14), 0, dp(6)))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(panel, 16, line)
        }
        rows.forEachIndexed { i, v ->
            if (i > 0) card.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
            card.addView(v)
        }
        parent.addView(card, lp(MATCH, WRAP))
    }

    private fun qualityName() = when (renderQuality) {
        1 -> tr("عادية", "Normal")
        2 -> tr("عالية (موصى بها)", "High (recommended)")
        else -> tr("أعلى", "Highest")
    }

    private fun fontName() = when (fontIdx) {
        0 -> tr("صغير", "Small")
        1 -> tr("متوسط", "Medium")
        else -> tr("كبير", "Large")
    }

    private fun putBool(key: String, v: Boolean) = prefs.edit().putBoolean(key, v).apply()

    private fun showSettings() {
        val r = screenRoot()
        r.addView(header(tr("الإعدادات", "Settings"), { onBackPressedCompat() }))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        section(
            col, tr("خيارات العرض", "Display options"), listOf(
                settingRow(
                    tr("جودة العرض", "Render quality"), qualityName(),
                    txt("HD", 13f, green, true, true).apply {
                        background = rounded(Color.TRANSPARENT, 6, green)
                        setPadding(dp(6), dp(2), dp(6), dp(2))
                    }
                ) { showQualityDialog() },
                settingRow(tr("وضع القراءة الليلي", "Night reading mode"), null,
                    toggle(nightMode) { nightMode = it; putBool("night", it) }),
                settingRow(tr("التمرير التلقائي", "Auto scroll"), null,
                    toggle(autoScroll) { autoScroll = it; putBool("autoscroll", it) }),
                settingRow(tr("تدوير تلقائي", "Auto rotate"), null,
                    toggle(autoRotate) { autoRotate = it; putBool("autorotate", it); applyOrientation() }),
                settingRow(tr("حجم الخط الافتراضي", "Default font size"), fontName(), chevron()) { showFontDialog() }
            )
        )
        section(
            col, tr("خيارات التعديل", "Edit options"), listOf(
                settingRow(tr("حفظ النسخة المعدلة تلقائيًا", "Auto-save edited copy"),
                    tr("الحفظ مباشرة كنسخة جديدة بدون شاشة الخيارات", "Save straight as a new copy"),
                    toggle(autoSaveCopy) { autoSaveCopy = it; putBool("autosave", it) }),
                settingRow(tr("إضافة علامة مائية", "Add watermark"),
                    tr("تُضاف إلى كل صفحات النسخة المحفوظة", "Added to every page when saving"),
                    toggle(defaultWatermark) { defaultWatermark = it; putBool("watermark", it) })
            )
        )
        section(
            col, tr("أخرى", "Other"), listOf(
                settingRow(tr("إدارة الملفات", "Manage files"), null, chevron()) { showFiles() },
                settingRow(tr("حذف الملفات المؤقتة", "Delete temporary files"), null, chevron()) {
                    var n = 0
                    cacheDir.listFiles()?.forEach { it.deleteRecursively(); n++ }
                    toast(tr("تم حذف $n عنصر", "Deleted $n items"))
                },
                settingRow(tr("تغيير اللغة", "Change language"), if (isArabic()) "العربية" else "English", chevron()) {
                    showLanguageDialog()
                }
            )
        )
        r.addView(ScrollView(this).apply { addView(col) }, lp(MATCH, 0, 1f))
        setScreen("settings", r)
    }

    private fun showQualityDialog() {
        showMenu(
            listOf(
                tr("جودة العرض: عادية", "Quality: Normal") to { setQuality(1) },
                tr("جودة العرض: عالية", "Quality: High") to { setQuality(2) },
                tr("جودة العرض: أعلى", "Quality: Highest") to { setQuality(3) }
            )
        )
    }

    private fun setQuality(q: Int) {
        renderQuality = q
        prefs.edit().putInt("quality", q).apply()
        showSettings()
    }

    private fun showFontDialog() {
        showMenu(
            listOf(
                tr("صغير (18)", "Small (18)") to { setFont(0) },
                tr("متوسط (24)", "Medium (24)") to { setFont(1) },
                tr("كبير (32)", "Large (32)") to { setFont(2) }
            )
        )
    }

    private fun setFont(i: Int) {
        fontIdx = i
        prefs.edit().putInt("fontIdx", i).apply()
        showSettings()
    }

    private fun showLanguageDialog() {
        showMenu(
            listOf(
                "العربية" to { setLanguage("ar") },
                "English" to { setLanguage("en") }
            )
        )
    }

    private fun setLanguage(l: String) {
        selectedLanguage = l
        prefs.edit().putString("language", l).apply()
        showSettings()
    }

    // ───────────── Files ─────────────
    private fun addRecent(uri: Uri, name: String) {
        val list = getRecent().filter { it.first != uri.toString() }.toMutableList()
        list.add(0, uri.toString() to name)
        prefs.edit().putString("recent", list.take(10).joinToString("\n") { it.first + "|" + it.second }).apply()
    }

    private fun getRecent(): List<Pair<String, String>> =
        (prefs.getString("recent", "") ?: "").split("\n")
            .filter { it.contains("|") }
            .map { val i = it.indexOf('|'); it.substring(0, i) to it.substring(i + 1) }

    private fun showFiles() {
        val r = screenRoot()
        r.addView(header(tr("إدارة الملفات", "Manage files"), { onBackPressedCompat() }))
        val recent = getRecent()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (recent.isEmpty()) {
            col.addView(txt(tr("لا توجد ملفات حديثة", "No recent files"), 15f, muted, false, true), lpm(MATCH, WRAP, 0f, 0, dp(40), 0, 0))
        } else {
            section(col, tr("الملفات الأخيرة", "Recent files"), recent.map { (u, n) ->
                settingRow(n, null, chevron()) {
                    try {
                        openPdf(Uri.parse(u))
                        showReader()
                    } catch (e: Exception) {
                        toast(tr("تعذر فتح الملف", "Could not open file"))
                    }
                }
            })
        }
        r.addView(ScrollView(this).apply { addView(col) }, lp(MATCH, 0, 1f))
        setScreen("files", r)
    }

    // ───────────── About ─────────────
    private fun showAbout() {
        val r = screenRoot()
        r.addView(header(tr("معلومات التطبيق", "About app"), { onBackPressedCompat() }))
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(panel, 18, green)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        card.addView(CloverView(this), lp(dp(140), dp(140)))
        card.addView(txt("BLACK CLOVER", 24f, gold, true, true).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) })
        card.addView(txt("PDF READER & EDITOR", 12f, white, false, true), lpm(WRAP, WRAP, 0f, 0, 0, 0, dp(16)))
        card.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        card.addView(aboutRow(tr("المطور", "Developer"), "Mikey (" + tr("محمد محمود عامر", "Mohamed Mahmoud Amer") + ")"))
        card.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        card.addView(aboutRow(tr("الإصدار", "Version"), "2.0"))
        card.addView(View(this).apply { setBackgroundColor(line) }, lp(MATCH, 1))
        card.addView(
            txt(
                tr(
                    "قارئ PDF بتصميم عصري وأدوات للقراءة والتعديل ونسخ النص والتوقيع.",
                    "A modern PDF reader with tools for reading, editing, copying text and signing."
                ), 14f, muted, false, true
            ),
            lpm(MATCH, WRAP, 0f, 0, dp(14), 0, 0)
        )
        r.addView(ScrollView(this).apply { addView(card, lpm(MATCH, WRAP, 0f, 0, dp(8), 0, 0)) }, lp(MATCH, 0, 1f))
        setScreen("about", r)
    }

    private fun aboutRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            layoutDirection = dir()
        }
        row.addView(txt(label, 14f, muted), lp(0, WRAP, 1f))
        row.addView(txt(value, 15f, white, true))
        return row
    }

    // ───────────── Custom views ─────────────
    private inner class CloverView(context: Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f

            p.style = Paint.Style.STROKE
            p.color = gold
            p.strokeWidth = r * 0.025f
            canvas.drawCircle(cx, cy, r * 0.96f, p)
            p.strokeWidth = r * 0.012f
            canvas.drawCircle(cx, cy, r * 0.86f, p)
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(r * 0.02f, r * 0.05f), 0f)
            canvas.drawCircle(cx, cy, r * 0.74f, p)
            p.pathEffect = null

            val s = r * 0.42f
            val heart = Path().apply {
                moveTo(0f, 0f)
                cubicTo(-s, -0.5f * s, -s, -1.2f * s, -0.5f * s, -1.2f * s)
                cubicTo(-0.2f * s, -1.2f * s, 0f, -1.0f * s, 0f, -0.8f * s)
                cubicTo(0f, -1.0f * s, 0.2f * s, -1.2f * s, 0.5f * s, -1.2f * s)
                cubicTo(s, -1.2f * s, s, -0.5f * s, 0f, 0f)
                close()
            }
            for (i in 0 until 4) {
                canvas.save()
                canvas.translate(cx, cy)
                canvas.rotate(45f + i * 90f)
                canvas.translate(0f, -r * 0.04f)
                p.style = Paint.Style.FILL
                p.color = Color.rgb(24, 140, 90)
                canvas.drawPath(heart, p)
                p.style = Paint.Style.STROKE
                p.strokeWidth = r * 0.015f
                p.color = gold
                canvas.drawPath(heart, p)
                canvas.restore()
            }
            p.style = Paint.Style.STROKE
            p.color = gold
            p.strokeWidth = r * 0.04f
            p.strokeCap = Paint.Cap.ROUND
            val stem = Path().apply {
                moveTo(cx, cy)
                quadTo(cx + r * 0.1f, cy + r * 0.3f, cx + r * 0.3f, cy + r * 0.5f)
            }
            canvas.drawPath(stem, p)
        }
    }

    private inner class ZoomImageView(context: Context) : ImageView(context) {
        var onTap: ((Float, Float) -> Unit)? = null
        var onSwipe: ((Int) -> Unit)? = null
        var onZoom: ((Float) -> Unit)? = null

        private var scaleFactor = 1f
        private var tx = 0f
        private var ty = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var multi = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    setZoom(scaleFactor * d.scaleFactor)
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.FIT_CENTER
        }

        private fun clamp() {
            val mx = width * (scaleFactor - 1f) / 2f
            val my = height * (scaleFactor - 1f) / 2f
            tx = tx.coerceIn(-mx, mx)
            ty = ty.coerceIn(-my, my)
        }

        fun setZoom(s: Float) {
            scaleFactor = s.coerceIn(1f, 5f)
            clamp()
            invalidate()
            onZoom?.invoke(scaleFactor)
        }

        fun zoomBy(d: Float) = setZoom(scaleFactor + d)

        fun resetZoom() {
            scaleFactor = 1f
            tx = 0f
            ty = 0f
            invalidate()
            onZoom?.invoke(1f)
        }

        private fun toPageFraction(x: Float, y: Float): PointF? {
            val d = drawable ?: return null
            val bw = d.intrinsicWidth.toFloat()
            val bh = d.intrinsicHeight.toFloat()
            if (bw <= 0f || bh <= 0f) return null
            val px = (x - tx - width / 2f) / scaleFactor + width / 2f
            val py = (y - ty - height / 2f) / scaleFactor + height / 2f
            val fit = min(width / bw, height / bh)
            val offX = (width - bw * fit) / 2f
            val offY = (height - bh * fit) / 2f
            val fx = (px - offX) / (bw * fit)
            val fy = (py - offY) / (bh * fit)
            return if (fx in 0f..1f && fy in 0f..1f) PointF(fx, fy) else null
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()
            canvas.translate(tx, ty)
            canvas.scale(scaleFactor, scaleFactor, width / 2f, height / 2f)
            super.onDraw(canvas)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x; lastY = event.y
                    downX = event.x; downY = event.y
                    moved = false; multi = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> multi = true
                MotionEvent.ACTION_POINTER_UP -> {
                    val i = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(i); lastY = event.getY(i)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!detector.isInProgress && event.pointerCount == 1) {
                        if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                        if (scaleFactor > 1f) {
                            tx += event.x - lastX
                            ty += event.y - lastY
                            clamp()
                            invalidate()
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi) {
                        performClick()
                        toPageFraction(event.x, event.y)?.let { onTap?.invoke(it.x, it.y) }
                    } else if (moved && !multi && scaleFactor == 1f) {
                        val dx = event.x - downX
                        if (abs(dx) > dp(80) && abs(dx) > abs(event.y - downY) * 1.5f) {
                            onSwipe?.invoke(if (dx < 0) 1 else -1)
                        }
                    }
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private inner class SignatureView(context: Context) : View(context) {
        private val path = Path()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            setBackgroundColor(Color.WHITE)
        }

        fun clear() {
            path.reset()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(path, paint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> path.moveTo(event.x, event.y)
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> path.lineTo(event.x, event.y)
            }
            invalidate()
            return true
        }

        fun exportBitmap(): Bitmap? {
            if (path.isEmpty) return null
            val b = RectF()
            path.computeBounds(b, true)
            val pad = dp(6).toFloat()
            val w = max(1, (b.width() + pad * 2).toInt())
            val h = max(1, (b.height() + pad * 2).toInt())
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.TRANSPARENT)
            val c = Canvas(bmp)
            c.translate(-b.left + pad, -b.top + pad)
            c.drawPath(path, paint)
            return bmp
        }
    }
}
