package com.example.pdfreader

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
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

    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var bitmap: Bitmap? = null
    private var pageIndex = 0

    private lateinit var pageImage: ImageView
    private lateinit var pageInfo: TextView
    private lateinit var previousButton: Button
    private lateinit var nextButton: Button

    companion object {
        private const val PICK_PDF = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createScreen()
    }

    private fun createScreen() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(16, 16, 16, 16)
        root.setBackgroundColor(Color.WHITE)

        val title = TextView(this)
        title.text = "PDF Reader"
        title.textSize = 28f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.rgb(35, 75, 150))

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val openButton = Button(this)
        openButton.text = "فتح ملف PDF"
        openButton.setOnClickListener {
            choosePdf()
        }

        root.addView(
            openButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        pageInfo = TextView(this)
        pageInfo.text = "اختر ملف PDF للقراءة"
        pageInfo.textSize = 16f
        pageInfo.gravity = Gravity.CENTER
        pageInfo.setTextColor(Color.DKGRAY)
        pageInfo.setPadding(0, 12, 0, 12)

        root.addView(
            pageInfo,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        pageImage = ImageView(this)
        pageImage.adjustViewBounds = true
        pageImage.scaleType = ImageView.ScaleType.FIT_CENTER
        pageImage.setBackgroundColor(Color.LTGRAY)

        val scroll = ScrollView(this)
        scroll.addView(
            pageImage,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        val navigation = LinearLayout(this)
        navigation.orientation = LinearLayout.HORIZONTAL

        previousButton = Button(this)
        previousButton.text = "السابق"
        previousButton.isEnabled = false
        previousButton.setOnClickListener {
            if (pageIndex > 0) {
                pageIndex--
                showPage()
            }
        }

        nextButton = Button(this)
        nextButton.text = "التالي"
        nextButton.isEnabled = false
        nextButton.setOnClickListener {
            val pdf = renderer
            if (pdf != null && pageIndex < pdf.pageCount - 1) {
                pageIndex++
                showPage()
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
            navigation,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
    }

    private fun choosePdf() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "application/pdf"

        startActivityForResult(intent, PICK_PDF)
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
            openPdf(uri)
        }
    }

    private fun openPdf(uri: android.net.Uri) {
        closePdf()

        try {
            val fd = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("File unavailable")

            descriptor = fd
            renderer = PdfRenderer(fd)
            pageIndex = 0
            showPage()

        } catch (e: Exception) {
            closePdf()
            pageInfo.text = "تعذر فتح الملف"
            Toast.makeText(
                this,
                "تعذر فتح ملف PDF",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPage() {
        val pdf = renderer ?: return
        var page: PdfRenderer.Page? = null

        try {
            page = pdf.openPage(pageIndex)

            val maxWidth = (
                resources.displayMetrics.widthPixels - 32
            ).coerceAtLeast(1)

            val scale = maxWidth.toFloat() / page.width
            val width = maxWidth
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

            val oldBitmap = bitmap
            bitmap = newBitmap
            pageImage.setImageBitmap(newBitmap)

            if (oldBitmap != null) {
                oldBitmap.recycle()
            }

            pageInfo.text =
                "الصفحة ${pageIndex + 1} من ${pdf.pageCount}"

            previousButton.isEnabled = pageIndex > 0
            nextButton.isEnabled = pageIndex < pdf.pageCount - 1

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "حدث خطأ أثناء عرض الصفحة",
                Toast.LENGTH_SHORT
            ).show()
        } finally {
            page?.close()
        }
    }

    private fun closePdf() {
        pageImageSafeClear()

        renderer?.close()
        renderer = null

        descriptor?.close()
        descriptor = null

        pageIndex = 0
    }

    private fun pageImageSafeClear() {
        if (::pageImage.isInitialized) {
            pageImage.setImageDrawable(null)
        }

        bitmap?.recycle()
        bitmap = null
    }

    override fun onDestroy() {
        closePdf()
        super.onDestroy()
    }
}
