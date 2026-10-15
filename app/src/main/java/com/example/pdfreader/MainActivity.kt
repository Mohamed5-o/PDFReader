package com.example.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private val bgColor = Color.rgb(10, 12, 12)
    private val panelColor = Color.rgb(24, 29, 27)
    private val green = Color.rgb(36, 190, 111)
    private val darkGreen = Color.rgb(17, 90, 58)
    private val gold = Color.rgb(232, 190, 75)
    private val white = Color.rgb(245, 247, 245)
    private val muted = Color.rgb(165, 176, 169)
    private val red = Color.rgb(230, 85, 85)

    private lateinit var root: LinearLayout
    private lateinit var pageImage: ZoomImageView
    private lateinit var pageLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var editButton: Button
    private lateinit var copyButton: Button
    private lateinit var searchButton: Button

    private var currentUri: Uri? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var currentPage = 0
    private var pageCount = 0
    private var editMode = false
    private var currentBitmap: Bitmap? = null

    private var selectedLanguage = "ar"
    private var renderQuality = 2

    private data class PdfOverlay(
        val pageIndex: Int,
        val bitmap: Bitmap
    )

    private val overlays = mutableListOf<PdfOverlay>()
    private val redoOverlays = mutableListOf<PdfOverlay>()

    private val requestOpenPdf = 1001
    private val requestSavePdf = 1002
    private val requestAddImage = 1003

    private fun isArabic() = selectedLanguage == "ar"

    private fun tr(ar: String, en: String): String {
        return if (isArabic()) ar else en
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PDFBoxResourceLoader.init(applicationContext)

        selectedLanguage = getPreferences(MODE_PRIVATE)
            .getString("language", "ar") ?: "ar"

        renderQuality = getPreferences(MODE_PRIVATE)
            .getInt("quality", 2)

        createScreen()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun label(text: String, size: Float = 13f): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
    }

    private fun button(
        text: String,
        color: Int = green,
        textColor: Int = Color.BLACK
    ): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(textColor)
            textSize = 13f
            isAllCaps = false
            setBackgroundColor(color)
            minHeight = dp(42)
            setPadding(dp(5), dp(3), dp(5), dp(3))
        }
    }

    private fun createScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setBackgroundColor(bgColor)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            button("⋮", green, gold).apply {
                textSize = 26f
                setOnClickListener { showAppMenu(it) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val title = label(
            "BLACK CLOVER\nPDF READER & EDITOR",
            17f
        ).apply {
            setTextColor(gold)
            typeface = Typeface.DEFAULT_BOLD
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        header.addView(
            button(
                tr("فتح PDF", "Open PDF"),
                darkGreen,
                white
            ).apply {
                setOnClickListener { openPdfPicker() }
            }
        )

        root.addView(header)

        statusLabel = label(
            tr(
                "افتح ملف PDF للبدء",
                "Open a PDF file to begin"
            )
        )

        root.addView(statusLabel)

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        copyButton = button(
            tr("نسخ النص", "Copy text"),
            panelColor,
            white
        )

        searchButton = button(
            tr("بحث", "Search"),
            panelColor,
            gold
        )

        editButton = button(
            tr("وضع التعديل", "Edit mode"),
            darkGreen,
            white
        )

        toolbar.addView(
            copyButton,
            LinearLayout.LayoutParams(0, dp(45), 1f)
        )

        toolbar.addView(
            searchButton,
            LinearLayout.LayoutParams(0, dp(45), 1f)
        )

        toolbar.addView(
            editButton,
            LinearLayout.LayoutParams(0, dp(45), 1f)
        )

        root.addView(toolbar)

        copyButton.setOnClickListener { showCopyDialog() }
        searchButton.setOnClickListener { showSearchDialog() }

        editButton.setOnClickListener {
            if (currentUri == null) {
                toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            } else {
                editMode = !editMode

                editButton.text = if (editMode) {
                    tr("إنهاء التعديل", "Finish editing")
                } else {
                    tr("وضع التعديل", "Edit mode")
                }

                editButton.setBackgroundColor(
                    if (editMode) gold else darkGreen
                )

                editButton.setTextColor(
                    if (editMode) Color.BLACK else white
                )

                updateStatus()
            }
        }

        val imageFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(35, 38, 36))
        }

        pageImage = ZoomImageView(this)

        imageFrame.addView(
            pageImage,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val imageParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )

        imageParams.setMargins(0, dp(8), 0, dp(8))
        root.addView(imageFrame, imageParams)

        pageLabel = label("0 / 0", 15f).apply {
            setTextColor(gold)
            typeface = Typeface.DEFAULT_BOLD
        }

        root.addView(pageLabel)

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        navigation.addView(
            button(
                tr("السابق", "Previous"),
                panelColor,
                white
            ).apply {
                setOnClickListener { changePage(-1) }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )

        navigation.addView(
            button(
                tr("التالي", "Next"),
                panelColor,
                white
            ).apply {
                setOnClickListener { changePage(1) }
            },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )

        root.addView(navigation)

        val editTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        editTools.addView(
            button(tr("إضافة نص", "Add text"), darkGreen, white).apply {
                setOnClickListener { showAddTextDialog() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        editTools.addView(
            button(tr("علامة مائية", "Watermark"), panelColor, gold).apply {
                setOnClickListener { showWatermarkDialog() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        editTools.addView(
            button(tr("إضافة صورة", "Add image"), panelColor, white).apply {
                setOnClickListener { openImagePicker() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        root.addView(editTools)

        val editTools2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        editTools2.addView(
            button(tr("توقيع", "Signature"), panelColor, gold).apply {
                setOnClickListener { showSignatureDialog() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        editTools2.addView(
            button(tr("تراجع", "Undo"), panelColor, white).apply {
                setOnClickListener { undoEdit() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        editTools2.addView(
            button(tr("إعادة", "Redo"), panelColor, white).apply {
                setOnClickListener { redoEdit() }
            },
            LinearLayout.LayoutParams(0, dp(44), 1f)
        )

        root.addView(editTools2)

        root.addView(
            button(
                tr("حفظ نسخة PDF جديدة", "Save new PDF copy"),
                green,
                Color.BLACK
            ).apply {
                setOnClickListener { savePdfPicker() }
            }
        )

        setContentView(root)
        updateStatus()
    }

    private fun showAppMenu(anchor: View) {
        val options = arrayOf(
            tr("الإعدادات", "Settings"),
            tr("معلومات التطبيق", "About app"),
            tr("تغيير اللغة", "Change language"),
            tr("فتح ملف PDF", "Open PDF"),
            tr("حفظ نسخة جديدة", "Save new copy")
        )

        AlertDialog.Builder(this)
            .setTitle("BLACK CLOVER")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSettings()
                    1 -> showAbout()
                    2 -> showLanguageDialog()
                    3 -> openPdfPicker()
                    4 -> savePdfPicker()
                }
            }
            .show()
    }

    private fun showSettings() {
        val options = arrayOf(
            tr("جودة العرض: عادية", "Render quality: Normal"),
            tr("جودة العرض: عالية", "Render quality: High"),
            tr("جودة العرض: أعلى", "Render quality: Highest"),
            tr("إعادة ضبط التكبير", "Reset zoom")
        )

        AlertDialog.Builder(this)
            .setTitle(tr("الإعدادات", "Settings"))
            .setItems(options) { _, which ->
                when (which) {
                    0, 1, 2 -> {
                        renderQuality = which + 1
                        getPreferences(MODE_PRIVATE)
                            .edit()
                            .putInt("quality", renderQuality)
                            .apply()

                        renderCurrentPage()
                        toast(tr("تم تغيير جودة العرض", "Display quality changed"))
                    }

                    3 -> pageImage.resetZoom()
                }
            }
            .show()
    }

    private fun showLanguageDialog() {
        val options = arrayOf("العربية", "English")

        AlertDialog.Builder(this)
            .setTitle(tr("اختيار اللغة", "Choose language"))
            .setItems(options) { _, which ->
                selectedLanguage = if (which == 0) "ar" else "en"

                getPreferences(MODE_PRIVATE)
                    .edit()
                    .putString("language", selectedLanguage)
                    .apply()

                createScreen()

                if (currentUri != null) {
                    renderCurrentPage()
                }
            }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(tr("عن التطبيق", "About"))
            .setMessage(
                """
                BLACK CLOVER PDF READER & EDITOR

                ${tr("قارئ ملفات PDF وأدوات التعديل", "PDF reader and editing tools")}

                Developer:
                Mikey (Mohamed Mahmoud Amer)

                Version 2.0
                """.trimIndent()
            )
            .setPositiveButton(tr("حسنًا", "OK"), null)
            .show()
    }

    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        startActivityForResult(intent, requestOpenPdf)
    }

    private fun openImagePicker() {
        if (!requireEditMode()) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(intent, requestAddImage)
    }

    @Deprecated("Uses activity result API for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!

        when (requestCode) {
            requestOpenPdf -> {
                try {
                    currentUri = uri

                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }

                    openPdf(uri)
                } catch (e: Exception) {
                    toast(
                        tr(
                            "تعذر فتح الملف: ${e.message}",
                            "Could not open file: ${e.message}"
                        )
                    )
                }
            }

            requestSavePdf -> {
                saveEditedPdf(uri)
            }

            requestAddImage -> {
                addImageOverlay(uri)
            }
        }
    }

    private fun openPdf(uri: Uri) {
        closePdf()

        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("Cannot open PDF")

        fileDescriptor = descriptor
        renderer = PdfRenderer(descriptor)
        pageCount = renderer!!.pageCount
        currentPage = 0

        overlays.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }

        redoOverlays.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }

        overlays.clear()
        redoOverlays.clear()

        editMode = false
        editButton.text = tr("وضع التعديل", "Edit mode")
        editButton.setBackgroundColor(darkGreen)
        editButton.setTextColor(white)

        renderCurrentPage()
        updateStatus()
    }

    private fun renderCurrentPage() {
        val pdfRenderer = renderer ?: return

        if (currentPage !in 0 until pdfRenderer.pageCount) return

        try {
            val page = pdfRenderer.openPage(currentPage)

            val screenWidth = resources.displayMetrics.widthPixels
            val scale = min(
                screenWidth.toFloat() / page.width,
                2.5f
            ).coerceAtLeast(1f)

            val bitmapWidth = max(1, (page.width * scale * renderQuality / 2f).toInt())
            val bitmapHeight = max(1, (page.height * scale * renderQuality / 2f).toInt())

            val bitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )

            bitmap.eraseColor(Color.WHITE)

            page.render(
                bitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            page.close()

            val previous = currentBitmap
            currentBitmap = bitmap
            pageImage.setImageBitmap(bitmap)

            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                previous.recycle()
            }

            updateStatus()
        } catch (e: Exception) {
            toast(
                tr(
                    "حدث خطأ أثناء عرض الصفحة",
                    "Could not render page"
                )
            )
        }
    }

    private fun changePage(direction: Int) {
        if (renderer == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }

        val next = currentPage + direction

        if (next !in 0 until pageCount) return

        currentPage = next
        pageImage.resetZoom()
        renderCurrentPage()
    }

    private fun updateStatus() {
        pageLabel.text = if (pageCount > 0) {
            "${currentPage + 1} / $pageCount"
        } else {
            "0 / 0"
        }

        statusLabel.text = when {
            currentUri == null ->
                tr("افتح ملف PDF للبدء", "Open a PDF to begin")

            editMode ->
                tr("وضع التعديل مفعّل", "Editing mode enabled")

            else ->
                tr("وضع القراءة", "Reading mode")
        }
    }

    private fun extractText(): String {
        val uri = currentUri ?: return ""

        return try {
            val input = contentResolver.openInputStream(uri) ?: return ""

            input.use {
                val document = PDDocument.load(it)
                try {
                    val stripper =
                        com.tom_roush.pdfbox.text.PDFTextStripper()

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

        val text = extractText()

        if (text.isBlank()) {
            toast(
                tr(
                    "لم يتم العثور على نص قابل للاستخراج في الصفحة",
                    "No extractable text found on this page"
                )
            )
            return
        }

        val input = EditText(this).apply {
            setText(text)
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minLines = 5
            gravity = Gravity.TOP
        }

        AlertDialog.Builder(this)
            .setTitle(tr("النص المستخرج", "Extracted text"))
            .setView(input)
            .setPositiveButton(tr("نسخ الكل", "Copy all")) { _, _ ->
                val clipboard =
                    getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager

                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        "PDF text",
                        input.text.toString()
                    )
                )

                toast(tr("تم نسخ النص", "Text copied"))
            }
            .setNegativeButton(tr("إغلاق", "Close"), null)
            .show()
    }

    private fun showSearchDialog() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }

        val input = EditText(this).apply {
            hint = tr("اكتب النص الذي تبحث عنه", "Enter search text")
            setTextColor(white)
            setHintTextColor(muted)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("البحث في الصفحة الحالية", "Search current page"))
            .setView(input)
            .setPositiveButton(tr("بحث", "Search")) { _, _ ->
                val query = input.text.toString().trim()
                val text = extractText()

                if (query.isEmpty()) {
                    return@setPositiveButton
                }

                val found = text.contains(query, ignoreCase = true)

                toast(
                    if (found) {
                        tr("تم العثور على النص", "Text found")
                    } else {
                        tr("النص غير موجود في الصفحة الحالية", "Text not found on current page")
                    }
                )
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun requireEditMode(): Boolean {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return false
        }

        if (!editMode) {
            toast(tr("فعّل وضع التعديل أولًا", "Enable editing mode first"))
            return false
        }

        return true
    }

    private fun showAddTextDialog() {
        if (!requireEditMode()) return

        val input = EditText(this).apply {
            hint = tr("اكتب النص الذي تريد إضافته", "Enter text to add")
            setTextColor(Color.BLACK)
            minLines = 2
        }

        val sizeInput = EditText(this).apply {
            hint = tr("حجم الخط (مثل 24)", "Font size (e.g. 24)")
            setText("24")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.BLACK)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(10))
            addView(input)
            addView(sizeInput)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("إضافة نص جديد", "Add new text"))
            .setView(container)
            .setPositiveButton(tr("إضافة", "Add")) { _, _ ->
                val text = input.text.toString()
                val size = sizeInput.text.toString().toFloatOrNull() ?: 24f

                if (text.isNotBlank()) {
                    addTextOverlay(text, size.coerceIn(8f, 100f))
                }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun addTextOverlay(text: String, size: Float) {
        val bitmap = createOverlayBitmap()

        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = size * bitmap.width / 595f
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.NORMAL
            )
        }

        val x = bitmap.width * 0.10f
        val y = bitmap.height * 0.15f + paint.textSize

        drawMultilineText(canvas, text, x, y, paint)

        addOverlay(bitmap)
    }

    private fun showWatermarkDialog() {
        if (!requireEditMode()) return

        val input = EditText(this).apply {
            hint = tr("اكتب العلامة المائية", "Enter watermark")
            setTextColor(Color.BLACK)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("إضافة علامة مائية", "Add watermark"))
            .setView(input)
            .setPositiveButton(tr("إضافة", "Add")) { _, _ ->
                val text = input.text.toString()

                if (text.isNotBlank()) {
                    addWatermark(text)
                }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun addWatermark(text: String) {
        val bitmap = createOverlayBitmap()
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 20, 120, 75)
            textSize = bitmap.width * 0.075f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        canvas.save()
        canvas.rotate(-35f, bitmap.width / 2f, bitmap.height / 2f)

        canvas.drawText(
            text,
            bitmap.width / 2f,
            bitmap.height / 2f,
            paint
        )

        canvas.restore()

        addOverlay(bitmap)
    }

    private fun addImageOverlay(uri: Uri) {
        if (!requireEditMode()) return

        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Cannot read image")

            val source = input.use {
                BitmapFactory.decodeStream(it)
            } ?: throw IllegalStateException("Invalid image")

            val overlay = createOverlayBitmap()
            val canvas = Canvas(overlay)

            val maxWidth = overlay.width * 0.45f
            val maxHeight = overlay.height * 0.30f

            val factor = min(
                maxWidth / source.width,
                maxHeight / source.height
            ).coerceAtMost(1f)

            val width = source.width * factor
            val height = source.height * factor

            val destination = RectF(
                overlay.width * 0.10f,
                overlay.height * 0.12f,
                overlay.width * 0.10f + width,
                overlay.height * 0.12f + height
            )

            canvas.drawBitmap(source, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))

            source.recycle()

            addOverlay(overlay)
        } catch (e: Exception) {
            toast(tr("تعذر إضافة الصورة", "Could not add image"))
        }
    }

    private fun showSignatureDialog() {
        if (!requireEditMode()) return

        val signatureView = SignatureView(this)

        AlertDialog.Builder(this)
            .setTitle(tr("ارسم توقيعك", "Draw your signature"))
            .setView(signatureView)
            .setPositiveButton(tr("إضافة التوقيع", "Add signature")) { _, _ ->
                val signature = signatureView.exportBitmap()

                val overlay = createOverlayBitmap()
                val canvas = Canvas(overlay)

                val width = overlay.width * 0.45f
                val height = overlay.height * 0.20f

                val destination = RectF(
                    overlay.width * 0.10f,
                    overlay.height * 0.65f,
                    overlay.width * 0.10f + width,
                    overlay.height * 0.65f + height
                )

                canvas.drawBitmap(
                    signature,
                    null,
                    destination,
                    Paint(Paint.ANTI_ALIAS_FLAG)
                )

                signature.recycle()
                addOverlay(overlay)
            }
            .setNeutralButton(tr("مسح", "Clear"), null)
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun createOverlayBitmap(): Bitmap {
        val pdfRenderer = renderer
            ?: throw IllegalStateException("No PDF opened")

        val page = pdfRenderer.openPage(currentPage)

        val width = max(1, page.width * 2)
        val height = max(1, page.height * 2)

        page.close()

        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).apply {
            eraseColor(Color.TRANSPARENT)
        }
    }

    private fun drawMultilineText(
        canvas: Canvas,
        text: String,
        x: Float,
        startY: Float,
        paint: Paint
    ) {
        val lines = text.split("\n")
        var y = startY
        val lineHeight = paint.textSize * 1.35f

        for (line in lines) {
            canvas.drawText(line, x, y, paint)
            y += lineHeight
        }
    }

    private fun addOverlay(bitmap: Bitmap) {
        overlays.add(PdfOverlay(currentPage, bitmap))

        redoOverlays.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }

        redoOverlays.clear()

        toast(tr("تمت إضافة العنصر", "Element added"))
    }

    private fun undoEdit() {
        if (overlays.isEmpty()) {
            toast(tr("لا توجد تعديلات للتراجع عنها", "Nothing to undo"))
            return
        }

        val last = overlays.removeAt(overlays.lastIndex)
        redoOverlays.add(last)

        toast(tr("تم التراجع عن آخر إضافة", "Last addition undone"))
    }

    private fun redoEdit() {
        if (redoOverlays.isEmpty()) {
            toast(tr("لا توجد تعديلات لإعادتها", "Nothing to redo"))
            return
        }

        overlays.add(redoOverlays.removeAt(redoOverlays.lastIndex))

        toast(tr("تمت إعادة الإضافة", "Addition restored"))
    }

    private fun savePdfPicker() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(
                Intent.EXTRA_TITLE,
                "BlackClover_Edited.pdf"
            )
        }

        startActivityForResult(intent, requestSavePdf)
    }

    private fun saveEditedPdf(destination: Uri) {
        val sourceUri = currentUri ?: return

        try {
            val input = contentResolver.openInputStream(sourceUri)
                ?: throw IllegalStateException("Cannot read source PDF")

            val document = input.use {
                PDDocument.load(it)
            }

            try {
                for (overlay in overlays) {
                    if (overlay.pageIndex !in 0 until document.numberOfPages) {
                        continue
                    }

                    val page = document.getPage(overlay.pageIndex)

                    val image = LosslessFactory.createFromImage(
                        document,
                        overlay.bitmap
                    )

                    PDPageContentStream(
                        document,
                        page,
                        PDPageContentStream.AppendMode.APPEND,
                        true,
                        true
                    ).use { stream ->
                        val box = page.mediaBox

                        stream.drawImage(
                            image,
                            box.lowerLeftX,
                            box.lowerLeftY,
                            box.width,
                            box.height
                        )
                    }
                }

                val output: OutputStream =
                    contentResolver.openOutputStream(destination, "w")
                        ?: throw IllegalStateException("Cannot write output PDF")

                output.use {
                    document.save(it)
                }
            } finally {
                document.close()
            }

            toast(
                tr(
                    "تم حفظ نسخة PDF الجديدة",
                    "New PDF copy saved"
                )
            )
        } catch (e: Exception) {
            toast(
                tr(
                    "تعذر حفظ الملف: ${e.message}",
                    "Could not save PDF: ${e.message}"
                )
            )
        }
    }

    private fun closePdf() {
        try {
            renderer?.close()
        } catch (_: Exception) {
        }

        renderer = null

        try {
            fileDescriptor?.close()
        } catch (_: Exception) {
        }

        fileDescriptor = null

        currentBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }

        currentBitmap = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        closePdf()

        overlays.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }

        redoOverlays.forEach {
            if (!it.bitmap.isRecycled) it.bitmap.recycle()
        }

        overlays.clear()
        redoOverlays.clear()

        super.onDestroy()
    }

    private inner class ZoomImageView(
        context: android.content.Context
    ) : ImageView(context) {

        private var scaleFactor = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var translateX = 0f
        private var translateY = 0f
        private var dragging = false

        private val matrixValues = FloatArray(9)

        private val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    scaleFactor = (scaleFactor * detector.scaleFactor)
                        .coerceIn(1f, 5f)

                    invalidate()
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
        }

        fun resetZoom() {
            scaleFactor = 1f
            translateX = 0f
            translateY = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.save()

            canvas.translate(translateX, translateY)
            canvas.scale(
                scaleFactor,
                scaleFactor,
                width / 2f,
                height / 2f
            )

            super.onDraw(canvas)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging && scaleFactor > 1f) {
                        translateX += event.x - lastX
                        translateY += event.y - lastY

                        lastX = event.x
                        lastY = event.y

                        invalidate()
                    }

                    return true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    performClick()
                    return true
                }
            }

            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private inner class SignatureView(
        context: android.content.Context
    ) : View(context) {

        private val signaturePath = Path()

        private val signaturePaint = Paint(
            Paint.ANTI_ALIAS_FLAG
        ).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        init {
            setBackgroundColor(Color.WHITE)
            minimumWidth = dp(280)
            minimumHeight = dp(180)
            layoutParams = ViewGroup.LayoutParams(
                dp(300),
                dp(220)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawPath(signaturePath, signaturePaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    signaturePath.moveTo(event.x, event.y)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    signaturePath.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    signaturePath.lineTo(event.x, event.y)
                    invalidate()
                    return true
                }
            }

            return true
        }

        fun exportBitmap(): Bitmap {
            val result = Bitmap.createBitmap(
                max(1, width),
                max(1, height),
                Bitmap.Config.ARGB_8888
            )

            result.eraseColor(Color.TRANSPARENT)

            val canvas = Canvas(result)
            canvas.drawPath(signaturePath, signaturePaint)

            return result
        }
    }
}
