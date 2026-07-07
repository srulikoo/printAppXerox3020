# SPL Print — Xerox Phaser 3020 direct print app

Minimal, ad-free Android app that prints images and PDFs (e.g. coloring
pages) from your phone directly to a Xerox Phaser 3020 (or other Samsung
SPL2/QPDL host-based mono laser printer) over your local Wi-Fi, bypassing
Android's print spooler entirely.

## Why this exists

The Xerox Phaser 3020 is a budget, host-based ("GDI") laser printer: it has
no onboard rendering engine of its own. It relies entirely on the connected
computer to do all the page rasterization and speaks only its own
proprietary SPL/QPDL protocol — not PDF, not standard raster, and (contrary
to what its network settings page suggests) not a working IPP/AirPrint
pipeline either. Xerox's own official Android app (Xerox Easy Assist) does
not offer a print function for this model at all, and Android's built-in
Mopria/print-service framework fails outright ("Printer blocked") because it
only knows how to send standard formats the printer's engine can't
interpret.

The realistic alternatives were: install a full desktop print server (CUPS +
the community SpliX driver) and always have a computer on, or use a
third-party Android app with a generic SPL driver — the ones that exist work,
but come bundled with aggressive advertising.

This app exists to close that gap directly: a small, ad-free, phone-only tool
that talks to the printer's real protocol itself, so printing from a phone to
this specific class of budget printer doesn't require a PC, a subscription,
or tolerating ads to print a coloring page.

## How it works

1. You pick one or more images/PDFs from your phone and enter the printer's
   IP address (or use the built-in network scanner to find it).
2. Each page is converted to grayscale, thresholded to pure black/white, and
   rasterized to the exact pixel dimensions the printer's engine expects (A4
   @ 600dpi, matching the Samsung M2020-series "SpecialBandWidth"
   requirement).
3. A native (C++/JNI) encoder — ported from the open-source
   [OpenPrinting/SpliX](https://github.com/OpenPrinting/splix) CUPS driver —
   compresses the bitmap using the printer's real QPDL algorithm 0x11 and
   wraps it in the correct PJL/QPDL binary job format.
4. The app opens a raw TCP socket to the printer on port 9100 and streams the
   job directly. No CUPS, no print spooler, no ads.

## Features

- Print images (gallery/files) and PDFs (each page printed in sequence)
- Multi-document print queue: queue several files, watch them print in
  order, remove items before printing
- "Recently sent" history (last 5 documents)
- Live preview reflecting scale mode, orientation, and black-threshold
  choices exactly as they'll print
- Fit / Fill / Stretch scaling, Portrait / Landscape orientation
- Adjustable black/white threshold with live preview
- One-tap printer connection check
- Local network scanner to find the printer's IP automatically

## Building

You don't need Android Studio or a local SDK. Every push to `main` triggers
`.github/workflows/build.yml`, which builds a debug APK in the cloud. Check
the **Actions** tab after pushing, then download the APK from the run's
**Artifacts** section once it finishes.

To install: transfer the APK to your phone and open it (you'll need to allow
"install unknown apps" once for whichever app you use to open the file).

### Creating a versioned release

Ordinary pushes to `main` never create a release — only pushing a version
tag (e.g. `v1.0`) does, via `.github/workflows/release.yml`. To cut a
release: test a build via the Actions artifact as usual, then once you're
happy, go to **Releases → Create a new release**, type a new tag name, and
publish. That tag push triggers a fresh build and attaches the APK to the
release automatically.

## Status / known limitations

- Paper size is fixed to A4.
- Verified working on the physical printer at standard 600×600dpi. The
  printer's firmware explicitly rejects 1200×600 ("high resolution") mode
  with an `IllegalResolution` error, so that option has been removed.
- Landscape printing rotates the content to fit the printer's fixed
  (portrait-only) paper feed — this is a hardware limitation shared by any
  single-feed printer, not specific to this app.
- The printer has no way to report per-page job status back to the app, so
  the print queue can show "sending document 2 of 4" but not live
  page-by-page progress once a job has been handed to the printer.
