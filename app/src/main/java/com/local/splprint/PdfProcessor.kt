package com.local.splprint

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor

object PdfProcessor {

    // Longer-side render resolution. Generous enough for print quality at
    // this printer's real max (600dpi standard), modest enough to keep
    // memory/time reasonable across multi-page documents.
    private const val MAX_RENDER_DIMENSION = 3000

    /**
     * Returns the number of pages in the PDF at [uri], or -1 if it can't be opened.
     */
    fun getPageCount(context: Context, uri: Uri): Int {
        val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return -1
        return pfd.use { PdfRenderer(it).use { renderer -> renderer.pageCount } }
    }

    /**
     * Renders the given 0-based [pageIndices] of the PDF at [uri] into plain
     * ARGB Bitmaps, each sized proportionally to that page's own aspect ratio
     * (not the printer's page size) so the standard image pipeline
     * (ImageProcessor.composePage etc.) can lay each one out exactly like
     * any picked photo -- no special-casing needed downstream.
     */
    fun renderPages(context: Context, uri: Uri, pageIndices: List<Int>): List<Bitmap> {
        val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()

        val pages = mutableListOf<Bitmap>()
        pfd.use {
            PdfRenderer(it).use { renderer ->
                for (i in pageIndices) {
                    if (i < 0 || i >= renderer.pageCount) continue
                    renderer.openPage(i).use { page ->
                        val pageWidthPt = page.width.toFloat()
                        val pageHeightPt = page.height.toFloat()
                        val scale = MAX_RENDER_DIMENSION / maxOf(pageWidthPt, pageHeightPt)
                        val outW = (pageWidthPt * scale).toInt().coerceAtLeast(1)
                        val outH = (pageHeightPt * scale).toInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)

                        val matrix = Matrix()
                        matrix.setScale(scale, scale)
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        pages.add(bitmap)
                    }
                }
            }
        }
        return pages
    }
}
