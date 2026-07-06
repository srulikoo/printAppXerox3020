package com.local.splprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect

enum class ScaleMode { FIT, FILL, STRETCH }
enum class Orientation { PORTRAIT, LANDSCAPE }

object ImageProcessor {

    /**
     * Renders [source] onto a page canvas of exactly [pageWidthPx] x [pageHeightPx],
     * converts to grayscale, applies a threshold to produce pure black/white,
     * and packs the result into a 1bpp row-major MSB-first bitmap
     * (1 = black ink, 0 = white paper) as required by [QpdlEncoder.encode].
     *
     * @param threshold 0-255; pixels darker than this become black.
     */
    fun renderToPackedBitmap(
        source: Bitmap,
        pageWidthPx: Int,
        pageHeightPx: Int,
        scaleMode: ScaleMode,
        threshold: Int = 150,
        orientation: Orientation = Orientation.PORTRAIT
    ): ByteArray {
        // The printer's physical page is always pageWidthPx x pageHeightPx
        // (portrait, matching the printer's exact band-width table). For
        // landscape, we compose the image into a swapped-dimension canvas
        // and then rotate the whole composed page 90 degrees to fit back
        // into the physical portrait canvas -- the printer itself is never
        // told about orientation, only the content is rotated.
        val composeW = if (orientation == Orientation.LANDSCAPE) pageHeightPx else pageWidthPx
        val composeH = if (orientation == Orientation.LANDSCAPE) pageWidthPx else pageHeightPx

        val composed = Bitmap.createBitmap(composeW, composeH, Bitmap.Config.ARGB_8888)
        val composeCanvas = Canvas(composed)
        composeCanvas.drawColor(Color.WHITE)

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val dstRect: Rect = when (scaleMode) {
            ScaleMode.STRETCH -> Rect(0, 0, composeW, composeH)
            ScaleMode.FIT -> {
                val scale = minOf(composeW / srcW, composeH / srcH)
                val w = (srcW * scale).toInt()
                val h = (srcH * scale).toInt()
                val left = (composeW - w) / 2
                val top = (composeH - h) / 2
                Rect(left, top, left + w, top + h)
            }
            ScaleMode.FILL -> {
                val scale = maxOf(composeW / srcW, composeH / srcH)
                val w = (srcW * scale).toInt()
                val h = (srcH * scale).toInt()
                val left = (composeW - w) / 2
                val top = (composeH - h) / 2
                Rect(left, top, left + w, top + h)
            }
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        composeCanvas.drawBitmap(source, null, dstRect, paint)

        // Rotate into the physical (portrait) page if landscape was requested.
        val page: Bitmap
        if (orientation == Orientation.LANDSCAPE) {
            page = Bitmap.createBitmap(pageWidthPx, pageHeightPx, Bitmap.Config.ARGB_8888)
            val rotateCanvas = Canvas(page)
            rotateCanvas.drawColor(Color.WHITE)
            val matrix = Matrix()
            matrix.postRotate(90f)
            matrix.postTranslate(pageWidthPx.toFloat(), 0f)
            rotateCanvas.drawBitmap(composed, matrix, paint)
            composed.recycle()
        } else {
            page = composed
        }

        // 2. Grayscale + threshold -> pack into 1bpp MSB-first rows.
        val lineBytes = (pageWidthPx + 7) / 8
        val packed = ByteArray(lineBytes * pageHeightPx)
        val row = IntArray(pageWidthPx)

        for (y in 0 until pageHeightPx) {
            page.getPixels(row, 0, pageWidthPx, 0, y, pageWidthPx, 1)
            var byteIndex = y * lineBytes
            var bitMask = 0x80
            var currentByte = 0
            for (x in 0 until pageWidthPx) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // Standard luma weighting.
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                val isBlack = gray < threshold
                if (isBlack) currentByte = currentByte or bitMask
                bitMask = bitMask ushr 1
                if (bitMask == 0) {
                    packed[byteIndex] = currentByte.toByte()
                    byteIndex++
                    bitMask = 0x80
                    currentByte = 0
                }
            }
            if (bitMask != 0x80) {
                packed[byteIndex] = currentByte.toByte()
            }
        }

        page.recycle()
        return packed
    }
}
