package com.local.splprint

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect

enum class ScaleMode { FIT, FILL, STRETCH }
enum class Orientation { PORTRAIT, LANDSCAPE }
enum class RenderMode { THRESHOLD, DITHERED }

object ImageProcessor {

    /**
     * Composes [source] into its "natural" orientation canvas: for portrait,
     * a pageWidthPx x pageHeightPx canvas; for landscape, a *wide* canvas
     * (pageHeightPx x pageWidthPx) with the content sitting upright and
     * correctly oriented -- exactly how you'd expect a landscape image to
     * look on screen. This is what the on-screen preview should show
     * directly; it is NOT yet rotated to fit the printer's fixed (portrait)
     * paper feed. For that, see [rotateForPrinterPage].
     */
    fun composeContent(
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
        return composed
    }

    /**
     * Takes a "natural orientation" bitmap from [composeContent] and, only
     * for landscape, rotates it 90 degrees to fit the printer's physical
     * (always-portrait) page. Portrait input is returned unchanged. This
     * step is for the actual print job only -- never applied to the preview.
     */
    fun rotateForPrinterPage(
        natural: Bitmap,
        pageWidthPx: Int,
        pageHeightPx: Int,
        orientation: Orientation
    ): Bitmap {
        if (orientation != Orientation.LANDSCAPE) return natural

        val page = Bitmap.createBitmap(pageWidthPx, pageHeightPx, Bitmap.Config.ARGB_8888)
        val rotateCanvas = Canvas(page)
        rotateCanvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()
        matrix.postRotate(-90f)
        matrix.postTranslate(0f, pageHeightPx.toFloat())
        rotateCanvas.drawBitmap(natural, matrix, paint)
        return page
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
     * Full print pipeline: compose in natural orientation, rotate to fit the
     * printer's physical page if needed, threshold, and pack. Used only for
     * the actual print job -- the preview uses composeContent() directly
     * (see MainActivity.updatePreview) so it never shows the physical-page
     * rotation, only the correctly-oriented content.
     */
    /**
     * Automatically computes a good black/white threshold (0-255) for
     * [bitmap] using Otsu's method: it analyzes the image's own brightness
     * histogram and picks the cutoff that best separates "ink" from
     * "background" for that specific image, rather than using one fixed
     * value for every document. Works on a downscaled copy for speed --
     * exact accuracy at full resolution isn't needed for a threshold
     * suggestion.
     */
    fun computeAutoThreshold(bitmap: Bitmap): Int {
        // Downscale for speed; histogram shape is essentially unaffected.
        val maxDim = 400
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sample = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        } else bitmap

        val w = sample.width
        val h = sample.height
        val histogram = IntArray(256)
        val row = IntArray(w)
        for (y in 0 until h) {
            sample.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                histogram[gray]++
            }
        }
        if (sample !== bitmap) sample.recycle()

        val total = w * h
        var sumAll = 0.0
        for (i in 0 until 256) sumAll += i.toDouble() * histogram[i]

        var sumB = 0.0
        var weightBackground = 0
        var maxVariance = 0.0
        var bestThreshold = 150 // sane fallback if the image is degenerate (e.g. blank)

        for (i in 0 until 256) {
            weightBackground += histogram[i]
            if (weightBackground == 0) continue
            val weightForeground = total - weightBackground
            if (weightForeground == 0) break

            sumB += i.toDouble() * histogram[i]
            val meanBackground = sumB / weightBackground
            val meanForeground = (sumAll - sumB) / weightForeground
            val diff = meanBackground - meanForeground
            val betweenVariance = weightBackground.toDouble() * weightForeground.toDouble() * diff * diff

            if (betweenVariance > maxVariance) {
                maxVariance = betweenVariance
                bestThreshold = i
            }
        }
        return bestThreshold
    }

    /**
     * Suggests THRESHOLD or DITHERED for a given source image: THRESHOLD
     * for content that's already mostly black-or-white (line art, coloring
     * pages), DITHERED for content with substantial midtone gray (scanned
     * forms, photos, shaded fills) where a hard cutoff would look blotchy.
     * Based on what fraction of pixels fall in the broad midtone band.
     */
    fun suggestRenderMode(bitmap: Bitmap): RenderMode {
        val maxDim = 300
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        val sample = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
        } else bitmap

        val w = sample.width
        val h = sample.height
        val row = IntArray(w)
        var midtoneCount = 0
        val total = w * h

        for (y in 0 until h) {
            sample.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val gray = (r * 299 + g * 587 + b * 114) / 1000
                if (gray in 60..200) midtoneCount++
            }
        }
        if (sample !== bitmap) sample.recycle()

        val midtoneFraction = midtoneCount.toDouble() / total
        // More than ~8% of pixels sitting in the midtone band suggests real
        // grayscale content (shading, photos) rather than pure line art.
        return if (midtoneFraction > 0.08) RenderMode.DITHERED else RenderMode.THRESHOLD
    }

    /**
     * Floyd-Steinberg error-diffusion dithering: represents grayscale
     * content on black/white-only hardware by spreading each pixel's
     * quantization error into its neighbors, so broad gray areas come out
     * as a pattern of black/white dots whose density reads as gray, instead
     * of a hard cutoff producing blotchy noise. Uses a rolling two-row
     * buffer rather than a full-page float buffer to keep memory bounded on
     * large pages.
     */
    fun ditherToPackedBitmap(page: Bitmap, threshold: Int): ByteArray {
        val w = page.width
        val h = page.height
        val lineBytes = (w + 7) / 8
        val packed = ByteArray(lineBytes * h)
        val rowPixels = IntArray(w)

        fun toGray(p: Int): Float {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return ((r * 299 + g * 587 + b * 114) / 1000).toFloat()
        }

        var currentRow = FloatArray(w)
        var nextRow = FloatArray(w)

        page.getPixels(rowPixels, 0, w, 0, 0, w, 1)
        for (x in 0 until w) currentRow[x] = toGray(rowPixels[x])

        for (y in 0 until h) {
            if (y + 1 < h) {
                page.getPixels(rowPixels, 0, w, 0, y + 1, w, 1)
                for (x in 0 until w) nextRow[x] = toGray(rowPixels[x])
            }

            var byteIndex = y * lineBytes
            var bitMask = 0x80
            var currentByte = 0

            for (x in 0 until w) {
                val oldVal = currentRow[x].coerceIn(0f, 255f)
                val isBlack = oldVal < threshold
                val newVal = if (isBlack) 0f else 255f
                if (isBlack) currentByte = currentByte or bitMask

                val err = oldVal - newVal
                if (x + 1 < w) currentRow[x + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x - 1 >= 0) nextRow[x - 1] += err * 3f / 16f
                    nextRow[x] += err * 5f / 16f
                    if (x + 1 < w) nextRow[x + 1] += err * 1f / 16f
                }

                bitMask = bitMask ushr 1
                if (bitMask == 0) {
                    packed[byteIndex] = currentByte.toByte()
                    byteIndex++
                    bitMask = 0x80
                    currentByte = 0
                }
            }
            if (bitMask != 0x80) packed[byteIndex] = currentByte.toByte()

            val tmp = currentRow
            currentRow = nextRow
            nextRow = tmp
        }
        return packed
    }

    /** Same Floyd-Steinberg algorithm as [ditherToPackedBitmap], but returns
     *  a black/white ARGB Bitmap for on-screen preview instead of packed
     *  bytes. */
    fun applyDitheredPreview(page: Bitmap, threshold: Int): Bitmap {
        val w = page.width
        val h = page.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rowPixels = IntArray(w)
        val outRow = IntArray(w)

        fun toGray(p: Int): Float {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return ((r * 299 + g * 587 + b * 114) / 1000).toFloat()
        }

        var currentRow = FloatArray(w)
        var nextRow = FloatArray(w)

        page.getPixels(rowPixels, 0, w, 0, 0, w, 1)
        for (x in 0 until w) currentRow[x] = toGray(rowPixels[x])

        for (y in 0 until h) {
            if (y + 1 < h) {
                page.getPixels(rowPixels, 0, w, 0, y + 1, w, 1)
                for (x in 0 until w) nextRow[x] = toGray(rowPixels[x])
            }

            for (x in 0 until w) {
                val oldVal = currentRow[x].coerceIn(0f, 255f)
                val isBlack = oldVal < threshold
                val newVal = if (isBlack) 0f else 255f
                outRow[x] = if (isBlack) Color.BLACK else Color.WHITE

                val err = oldVal - newVal
                if (x + 1 < w) currentRow[x + 1] += err * 7f / 16f
                if (y + 1 < h) {
                    if (x - 1 >= 0) nextRow[x - 1] += err * 3f / 16f
                    nextRow[x] += err * 5f / 16f
                    if (x + 1 < w) nextRow[x + 1] += err * 1f / 16f
                }
            }
            out.setPixels(outRow, 0, w, 0, y, w, 1)

            val tmp = currentRow
            currentRow = nextRow
            nextRow = tmp
        }
        return out
    }

    /**
     * Full print pipeline: compose in natural orientation, rotate to fit the
     * printer's physical page if needed, threshold/dither, and pack. Used
     * only for the actual print job -- the preview uses composeContent()
     * directly (see MainActivity.updatePreview) so it never shows the
     * physical-page rotation, only the correctly-oriented content.
     */
    fun renderToPackedBitmap(
        source: Bitmap,
        pageWidthPx: Int,
        pageHeightPx: Int,
        scaleMode: ScaleMode,
        threshold: Int = 150,
        orientation: Orientation = Orientation.PORTRAIT,
        renderMode: RenderMode = RenderMode.THRESHOLD
    ): ByteArray {
        val natural = composeContent(source, pageWidthPx, pageHeightPx, scaleMode, orientation)
        val page = rotateForPrinterPage(natural, pageWidthPx, pageHeightPx, orientation)
        if (page !== natural) natural.recycle()
        val packed = if (renderMode == RenderMode.DITHERED)
            ditherToPackedBitmap(page, threshold)
        else
            packBitmap(page, threshold)
        page.recycle()
        return packed
    }
}
