package com.local.splprint

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
    private lateinit var qualityGroup: RadioGroup
    private lateinit var thresholdSeekBar: SeekBar
    private lateinit var statusText: TextView

    private var selectedBitmap: Bitmap? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) loadSelectedImage(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipInput = findViewById(R.id.ipInput)
        previewImage = findViewById(R.id.previewImage)
        scaleModeGroup = findViewById(R.id.scaleModeGroup)
        orientationGroup = findViewById(R.id.orientationGroup)
        qualityGroup = findViewById(R.id.qualityGroup)
        thresholdSeekBar = findViewById(R.id.thresholdSeekBar)
        statusText = findViewById(R.id.statusText)

        findViewById<Button>(R.id.selectImageButton).setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        findViewById<Button>(R.id.printButton).setOnClickListener {
            onPrintClicked()
        }
    }

    private fun loadSelectedImage(uri: Uri) {
        val resolver: ContentResolver = contentResolver
        resolver.openInputStream(uri)?.use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            selectedBitmap = bitmap
            previewImage.setImageBitmap(bitmap)
        }
    }

    private fun currentScaleMode(): ScaleMode = when (scaleModeGroup.checkedRadioButtonId) {
        R.id.radioFill -> ScaleMode.FILL
        R.id.radioStretch -> ScaleMode.STRETCH
        else -> ScaleMode.FIT
    }

    private fun currentOrientation(): Orientation =
        if (orientationGroup.checkedRadioButtonId == R.id.radioLandscape)
            Orientation.LANDSCAPE else Orientation.PORTRAIT

    private fun currentHighRes(): Boolean =
        qualityGroup.checkedRadioButtonId == R.id.radioHigh

    private fun onPrintClicked() {
        val bitmap = selectedBitmap
        if (bitmap == null) {
            statusText.text = "Select an image first."
            return
        }
        val ip = ipInput.text.toString().trim()
        if (ip.isEmpty()) {
            statusText.text = "Enter the printer IP address."
            return
        }

        val scaleMode = currentScaleMode()
        val orientation = currentOrientation()
        val highRes = currentHighRes()
        val threshold = thresholdSeekBar.progress

        statusText.text = "Preparing print job..."

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val jobBytes = withContext(Dispatchers.Default) {
                    val paperName = "A4"
                    val widthPx = QpdlEncoder.requiredWidthPx(paperName, highRes)
                    val heightPx = QpdlEncoder.requiredHeightPx(paperName)
                    val packed = ImageProcessor.renderToPackedBitmap(
                        bitmap, widthPx, heightPx, scaleMode, threshold, orientation
                    )
                    QpdlEncoder.encode(
                        packed, widthPx, heightPx, paperName,
                        1, "android", "coloring page", highRes
                    )
                }

                statusText.text = "Sending to printer at $ip..."

                withContext(Dispatchers.IO) {
                    PrinterSender.send(ip, jobBytes)
                }

                statusText.text = "Sent (${jobBytes.size} bytes). Check the printer."
            } catch (e: Exception) {
                statusText.text = "Failed: ${e.message}"
            }
        }
    }
}
