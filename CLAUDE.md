# SwidScanner

Open-source Android document scanner (CamScanner replacement). Kotlin, Jetpack Compose, CameraX, OpenCV 4.12, PdfBox-Android, ZXing, ML Kit text recognition. MIT licensed. See README.md for features and the Google Drive setup.

## Build and run (Windows)

- Debug build: `gradlew.bat assembleDebug`
- Release build: `gradlew.bat assembleRelease` (signed with swidscanner-release.jks via keystore.properties; never commit either file and never regenerate the key)
- Install on the USB-connected phone: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
  - The debug build has a different signature from the installed release. Uninstall the release first (`adb uninstall io.github.swikrutd.swidscanner`) or Android refuses the install.
- Logs: `adb logcat --pid=(adb shell pidof io.github.swikrutd.swidscanner)` or filter on the tag you add
- Pull app files from a debug build: `adb exec-out run-as io.github.swikrutd.swidscanner cat files/<path> > local-file`

## Layout

- `core/DocProcessing.kt`: page detection, perspective warp and filters. Pure OpenCV with no Android imports, so it runs on the desktop JVM.
- `ui/CameraScreen.kt`: CameraX preview, ImageAnalysis frames into `detectFrame()`, overlay drawing, auto-capture state machine in `onFrame()`.
- `imaging/Images.kt`: bitmap decode, EXIF, `detectCorners()` on full captures, `renderPage()`.
- `data/DocStore.kt`: documents as JSON + JPEG per page in filesDir/docs.
- `tools/detect-harness/`: desktop JVM harness that runs DocProcessing on a folder of images and writes overlays.
  `cd tools\detect-harness; .\get-samples.ps1; ..\..\gradlew.bat -p . run --args="samples out"`

## Current bug: live page outline is chaotic on a real phone

Reported on a real Android phone in both Auto and Manual mode: the outline jumps around and does not reliably lock onto the page. Still photos were tuned and pass (6/6 detectable test photos), so the problem is specific to the live preview path. Likely causes, most likely first:

1. No temporal stability. `detectFrame()` runs an independent detection on every 640x480 frame and `onFrame()` displays it raw. When two candidates score similarly (page vs table edge vs page plus shadow) the overlay flips between them every frame.
2. Candidate scoring is too permissive for live frames. Edge support uses a morphological gradient thresholded at 14, which lights up almost everywhere on textured surfaces (wood grain, carpet, sensor noise), so wrong quads still score above the 0.5 support cutoff. Canny is fixed at 20/60, also low for noisy preview frames.
3. Live detection uses only the luminance plane, so the saturation pass that separated white paper from wood in the still tests never runs live.
4. Possible overlay mapping error. The overlay assumes the preview is a 4:3 stream shown FIT_CENTER inside a 3:4 box, and that the analysis frame has the same field of view. If the phone gives Preview a non-4:3 stream, or ImageAnalysis has a crop rect, the outline is drawn in the wrong place even when detection is right. Verify this first, it's cheap.

## Plan

1. Get real data. Add a debug-only "dump frames" toggle that saves ImageAnalysis frames (upright gray plus the detected quad as JSON) to filesDir/frames. Record a few seconds aiming at a page on different surfaces, pull them with adb into tools/detect-harness/frames, and make the harness print per-frame quads plus a jitter metric (mean corner movement between consecutive frames).
2. Check the overlay mapping on the device: draw the analysis frame bounds and log Preview/ImageAnalysis resolutions and crop rects. Fix the mapping before tuning detection.
3. Stabilise: keep a short history of quads; only switch to a new quad after it persists for about 3 frames; smooth corners with an exponential moving average; hold the last good quad for about 300 ms when detection drops out.
4. Tighten scoring for live frames: thin Canny edges for support, higher thresholds, penalise quads touching the frame border, mild preference for central quads, and run the saturation channel on the chroma planes when available.
5. Measure jitter and accuracy on the recorded frames in the harness before and after each change, then confirm on the phone.

If classic CV still isn't reliable enough, options are a small document-segmentation model run with TFLite/ONNX (stays offline and open source) or Google's ML Kit Document Scanner API (very reliable but closed source and it replaces our camera UI).

## Style

User preferences for prose (README, UI text, commit messages): no em dashes, no Oxford commas, straight quotes, plain verbs.
