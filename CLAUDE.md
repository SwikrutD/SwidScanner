# SwidScanner

Open-source Android document scanner (CamScanner replacement). Kotlin, Jetpack Compose, CameraX, OpenCV 4.12, PdfBox-Android, ZXing, ML Kit text recognition. MIT licensed. See README.md for features and the Google Drive setup.

## Build and run (Windows)

- Debug build: `gradlew.bat assembleDebug`
- Release build: `gradlew.bat assembleRelease` (signed with swidscanner-release.jks via keystore.properties; never commit either file and never regenerate the key)
- Release APKs live in `releases\SwidScanner-<version>.apk` (git-ignored). After a release build, copy `app\build\outputs\apk\release\app-release.apk` there under that name. Current: 1.5.0 (versionCode 6).
- adb is at `C:\Android\sdk\platform-tools\adb.exe` (not on PATH)
- Install on the USB-connected phone: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
  - Debug builds are `io.github.swikrutd.swidscanner.dev` and install beside the release, so the release and its documents stay untouched.
  - MIUI asks for approval on the phone for USB installs ("Install via USB" must be on in Developer options).
- Open the camera directly: `adb shell am start -n io.github.swikrutd.swidscanner.dev/io.github.swikrutd.swidscanner.MainActivity --es route camera`
- Logs: `adb logcat -s SwidCam` (camera mapping and analysis ms/frame in debug builds)
- Pull app files from a debug build: `adb exec-out run-as io.github.swikrutd.swidscanner.dev cat files/<path> > local-file`

## Layout

- `core/DocProcessing.kt`: page detection, perspective warp and filters. Pure OpenCV with no Android imports, so it runs on the desktop JVM.
- `ui/CameraScreen.kt`: CameraX preview, ImageAnalysis frames into `detectFrame()`, overlay drawing, auto-capture state machine in `onFrame()`.
- `imaging/Images.kt`: bitmap decode, EXIF, `detectCorners()` on full captures, `renderPage()`.
- `imaging/MlKitScan.kt`: optional Google ML Kit Document Scanner (Settings > Scanner). Only the home Scan button uses it; book mode, ID card, quick scan and Add pages stay on our camera. Pages go through `DocStore.addPage` like a gallery import.
- `data/DocStore.kt`: documents as JSON + JPEG per page in filesDir/docs.
- `tools/detect-harness/`: desktop JVM harness that runs DocProcessing on a folder of images and writes overlays.
  `cd tools\detect-harness; .\get-samples.ps1; ..\..\gradlew.bat -p . run --args="samples out"`

## Live page outline

Done so far: debug frame dump (REC chip on the camera screen, `ui/FrameDump.kt`), `core/QuadTracker.kt` (vote over 6 frames, size-weighted smoothing, 300 ms hold), stricter live scoring (direction-aware thin edges, step check, dark share, centre bias, chroma plane from U/V) and shape checks (every corner in view, corner angles 50 to 130 degrees, opposite sides within 2x). The overlay mapping was checked on the Mi 10T Pro: analysis 640x480 and preview 1600x1200 cover the same view, no crop.

Pages cut off by the frame are rejected on purpose so the user steps back; the capture is re-detected at full size anyway.

Recorded sessions live in `tools/detect-harness/frames/sNN` (not committed). s15 to s22 are a single sheet: s15 wood handheld, s16 white stove, s17 and s18 fabric, s19 strong angle on granite, s20 bank card (under 10% of the frame, so not found), s21 phone too close (page cut off), s22 held in hand with clutter. `truth.json` holds hand-checked keys and "none" ranges where no outline should show.

- Replay: `cd tools\detect-harness; ..\..\gradlew.bat -p . run --args="frames frames [out]"`. Look at the `chroma+trk` line, that is what the phone shows. "wrong" counts frames showing an outline more than 6% off.
- `label <session> <step> <out.jpg>` and `quads <session>` help pick new truth keys.
- Known weak spots: an open book on a white surface (s06), a page cut off with a dark band at the frame edge (s21), small cards. Smaller minimum areas were tried and let blocks inside a page through.

If classic CV still isn't reliable enough, options are a small document-segmentation model run with TFLite/ONNX (stays offline and open source) or Google's ML Kit Document Scanner API (free and very reliable but closed source, needs Play services and replaces our camera UI).

## Style

User preferences for prose (README, UI text, commit messages): no em dashes, no Oxford commas, straight quotes, plain verbs.
