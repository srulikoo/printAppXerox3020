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
     * Composes [source] onto a white page of exactly [pageWidthPx] x
     * [pageHeightPx] using [scaleMode] and [orientation]. This is the exact
     * layout logic used for both the on-screen preview and the actual print
     * job, so what you see in the preview matches what gets printed.
     *
     * For landscape, the image is composed into a swapped-dimension canvas
     * and then the whole page is rotated 90 degrees to fit the physical
     * (always-portrait) page size -- the printer itself is never told about
     * orientation, only the content is rotated.
     */
    fun composePage(
        source: Bitmap,
        pageWidthPx: Int,
        pageHeightPx: Int,
        scaleMode: ScaleMode,
        orientation: Orientation
    ): Bitmap {
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

        if (orientation == Orientation.LANDSCAPE) {
            val page = Bitmap.createBitmap(pageWidthPx, pageHeightPx, Bitmap.Config.ARGB_8888)
            val rotateCanvas = Canvas(page)
            rotateCanvas.drawColor(Color.WHITE)
            val matrix = Matrix()
            matrix.postRotate(90f)
            matrix.postTranslate(pageWidthPx.toFloat(), 0f)
            rotateCanvas.drawBitmap(composed, matrix, paint)
            composed.recycle()
            return page
        }
        return composed
    }

    /**
     * Applies grayscale + threshold to [page], returning a new black/white
     * ARGB bitmap of the same size -- useful for an accurate on-screen
     * preview of what will actually print.
     */
    fun applyThresholdPreview(page: Bitmap, threshold: Int): Bitmap {
        val w = page.width
        val h = page.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            page.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                row[x] = if (gray < threshold) Color.BLACK else Color.WHITE
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }

    /**
     * Converts an already-composed, page-sized ARGB bitmap into a 1bpp
     * row-major MSB-first packed bitmap (1 = black ink, 0 = white paper),
     * applying [threshold] as it packs.
     */
    fun packBitmap(page: Bitmap, threshold: Int): ByteArray {
        val pageWidthPx = page.width
        val pageHeightPx = page.height
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
        return packed
    }

    /**
     * Convenience one-shot used by the print pipeline: compose + threshold +
     * pack in a single call.
     */
    fun renderToPackedBitmap(
        source: Bitmap,
        pageWidthPx: Int,
        pageHeightPx: Int,
        scaleMode: ScaleMode,
        threshold: Int = 150,
        orientation: Orientation = Orientation.PORTRAIT
    ): ByteArray {
        val page = composePage(source, pageWidthPx, pageHeightPx, scaleMode, orientation)
        val packed = packBitmap(page, threshold)
        page.recycle()
        return packed
    }
}
