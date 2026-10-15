package com.example.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
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
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var bitmap: Bitmap? = null
    private var currentUri: Uri? = null
    private var pageIndex = 0

    private lateinit var pageImage: ZoomImageView
    private lateinit var pageInfo: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var root: LinearLayout

    private val bg = Color.rgb(12, 15, 18)
    private val panel = Color.rgb(24, 30, 34)
    private val green = Color.rgb(25, 190, 105)
    private val gold = Color.rgb(225, 184, 75)
    private val white = Color.rgb(240, 245, 242)
    private val muted = Color.rgb(160, 175, 167)

    companion object {
        private const val PICK_PDF = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PDFBoxResourceLoader.init(applicationContext)
        createScreen()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun makeButton(
        text: String,
        color: Int = green
    ): Button {
        return Button(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            textSize = 14f
            isAllCaps = false
            setBackgroundColor(color)
            minHeight = dp(46)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
    }

    private fun addButton(
        container: LinearLayout,
        button: Button,
        weight: Float = 1f
    ) {
        val params = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            weight
        )
        params.setMargins(dp(4), dp(3), dp(4), dp(3))
        container.addView(button, params)
    }

    private fun createScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(10))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(14))
        }

        val title = TextView(this).apply {
            text = "BLACK CLOVER"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(green)
            letterSpacing = 0.12f
        }

        val subtitle = TextView(this).apply {
            text = "PDF GRIMOIRE  •  READ YOUR WORLD"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(gold)
            letterSpacing = 0.08f
        }

        header.addView(title)
        header.addView(subtitle)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val openButton = makeButton("📂  فتح ملف PDF", gold)
        openButton.setOnClickListener { choosePdf() }

        root.addView(
            openButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        pageInfo = TextView(this).apply {
            text = "اختر ملف PDF للبدء"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }

        root.addView(pageInfo)

        pageImage = ZoomImageView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val documentFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                pageImage,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        root.addView(
            documentFrame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val zoomOutButton = makeButton("−  تصغير", panel).apply {
            setTextColor(white)
            setOnClickListener { pageImage.zoomBy(0.8f) }
        }

        val zoomResetButton = makeButton("100%", panel).apply {
            setTextColor(gold)
            setOnClickListener { pageImage.resetZoom() }
        }

        val zoomInButton = makeButton("+  تكبير", panel).apply {
            setTextColor(white)
            setOnClickListener { pageImage.zoomBy(1.25f) }
        }

        addButton(zoomRow, zoomOutButton)
        addButton(zoomRow, zoomResetButton)
        addButton(zoomRow, zoomInButton)

        root.addView(zoomRow)

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        previousButton = makeButton("السابق", panel).apply {
            setTextColor(white)
            isEnabled = false
            setOnClickListener {
                if (pageIndex > 0) {
                    pageIndex--
                    showPage()
                }
            }
        }

        nextButton = makeButton("التالي", panel).apply {
            setTextColor(white)
            isEnabled = false
            setOnClickListener {
                val pdf = renderer
                if (pdf != null && pageIndex < pdf.pageCount - 1) {
                    pageIndex++
                    showPage()
                }
            }
        }

        addButton(navigation, previousButton)
        addButton(navigation, nextButton)
        root.addView(navigation)

        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val copyButton = makeButton("نسخ النص", green)
        copyButton.setOnClickListener { showCurrentPageText() }

        val searchButton = makeButton("بحث", gold)
        searchButton.setOnClickListener { showSearchDialog() }

        val settingsButton = makeButton("الإعدادات", panel).apply {
            setTextColor(white)
        }
        settingsButton.setOnClickListener { showSettings() }

        addButton(tools, copyButton)
        addButton(tools, searchButton)
        addButton(tools, settingsButton)

        root.addView(tools)

        val footer = TextView(this).apply {
            text = "تلميح: استخدم إصبعين للتكبير والتصغير"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setPadding(0, dp(5), 0, 0)
        }

        root.addView(footer)

        setContentView(root)
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivityForResult(intent, PICK_PDF)
        } catch (e: Exception) {
            toast("تعذر فتح مدير الملفات")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_PDF && resultCode == RESULT_OK) {
            val uri = data?.data ?: return

            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Some document providers do not support persistent access.
            }

            openPdf(uri)
        }
    }

    private fun openPdf(uri: Uri) {
        closePdf()

        try {
            val fd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("File unavailable")

            descriptor = fd
            renderer = PdfRenderer(fd)
            currentUri = uri
            pageIndex = 0

            showPage()
            toast("تم فتح ملف PDF بنجاح")

        } catch (e: Exception) {
            closePdf()
            pageInfo.text = "تعذر فتح الملف"
            toast("تعذر فتح الملف. قد يكون تالفًا أو محميًا.")
        }
    }

    private fun showPage() {
        val pdf = renderer ?: return
        var page: PdfRenderer.Page? = null

        try {
            page = pdf.openPage(pageIndex)

            val maxWidth = (
                resources.displayMetrics.widthPixels - dp(24)
            ).coerceAtLeast(1)

            val scale = maxWidth.toFloat() / page.width
            val width = maxWidth
            val height = (page.height * scale)
                .toInt()
                .coerceAtLeast(1)

            val newBitmap = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

            newBitmap.eraseColor(Color.WHITE)

            page.render(
                newBitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            val oldBitmap = bitmap
            bitmap = newBitmap
            pageImage.setImageBitmap(newBitmap)

            if (oldBitmap != null && !oldBitmap.isRecycled) {
                oldBitmap.recycle()
            }

            pageInfo.text =
                "الصفحة ${pageIndex + 1} من ${pdf.pageCount}"

            previousButton.isEnabled = pageIndex > 0
            nextButton.isEnabled = pageIndex < pdf.pageCount - 1

        } catch (e: Exception) {
            toast("حدث خطأ أثناء عرض الصفحة")
        } finally {
            page?.close()
        }
    }

    private fun openTextDocument(): PDDocument? {
        val uri = currentUri ?: return null
        val input: InputStream =
            contentResolver.openInputStream(uri) ?: return null

        return try {
            input.use {
                PDDocument.load(it)
            }
        } catch (e: Exception) {
            toast("تعذر استخراج النص. ربما الملف محمي أو غير مدعوم.")
            null
        }
    }

    private fun extractPageText(pageNumber: Int): String {
        val document = openTextDocument() ?: return ""

        return try {
            val stripper = PDFTextStripper().apply {
                startPage = pageNumber
                endPage = pageNumber
            }
            stripper.getText(document).trim()
        } catch (e: Exception) {
            ""
        } finally {
            document.close()
        }
    }

    private fun showCurrentPageText() {
        if (currentUri == null) {
            toast("افتح ملف PDF أولًا")
            return
        }

        Thread {
            val text = extractPageText(pageIndex + 1)

            runOnUiThread {
                if (text.isBlank()) {
                    toast(
                        "لم يتم العثور على نص قابل للنسخ في هذه الصفحة"
                    )
                    return@runOnUiThread
                }

                showTextDialog(
                    "نص الصفحة ${pageIndex + 1}",
                    text
                )
            }
        }.start()
    }

    private fun showTextDialog(
        title: String,
        content: String
    ) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(bg)
        }

        val textView = TextView(this).apply {
            text = content
            textSize = 16f
            setTextColor(white)
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }

        val scroll = ScrollView(this)
        scroll.addView(
            textView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360)
            )
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("نسخ الكل", null)
            .setNegativeButton("إغلاق", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val clipboard = getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            "PDF text",
                            content
                        )
                    )

                    toast("تم نسخ النص")
                }
        }

        dialog.show()
    }

    private fun showSearchDialog() {
        if (currentUri == null) {
            toast("افتح ملف PDF أولًا")
            return
        }

        val input = EditText(this).apply {
            hint = "اكتب كلمة للبحث عنها"
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(muted)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setBackgroundColor(bg)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("البحث داخل الملف")
            .setView(container)
            .setPositiveButton("بحث") { _, _ ->
                val query = input.text.toString().trim()

                if (query.isNotEmpty()) {
                    searchDocument(query)
                } else {
                    toast("اكتب كلمة للبحث")
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun searchDocument(query: String) {
        Thread {
            val results = StringBuilder()
            var matches = 0
            var failed = false

            val document = openTextDocument()

            if (document == null) {
                failed = true
            } else {
                try {
                    val stripper = PDFTextStripper()

                    for (i in 1..document.numberOfPages) {
                        stripper.startPage = i
                        stripper.endPage = i

                        val text = stripper.getText(document)

                        if (text.contains(
                                query,
                                ignoreCase = true
                            )
                        ) {
                            matches++

                            val lines = text.lines()
                                .filter {
                                    it.contains(
                                        query,
                                        ignoreCase = true
                                    )
                                }
                                .take(3)

                            results.append("الصفحة $i\n")
                            results.append(lines.joinToString("\n"))
                            results.append("\n\n")
                        }
                    }
                } catch (e: Exception) {
                    failed = true
                } finally {
                    document.close()
                }
            }

            runOnUiThread {
                when {
                    failed -> toast("تعذر إتمام البحث")
                    matches == 0 -> toast("لم يتم العثور على الكلمة")
                    else -> showTextDialog(
                        "نتائج البحث: $matches صفحة",
                        results.toString().trim()
                    )
                }
            }
        }.start()
    }

    private fun showSettings() {
        val options = arrayOf(
            "معلومات التطبيق",
            "طريقة التكبير",
            "نسخ النصوص"
        )

        AlertDialog.Builder(this)
            .setTitle("إعدادات القراءة")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> AlertDialog.Builder(this)
                        .setTitle("BLACK CLOVER PDF")
                        .setMessage(
                            "قارئ PDF بتصميم داكن.\n\n" +
                            "عرض الصفحات والتكبير والبحث " +
                            "واستخراج النصوص ونسخها.\n\n" +
                            "الإصدار 2.0"
                        )
                        .setPositiveButton("حسنًا", null)
                        .show()

                    1 -> AlertDialog.Builder(this)
                        .setTitle("التكبير")
                        .setMessage(
                            "استخدم إصبعين للتكبير والتصغير، " +
                            "أو استخدم أزرار + و− أسفل الصفحة."
                        )
                        .setPositiveButton("حسنًا", null)
                        .show()

                    2 -> AlertDialog.Builder(this)
                        .setTitle("نسخ النص")
                        .setMessage(
                            "اضغط على زر نسخ النص، " +
                            "ثم استخدم الضغط المطول لتحديد " +
                            "النص ونسخه.\n\n" +
                            "الملفات المصورة قد تحتاج OCR."
                        )
                        .setPositiveButton("حسنًا", null)
                        .show()
                }
            }
            .show()
    }

    private fun closePdf() {
        if (::pageImage.isInitialized) {
            pageImage.setImageDrawable(null)
        }

        renderer?.close()
        renderer = null

        descriptor?.close()
        descriptor = null

        val oldBitmap = bitmap
        bitmap = null

        if (oldBitmap != null && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }

        currentUri = null
        pageIndex = 0
    }

    private fun toast(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_SHORT
        ).show()
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }

    private class ZoomImageView(
        context: Context
    ) : ImageView(context) {

        private val imageMatrixValue = Matrix()
        private var scaleFactor = 1f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        private val detector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(
                    detector: ScaleGestureDetector
                ): Boolean {
                    val oldScale = scaleFactor

                    scaleFactor = (
                        scaleFactor * detector.scaleFactor
                    ).coerceIn(1f, 5f)

                    val ratio = scaleFactor / oldScale

                    imageMatrixValue.postScale(
                        ratio,
                        ratio,
                        detector.focusX,
                        detector.focusY
                    )

                    imageMatrix = imageMatrixValue
                    return true
                }
            }
        )

        init {
            scaleType = ScaleType.MATRIX
            isClickable = true
        }

        override fun setImageBitmap(bm: Bitmap?) {
            super.setImageBitmap(bm)
            resetZoom()
        }

        fun zoomBy(factor: Float) {
            val oldScale = scaleFactor

            scaleFactor = (
                scaleFactor * factor
            ).coerceIn(1f, 5f)

            val ratio = scaleFactor / oldScale

            imageMatrixValue.postScale(
                ratio,
                ratio,
                width / 2f,
                height / 2f
            )

            imageMatrix = imageMatrixValue
        }

        fun resetZoom() {
            scaleFactor = 1f
            imageMatrixValue.reset()
            imageMatrix = imageMatrixValue
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            detector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (
                        dragging &&
                        !detector.isInProgress &&
                        scaleFactor > 1f
                    ) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY

                        imageMatrixValue.postTranslate(dx, dy)
                        imageMatrix = imageMatrixValue
                    }

                    lastX = event.x
                    lastY = event.y
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                }
            }

            return true
        }
    }
}
