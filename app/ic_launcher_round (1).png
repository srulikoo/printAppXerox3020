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
     * Renders every page of the PDF at [uri] into a plain ARGB Bitmap, one
     * per page, each sized proportionally to that page's own aspect ratio
     * (not the printer's page size) so the standard image pipeline
     * (ImageProcessor.composePage etc.) can lay each one out exactly like
     * any picked photo -- no special-casing needed downstream.
     */
    fun renderAllPages(context: Context, uri: Uri): List<Bitmap> {
        val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return emptyList()

        val pages = mutableListOf<Bitmap>()
        PdfRenderer(pfd).use { renderer ->
            for (i in 0 until renderer.pageCount) {
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
        pfd.close()
        return pages
    }
}
