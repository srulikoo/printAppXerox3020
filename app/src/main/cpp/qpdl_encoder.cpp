/*
 * qpdl_encoder.cpp
 *
 * A standalone Android/JNI port of the QPDL (Quick Page Description Language)
 * job encoder used by Samsung/Xerox "SPL2" host-based mono laser printers,
 * including the Xerox Phaser 3020 (Samsung M2020-series engine).
 *
 * The compression algorithm (0x11) and the page/band/PJL wire-format layout
 * below are ported line-for-line from the real, verified, open-source
 * OpenPrinting/SpliX CUPS driver (GPLv2), specifically:
 *   - src/algo0x11.cpp / include/algo0x11.h   (compression algorithm 0x11)
 *   - src/qpdl.cpp                            (page/band binary record layout)
 *   - src/printer.cpp                         (PJL text wrapper, paper table)
 *   - src/compress.cpp                        (band splitting, M2020/M2026
 *                                               "SpecialBandWidth" patch,
 *                                               reverseLineColumn/inverseByte
 *                                               transform required by algo 0x11)
 *   - src/bandplane.cpp                       (checksum = sum of output bytes)
 *
 * We do NOT go through CUPS at all -- this file takes a raw 1bpp bitmap
 * (row-major, MSB-first, 1 = black ink, 0 = white paper) produced by the
 * Android app and returns a complete, ready-to-send byte stream: PJL header,
 * binary QPDL page header, one or more compressed bands, page footer, PJL
 * footer. The Kotlin side just opens a TCP socket to port 9100 and writes it.
 *
 * Only what this app needs is implemented: monochrome, single copy or more,
 * simplex, compression algorithm 0x11, resolution 600dpi, paper type A4.
 */

#include <jni.h>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <string>
#include <ctime>
#include <algorithm>

// ---------------------------------------------------------------------------
// Algorithm 0x11 constants (verbatim from SpliX include/algo0x11.h)
// ---------------------------------------------------------------------------
#define COMPRESS_SAMPLE_RATE    0x800
#define TABLE_PTR_SIZE          0x40
#define MAX_UNCOMPRESSED_BYTES  0x80
#define MAX_COMPRESSED_BYTES    0x202
#define MIN_COMPRESSED_BYTES    0x2
#define COMPRESSION_FLAG        0x80

// ---------------------------------------------------------------------------
// Samsung M2020/M2026 "SpecialBandWidth" table, verbatim from SpliX
// src/compress.cpp _get2020BandWidthInB(). Width in BYTES, standard (600dpi)
// column only (we don't support 1200dpi high-res mode).
// Index = SpliX paper-type numeric code (see paperTypeCode() below).
// ---------------------------------------------------------------------------
struct BandWidthEntry { unsigned char paperType; unsigned long widthBytesStd; unsigned long widthBytesHigh; };
static const BandWidthEntry k2020BandWidth[] = {
    {0, 640, 1248},   // Letter
    {1, 640, 1248},   // Legal
    {2, 608, 1216},   // A4
    {3, 544, 1056},   // Executive
    {4, 800, 1600},   // Ledger
    {5, 864, 1728},   // A3
    {6, 288, 576},    // Env10
    {7, 288, 544},    // Monarch
    {8, 480, 928},    // C5
    {9, 320, 608},    // DL
    {10, 736, 1472},  // B4
    {11, 544, 1056},  // B5
    {12, 512, 992},   // EnvISOB5
    {14, 288, 576},   // Postcard
    {16, 416, 832},   // A5
    {17, 288, 576},   // A6
    {18, 384, 736},   // B6
    {23, 320, 640},   // C6
    {24, 640, 1248},  // Folio
    {25, 256, 512},   // EnvPersonal
    {26, 288, 544},   // Env9
    {27, 224, 416},   // IndexCard/3x5
    {28, 640, 1248},  // Oficio
    {30, 416, 800},   // Statement
    {34, 800, 1568},  // 8K
    {35, 576, 1120},  // 16K
};

// SpliX printer.cpp paper-type numeric codes (subset we support / may extend).
static int paperTypeCode(const std::string& name) {
    if (name == "Letter") return 0;
    if (name == "Legal") return 1;
    if (name == "A4") return 2;
    if (name == "Executive") return 3;
    if (name == "Ledger") return 4;
    if (name == "A3") return 5;
    if (name == "A5") return 16;
    if (name == "A6") return 17;
    return 2; // default A4
}

static unsigned long bandWidthBytesFor(int paperCode, bool highRes) {
    for (auto &e : k2020BandWidth)
        if (e.paperType == paperCode) return highRes ? e.widthBytesHigh : e.widthBytesStd;
    return highRes ? 1216 : 608; // fall back to A4
}

// Physical page size in points (1/72 inch), same table SpliX PPDs use.
static void pageSizePts(const std::string& name, double &wPts, double &hPts) {
    if (name == "A4") { wPts = 595; hPts = 842; return; }
    if (name == "Letter") { wPts = 612; hPts = 792; return; }
    if (name == "Legal") { wPts = 612; hPts = 1008; return; }
    wPts = 595; hPts = 842; // default A4
}

// ---------------------------------------------------------------------------
// Algorithm 0x11 compressor -- ported verbatim from SpliX src/algo0x11.cpp
// (Algo0x11::_lookupBestOccurs + Algo0x11::_compress), stripped of the
// BandPlane/Request/Algorithm class wrappers since we don't need them here.
// ---------------------------------------------------------------------------
namespace algo0x11 {

static int compareOccurs(const void* n1, const void* n2) {
    // n2 and n1 exchanged since the first element must be the biggest.
    return *(const uint32_t*)n2 - *(const uint32_t*)n1;
}

static void lookupBestOccurs(const unsigned char* data, unsigned long size,
                              uint32_t ptrArray[TABLE_PTR_SIZE]) {
    uint32_t occurs[COMPRESS_SAMPLE_RATE][2];
    bool oneIsPresent = false;
    unsigned char b;
    unsigned long i;

    for (i = 0; i < COMPRESS_SAMPLE_RATE; i++) {
        occurs[i][0] = 0;
        occurs[i][1] = i;
    }
    for (i = COMPRESS_SAMPLE_RATE; i < size; i += COMPRESS_SAMPLE_RATE) {
        b = data[i];
        for (unsigned long j = 1; j < COMPRESS_SAMPLE_RATE; j++)
            if (data[i - j] == b)
                occurs[(j - 1)][0]++;
    }
    qsort(occurs, COMPRESS_SAMPLE_RATE, sizeof(uint32_t) * 2, compareOccurs);

    for (i = 0; i < TABLE_PTR_SIZE; i++) {
        ptrArray[i] = occurs[i][1] + 1;
        if (ptrArray[i] == 1) oneIsPresent = true;
    }
    if (!oneIsPresent) ptrArray[TABLE_PTR_SIZE - 1] = 1;
}

// Returns compressed bytes; caller owns the returned buffer (new[]).
static unsigned char* compress(const unsigned char* data, unsigned long size,
                                unsigned long& outputSize) {
    uint32_t ptrArray[TABLE_PTR_SIZE];
    lookupBestOccurs(data, size, ptrArray);

    unsigned long r, w = 4, uncompSize = 0, maxCompSize, bestCompCounter, bestPtr;
    unsigned long rawDataCounter = 0, rawDataCounterPtr = 0;
    unsigned long maxOutputSize = size;
    unsigned char* out = new unsigned char[maxOutputSize];

    for (unsigned long i = 0; i < TABLE_PTR_SIZE; i++, w += 2) {
        *(uint16_t*)(out + w) = (uint16_t)ptrArray[i];
        if (ptrArray[i] > uncompSize) uncompSize = ptrArray[i];
    }

    if (uncompSize > MAX_UNCOMPRESSED_BYTES) uncompSize = MAX_UNCOMPRESSED_BYTES;
    *(uint32_t*)out = (uint32_t)uncompSize;
    for (r = 0; r < uncompSize; r++, w++) out[w] = data[r];

    do {
        maxCompSize = size - r > MAX_COMPRESSED_BYTES ? MAX_COMPRESSED_BYTES : size - r;

        if (!maxCompSize) {
            if (rawDataCounter) out[rawDataCounterPtr] = (unsigned char)(rawDataCounter - 1);
            break;
        } else if (maxCompSize >= 2) {
            bestCompCounter = 0;
            bestPtr = 0;
            if (w + 2 >= maxOutputSize) { w += 2; break; }

            for (unsigned long i = 0; i < TABLE_PTR_SIZE; i++) {
                unsigned long rTmp, counter;
                if (ptrArray[i] > r) continue;
                rTmp = r - ptrArray[i];
                for (counter = 0; counter < maxCompSize; counter++)
                    if (data[r + counter] != data[rTmp + counter]) break;
                if (counter > bestCompCounter) { bestCompCounter = counter; bestPtr = i; }
            }

            if (bestCompCounter > MIN_COMPRESSED_BYTES) {
                r += bestCompCounter;
                bestCompCounter -= 3;
                out[w] = (unsigned char)(COMPRESSION_FLAG | (bestCompCounter & 0x7F));
                out[w + 1] = (unsigned char)(((bestCompCounter >> 1) & 0xC0) | (bestPtr & 0x3F));
                w += 2;
                if (rawDataCounter) {
                    out[rawDataCounterPtr] = (unsigned char)(rawDataCounter - 1);
                    rawDataCounter = 0;
                }
                continue;
            }
        }

        rawDataCounter++;
        if (rawDataCounter == 1) {
            if (w + 2 >= maxOutputSize) { w += 2; break; }
            rawDataCounterPtr = w;
            w++;
        } else if (rawDataCounter == MAX_UNCOMPRESSED_BYTES) {
            out[rawDataCounterPtr] = 0x7F;
            rawDataCounter = 0;
        }
        out[w] = data[r];
        w++;
        r++;
    } while (w < maxOutputSize);

    if (w >= maxOutputSize) {
        // Compression failed to fit (extremely unlikely for real page data).
        delete[] out;
        outputSize = 0;
        return nullptr;
    }

    outputSize = w;
    unsigned char* finalOut = new unsigned char[outputSize];
    memcpy(finalOut, out, outputSize);
    delete[] out;
    return finalOut;
}

} // namespace algo0x11

// ---------------------------------------------------------------------------
// Byte stream builder helper
// ---------------------------------------------------------------------------
struct ByteBuf {
    std::vector<unsigned char> data;
    void u8(unsigned v) { data.push_back((unsigned char)v); }
    void bytes(const unsigned char* p, size_t n) { data.insert(data.end(), p, p + n); }
    void str(const std::string& s) { data.insert(data.end(), s.begin(), s.end()); }
    void u16be(unsigned long v) { u8((v >> 8) & 0xFF); u8(v & 0xFF); }
    void u32be(unsigned long v) {
        u8((v >> 24) & 0xFF); u8((v >> 16) & 0xFF); u8((v >> 8) & 0xFF); u8(v & 0xFF);
    }
};

// ---------------------------------------------------------------------------
// Build one band: transpose to column-major byte order + invert bits
// (Algo0x11::reverseLineColumn()==true, inverseByte()==true, ported from
// src/compress.cpp), then compress with algorithm 0x11.
//
// srcRows: pointers to `bandHeightPx` consecutive source scanlines (each
// exactly bandWidthBytes long, row-major MSB-first, 1=black). For the final
// (possibly partial) band, pass fewer valid rows via validRows and the rest
// will be treated as blank (all-zero / all-white), matching SpliX behaviour.
// ---------------------------------------------------------------------------
static unsigned char* buildTransposedInvertedBand(
        const std::vector<const unsigned char*>& srcRows,
        unsigned long validRows,
        unsigned long bandWidthBytes,
        unsigned long bandHeightPx,
        unsigned long& bandSizeOut) {
    unsigned long bandSize = bandWidthBytes * bandHeightPx;
    unsigned char* band = new unsigned char[bandSize];
    memset(band, 0, bandSize); // blank rows / padding default to 0 before invert

    for (unsigned long y = 0; y < validRows; y++) {
        const unsigned char* row = srcRows[y];
        for (unsigned long x = 0; x < bandWidthBytes; x++) {
            band[x * bandHeightPx + y] = row[x];
        }
    }
    // inverseByte(): flip every byte
    for (unsigned long j = 0; j < bandSize; j++) band[j] = (unsigned char)~band[j];

    bandSizeOut = bandSize;
    return band;
}

// ---------------------------------------------------------------------------
// Full job builder
// ---------------------------------------------------------------------------
// bitmap: row-major, MSB-first packed 1bpp, 1 = black ink, 0 = white paper.
// widthPx/heightPx: dimensions of `bitmap` as rendered by the caller. The
// caller is expected to have already rendered the page at exactly
// (bandWidthBytes*8) x heightPx pixels for the chosen paper type, i.e. no
// margin trimming is done here -- the app renders straight into the
// M2020-family's exact expected band width.
static std::vector<unsigned char> buildQpdlJob(
        const unsigned char* bitmap, unsigned long widthPx, unsigned long heightPx,
        const std::string& paperName, unsigned long copies, const std::string& userName,
        const std::string& jobName, bool highRes) {

    const unsigned long yResolution = 600;               // vertical dpi (fixed)
    const unsigned long xResolution = highRes ? 1200 : 600; // horizontal dpi
    const unsigned long bandHeightPx = 128;   // fixed for 600dpi (SpliX QPDL BandSize)
    const int paperCode = paperTypeCode(paperName);
    const unsigned long bandWidthBytes = bandWidthBytesFor(paperCode, highRes);
    const unsigned long lineBytes = (widthPx + 7) / 8;
    // Sanity: caller should render to exactly bandWidthBytes*8 px wide.
    const unsigned long widthBytes = std::min(lineBytes, bandWidthBytes);

    unsigned long numBands = (heightPx + bandHeightPx - 1) / bandHeightPx;

    ByteBuf job;

    // ---- PJL header (verbatim text sequence from printer.cpp sendPJLHeader,
    //      for the compression != 0x15 branch, i.e. our algo 0x11 case) ----
    job.u8(0x1B); job.str("%-12345X");           // Universal Exit Language (BeginPJL)

    time_t now = time(nullptr);
    struct tm tmv{}; localtime_r(&now, &tmv);
    char dateBuf[16];
    snprintf(dateBuf, sizeof(dateBuf), "%04d%02d%02d",
              1900 + tmv.tm_year, tmv.tm_mon + 1, tmv.tm_mday);

    job.str("@PJL DEFAULT SERVICEDATE=" + std::string(dateBuf) + "\n");
    job.str("@PJL SET USERNAME=\"" + userName + "\"\n");
    job.str("@PJL SET JOBNAME=\"" + jobName + "\"\n");
    job.str("@PJL SET JAMRECOVERY=OFF\n");
    job.str("@PJL SET DUPLEX=OFF\n");
    job.str("@PJL SET PAPERTYPE=OFF\n");
    job.str("@PJL SET ALTITUDE=LOW\n");
    job.str("@PJL SET DENSITY=3\n");
    job.str("@PJL SET RET=NORMAL\n");
    job.str("@PJL ENTER LANGUAGE = QPDL\n");

    // ---- Binary QPDL page header (17 bytes, ported from qpdl.cpp renderPage) ----
    {
        unsigned char h[0x11];
        h[0x0] = 0;                          // page-start signature
        h[0x1] = (unsigned char)(yResolution / 100);  // Y resolution
        h[0x2] = (unsigned char)((copies >> 8) & 0xFF);
        h[0x3] = (unsigned char)(copies & 0xFF);
        h[0x4] = (unsigned char)paperCode;
        h[0x5] = (unsigned char)((widthPx >> 8) & 0xFF);
        h[0x6] = (unsigned char)(widthPx & 0xFF);
        h[0x7] = (unsigned char)((heightPx >> 8) & 0xFF);
        h[0x8] = (unsigned char)(heightPx & 0xFF);
        h[0x9] = 1;                          // paper source: Auto
        h[0xA] = 0;                          // unknownByte1 ("always 0")
        h[0xB] = 1;                          // duplex byte (SpliX: 1 for non-JBIG simplex)
        h[0xC] = 0;                          // tumble
        h[0xD] = 0;                          // unknownByte2 ("always 0")
        h[0xE] = 3;                          // QPDL version
        h[0xF] = 1;                          // unknownByte3 = colorplanes (1 = mono)
        h[0x10] = (unsigned char)(xResolution / 100); // X resolution
        job.bytes(h, sizeof(h));
    }

    // ---- Bands ----
    for (unsigned long b = 0; b < numBands; b++) {
        unsigned long rowStart = b * bandHeightPx;
        unsigned long validRows = std::min(bandHeightPx, heightPx - rowStart);

        std::vector<const unsigned char*> rows(validRows);
        for (unsigned long y = 0; y < validRows; y++)
            rows[y] = bitmap + (rowStart + y) * lineBytes;

        unsigned long bandSize = 0;
        unsigned char* transposed = buildTransposedInvertedBand(
                rows, validRows, widthBytes, bandHeightPx, bandSize);

        unsigned long compSize = 0;
        unsigned char* compressed = algo0x11::compress(transposed, bandSize, compSize);
        delete[] transposed;
        if (!compressed) continue; // skip unencodable (shouldn't happen)

        // checksum = sum of compressed bytes (BandPlane::setData)
        unsigned long checksum = 0;
        for (unsigned long i = 0; i < compSize; i++) checksum += compressed[i];

        unsigned long dataSize = compSize + 4 /*sig*/ + 4 /*checksum*/;

        // 11-byte band+plane header (mono => no color byte; version=3, subVersion=0)
        unsigned char bh[11];
        bh[0] = 0xC;
        bh[1] = (unsigned char)(b & 0xFF);
        bh[2] = (unsigned char)((widthPx >> 8) & 0xFF);
        bh[3] = (unsigned char)(widthPx & 0xFF);
        bh[4] = (unsigned char)((bandHeightPx >> 8) & 0xFF);
        bh[5] = (unsigned char)(bandHeightPx & 0xFF);
        bh[6] = 0x11;                        // compression algorithm
        bh[7] = (unsigned char)((dataSize >> 24) & 0xFF);
        bh[8] = (unsigned char)((dataSize >> 16) & 0xFF);
        bh[9] = (unsigned char)((dataSize >> 8) & 0xFF);
        bh[10] = (unsigned char)(dataSize & 0xFF);
        job.bytes(bh, sizeof(bh));

        // 4-byte magic sub-header (little-endian machine layout, subVersion=0)
        unsigned char sub[4] = {0xEF, 0xCD, 0xAB, 0x09};
        job.bytes(sub, 4);
        checksum += 0xEF + 0xCD + 0xAB + 0x09;

        // compressed data
        job.bytes(compressed, compSize);
        delete[] compressed;

        // 4-byte checksum footer (big-endian)
        job.u32be(checksum);
    }

    // ---- Page footer (3 bytes) ----
    job.u8(1);
    job.u16be(copies);

    // ---- PJL footer ----
    job.u8(0x1B); job.str("%-12345X");

    return job.data;
}

// ---------------------------------------------------------------------------
// JNI entry point
// ---------------------------------------------------------------------------
extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_local_splprint_QpdlEncoder_encode(
        JNIEnv* env, jclass /*clazz*/,
        jbyteArray bitmap_, jint widthPx, jint heightPx,
        jstring paperName_, jint copies, jstring userName_, jstring jobName_,
        jboolean highRes) {

    jbyte* bitmapPtr = env->GetByteArrayElements(bitmap_, nullptr);
    const char* paperNameC = env->GetStringUTFChars(paperName_, nullptr);
    const char* userNameC = env->GetStringUTFChars(userName_, nullptr);
    const char* jobNameC = env->GetStringUTFChars(jobName_, nullptr);

    std::vector<unsigned char> job = buildQpdlJob(
            reinterpret_cast<const unsigned char*>(bitmapPtr),
            (unsigned long)widthPx, (unsigned long)heightPx,
            std::string(paperNameC), (unsigned long)copies,
            std::string(userNameC), std::string(jobNameC), highRes == JNI_TRUE);

    env->ReleaseByteArrayElements(bitmap_, bitmapPtr, JNI_ABORT);
    env->ReleaseStringUTFChars(paperName_, paperNameC);
    env->ReleaseStringUTFChars(userName_, userNameC);
    env->ReleaseStringUTFChars(jobName_, jobNameC);

    jbyteArray result = env->NewByteArray((jsize)job.size());
    env->SetByteArrayRegion(result, 0, (jsize)job.size(),
                             reinterpret_cast<const jbyte*>(job.data()));
    return result;
}

// Exposes the exact pixel width the caller must render to for a given paper
// type, so the Kotlin side can size its canvas to match the M2020 band-width
// table exactly (avoids any cropping/margin mismatch).
extern "C"
JNIEXPORT jint JNICALL
Java_com_local_splprint_QpdlEncoder_requiredWidthPx(
        JNIEnv* env, jclass /*clazz*/, jstring paperName_, jboolean highRes) {
    const char* paperNameC = env->GetStringUTFChars(paperName_, nullptr);
    int code = paperTypeCode(std::string(paperNameC));
    env->ReleaseStringUTFChars(paperName_, paperNameC);
    return (jint)(bandWidthBytesFor(code, highRes == JNI_TRUE) * 8);
}

// Exposes the recommended pixel height for a given paper type. Height is
// always at 600dpi (Y resolution is fixed regardless of highRes, which only
// doubles X/horizontal density) so it does not depend on highRes.
extern "C"
JNIEXPORT jint JNICALL
Java_com_local_splprint_QpdlEncoder_requiredHeightPx(
        JNIEnv* env, jclass /*clazz*/, jstring paperName_) {
    const char* paperNameC = env->GetStringUTFChars(paperName_, nullptr);
    double wPts, hPts;
    pageSizePts(std::string(paperNameC), wPts, hPts);
    env->ReleaseStringUTFChars(paperName_, paperNameC);
    double inches = hPts / 72.0;
    return (jint)(inches * 600.0);
}
