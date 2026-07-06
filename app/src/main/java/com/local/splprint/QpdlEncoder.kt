package com.local.splprint

/**
 * Thin JNI binding to the native QPDL/SPL job encoder (qpdl_encoder.cpp),
 * ported from the verified open-source OpenPrinting/SpliX driver.
 */
object QpdlEncoder {
    init {
        System.loadLibrary("qpdlencoder")
    }

    /**
     * Encode a 1bpp bitmap (row-major, MSB-first packed, 1 = black ink,
     * 0 = white paper) into a complete QPDL job byte stream ready to be
     * written directly to the printer's raw socket (port 9100).
     *
     * @param bitmap packed 1bpp bitmap data
     * @param widthPx width of the bitmap in pixels (must match [requiredWidthPx])
     * @param heightPx height of the bitmap in pixels
     * @param paperName one of "A4", "Letter", "Legal", "Executive", "Ledger", "A3", "A5", "A6"
     * @param copies number of copies
     * @param userName job owner name shown in printer job info (cosmetic)
     * @param jobName job title shown in printer job info (cosmetic)
     */
    external fun encode(
        bitmap: ByteArray,
        widthPx: Int,
        heightPx: Int,
        paperName: String,
        copies: Int,
        userName: String,
        jobName: String,
        highRes: Boolean
    ): ByteArray

    /**
     * The exact pixel width the printer's engine expects for this paper type.
     * @param highRes false = 600x600dpi (verified working baseline),
     *                true = 1200x600dpi (sharper horizontal detail, larger job).
     */
    external fun requiredWidthPx(paperName: String, highRes: Boolean): Int

    /** The recommended pixel height for this paper type at 600dpi. */
    external fun requiredHeightPx(paperName: String): Int
}
