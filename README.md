# SPL Print — Xerox Phaser 3020 direct print app

Minimal, ad-free Android app that prints images (e.g. coloring pages) from
your phone's gallery directly to a Xerox Phaser 3020 (or other Samsung
SPL2/QPDL host-based mono laser printer) over your local Wi-Fi, bypassing
Android's print spooler entirely.

## How it works

1. You pick an image from your gallery and enter the printer's IP address.
2. The app converts the image to grayscale, thresholds it to pure black/white,
   and rasterizes it to the exact pixel dimensions the printer's engine
   expects (A4 @ 600dpi, matching the Samsung M2020-series "SpecialBandWidth"
   requirement).
3. A native (C++/JNI) encoder — ported from the open-source
   [OpenPrinting/SpliX](https://github.com/OpenPrinting/splix) CUPS driver —
   compresses the bitmap using the printer's real QPDL algorithm 0x11 and
   wraps it in the correct PJL/QPDL binary job format.
4. The app opens a raw TCP socket to the printer on port 9100 and streams the
   job directly. No CUPS, no print spooler, no ads.

## Building

You don't need Android Studio or a local SDK. Every push to `main` triggers
`.github/workflows/build.yml`, which builds a debug APK in the cloud. Check
the **Actions** tab after pushing, then download the APK from the run's
**Artifacts** section once it finishes.

To install: transfer the APK to your phone and open it (you'll need to allow
"install unknown apps" once for whichever app you use to open the file).

## Status / known limitations

- Paper size is fixed to A4 in this version.
- The QPDL wire format was reconstructed from the real SpliX driver source
  and validated with synthetic test bitmaps (all-white, all-black, sparse,
  multi-copy) in a standalone test harness — but has **not yet been verified
  against the physical printer**. If the first print doesn't come out
  correctly, useful things to report back: any error code on the printer's
  display, whether it prints garbage vs. nothing at all, and how many pages
  (if any) it attempted.
