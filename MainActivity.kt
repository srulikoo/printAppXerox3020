package com.local.splprint

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var previewImage: ImageView
    private lateinit var scaleModeGroup: RadioGroup
    private lateinit var orientationGroup: RadioGroup
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView

    // One entry per page to print. A picked image produces a single-item
    // list; a picked PDF produces one Bitmap per page. Both flow through
    // the exact same downstream pipeline (ImageProcessor / QpdlEncoder /
    // PrinterSender) unchanged.
    private var selectedPages: List<Bitmap> = emptyList()

    private val previewPageWidth = 350
    private val previewPageHeight = 495

    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) loadSelectedFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        previewImage = findViewById(R.id.previewImage)
        scaleModeGroup = findViewById(R.id.scaleModeGroup)
        orientationGroup = findViewById(R.id.orientationGroup)
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar)
        statusText = findViewById(R.id.statusText)
        connectionStatusText = findViewById(R.id.connectionStatusText)

        findViewById<Button>(R.id.selectImageButton).setOnClickListener {
            pickFileLauncher.launch(arrayOf("image/*", "application/pdf"))
        }

        findViewById<Button>(R.id.printButton).setOnClickListener {
            onPrintClicked()
        }

        findViewById<Button>(R.id.checkConnectionButton).setOnClickListener {
            onCheckConnectionClicked()
        }

        scaleModeGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        orientationGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadSelectedFile(uri: Uri) {
        val resolver: ContentResolver = contentResolver
        val mimeType = resolver.getType(uri) ?: ""

        statusText.text = "Loading..."

        CoroutineScope(Dispatchers.Main).launch {
            val pages = withContext(Dispatchers.Default) {
                if (mimeType == "application/pdf") {
                    PdfProcessor.renderAllPages(this@MainActivity, uri)
                } else {
                    // Image path -- unchanged from before, just wrapped in a
                    // single-item list so it shares the same pipeline as PDFs.
                    resolver.openInputStream(uri)?.use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream)
                        if (bmp != null) listOf(bmp) else emptyList()
                    } ?: emptyList()
                }
            }

            if (pages.isEmpty()) {
                statusText.text = "Could not load file."
                return@launch
            }

            selectedPages = pages
            statusText.text = if (pages.size > 1)
                "Loaded PDF: ${pages.size} pages." else "Image loaded."
            updatePreview()
        }
    }

    /**
     * Renders a small preview of the first page using the exact same
     * compose+rotate logic as the real print job, so the preview accurately
     * reflects the chosen scale mode, orientation, and threshold.
     */
    private fun updatePreview() {
        val firstPage = selectedPages.firstOrNull() ?: return
        val scaleMode = currentScaleMode()
        val orientation = currentOrientation()
        val threshold = thresholdSeekBar.progress

        val composed = ImageProcessor.composePage(
            firstPage, previewPageWidth, previewPageHeight, scaleMode, orientation
        )
        val thresholded = ImageProcessor.applyThresholdPreview(composed, threshold)
        composed.recycle()
        previewImage.setImageBitmap(thresholded)
    }

    private fun currentScaleMode(): ScaleMode = when (scaleModeGroup.checkedRadioButtonId) {
        R.id.radioFill -> ScaleMode.FILL
        R.id.radioStretch -> ScaleMode.STRETCH
        else -> ScaleMode.FIT
    }

    private fun currentOrientation(): Orientation =
        if (orientationGroup.checkedRadioButtonId == R.id.radioLandscape)
            Orientation.LANDSCAPE else Orientation.PORTRAIT

    private fun onCheckConnectionClicked() {
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            connectionStatusText.text = "Enter an IP first"
            connectionStatusText.setTextColor(Color.YELLOW)
            return
        }
        connectionStatusText.text = "Checking..."
        connectionStatusText.setTextColor(Color.YELLOW)

        CoroutineScope(Dispatchers.Main).launch {
            val reachable = withContext(Dispatchers.IO) {
                PrinterSender.checkConnection(ip)
            }
            if (reachable) {
                connectionStatusText.text = "Printer online"
                connectionStatusText.setTextColor(Color.GREEN)
            } else {
                connectionStatusText.text = "Not reachable"
                connectionStatusText.setTextColor(Color.RED)
            }
        }
    }

    private fun onPrintClicked() {
        val pages = selectedPages
        if (pages.isEmpty()) {
            statusText.text = "Select an image or PDF first."
            return
        }
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            statusText.text = "Enter the printer IP address."
            return
        }

        val scaleMode = currentScaleMode()
        val orientation = currentOrientation()
        val threshold = thresholdSeekBar.progress

        CoroutineScope(Dispatchers.Main).launch {
            try {
                for ((index, page) in pages.withIndex()) {
                    val pageLabel = if (pages.size > 1)
                        "page ${index + 1}/${pages.size}" else "page"

                    statusText.text = "Preparing $pageLabel..."

                    val jobBytes = withContext(Dispatchers.Default) {
                        val paperName = "A4"
                        val highRes = false // printer firmware rejects 1200x600 (IllegalResolution)
                        val widthPx = QpdlEncoder.requiredWidthPx(paperName, highRes)
                        val heightPx = QpdlEncoder.requiredHeightPx(paperName)
                        val packed = ImageProcessor.renderToPackedBitmap(
                            page, widthPx, heightPx, scaleMode, threshold, orientation
                        )
                        QpdlEncoder.encode(
                            packed, widthPx, heightPx, paperName,
                            1, "android", "print job", highRes
                        )
                    }

                    statusText.text = "Sending $pageLabel to $ip..."

                    withContext(Dispatchers.IO) {
                        PrinterSender.send(ip, jobBytes)
                    }
                }

                statusText.text = if (pages.size > 1)
                    "Sent all ${pages.size} pages. Check the printer."
                else
                    "Sent. Check the printer."
            } catch (e: Exception) {
                statusText.text = "Failed: ${e.message}"
            }
        }
    }
}
