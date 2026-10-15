# SwidScanner

An open-source document scanner for Android. No account, no ads and no cloud processing: pages are detected, cropped, cleaned up and read entirely on the phone.

## Features

- **Live page detection.** The camera outlines the document as you aim.
- **Auto-capture or manual.** In Auto mode the shot is taken once the page has been steady for about a second. The shutter button always works too.
- **Single or batch.** Single takes you straight to corner adjustment after each shot. Batch keeps scanning page after page (it re-arms when you move or turn the page) and you review everything at the end.
- **Gallery import.** Turn existing photos into scanned pages, with the same automatic crop.
- **Corner adjustment** with draggable handles and a magnifier, plus Auto (re-detect) and Full (no crop).
- **Five filters:** Original, Magic (flattens shadows and whitens paper), Enhance, Grayscale and B&W. Set a default and apply one to every page.
- **Page tools:** rotate, reorder, delete, pinch or double-tap to zoom.
- **Text recognition (OCR):** extract text from a page or the whole document, edit it, copy or share it. Works offline.
- **Export:** PDF (Fit image, A4 or Letter; three quality levels; optional searchable text layer; optional password), JPEG images or a .txt file.
- **Save locations:** any folder on the phone or SD card and/or a linked Google Drive folder. Share to any app at any time.
- **QR and barcode scanner** (QR, Data Matrix, Aztec, PDF417, EAN, UPC, Code 128, Code 39, ITF).
- Light and dark themes follow the system.

## Install

Copy `SwidScanner-1.0.0.apk` to the phone and open it. Android will ask you to allow installs from that source the first time. Requires Android 8.0 or later.

## Build

Requires JDK 17 or newer and the Android SDK (Android Studio installs both).

```
gradlew.bat assembleRelease      (Windows)
./gradlew assembleRelease        (macOS / Linux)
```

The APK is written to `app/build/outputs/apk/release/`. Release builds are signed with the key described in `keystore.properties`. Without that file the release build comes out unsigned. `./gradlew assembleDebug` always works and signs with the debug key.

Add `-Pemulator` to include x86_64 libraries for running on an emulator.

### Keep the signing key safe

`swidscanner-release.jks` and `keystore.properties` are deliberately not committed to git. Back them up. Android only installs an update over an existing copy if it is signed with the same key. Google Drive linking is also tied to this key's SHA-1.

## Google Drive setup (one time)

Drive uploads use Google's official sign-in and the `drive.file` permission, so the app can only see files and folders it created. Google requires every app that does this to be registered, which you do once in your own Google Cloud project. It's free.

1. Go to <https://console.cloud.google.com/> and create a project (any name).
2. **APIs & Services > Library:** search for "Google Drive API" and enable it.
3. **APIs & Services > OAuth consent screen:** choose External, fill in the app name and your email, then add your own Gmail address under **Test users**.
4. **APIs & Services > Credentials > Create credentials > OAuth client ID:** choose **Android**, then enter:
   - Package name: `io.github.swikrutd.swidscanner`
   - SHA-1: shown in the app under Settings > Save locations (tap to copy). For the provided release key it is `31:E9:06:AE:FE:92:F9:1C:2A:2C:9C:17:54:AE:E9:87:0A:E8:FB:C1`.
5. In the app, open Settings and tap **Link** next to Google Drive.

Exports then go to a `SwidScanner` folder in your Drive (rename it in Settings). If you build with a different key, add a second Android client with that key's SHA-1.

## How it works

| Part | Library | License |
| --- | --- | --- |
| Edge detection, perspective correction, filters | OpenCV 4.12 | Apache 2.0 |
| Camera | CameraX | Apache 2.0 |
| PDF writing, password protection, text layer | PdfBox-Android | Apache 2.0 |
| QR and barcodes | ZXing | Apache 2.0 |
| UI | Jetpack Compose, Material 3 | Apache 2.0 |
| Text recognition | Google ML Kit (bundled model) | Google APIs Terms (free, closed source) |
| Drive sign-in | Google Play services Auth | Google APIs Terms (free, closed source) |

All of SwidScanner's own code is MIT licensed. Two dependencies are free but not open source: ML Kit text recognition and Play services sign-in. Tesseract would be the fully open OCR alternative. It can be swapped in later if you want a build that is open source end to end (for example, for F-Droid).

Page detection lives in `core/DocProcessing.kt`. It has no Android dependencies, so it can be tested on a desktop JVM. It runs Canny edges and Otsu segmentation on both brightness and a saturation channel, after a morphological close that erases text. It keeps four-sided convex outlines and scores them by area and by how much of their outline sits on real edges. Photos where the page fills the whole frame return no outline and use the full image.

## Project layout

```
app/src/main/java/io/github/swikrutd/swidscanner/
  core/DocProcessing.kt   detection, warp, filters (pure OpenCV)
  data/                   document storage (JSON + JPEG per page) and settings
  imaging/Images.kt       bitmap decoding, EXIF, page rendering
  export/                 PDF/JPEG/TXT export, OCR, folder saving, Google Drive
  ui/                     Compose screens
```

Scans are stored privately in the app's own storage and are removed if the app is uninstalled. Export anything you want to keep.
