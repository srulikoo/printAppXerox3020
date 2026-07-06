package com.local.splprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect

enum class ScaleMode { FIT, FILL, STRETCH }

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
        threshold: Int = 150
    ): ByteArray {
        // 1. Compose the source image onto a white page-sized canvas per scaleMode.
        val page = Bitmap.createBitmap(pageWidthPx, pageHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.WHITE)

        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val dstRect: Rect = when (scaleMode) {
            ScaleMode.STRETCH -> Rect(0, 0, pageWidthPx, pageHeightPx)
            ScaleMode.FIT -> {
                val scale = minOf(pageWidthPx / srcW, pageHeightPx / srcH)
                val w = (srcW * scale).toInt()
                val h = (srcH * scale).toInt()
                val left = (pageWidthPx - w) / 2
                val top = (pageHeightPx - h) / 2
                Rect(left, top, left + w, top + h)
            }
            ScaleMode.FILL -> {
                val scale = maxOf(pageWidthPx / srcW, pageHeightPx / srcH)
                val w = (srcW * scale).toInt()
                val h = (srcH * scale).toInt()
                val left = (pageWidthPx - w) / 2
                val top = (pageHeightPx - h) / 2
                Rect(left, top, left + w, top + h)
            }
        }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(source, null, dstRect, paint)

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
