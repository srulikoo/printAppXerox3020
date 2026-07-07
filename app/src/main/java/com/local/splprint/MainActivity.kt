package com.local.splprint

import android.app.AlertDialog
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One queued document: its display name and its rendered page bitmaps
 *  (a single-item list for images, one entry per page for PDFs). */
private data class QueueItem(val name: String, val pages: List<Bitmap>)

class MainActivity : AppCompatActivity() {

    private lateinit var ipInput: EditText
    private lateinit var previewImage: ImageView
    private lateinit var scaleModeGroup: RadioGroup
    private lateinit var orientationGroup: RadioGroup
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var statusText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var queueContainer: LinearLayout
    private lateinit var recentSentText: TextView

    private val queue: MutableList<QueueItem> = mutableListOf()
    private val recentSent: MutableList<String> = mutableListOf() // newest first, capped at 5

    private val previewPageWidth = 350
    private val previewPageHeight = 495

    private val pickFilesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isNotEmpty()) loadSelectedFiles(uris)
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
        queueContainer = findViewById(R.id.queueContainer)
        recentSentText = findViewById(R.id.recentSentText)

        findViewById<Button>(R.id.selectImageButton).setOnClickListener {
            pickFilesLauncher.launch(arrayOf("image/*", "application/pdf"))
        }

        findViewById<Button>(R.id.printButton).setOnClickListener {
            onPrintClicked()
        }

        findViewById<Button>(R.id.checkConnectionButton).setOnClickListener {
            onCheckConnectionClicked()
        }

        findViewById<Button>(R.id.scanNetworkButton).setOnClickListener {
            onScanNetworkClicked()
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

        refreshQueueUI()
        refreshRecentSentUI()
    }

    private fun displayNameOf(uri: Uri): String {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment ?: "file"
    }

    private fun loadSelectedFiles(uris: List<Uri>) {
        val resolver: ContentResolver = contentResolver
        statusText.text = "Loading ${uris.size} file(s)..."

        CoroutineScope(Dispatchers.Main).launch {
            for (uri in uris) {
                val name = displayNameOf(uri)
                val mimeType = resolver.getType(uri) ?: ""

                val pages = withContext(Dispatchers.Default) {
                    if (mimeType == "application/pdf") {
                        PdfProcessor.renderAllPages(this@MainActivity, uri)
                    } else {
                        resolver.openInputStream(uri)?.use { stream ->
                            val bmp = BitmapFactory.decodeStream(stream)
                            if (bmp != null) listOf(bmp) else emptyList()
                        } ?: emptyList()
                    }
                }

                if (pages.isNotEmpty()) {
                    queue.add(QueueItem(name, pages))
                }
            }

            statusText.text = "Queue has ${queue.size} document(s)."
            refreshQueueUI()
            updatePreview()
        }
    }

    /** Rebuilds the queue list UI: one row per queued document, with a
     *  remove ("✕") button per row. */
    private fun refreshQueueUI() {
        queueContainer.removeAllViews()
        for (index in queue.indices) {
            val item = queue[index]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = 4
                    bottomMargin = 4
                }
            }
            val label = TextView(this).apply {
                val pageSuffix = if (item.pages.size > 1) " (${item.pages.size} pages)" else ""
                text = "${index + 1}. ${item.name}$pageSuffix"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val removeButton = Button(this).apply {
                text = "Remove"
                textSize = 11f
                setOnClickListener {
                    queue.removeAt(index)
                    refreshQueueUI()
                    updatePreview()
                }
            }
            row.addView(label)
            row.addView(removeButton)
            queueContainer.addView(row)
        }
    }

    private fun refreshRecentSentUI() {
        recentSentText.text = if (recentSent.isEmpty())
            "No documents sent yet."
        else
            recentSent.joinToString("\n") { "\u2022 $it" }
    }

    /**
     * Preview shows the next document in the queue (the one about to print),
     * in its natural orientation (composeContent only -- no physical-page
     * rotation), so a landscape selection shows correctly-oriented wide
     * content on screen rather than the printer's rotated portrait layout.
     */
    private fun updatePreview() {
        val firstPage = queue.firstOrNull()?.pages?.firstOrNull()
        if (firstPage == null) {
            previewImage.setImageBitmap(null)
            return
        }
        val scaleMode = currentScaleMode()
        val orientation = currentOrientation()
        val threshold = thresholdSeekBar.progress

        val composed = ImageProcessor.composeContent(
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

    private fun onScanNetworkClicked() {
        connectionStatusText.text = "Scanning..."
        connectionStatusText.setTextColor(Color.YELLOW)

        CoroutineScope(Dispatchers.Main).launch {
            val localIp = withContext(Dispatchers.IO) { NetworkScanner.getLocalIPv4() }
            val prefix = localIp?.let { NetworkScanner.subnetPrefixOf(it) }

            if (prefix == null) {
                connectionStatusText.text = "Couldn't detect network"
                connectionStatusText.setTextColor(Color.RED)
                return@launch
            }

            val found = withContext(Dispatchers.IO) { NetworkScanner.scanForPrinters(prefix) }

            if (found.isEmpty()) {
                connectionStatusText.text = "No printers found on $prefix.x"
                connectionStatusText.setTextColor(Color.RED)
                return@launch
            }

            connectionStatusText.text = "Found ${found.size} device(s)"
            connectionStatusText.setTextColor(Color.GREEN)

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Select printer IP")
                .setItems(found.toTypedArray()) { _, which ->
                    ipInput.setText(found[which])
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

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

    /**
     * Prints every document currently in the queue, in order. Each document
     * that finishes sending successfully is removed from the queue and its
     * name is pushed onto the "recently sent" list (capped at 5, newest
     * first). If a document fails partway, it stays in the queue (so it can
     * be retried) and the run stops there, leaving later queue items intact.
     */
    private fun onPrintClicked() {
        if (queue.isEmpty()) {
            statusText.text = "Add an image or PDF to the queue first."
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
            var docIndex = 0
            try {
                while (queue.isNotEmpty()) {
                    val item = queue[0]
                    docIndex++
                    val totalDocs = docIndex + queue.size - 1

                    for ((pageIndex, page) in item.pages.withIndex()) {
                        val label = if (item.pages.size > 1)
                            "\"${item.name}\" page ${pageIndex + 1}/${item.pages.size} (doc $docIndex/$totalDocs)"
                        else
                            "\"${item.name}\" (doc $docIndex/$totalDocs)"

                        statusText.text = "Sending $label..."

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
                                1, "android", item.name, highRes
                            )
                        }

                        withContext(Dispatchers.IO) {
                            PrinterSender.send(ip, jobBytes)
                        }
                    }

                    // Whole document sent successfully: remove from the
                    // active queue and record it as recently sent.
                    queue.removeAt(0)
                    recentSent.add(0, item.name)
                    while (recentSent.size > 5) recentSent.removeAt(recentSent.size - 1)
                    refreshQueueUI()
                    refreshRecentSentUI()
                    updatePreview()
                }

                statusText.text = "Queue complete. All documents sent."
            } catch (e: Exception) {
                statusText.text = "Failed on document $docIndex: ${e.message}"
            }
        }
    }
}
