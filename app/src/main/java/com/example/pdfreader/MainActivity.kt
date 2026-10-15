package com.example.pdfreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var pageBitmap: Bitmap? = null
    private var currentUri: Uri? = null
    private var pageIndex = 0
    private var editMode = false

    private lateinit var root: LinearLayout
    private lateinit var pageImage: ZoomImageView
    private lateinit var pageInfo: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button
    private lateinit var editButton: Button

    private val preferences by lazy {
        getSharedPreferences("black_clover_settings", MODE_PRIVATE)
    }

    private var language = "ar"
    private var renderScale = 2f
    private var watermarkText = "BLACK CLOVER"

    private val edits = mutableListOf<PdfEdit>()
    private val undoStack = mutableListOf<List<PdfEdit>>()
    private val redoStack = mutableListOf<List<PdfEdit>>()

    private val background = Color.rgb(10, 13, 15)
    private val panel = Color.rgb(25, 31, 34)
    private val green = Color.rgb(34, 205, 117)
    private val gold = Color.rgb(226, 184, 72)
    private val white = Color.rgb(240, 245, 242)
    private val muted = Color.rgb(160, 175, 167)

    companion object {
        private const val PICK_PDF = 100
        private const val PICK_IMAGE = 101
        private const val SAVE_PDF = 102
    }

    private data class PdfEdit(
        val page: Int,
        val bytes: ByteArray,
        val centerX: Float,
        val centerY: Float,
        val widthFraction: Float,
        val heightFraction: Float,
        val description: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PDFBoxResourceLoader.init(applicationContext)

        language = preferences.getString("language", "ar") ?: "ar"
        renderScale = preferences.getFloat("render_scale", 2f)
        watermarkText = preferences.getString(
            "watermark",
            "BLACK CLOVER"
        ) ?: "BLACK CLOVER"

        createScreen()
    }

    private fun ar() = language == "ar"

    private fun tr(arabic: String, english: String): String =
        if (ar()) arabic else english

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun button(
        text: String,
        color: Int = green,
        textColor: Int = Color.BLACK
    ) = Button(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 13f
        isAllCaps = false
        setBackgroundColor(color)
        minHeight = dp(42)
        setPadding(dp(5), dp(3), dp(5), dp(3))
    }

    private fun addWeighted(
        row: LinearLayout,
        view: View,
        weight: Float = 1f
    ) {
        val params = LinearLayout.LayoutParams(0, -2, weight)
        params.setMargins(dp(3), dp(2), dp(3), dp(2))
        row.addView(view, params)
    }

    private fun label(text: String, size: Float = 13f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(white)
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }

    private fun createScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(6))
            setBackgroundColor(background)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            button("⋮", background, gold).apply {
                textSize = 26f
                setOnClickListener { showAppMenu(it) }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val branding = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        branding.addView(label("BLACK CLOVER", 23f).apply {
            setTextColor(green)
            letterSpacing = 0.1f
        })

        branding.addView(label("PDF GRIMOIRE • READER & EDITOR", 9f).apply {
            setTextColor(gold)
        })

        header.addView(
            branding,
            LinearLayout.LayoutParams(0, -2, 1f)
        )

        header.addView(View(this), LinearLayout.LayoutParams(dp(48), dp(1)))
        root.addView(header)

        root.addView(
            button(tr("📂 فتح ملف PDF", "📂 Open PDF"), gold).apply {
                setOnClickListener { choosePdf() }
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        pageInfo = label(tr("اختر ملف PDF للبدء", "Choose a PDF to begin"))
        root.addView(pageInfo)

        pageImage = ZoomImageView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(pageImage, FrameLayout.LayoutParams(-1, -1))
        }

        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val zoomRow = LinearLayout(this)
        addWeighted(
            zoomRow,
            button(tr("− تصغير", "− Zoom out"), panel, white).apply {
                setOnClickListener { pageImage.zoomBy(0.8f) }
            }
        )
        addWeighted(
            zoomRow,
            button("100%", panel, gold).apply {
                setOnClickListener { pageImage.resetZoom() }
            }
        )
        addWeighted(
            zoomRow,
            button(tr("+ تكبير", "+ Zoom in"), panel, white).apply {
                setOnClickListener { pageImage.zoomBy(1.25f) }
            }
        )
        root.addView(zoomRow)

        val nav = LinearLayout(this)
        previousButton = button(tr("السابق", "Previous"), panel, white).apply {
            isEnabled = false
            setOnClickListener {
                if (pageIndex > 0) {
                    pageIndex--
                    showPage()
                }
            }
        }
        nextButton = button(tr("التالي", "Next"), panel, white).apply {
            isEnabled = false
            setOnClickListener {
                val pdf = renderer
                if (pdf != null && pageIndex < pdf.pageCount - 1) {
                    pageIndex++
                    showPage()
                }
            }
        }
        addWeighted(nav, previousButton)
        addWeighted(nav, nextButton)
        root.addView(nav)

        val readingTools = LinearLayout(this)

        addWeighted(
            readingTools,
            button(tr("نسخ النص", "Copy text")).apply {
                setOnClickListener { showCurrentPageText() }
            }
        )

        addWeighted(
            readingTools,
            button(tr("بحث", "Search"), gold).apply {
                setOnClickListener { showSearchDialog() }
            }
        )

        editButton = button(tr("وضع التعديل", "Edit mode"), panel, white).apply {
            setOnClickListener { toggleEditMode() }
        }
        addWeighted(readingTools, editButton)
        root.addView(readingTools)

        val editingTools = LinearLayout(this)

        addWeighted(
            editingTools,
            button(tr("إضافة نص", "Add text")).apply {
                setOnClickListener { promptText() }
            }
        )

        addWeighted(
            editingTools,
            button(tr("علامة مائية", "Watermark"), panel, gold).apply {
                setOnClickListener { promptWatermark() }
            }
        )

        addWeighted(
            editingTools,
            button(tr("إضافة صورة", "Add image"), panel, white).apply {
                setOnClickListener { chooseImage() }
            }
        )

        root.addView(editingTools)

        val signatureTools = LinearLayout(this)

        addWeighted(
            signatureTools,
            button(tr("توقيع مرسوم", "Draw signature"), panel, gold).apply {
                setOnClickListener { showSignatureDialog() }
            }
        )

        addWeighted(
            signatureTools,
            button(tr("تراجع", "Undo"), panel, white).apply {
                setOnClickListener { undo() }
            }
        )

        addWeighted(
            signatureTools,
            button(tr("إعادة", "Redo"), panel, white).apply {
                setOnClickListener { redo() }
            }
        )

        root.addView(signatureTools)

        root.addView(
            button(tr("💾 حفظ نسخة جديدة", "💾 Save a new copy"), gold).apply {
                setOnClickListener { requestSave() }
            },
            LinearLayout.LayoutParams(-1, -2)
        )

        root.addView(
            label(
                tr(
                    "التعديلات إضافات فوق الصفحة ولا تستبدل النص الأصلي تلقائيًا",
                    "Edits are overlays; original text is not automatically replaced"
                ),
                9f
            ).apply {
                setTextColor(muted)
            }
        )

        setContentView(root)
        updateEditControls()
    }

    private fun showAppMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)

        popup.menu.add(
            0, 1, 0,
            tr("الإعدادات", "Settings")
        )
        popup.menu.add(
            0, 2, 1,
            tr("تغيير اللغة", "Change language")
        )
        popup.menu.add(
            0, 3, 2,
            tr("معلومات التطبيق", "About")
        )
        popup.menu.add(
            0, 4, 3,
            tr("أدوات التحرير", "Editing tools")
        )

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                1 -> showSettings()
                2 -> chooseLanguage()
                3 -> showAbout()
                4 -> showEditingHelp()
            }
            true
        }

        popup.show()
    }

    private fun chooseLanguage() {
        val options = arrayOf("العربية", "English")
        AlertDialog.Builder(this)
            .setTitle(tr("لغة التطبيق", "App language"))
            .setItems(options) { _, which ->
                language = if (which == 0) "ar" else "en"
                preferences.edit().putString("language", language).apply()
                recreate()
            }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("BLACK CLOVER PDF")
            .setMessage(
                "BLACK CLOVER PDF READER & EDITOR\n\n" +
                    "Developer: Mikey (Mohamed Mahmoud Amer)\n\n" +
                    "Version 2.0\n\n" +
                    "PDF reading, search, text extraction and overlay editing."
            )
            .setPositiveButton(tr("حسنًا", "OK"), null)
            .show()
    }

    private fun showSettings() {
        val options = arrayOf(
            tr("جودة العرض", "Rendering quality"),
            tr("تغيير اللغة", "Change language"),
            tr("نص العلامة المائية", "Watermark text"),
            tr("معلومات المطور", "Developer information")
        )

        AlertDialog.Builder(this)
            .setTitle(tr("الإعدادات", "Settings"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> chooseQuality()
                    1 -> chooseLanguage()
                    2 -> editWatermarkText()
                    3 -> showAbout()
                }
            }
            .show()
    }

    private fun chooseQuality() {
        val values = arrayOf(
            tr("اقتصادية - أسرع وأقل استهلاكًا", "Low - faster"),
            tr("متوسطة", "Medium"),
            tr("عالية - قد تستهلك ذاكرة أكبر", "High - uses more memory")
        )

        AlertDialog.Builder(this)
            .setTitle(tr("جودة عرض الصفحات", "Rendering quality"))
            .setSingleChoiceItems(
                values,
                when {
                    renderScale < 1.5f -> 0
                    renderScale < 2.5f -> 1
                    else -> 2
                }
            ) { dialog, which ->
                renderScale = when (which) {
                    0 -> 1f
                    1 -> 2f
                    else -> 2.5f
                }

                preferences.edit()
                    .putFloat("render_scale", renderScale)
                    .apply()

                dialog.dismiss()
                if (renderer != null) showPage()
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun editWatermarkText() {
        val input = EditText(this).apply {
            setText(watermarkText)
            setTextColor(white)
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("نص العلامة المائية", "Watermark text"))
            .setView(input)
            .setPositiveButton(tr("حفظ", "Save")) { _, _ ->
                watermarkText = input.text.toString().trim()
                preferences.edit()
                    .putString("watermark", watermarkText)
                    .apply()
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun showEditingHelp() {
        AlertDialog.Builder(this)
            .setTitle(tr("أدوات التحرير", "Editing tools"))
            .setMessage(
                tr(
                    "يمكنك إضافة نصوص وصور وعلامات مائية وتوقيعات مرسومة. " +
                        "هذه العناصر تُضاف فوق الصفحة. لا يتم استبدال النص الأصلي " +
                        "تلقائيًا، وقد تختلف النتيجة حسب الملف.",
                    "You can add text, images, watermarks and drawn signatures. " +
                        "These are overlays; existing PDF text is not automatically " +
                        "replaced. Results depend on the PDF."
                )
            )
            .setPositiveButton(tr("حسنًا", "OK"), null)
            .show()
    }

    private fun toggleEditMode() {
        editMode = !editMode
        updateEditControls()
        toast(
            if (editMode) {
                tr("وضع التعديل مفعّل", "Edit mode enabled")
            } else {
                tr("وضع القراءة مفعّل", "Reading mode enabled")
            }
        )
    }

    private fun updateEditControls() {
        if (!::editButton.isInitialized) return

        editButton.text = if (editMode) {
            tr("إيقاف التعديل", "Exit edit mode")
        } else {
            tr("وضع التعديل", "Edit mode")
        }

        editButton.setTextColor(if (editMode) green else white)
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(intent, PICK_PDF)
    }

    private fun chooseImage() {
        if (!ensureEditMode()) return

        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        startActivityForResult(intent, PICK_IMAGE)
    }

    private fun requestSave() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }

        if (edits.isEmpty()) {
            toast(tr("لا توجد تعديلات لحفظها", "There are no edits to save"))
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(
                Intent.EXTRA_TITLE,
                "black_clover_edited.pdf"
            )
        }

        startActivityForResult(intent, SAVE_PDF)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            PICK_PDF -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }

                openPdf(uri)
            }

            PICK_IMAGE -> {
                addImageFromUri(uri)
            }

            SAVE_PDF -> {
                saveEditedPdfTo(uri)
            }
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

            edits.clear()
            undoStack.clear()
            redoStack.clear()

            showPage()
            toast(tr("تم فتح الملف", "PDF opened"))
        } catch (e: Exception) {
            closePdf()
            pageInfo.text = tr("تعذر فتح الملف", "Could not open PDF")
            toast(tr("قد يكون الملف تالفًا أو محميًا", "File may be damaged or protected"))
        }
    }

    private fun showPage() {
        val pdf = renderer ?: return
        var page: PdfRenderer.Page? = null

        try {
            page = pdf.openPage(pageIndex)

            val screenWidth = (
                resources.displayMetrics.widthPixels - dp(24)
            ).coerceAtLeast(1)

            val scale = min(
                renderScale,
                screenWidth.toFloat() / page.width * renderScale
            ).coerceAtLeast(1f)

            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)

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

            pageBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }

            pageBitmap = newBitmap
            pageImage.setImageBitmap(newBitmap)

            pageInfo.text = tr(
                "الصفحة ${pageIndex + 1} من ${pdf.pageCount}",
                "Page ${pageIndex + 1} of ${pdf.pageCount}"
            )

            previousButton.isEnabled = pageIndex > 0
            nextButton.isEnabled = pageIndex < pdf.pageCount - 1

        } catch (e: OutOfMemoryError) {
            toast(tr("الصفحة كبيرة جدًا على ذاكرة الجهاز", "Page is too large for device memory"))
        } catch (e: Exception) {
            toast(tr("حدث خطأ أثناء عرض الصفحة", "Could not render page"))
        } finally {
            page?.close()
        }
    }

    private fun openTextDocument(): PDDocument? {
        val uri = currentUri ?: return null

        return try {
            val input: InputStream =
                contentResolver.openInputStream(uri) ?: return null

            input.use { PDDocument.load(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractPageText(number: Int): String {
        val document = openTextDocument() ?: return ""

        return try {
            val stripper = PDFTextStripper().apply {
                startPage = number
                endPage = number
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
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }

        thread {
            val text = extractPageText(pageIndex + 1)

            runOnUiThread {
                if (text.isBlank()) {
                    toast(
                        tr(
                            "لا يوجد نص قابل للاستخراج؛ ربما الصفحة صورة",
                            "No extractable text; the page may be an image"
                        )
                    )
                } else {
                    showTextDialog(
                        tr("نص الصفحة", "Page text"),
                        text
                    )
                }
            }
        }
    }

    private fun showTextDialog(title: String, text: String) {
        val textView = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(white)
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }

        val scroll = ScrollView(this).apply {
            addView(textView)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(tr("نسخ الكل", "Copy all"), null)
            .setNegativeButton(tr("إغلاق", "Close"), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val clipboard = getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager

                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("PDF text", text)
                    )

                    toast(tr("تم نسخ النص", "Text copied"))
                }
        }

        dialog.show()
    }

    private fun showSearchDialog() {
        if (currentUri == null) {
            toast(tr("افتح ملف PDF أولًا", "Open a PDF first"))
            return
        }

        val input = EditText(this).apply {
            hint = tr("اكتب كلمة للبحث عنها", "Enter search term")
            setTextColor(white)
            setHintTextColor(muted)
            setSingleLine(true)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("البحث داخل الملف", "Search PDF"))
            .setView(input)
            .setPositiveButton(tr("بحث", "Search")) { _, _ ->
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) searchDocument(query)
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun searchDocument(query: String) {
        thread {
            val document = openTextDocument()

            if (document == null) {
                runOnUiThread {
                    toast(tr("تعذر قراءة الملف للبحث", "Could not read PDF"))
                }
                return@thread
            }

            val results = StringBuilder()
            var matches = 0

            try {
                val stripper = PDFTextStripper()

                for (i in 1..document.numberOfPages) {
                    stripper.startPage = i
                    stripper.endPage = i

                    val text = stripper.getText(document)
                    if (text.contains(query, ignoreCase = true)) {
                        matches++

                        results.append(
                            tr("الصفحة", "Page")
                        ).append(" $i\n")

                        text.lines()
                            .filter { it.contains(query, ignoreCase = true) }
                            .take(3)
                            .forEach {
                                results.append(it).append('\n')
                            }

                        results.append('\n')
                    }
                }
            } catch (_: Exception) {
            } finally {
                document.close()
            }

            runOnUiThread {
                if (matches == 0) {
                    toast(tr("لم يتم العثور على الكلمة", "No matches found"))
                } else {
                    showTextDialog(
                        tr("نتائج البحث: $matches صفحة", "Search results: $matches pages"),
                        results.toString()
                    )
                }
            }
        }
    }

    private fun ensureEditMode(): Boolean {
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

    private fun promptText() {
        if (!ensureEditMode()) return

        val input = EditText(this).apply {
            hint = tr("اكتب النص الذي تريد إضافته", "Enter text to add")
            setTextColor(white)
            setHintTextColor(muted)
        }

        val sizeInput = EditText(this).apply {
            hint = tr("حجم النص (مثل 24)", "Text size (e.g. 24)")
            setText("24")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(white)
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
            addView(input)
            addView(sizeInput)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("إضافة نص", "Add text"))
            .setView(box)
            .setPositiveButton(tr("التالي", "Next")) { _, _ ->
                val text = input.text.toString().trim()
                val size = sizeInput.text.toString().toFloatOrNull() ?: 24f

                if (text.isNotEmpty()) {
                    promptPosition(text, size.coerceIn(8f, 100f), "TEXT")
                }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun promptWatermark() {
        if (!ensureEditMode()) return

        val input = EditText(this).apply {
            setText(watermarkText)
            setTextColor(white)
        }

        AlertDialog.Builder(this)
            .setTitle(tr("إضافة علامة مائية", "Add watermark"))
            .setView(input)
            .setPositiveButton(tr("التالي", "Next")) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    promptPosition(text, 36f, "WATERMARK")
                }
            }
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .show()
    }

    private fun promptPosition(
        text: String,
        size: Float,
        kind: String
    ) {
        val positions = arrayOf(
            tr("منتصف الصفحة", "Center"),
            tr("أعلى الصفحة", "Top"),
            tr("أسفل الصفحة", "Bottom")
        )

        AlertDialog.Builder(this)
            .setTitle(tr("مكان الإضافة", "Placement"))
            .setItems(positions) { _, which ->
                val y = when (which) {
                    1 -> 0.12f
                    2 -> 0.88f
                    else -> 0.5f
                }

                addTextOverlay(
                    text,
                    size,
                    kind,
                    0.5f,
                    y
                )
            }
            .show()
    }

    private fun addTextOverlay(
        text: String,
        size: Float,
        kind: String,
        centerX: Float,
        centerY: Float
    ) {
        try {
            val textSize = (size * 3).toInt().coerceIn(24, 600)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (kind == "WATERMARK") {
                    Color.argb(90, 20, 140, 75)
                } else {
                    Color.BLACK
                }
                this.textSize = textSize.toFloat()
                typeface = Typeface.create(
                    "sans-serif",
                    if (kind == "WATERMARK") Typeface.BOLD else Typeface.NORMAL
                )
            }

            val padding = 20
            val textWidth = paint.measureText(text).toInt().coerceAtLeast(1)
            val width = (textWidth + padding * 2).coerceIn(80, 2400)
            val height = (textSize * 1.8f).toInt().coerceIn(50, 800)

            val bmp = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

            val canvas = Canvas(bmp)
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            canvas.drawText(
                text,
                padding.toFloat(),
                height * 0.68f,
                paint
            )

            val bytes = bitmapToPng(bmp)
            bmp.recycle()

            val old = snapshot()
            edits.add(
                PdfEdit(
                    pageIndex,
                    bytes,
                    centerX,
                    centerY,
                    0.45f,
                    (height.toFloat() / width.toFloat()) * 0.45f,
                    kind
                )
            )
            pushUndo(old)

            toast(tr("تمت إضافة العنصر", "Element added"))
        } catch (_: Exception) {
            toast(tr("تعذرت إضافة النص", "Could not add text"))
        }
    }

    private fun addImageFromUri(uri: Uri) {
        if (!ensureEditMode()) return

        thread {
            try {
                val bmp = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                } ?: throw IllegalStateException()

                val maxSide = 1200
                val ratio = min(
                    1f,
                    maxSide.toFloat() / max(bmp.width, bmp.height)
                )

                val resized = if (ratio < 1f) {
                    Bitmap.createScaledBitmap(
                        bmp,
                        (bmp.width * ratio).toInt().coerceAtLeast(1),
                        (bmp.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bmp
                }

                val bytes = bitmapToPng(resized)

                if (resized !== bmp) resized.recycle()
                bmp.recycle()

                runOnUiThread {
                    val old = snapshot()
                    edits.add(
                        PdfEdit(
                            pageIndex,
                            bytes,
                            0.5f,
                            0.5f,
                            0.35f,
                            0.35f,
                            "IMAGE"
                        )
                    )
                    pushUndo(old)
                    toast(tr("تمت إضافة الصورة إلى الصفحة", "Image added to page"))
                }
            } catch (_: Exception) {
                runOnUiThread {
                    toast(tr("تعذر فتح الصورة", "Could not open image"))
                }
            }
        }
    }

    private fun showSignatureDialog() {
        if (!ensureEditMode()) return

        val signatureView = SignatureView(this).apply {
            setBackgroundColor(Color.WHITE)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                signatureView,
                LinearLayout.LayoutParams(-1, dp(220))
            )
        }

        AlertDialog.Builder(this)
            .setTitle(tr("ارسم توقيعك", "Draw your signature"))
            .setView(container)
            .setNeutralButton(tr("مسح", "Clear"), null)
            .setNegativeButton(tr("إلغاء", "Cancel"), null)
            .setPositiveButton(tr("إضافة", "Add")) { _, _ ->
                val signature = signatureView.toBitmap()

                if (signature != null) {
                    val bytes = bitmapToPng(signature)
                    signature.recycle()

                    val old = snapshot()
                    edits.add(
                        PdfEdit(
                            pageIndex,
                            bytes,
                            0.5f,
                            0.75f,
                            0.35f,
                            0.15f,
                            "SIGNATURE"
                        )
                    )
                    pushUndo(old)
                }
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                        .setOnClickListener {
                            signatureView.clear()
                        }
                }
                dialog.show()
            }
    }

    private fun bitmapToPng(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    private fun snapshot(): List<PdfEdit> = edits.toList()

    private fun pushUndo(old: List<PdfEdit>) {
        undoStack.add(old)
        redoStack.clear()
    }

    private fun undo() {
        if (undoStack.isEmpty()) {
            toast(tr("لا يوجد ما يمكن التراجع عنه", "Nothing to undo"))
            return
        }

        redoStack.add(snapshot())

        edits.clear()
        edits.addAll(undoStack.removeAt(undoStack.lastIndex))

        toast(tr("تم التراجع", "Undo complete"))
    }

    private fun redo() {
        if (redoStack.isEmpty()) {
            toast(tr("لا يوجد ما يمكن إعادته", "Nothing to redo"))
            return
        }

        undoStack.add(snapshot())

        edits.clear()
        edits.addAll(redoStack.removeAt(redoStack.lastIndex))

        toast(tr("تمت الإعادة", "Redo complete"))
    }

    private fun saveEditedPdfTo(destination: Uri) {
        val source = currentUri ?: return
        val editsToSave = edits.toList()

        thread {
            var success = false

            try {
                val sourceStream = contentResolver.openInputStream(source)
                    ?: throw IllegalStateException("Cannot open source")

                val document = sourceStream.use {
                    PDDocument.load(it)
                }

                try {
                    for (edit in editsToSave) {
                        if (edit.page !in 0 until document.numberOfPages) continue

                        val page = document.getPage(edit.page)
                        val pageWidth = page.mediaBox.width
                        val pageHeight = page.mediaBox.height

                        val image = LosslessFactory.createFromImage(
                            document,
                            BitmapFactory.decodeByteArray(
                                edit.bytes,
                                0,
                                edit.bytes.size
                            ) ?: continue
                        )

                        val drawWidth = (
                            pageWidth * edit.widthFraction
                        ).coerceIn(10f, pageWidth)

                        val drawHeight = (
                            pageHeight * edit.heightFraction
                        ).coerceIn(10f, pageHeight)

                        val x = (
                            pageWidth * edit.centerX - drawWidth / 2f
                        ).coerceIn(0f, pageWidth - drawWidth)

                        val y = (
                            pageHeight * (1f - edit.centerY) -
                                drawHeight / 2f
                        ).coerceIn(0f, pageHeight - drawHeight)

                        PDPageContentStream(
                            document,
                            page,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        ).use { stream ->
                            stream.drawImage(
                                image,
                                x,
                                y,
                                drawWidth,
                                drawHeight
                            )
                        }
                    }

                    val output = contentResolver.openOutputStream(destination)
                        ?: throw IllegalStateException("Cannot open output")

                    output.use {
                        document.save(it)
                    }

                    success = true
                } finally {
                    document.close()
                }
            } catch (_: Exception) {
                success = false
            }

            runOnUiThread {
                if (success) {
                    toast(
                        tr(
                            "تم حفظ النسخة المعدلة",
                            "Edited copy saved"
                        )
                    )
                } else {
                    toast(
                        tr(
                            "تعذر الحفظ. جرّب اسمًا أو مكانًا آخر",
                            "Could not save PDF. Try another location"
                        )
                    )
                }
            }
        }
    }

    private fun closePdf() {
        if (::pageImage.isInitialized) {
            pageImage.setImageDrawable(null)
        }

        try {
            renderer?.close()
        } catch (_: Exception) {
        }

        renderer = null

        try {
            descriptor?.close()
        } catch (_: Exception) {
        }

        descriptor = null

        pageBitmap?.let {
            if (!it.isRecycled) it.recycle()
        }

        pageBitmap = null
        currentUri = null
        pageIndex = 0
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }

    private class ZoomImageView(context: Context) : ImageView(context) {

        private val matrixValue = Matrix()
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

                    matrixValue.postScale(
                        ratio,
                        ratio,
                        detector.focusX,
                        detector.focusY
                    )

                    imageMatrix = matrixValue
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
            val old = scaleFactor
            scaleFactor = (scaleFactor * factor).coerceIn(1f, 5f)

            matrixValue.postScale(
                scaleFactor / old,
                scaleFactor / old,
                width / 2f,
                height / 2f
            )

            imageMatrix = matrixValue
        }

        fun resetZoom() {
            scaleFactor = 1f
            matrixValue.reset()
            imageMatrix = matrixValue
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
                    if (dragging && !detector.isInProgress && scaleFactor > 1f) {
                        matrixValue.postTranslate(
                            event.x - lastX,
                            event.y - lastY
                        )
                        imageMatrix = matrixValue
                    }

                    lastX = event.x
                    lastY = event.y
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> dragging = false
            }

            return true
        }
    }

    private class SignatureView(context: Context) : View(context) {

        private val signatureBitmap: Bitmap
        private val signatureCanvas: Canvas
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 5f
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val path = Path()

        init {
            val width = 1000
            val height = 500

            signatureBitmap = Bitmap.createBitmap(
                width,
                height,
                Bitmap.Config.ARGB_8888
            )

            signatureCanvas = Canvas(signatureBitmap)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val scaleX = width.toFloat() / signatureBitmap.width
            val scaleY = height.toFloat() / signatureBitmap.height

            canvas.save()
            canvas.scale(scaleX, scaleY)
            canvas.drawBitmap(signatureBitmap, 0f, 0f, null)
            canvas.restore()
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x * signatureBitmap.width / width.coerceAtLeast(1)
            val y = event.y * signatureBitmap.height / height.coerceAtLeast(1)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    path.moveTo(x, y)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    path.lineTo(x, y)
                    signatureCanvas.drawPath(path, paint)
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    signatureCanvas.drawPath(path, paint)
                    path.reset()
                    invalidate()
                    return true
                }
            }

            return true
        }

        fun clear() {
            signatureBitmap.eraseColor(Color.TRANSPARENT)
            path.reset()
            invalidate()
        }

        fun toBitmap(): Bitmap? {
            return try {
                signatureBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                null
            }
        }
    }
}
