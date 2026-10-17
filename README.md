# SwidScanner

An open-source document scanner for Android. No account, no ads and no cloud processing: pages are detected, cropped, cleaned up and read entirely on the phone.

## Features

- **Live page detection.** The camera outlines the document as you aim.
- **Auto-capture or manual.** In Auto mode the shot is taken once the page has been steady for about a second. The shutter button always works too.
- **Single or batch.** Single takes you straight to corner adjustment after each shot. Batch keeps scanning page after page (it re-arms when you move or turn the page) and you review everything at the end.
- **Book mode.** Tap the book icon in the camera, line the spine up with the guide and each photo becomes two pages, left and right.
- **Named from text.** New documents are named after the heading on their first page (switch off in Settings).
- **Quick scan.** Long-press the app icon and tap Scan, or add the Scan tile to the quick settings panel, to go straight to the camera.
- **Gallery import.** Turn existing photos into scanned pages, with the same automatic crop.
- **Corner adjustment** with draggable handles and a magnifier, plus Auto (re-detect), Full (no crop) and Retake (replace the photo, keeping the page in place).
- **Five filters:** Original, Magic (flattens shadows and whitens paper), Enhance, Grayscale and B&W. Set a default and apply one to every page.
- **Page tools:** rotate, delete, pinch or double-tap to zoom. Press and hold a page in the document grid, then drag it to a new place.
- **Select pages.** Press and hold a page to select it, tap others to add them, or use Select all. Share, save, delete, run OCR, apply a filter or extract the selected pages into a new document. Dragging a selected page moves the whole selection.
- **Signatures and stamps.** Build a library of signatures (draw, photo, camera scan or file) and stamps (photo, scan, file or text with templates such as APPROVED and PAID). Place them on any page, then move, resize, rotate, recolour or fade them. Copy one to other pages. Overlays stay editable until you export. Signatures and stamps can be shared between phones as .swidsig and .swidstamp files.
- **ID card scan.** Shoot the front and back of a card and get both on one A4 page at true size, ready to print. Presets for ID card, passport, single side and custom sizes, with an optional watermark text.
- **Text recognition (OCR):** extract text from a page or the whole document, edit it, copy or share it. Works offline.
- **Export:** PDF (Fit image, A4 or Letter; three quality levels; optional searchable text layer; optional password), JPEG images or a .txt file.
- **Save locations:** the Save button writes a document as a PDF to a folder on the phone or SD card, a linked Google Drive folder or both, each with its own on/off switch. Turn on **Auto-save** to save every document a few seconds after you scan or edit it. Scans themselves stay inside the app. Share to any app at any time.
- **QR and barcode scanner** (QR, Data Matrix, Aztec, PDF417, EAN, UPC, Code 128, Code 39, ITF).
- Light and dark themes: follow the system or pick one in Settings.

## Install

Copy `SwidScanner-1.2.0.apk` to the phone and open it. Android will ask you to allow installs from that source the first time. Requires Android 8.0 or later.

## Build

Requires JDK 17 or newer and the Android SDK (Android Studio installs both).

```
gradlew.bat assembleRelease      (Windows)
./gradlew assembleRelease        (macOS / Linux)
```

The APK is written to `app/build/outputs/apk/release/app-release.apk` (the `app/build` folder only exists after a build; `SwidScanner-<version>.apk` in the repo root is a copy of the latest release). Release builds are signed with the key described in `keystore.properties`. Without that file the release build comes out unsigned. `./gradlew assembleDebug` always works and signs with the debug key.

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
   - SHA-1: shown in the app under Settings > Save locations (tap to copy). It belongs to the key the installed APK was signed with. For the provided release key (`SwidScanner-<version>.apk`, `assembleRelease`) it is `31:E9:06:AE:FE:92:F9:1C:2A:2C:9C:17:54:AE:E9:87:0A:E8:FB:C1`. Debug builds are signed with the debug key of the computer that built them, so they show a different SHA-1.
5. In the app, open Settings and tap **Link** next to Google Drive.

Exports then go to a `SwidScanner` folder in your Drive (rename it in Settings). If you build with a different key, add a second Android client with that key's SHA-1.

### Who can link Drive

While the consent screen is in **Testing**, only the Google accounts listed as test users can link Drive (up to 100), and Google asks them to allow access again about once a week. To add someone, open **Google Auth Platform > Audience** (older consoles: **APIs & Services > OAuth consent screen**), click **Add users** under Test users and enter their Gmail address.

To let anyone link Drive, click **Publish app** on the same page so the status becomes **In production**. `drive.file` is a non-sensitive scope, so Google doesn't require an app review for it, and the weekly re-approval stops.

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
