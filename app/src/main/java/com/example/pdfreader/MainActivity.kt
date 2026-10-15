package com.example.pdfreader

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var openButton: Button
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
            setPadding(20, 20, 20, 20)
            setBackgroundColor(Color.WHITE)
        }

        title = TextView(this).apply {
            text = "PDF Reader"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(35, 75, 150))
            setPadding(0, 12, 0, 16)
        }

        status = TextView(this).apply {
            text = "افتح ملف PDF لبدء القراءة"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
            setPadding(0, 8, 0, 16)
        }

        openButton = Button(this).apply {
            text = "فتح ملف PDF"
            setOnClickListener {
                openPdfPicker()
            }
        }

        pageInfo = TextView(this).apply {
            text = ""
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
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
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
                    render
