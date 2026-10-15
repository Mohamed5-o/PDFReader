package com.example.pdfreader

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var layout: LinearLayout
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var openButton: Button

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    companion object {
        private const val PICK_PDF_REQUEST = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHomeScreen()
    }

    private fun showHomeScreen() {
        layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        title = TextView(this).apply {
            text = "PDF Reader"
            textSize = 30f
            setTextColor(Color.rgb(35, 75, 150))
            gravity = Gravity.CENTER
        }

        description = TextView(this).apply {
            text = "اقرأ ملفات PDF وعدّل عليها بسهولة"
            textSize = 18f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }

        openButton = Button(this).apply {
            text = "فتح ملف PDF"
            textSize = 18f
            isEnabled = true
            setOnClickListener {
                openPdfPicker()
            }
        }

        layout.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            description,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            openButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(layout)
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
                "تعذّر فتح مدير الملفات",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated by Android, retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_PDF_REQUEST &&
            resultCode == RESULT_OK &&
            data?.data != null
        ) {
            val uri: Uri = data.data!!

            try {
                fileDescriptor?.close()
                pdfRenderer?.close()

                fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("تعذّر فتح الملف")

                pdfRenderer = PdfRenderer(fileDescriptor!!)

                val pages = pdfRenderer!!.pageCount

                description.text =
                    "تم اختيار ملف PDF بنجاح\nعدد الصفحات: $pages"

                openButton.text = "اخت
