package com.example.pdfreader

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var pageImage: ImageView
    private lateinit var pageInfo: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPage = 0
    private var pageBitmap: Bitmap? = null

    companion object {
        private const val PICK_PDF_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHomeScreen()
    }

    private fun showHomeScreen() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "PDF Reader"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 75, 150))
            setPadding(0, 12, 0, 12)
        }

        status = TextView(this).apply {
            text = "افتح ملف PDF لبدء القراءة"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 12)
        }

        val openButton = Button(this).apply {
            text = "فتح ملف PDF"
            setOnClickListener {
                openPdfPicker()
            }
        }

        pageInfo = TextView(this).apply {
            text = "لم يتم فتح ملف"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 8)
        }

        pageImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.LTGRAY)
        }

        val imageScroll = ScrollView(this)
        imageScroll.addView(
            pageImage,
            ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        previousButton = Button(this).apply {
            text = "السابق"
            isEnabled = false
            setOnClickListener {
                if (currentPage > 0) {
                    currentPage--
                    renderPage()
                }
            }
        }

        nextButton = Button(this).apply {
            text = "التالي"
            isEnabled = false
            setOnClickListener {
                val renderer = pdfRenderer
                if (renderer != null &&
                    currentPage < renderer.pageCount - 1
                ) {
                    currentPage++
                    renderPage()
                }
            }
        }

        navigation.addView(
            previousButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        navigation.addView(
            nextButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            openButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            pageInfo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            imageScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        root.addView(
            navigation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun openPdfPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }

        try {
            startActivityForResult(intent, PICK_PDF_REQUEST)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "تعذر فتح مستعرض الملفات",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_PDF_REQUEST &&
            resultCode == RESULT_OK
        ) {
            val uri = data?.data ?: return
            openPdf(uri)
        }
    }

    private fun openPdf(uri: android.net.Uri) {
        closePdf()

        try {
            fileDescriptor = contentResolver.openFileDescriptor(
                uri,
                "r"
            )

            val descriptor = fileDescriptor
                ?: throw Exception("تعذر فتح الملف")

            pdfRenderer = PdfRenderer(descriptor)
            currentPage = 0

            status.text = "تم فتح ملف PDF بنجاح"
            renderPage()

        } catch (e: Exception) {
            closePdf()

            Toast.makeText(
                this,
                "تعذر فتح الملف. جرّب ملف PDF آخر.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun renderPage() {
        val renderer = pdfRenderer ?: return

        try {
            val page = renderer.openPage(currentPage)

            val width = page.width
            val height = page.height

            val maxWidth = (resources.displayMetrics.widthPixels * 0.9f)
                .toInt()
                .coerceAtLeast(1)

            val scale = maxWidth.toFloat() / width.toFloat()

            val bitmapWidth = maxWidth
            val bitmapHeight = (height * scale)
                .toInt()
                .coerceAtLeast(1)

            val newBitmap = Bitmap.createBitmap(
                bitmapWidth,
                bitmapHeight,
                Bitmap.Config.ARGB_8888
            )

            newBitmap.eraseColor(Color.WHITE)

            page.render(
                newBitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )

            page.close()

            pageBitmap?.recycle()
            pageBitmap = newBitmap
            pageImage.setImageBitmap(newBitmap)

            pageInfo.text =
                "الصفحة ${currentPage + 1} من ${renderer.pageCount}"

            previousButton.isEnabled = currentPage > 0
            nextButton.isEnabled =
                currentPage < renderer.pageCount - 1

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "حدث خطأ أثناء عرض الصفحة",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun closePdf() {
        pageImage.setImageDrawable(null)

        pageBitmap?.recycle()
        pageBitmap = null

        pdfRenderer?.close()
        pdfRenderer = null

        fileDescriptor?.close()
        fileDescriptor = null
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }
}
