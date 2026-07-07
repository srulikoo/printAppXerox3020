package com.local.splprint

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
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
private data class QueueItem(
    val name: String,
    val pages: List<Bitmap>,
    var scaleMode: ScaleMode = ScaleMode.FIT,
    var orientation: Orientation = Orientation.PORTRAIT,
    var threshold: Int = 150
)

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
    private var selectedIndex: Int = 0 // which queue item the controls/preview currently reflect

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

        findViewById<TextView>(R.id.aboutLink).setOnClickListener {
            showAboutDialog()
        }

        scaleModeGroup.setOnCheckedChangeListener { _, _ -> applyControlsToSelectedItem() }
        orientationGroup.setOnCheckedChangeListener { _, _ -> applyControlsToSelectedItem() }
        thresholdSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyControlsToSelectedItem()
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

        // New items default to whatever Fit/Orientation/Threshold is
        // currently showing on screen -- a reasonable starting point the
        // user can then adjust per-item by tapping it in the queue list.
        val defaultScaleMode = currentScaleMode()
        val defaultOrientation = currentOrientation()
        val defaultThreshold = thresholdSeekBar.progress
        val firstNewIndex = queue.size

        CoroutineScope(Dispatchers.Main).launch {
            var loadedCount = 0
            val failedNames = mutableListOf<String>()

            for (uri in uris) {
                val name = displayNameOf(uri)
                try {
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
                        queue.add(
                            QueueItem(name, pages, defaultScaleMode, defaultOrientation, defaultThreshold)
                        )
                        loadedCount++
                    } else {
                        failedNames.add(name)
                    }
                } catch (e: Throwable) {
                    // Catches both Exceptions (bad/corrupt file, permission
                    // revoked) and Errors (e.g. OutOfMemoryError on a very
                    // large image) so a failure here always surfaces a
                    // message instead of silently doing nothing.
                    failedNames.add("$name (${e::class.simpleName}: ${e.message})")
                }
            }

            if (loadedCount > 0) {
                selectedIndex = firstNewIndex
                loadItemIntoControls(queue[selectedIndex])
            }

            statusText.text = when {
                failedNames.isEmpty() -> "Queue has ${queue.size} document(s)."
                loadedCount == 0 -> "Failed to load: ${failedNames.joinToString(", ")}"
                else -> "Added $loadedCount. Failed: ${failedNames.joinToString(", ")}"
            }
            refreshQueueUI()
            updatePreview()
        }
    }

    /** Rebuilds the queue list UI: one row per queued document, tappable to
     *  select it for preview/editing (highlighted when selected), with a
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
                setBackgroundColor(if (index == selectedIndex) Color.parseColor("#332D2A55") else Color.TRANSPARENT)
                setOnClickListener {
                    selectedIndex = index
                    loadItemIntoControls(item)
                    refreshQueueUI()
                    updatePreview()
                }
            }
            val label = TextView(this).apply {
                val pageSuffix = if (item.pages.size > 1) " (${item.pages.size} pages)" else ""
                val marker = if (index == selectedIndex) "\u25B6 " else ""
                text = "$marker${index + 1}. ${item.name}$pageSuffix"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val removeButton = Button(this).apply {
                text = "Remove"
                textSize = 11f
                setOnClickListener {
                    queue.removeAt(index)
                    if (selectedIndex >= queue.size) selectedIndex = (queue.size - 1).coerceAtLeast(0)
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
     * Renders a preview of the currently selected queue item's first page,
     * using that item's own stored scale mode/orientation/threshold (not a
     * shared global setting), in its natural orientation (composeContent
     * only -- no physical-page rotation), so a landscape selection shows
     * correctly-oriented wide content on screen rather than the printer's
     * rotated portrait layout.
     */
    private fun updatePreview() {
        val item = queue.getOrNull(selectedIndex)
        val firstPage = item?.pages?.firstOrNull()
        if (item == null || firstPage == null) {
            previewImage.setImageBitmap(null)
            return
        }

        val composed = ImageProcessor.composeContent(
            firstPage, previewPageWidth, previewPageHeight, item.scaleMode, item.orientation
        )
        val thresholded = ImageProcessor.applyThresholdPreview(composed, item.threshold)
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

    /** Writes the current on-screen control values into whichever queue item
     *  is currently selected, then refreshes the preview to match. Called
     *  whenever the user changes Fit/Fill/Stretch, Portrait/Landscape, or
     *  the threshold slider -- so each document remembers its own settings
     *  instead of one shared global setting applying to everything. */
    private fun applyControlsToSelectedItem() {
        val item = queue.getOrNull(selectedIndex) ?: return
        item.scaleMode = currentScaleMode()
        item.orientation = currentOrientation()
        item.threshold = thresholdSeekBar.progress
        updatePreview()
    }

    /** Loads a queue item's stored settings into the on-screen controls
     *  (without re-triggering a write back into a *different* item -- this
     *  is called right after selectedIndex is updated, so any listener
     *  callbacks it fires just write the same values back into the newly
     *  selected item, which is harmless). */
    private fun loadItemIntoControls(item: QueueItem) {
        val scaleId = when (item.scaleMode) {
            ScaleMode.FILL -> R.id.radioFill
            ScaleMode.STRETCH -> R.id.radioStretch
            ScaleMode.FIT -> R.id.radioFit
        }
        scaleModeGroup.check(scaleId)
        orientationGroup.check(
            if (item.orientation == Orientation.LANDSCAPE) R.id.radioLandscape else R.id.radioPortrait
        )
        thresholdSeekBar.progress = item.threshold
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        val versionLine = if (versionName != null) "Version $versionName\n\n" else ""

        val message = versionLine +
            "The Xerox Phaser 3020 is a budget, host-based (\"GDI\") laser " +
            "printer with no rendering engine of its own -- it speaks only its " +
            "own proprietary SPL/QPDL protocol, not standard PDF/raster or a " +
            "working IPP/AirPrint pipeline. Xerox's own app has no print " +
            "function for this model, and Android's built-in print framework " +
            "fails outright (\"Printer blocked\").\n\n" +
            "This app talks to the printer's real protocol directly from your " +
            "phone -- no PC, no CUPS, no ads -- using a native encoder ported " +
            "from the open-source OpenPrinting/SpliX driver.\n\n" +
            "Features: image and PDF printing, multi-document queue, live " +
            "preview, Fit/Fill/Stretch scaling, Portrait/Landscape, adjustable " +
            "black threshold, connection check, and local network scanning."

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setMessage(message)
            .setPositiveButton("View on GitHub") { _, _ ->
                val intent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/srulikoo/printAppXerox3020")
                )
                startActivity(intent)
            }
            .setNegativeButton("Close", null)
            .show()
    }

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
                                page, widthPx, heightPx, item.scaleMode, item.threshold, item.orientation
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
                    if (selectedIndex >= queue.size) selectedIndex = (queue.size - 1).coerceAtLeast(0)
                    recentSent.add(0, item.name)
                    while (recentSent.size > 5) recentSent.removeAt(recentSent.size - 1)
                    refreshQueueUI()
                    refreshRecentSentUI()
                    updatePreview()
                }

                statusText.text = "Queue complete. All documents sent."
            } catch (e: Throwable) {
                statusText.text = "Failed on document $docIndex: ${e::class.simpleName}: ${e.message}"
            }
        }
    }
}
