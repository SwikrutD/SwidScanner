# SwidScanner feature plan

Feed this file to Claude Code one step at a time. Each step lists the model and effort to run it with, the files it touches and a ready-to-paste prompt. Finish and commit a step before starting the next. Always read CLAUDE.md first (style rules: no em dashes, no Oxford commas, straight quotes, plain verbs).

Model names: Opus 5.5 (`claude-opus-5-5`), Sonnet 5.5 (`claude-sonnet-5-5`), Haiku 5.5 (`claude-haiku-5-5`). Switch with `/model` and `/effort`.

## Progress (updated 2026-10-17)

| Step | Status |
|------|--------|
| 0 Reverse engineer reference APK | Done (notes kept outside the repo) |
| 1 Overlay model and render pipeline | Done (commit 1599436) |
| 2 Signature library | Done (commit 1599436) |
| 3 Stamp library | Done (commit 1599436) |
| 4 Place on page | Done (commit 804082d) |
| 5 Drag to reorder | Done (commit 4f1cc8b) |
| 6 Select and partial share or save | Done (commit 4986666) |
| 7 ID card scan | Done (commit 878bc37) |
| 8 Polish and release checks | Done (version 1.2.0, versionCode 3, README updated, debug build clean, no unused imports or TODOs; assembleRelease not run) |
| 9 Hybrid ML Kit document scanner | Done (version 1.3.0, versionCode 4; optional Google scanner, own camera stays the default; tested on the Mi 10T Pro with gallery import, signature, reorder and PDF export; fallback path not exercised on a device) |

Also done: live outline stability work (commit b88c108, see CLAUDE.md). The debug build was installed on the phone on 2026-10-17.

Decision on scanning: keep the open source OpenCV pipeline as the default and add Google's ML Kit Document Scanner as an optional mode (step 9). The default stays open and works without Play services. The optional mode gives better detection on phones that have them.

## What the repo looks like today (relevant parts)

- `data/DocStore.kt`: `Page(id, corners, filter, rotation, rev)` and `Doc(id, name, created, updated, pages)`. Files per page in filesDir/docs/<docId>: `-orig.jpg`, `-r<rev>.jpg` (rendered), `-t<rev>.jpg` (thumb). Editing a page calls `updatePage()`, which bumps `rev` and re-renders via `Images.renderPage()`. `movePage(docId, pageId, delta)` already exists (one step at a time, used by `PageScreen`).
- `imaging/Images.kt`: `renderPage(orig, page)` applies crop, filter and rotation. This is the right place to bake overlays (signatures, stamps) into the rendered JPEG, so export, share and thumbnails all pick them up for free.
- `export/Exporter.kt`: PDF/JPEG/TEXT export reads `DocStore.procFile()` for every page in `doc.pages`. Passing a subset of pages is a small change.
- `ui/DocScreen.kt`: `LazyVerticalGrid` of page thumbnails, top bar with OCR, Save, Share. No selection mode and no drag.
- `ui/ExportSheet.kt` and `export/Saver.kt`: take a whole `Doc`. Both need to accept a page subset.
- `ui/PageScreen.kt`: single page view with filter strip and actions. Natural home for a "Sign" and "Stamp" action.
- `ui/CropScreen.kt`: has a `Canvas` with draggable corner handles. Reuse its gesture code for placing and resizing signatures.
- Navigation lives in `MainActivity.kt` (`AppNav`) plus a `Routes` object. Settings in `data/Settings.kt`.
- Dependencies already available: Compose, Coil, OpenCV 4.12, PdfBox-Android, ML Kit text recognition, CameraX. No new libraries are needed for any step below.
- Reference app already in the repo: `references\uptodown-com.cambyte.okenscan.apk` (about 15 MB).

## Design decisions (apply to all steps)

1. **Overlays are non-destructive.** Add `overlays: List<Overlay>` to `Page`. `Overlay(id, assetId, type SIGNATURE|STAMP, cx, cy, width, rotation, opacity, tint)` with position and size normalised to 0..1 of the rendered page, same convention as `Corner`. `tint` is a nullable colour: null draws the asset as stored, a colour recolours the ink and keeps its alpha. OKEN Scan lets you change a signature's colour after placing it, so colour belongs to the placement as well as the asset. `renderPage()` draws them last. Editing or removing an overlay re-renders from `-orig.jpg`, so nothing is lost. (OKEN burns signatures into the JPEG and keeps a table to replay them after a re-filter. Non-destructive overlays make that unnecessary.)
2. **Signature and stamp library is global, not per document.** New `data/AssetStore.kt` keeps PNGs with transparent background in filesDir/assets/signatures and filesDir/assets/stamps plus an `assets.json` index (id, kind, name, created). Expose a `StateFlow<List<Asset>>` like `DocStore.docs`.
3. **Transparent background pipeline** (shared by image, scan and file imports): load, crop to the ink with a rectangle crop first (a photo holds far more than the signature), flatten uneven lighting (divide by a heavy blur or a morphological close, like the Magic filter), convert to grayscale, Otsu threshold into a mask, build a soft alpha from darkness inside the mask so edges stay smooth, drop small specks, keep ink colour (default dark blue or black, user can pick; stamps may keep their original colours), crop to the bounding box with a small margin. A plain global threshold on a phone photo breaks under shadows. OKEN also runs a contrast filter before its ink cleanup. Put this in `core/DocProcessing.kt` as a pure OpenCV function so the desktop harness can test it.
4. **Bump the doc.json format safely.** `read()` must default `overlays` to empty so existing documents still load.
5. Add `Routes` entries for each new screen and keep the debug `--es route` shortcut working.

## Step 0: Reverse engineer the reference APK (do this first)

- Model: **Opus 5.5**. Effort: **high**.
- Why first: it tells you how a mature app lays out ID scan, signatures, stamps and drag reorder, so steps 2 to 6 can copy the behaviour (not the code).
- Output goes to `references\okenscan-notes.md`. Do not commit the APK, decompiled sources or extracted assets.

Prompt to paste (use `/reverse-engineer-anything` in the same message):

```
/reverse-engineer-anything

Target: H:\Tools\SwidScanner\references\uptodown-com.cambyte.okenscan.apk
I own this APK. I want to learn which features it has and how they behave, so I can build my own clean versions in SwidScanner (Kotlin, Jetpack Compose). Do not copy code, strings, layouts or image assets into the repo. Write findings in your own words.

Work in a new empty folder outside the repo (for example H:\Tools\_rea\okenscan) and keep extracted files there.

Cover only these areas:
1. ID card scan: the capture flow (front, then back), guides or overlays shown, how the two sides are combined into one page, page size and layout, margins, whether there are card type presets (Aadhaar, passport, driving licence, generic), and what the output file is.
2. Signatures: every way to create one (draw with finger or stylus, import image, scan a signature, import a signature file). Note stroke settings (width, colour, smoothing, pressure), how the background is made transparent, how signatures are stored (file format, location, database tables) and how they are placed on a page (drag, pinch to resize, rotate, opacity, apply to all pages or one).
3. Stamps: same questions as signatures, plus any built-in stamp templates (date, "Approved", "Paid") and how text stamps are rendered.
4. Page reordering and selection: the gesture used to drag pages, the visual feedback, the multi-select UI, "select all", and what actions appear for the selection (share, save, delete, merge, export).
5. Anything else notable in the export or document screens that CamScanner-style apps usually have and SwidScanner lacks.

For each area give: the screens and activities involved (class names are fine for reference), the user flow step by step, the data model, third-party libraries used, and edge cases you noticed. End with a prioritised list of what is worth building and what is not. Mark every claim as confirmed from code or resources, or as inferred.
```

After it finishes, tell Claude: "Read references\okenscan-notes.md and adjust steps 1 to 6 in progress.md where the findings disagree with the plan."

## Step 1: Overlay data model and render pipeline

- Model: **Opus 5.5**. Effort: **high** (touches the render and storage core; mistakes corrupt saved documents).
- Files: `data/DocStore.kt`, `imaging/Images.kt`, new `data/AssetStore.kt`, new `core/DocProcessing.kt` function for background removal, tools/detect-harness for a quick test.
- Findings: OKEN stores signatures as opaque JPEGs and cuts out the ink every time it draws them. Storing a transparent PNG once is simpler. It also remembers the size each signature was last placed at, so the next placement starts there: keep `lastWidth` on the asset.

```
Read CLAUDE.md and progress.md (section "Design decisions" and Step 1). Implement:
1. Overlay data class (including the nullable tint) and `overlays` on Page, with JSON read and write in DocStore that defaults to an empty list for old documents.
2. AssetStore with Asset(id, kind, name, created, lastWidth), PNG storage in filesDir/assets/<kind>, an assets.json index and a StateFlow. Methods: add(kind, bitmap, name), rename, delete, file(id), setLastWidth(id, width).
3. Images.renderPage draws overlays last, using normalised cx, cy, width and rotation. Height comes from the asset aspect ratio. When tint is set, replace the asset's colour with tint and keep its alpha. Apply opacity.
4. A pure OpenCV function in core/DocProcessing.kt, `inkToTransparent(src: Mat, inkColor: Int?): Mat`: flatten lighting, Otsu threshold to a mask, soft alpha from darkness, remove small specks, crop to bounds. A null inkColor keeps the original colours (stamps need this in Step 3). Add a harness case in tools/detect-harness that runs it on a sample photo of a signature with a shadow across it and writes a PNG.
5. DocStore.setOverlays(docId, pageId, list) that goes through updatePage so the page re-renders.
Do not build any UI yet. Run gradlew.bat assembleDebug and the harness, and show me the result.
```

## Step 2: Signature library and creation

- Model: **Sonnet 5.5**. Effort: **medium**.
- Files: new `ui/SignatureScreen.kt` (library grid plus creator), new rectangle crop composable, `ui/CameraScreen.kt` (signature mode), `MainActivity.kt` routes, `HomeScreen.kt` or `SettingsScreen.kt` entry, `AndroidManifest.xml` only if a new permission is needed (it should not be).
- Findings: OKEN offers only "scan your signature" and "import from album". It has no drawing pad and no signature file import, so Draw and From file are new ground for SwidScanner. Its scan does not use page detection. The camera shows a fixed centred window (about 4:3, outside dimmed, corner brackets) and the next screen is a rectangle crop, not a four-corner page crop. A signature on a full sheet would otherwise be cropped to the whole sheet.

```
Read CLAUDE.md and progress.md. Build the signature library on top of AssetStore (Step 1 is done). Add a "Signatures" screen reachable from Settings and from the page editor later. It shows a grid of saved signatures with rename and delete, and an Add button with four options:
1. Draw: a full-width Compose Canvas using pointerInput. Support finger and stylus (use pressure from PointerInputChange when the tool type is Stylus to vary stroke width). Smooth strokes with quadratic Bezier between midpoints. Controls: pen colour (black, blue, custom), width slider, undo, clear. Save as a transparent PNG cropped to the ink bounds.
2. From image: system photo picker, then a rectangle crop (axis-aligned box with draggable edges and corners, separate from the four-corner CropScreen), then inkToTransparent, preview over a checkerboard, ink colour picker, Save.
3. From scan: open CameraScreen in a signature mode: a fixed centred window with the outside dimmed, no page detection and no auto-capture, shutter only. Then the same rectangle crop with the box preset to that window, then inkToTransparent and Save.
4. From file: ActivityResultContracts.OpenDocument for png, jpg, webp and svg is out of scope. Accept PNG with alpha as is, and any other image through inkToTransparent. Also export and import a "signature file": a plain .swidsig file that is a zip of the PNG plus a small json (name, created), so signatures can move between phones.
Keep UI text plain and short. Build with gradlew.bat assembleDebug and install on the phone.
```

## Step 3: Stamp library

- Model: **Sonnet 5.5**. Effort: **medium**.
- Files: reuse the library screen from Step 2 by making it generic over `kind`; add a text stamp creator.
- Findings: OKEN has no stamps at all: no templates, no text stamps, no date stamp. Its nearest features are a plain movable text box ("watermark") and a tiled text mark, so there is nothing to copy and the plan stands. The only addition is built-in templates and a date line, which are cheap and are what people expect from a stamp.

```
Read CLAUDE.md and progress.md. Generalise the signature library screen into AssetLibraryScreen(kind) so it serves both Signatures and Stamps. For stamps add these creation options: from image, from scan (both through inkToTransparent but keep colour: add a "keep original colours" switch because stamps are often red or blue), from file (.swidstamp zip like .swidsig), and "text stamp" which renders a bordered rounded rectangle with text, colour, border width and rotation of -10 to 10 degrees into a transparent PNG. Offer templates to start from: APPROVED, PAID, RECEIVED, COPY, DRAFT and CONFIDENTIAL, each with an optional second line holding today's date in the system date format (filled in when the stamp is saved, editable before saving). Share one picker and one transparent-background preview composable between both kinds.
```

## Step 4: Place signatures and stamps on a page

- Model: **Opus 5.5**. Effort: **high** (gesture maths, coordinate mapping between screen and normalised page space, and re-render consistency).
- Files: `ui/PageScreen.kt`, new `ui/PlaceOverlayScreen.kt`, `DocStore.setOverlays`.
- Findings: OKEN's placement uses one-finger handles rather than pinch. Drag the signature to move it (kept inside the page), drag a handle on one corner to scale and rotate together around the centre, tap a handle on the opposite corner to delete. Pinch zooms the page, not the signature. This avoids the conflict between "page is zoomable" and "pinch resizes". Colour and thickness can be changed after placing. It has no opacity control and no "apply to all pages", so those two remain SwidScanner extras.

```
Read CLAUDE.md and progress.md. In PageScreen add "Sign" and "Stamp" actions. Each opens a bottom sheet with the library; tapping an asset opens PlaceOverlayScreen showing the rendered page (zoomable) with the asset on top, starting at the asset's lastWidth. Support: drag the overlay to move it (keep its centre inside the page), a corner handle that scales and rotates together around the centre, pinch and twist with both fingers on the overlay as a second way to resize and rotate, a delete handle on the opposite corner, pinch anywhere else zooms the page. Below the page: a colour row (original, black, blue, red) that sets tint, an opacity slider, Done. Several overlays can sit on one page; tap one to select it. Save the final width back to the asset with setLastWidth. Store position as normalised values so the result is identical after export at any quality. Map between screen and page space using the displayed image rect (letterboxing matters; see how CropScreen does this). Also add "Apply to all pages" and "Apply to pages..." options that copy the overlay at the same normalised position to other pages. Tapping an existing overlay on PageScreen re-opens it for edit. Verify by placing a signature, exporting a PDF through ExportSheet and confirming it appears at the same spot.
```

## Step 5: Drag and drop page reordering

- Model: **Sonnet 5.5**. Effort: **medium**.
- Files: `ui/DocScreen.kt`, `data/DocStore.kt` (new `movePages`).
- Findings: in OKEN a long press that is released enters selection mode, and dragging also works on a selection: drop it and all selected pages land together as one block at the drop point, in their existing order. The plan kept drag and selection apart. The gesture below gives both: long press and drag moves, long press and release selects (Step 6). OKEN also writes the new page order once, when the drag ends.

```
Read CLAUDE.md and progress.md. Replace the static LazyVerticalGrid in DocScreen with a drag-to-reorder grid. No new library: implement it with LazyGridState, detectDragGesturesAfterLongPress, item offset tracking, and animateItem for neighbours. Long press lifts a page (scale up, shadow, light haptic); moving the finger without lifting drags it, dragging over another cell moves the target slot, auto-scroll near the top and bottom edges, release commits. A long press released without moving is reserved for Step 6 (enter selection); until then it does nothing. Add DocStore.movePages(docId, pageIds, toIndex) that removes the given pages and inserts them as one block at toIndex, keeping their relative order, and writes once on drop (not on every swap). A single page drag calls it with one id. Page number badges must update live. Keep the move left and right buttons in PageScreen working. Test with at least 12 pages so scrolling during drag is exercised.
```

## Step 6: Select, select all, and partial save or share

- Model: **Sonnet 5.5**. Effort: **medium**.
- Files: `ui/DocScreen.kt`, `ui/ExportSheet.kt`, `export/Exporter.kt`, `export/Saver.kt`, `data/DocStore.kt` (extract to a new document).
- Findings: OKEN enters selection with a long press (the pressed page starts selected), dims each selected thumbnail by about 30% and adds a check, and uses a single toggle that reads "Select all" or "Cancel select all". Dragging a selected page moves the whole selection: the other selected thumbnails slide onto the dragged one, sit behind it as a tilted stack (two cards at most) and a badge shows the count. Its selection actions are Share, Save to gallery, Move, Delete and a More menu with Copy, Batch edit and Collage. A PDF tools variant adds Extract, which copies the selected pages into a new document. There is no "merge selected pages".

```
Read CLAUDE.md and progress.md. Add selection mode to DocScreen. Entering: a long press released without moving (Step 5 already reserves it), with the pressed page selected, or a Select action in the top bar. In selection mode: each selected thumbnail is dimmed by about 30% with a check in the corner, tapping a page toggles it, a top bar shows "N selected" with one toggle that reads Select all or Clear, and actions Share, Save, Delete, OCR, Filter (apply one filter to every selected page) and Extract (copy the selected pages into a new document named after the original). Back exits selection mode. Reorder stays on in selection mode: long press and drag on a selected page moves every selected page as one block through DocStore.movePages; show the dragged page with a count badge and up to two of the other selected thumbnails tilted behind it. Dragging an unselected page moves only that page. Then thread a page subset through the export path: add `pages: List<Page>? = null` to Exporter.export, ExportSheet and Saver.savePdf so a null means the whole document. File names for a subset get a suffix like "_pages_2-5" (compress consecutive runs). Confirm that a 3 of 8 page share produces a 3 page PDF, and that dragging 3 scattered selected pages leaves them next to each other in their old order.
```

## Step 7: ID card scan (front and back on one page)

- Model: **Opus 5.5**. Effort: **high** for the first pass (new capture flow and layout), then drop to Sonnet 5.5 medium for polish.
- Files: new `ui/IdScanScreen.kt`, `ui/CameraScreen.kt` (reuse; add an ID mode with a card-shaped guide), `core/DocProcessing.kt` (card aspect ratio warp), `DocStore` (create the doc), `HomeScreen.kt` (entry).
- Layout and flow follow OKEN Scan's ID mode (full notes in `H:\Tools\_rea\okenscan\FINDINGS.md`, kept outside the repo).
- Findings:
  - **Guide:** a centred frame in the card's ratio, about 80% of the screen width, drawn as corner brackets only with no dimmed outside. A large label in the middle reads front and switches to back after the first shot.
  - **Each shot:** detected, warped, filtered and turned upright automatically. OKEN uses a text-angle detector for upright; here a rotate button in review is enough.
  - **Page:** white A4. Cards at true size, centred left to right. The front sits above the page's horizontal centre line and the back below it, each 20 mm from that line (so 40 mm between the cards, not 20). Corners rounded at 3.18 mm, the real ID-1 corner radius, with no border. Each photo is scaled to fit its slot without distortion.
  - **Presets:** OKEN has none for India. Its generic ID card (85.6 x 54 mm) covers Aadhaar, PAN, driving licence and bank cards. Passport is one shot in a single 125 x 176 mm slot centred on the page. "Single side" takes many cards in a row and puts one card per page, centred at true size.
  - **Extras:** the review screen can add a text watermark repeated across the page at -45 degrees and about 10% opacity (used for "for KYC only" style marks). One filter applies to both cards. Leaving the camera after a shot asks before discarding it.

```
Read CLAUDE.md and progress.md. Add an "ID card" mode on the Home screen.

Capture: CameraScreen in an ID mode with a centred card guide (ratio 85.6 x 54 mm, ISO ID-1, about 80% of the screen width, corner brackets only) and a large "Front" label in the middle that switches to "Back" after the first shot. Auto-capture off by default. Capture, auto-crop with the existing detectCorners and a fixed ratio perspective warp to 1011 x 638 px (ID-1 at 300 dpi). If no card is found, crop to the guide frame instead. Review with retake and rotate, then repeat for the back. Let the user pick front or back from the gallery instead. Back after a shot asks before discarding.

Presets:
- ID card (default): two shots, front and back on one page.
- Passport: one shot, one 125 x 176 mm slot centred on the page.
- Single side: keep shooting cards until Done, one card per page, each centred at true size.
- Custom: width and height in mm.

Layout: a white A4 canvas (2480 x 3508 px at 300 dpi). Cards at true printed size, centred horizontally. For two-sided cards the front goes above the horizontal centre line and the back below it, each with a 20 mm gap to that line. Scale each image to fit its slot keeping its aspect ratio and centre it. Round the card corners at 3.18 mm (scale with card width for custom sizes). Draw no border.

Options on the review screen:
- filter for the whole page (Colour or Enhanced)
- a "keep front and back as separate pages" switch
- an optional watermark text that is tiled across the page at -45 degrees, black at about 10% opacity, on staggered rows

Store each finished page through DocStore.addPage so it behaves like any other page (filters, signatures, export all work). Name the doc "ID card <date>" ("Passport <date>" for the passport preset) through the existing AutoName helper.

Put the layout maths (slot rectangles in mm to pixels, fit and centre) in a pure function in core/DocProcessing.kt so the desktop harness can render a test page from two sample images. Test with a real card on the phone and print one page at 100% to check the card measures 85.6 x 54 mm.
```

## Step 8: Polish and release checks

- Model: **Haiku 5.5** for small fixes, **Sonnet 5.5** low effort for the rest.
- Do these as one prompt:

```
Read CLAUDE.md and progress.md. Review everything added in Steps 1 to 7: bump versionName to 1.2.0 and versionCode, update README.md features list (plain words, no em dashes), add the new screens to the privacy notes only if data handling changed (it should not), run gradlew.bat assembleDebug, then list any unused imports and TODOs. Do not run assembleRelease and do not touch swidscanner-release.jks or keystore.properties.
```

## Step 9: Hybrid scanning with the ML Kit Document Scanner (optional mode)

- Model: **Sonnet 5.5**. Effort: **high** (new dependency and a second capture path, but it reuses the existing page pipeline).
- Do this after step 7 is committed, and before or after step 8 (if after, bump the version there).
- Files: `app/build.gradle.kts`, `data/Settings.kt`, `ui/HomeScreen.kt` (entry), a new `imaging/MlKitScan.kt`, `ui/CameraScreen.kt` (only for the fallback), `MainActivity.kt` (activity result launcher), README.md.
- Design:
  - The default stays the current CameraX screen with our own detection. Nothing changes for users who do nothing.
  - A setting "Scanner" with two values: "SwidScanner camera" (default) and "Google scanner (needs Google Play services)". The Google choice is hidden or disabled when Play services are missing (check with `GoogleApiAvailability`).
  - The Google scanner uses `com.google.android.gms:play-services-mlkit-document-scanner`. It runs its own UI and returns JPEG pages. Use `setGalleryImportAllowed(true)`, `setPageLimit` high (about 50), `setResultFormats(RESULT_FORMAT_JPEG)` and `SCANNER_MODE_FULL`.
  - Each returned page goes through the normal import path (`DocStore.addPage`) so filters, signatures, stamps, reorder and export all work. Run our own `detectCorners()` on the returned image only to fill the crop corners (they will be near the full frame since Google already cropped), so the crop screen still works.
  - Fallback: if the Google scanner is unavailable or fails to start, show a short message and open our camera.
  - Do not remove or weaken `core/DocProcessing.kt`, `QuadTracker` or the harness. ID card and book modes keep using our camera.
- Privacy: the ML Kit scanner works on device. Check the privacy notes in README.md and the homepage and add one line saying the optional Google scanner is a Google Play services component, only if that is accurate after you read Google's current docs.

Prompt to paste:

```
Read CLAUDE.md and progress.md (step 9). Add the optional Google ML Kit Document Scanner as a second scanning mode next to our own camera, keeping our OpenCV pipeline as the default.

1. Add the dependency com.google.android.gms:play-services-mlkit-document-scanner to app/build.gradle.kts (use the current stable version) and keep the app building with gradlew.bat assembleDebug.
2. Add a Scanner setting in data/Settings.kt (values: OWN, GOOGLE; default OWN) and a row for it in the Settings screen. Disable the GOOGLE choice with a short hint when Google Play services is not available.
3. Create imaging/MlKitScan.kt that builds GmsDocumentScannerOptions (gallery import on, page limit 50, JPEG result, full scanner mode), starts the scan through an ActivityResultLauncher registered in MainActivity, and returns the page URIs.
4. When the setting is GOOGLE, the Scan button on the home screen launches this scanner instead of our camera. Copy each returned page into the document through the existing DocStore add path, fill the crop corners with detectCorners() on the image (or the full frame if none is found), and open the document like a normal scan. Book mode, ID card and quick-scan shortcuts keep using our camera.
5. If the scanner cannot start or returns an error, show a short snackbar and fall back to our camera.
6. Update README.md features list and privacy notes (plain words, no em dashes, no Oxford commas, straight quotes). Do not touch swidscanner-release.jks or keystore.properties and do not run assembleRelease.

Install the debug build on the phone with adb, check with a real page that pages arrive in the document and that signatures, export and reorder still work, then report what you tested. Do not commit.
```

## Suggested order and effort summary

| Step | Task | Model | Effort |
|------|------|-------|--------|
| 0 | Reverse engineer reference APK | Opus 5.5 | high |
| 1 | Overlay model and render pipeline | Opus 5.5 | high |
| 2 | Signature library and creation | Sonnet 5.5 | medium |
| 3 | Stamp library | Sonnet 5.5 | medium |
| 4 | Place on page | Opus 5.5 | high |
| 5 | Drag to reorder | Sonnet 5.5 | medium |
| 6 | Select and partial share or save | Sonnet 5.5 | medium |
| 7 | ID card scan | Opus 5.5 then Sonnet 5.5 | high then medium |
| 8 | Polish and release prep | Haiku 5.5 or Sonnet 5.5 | low |
| 9 | Hybrid ML Kit document scanner | Sonnet 5.5 | high |

Steps 5 and 6 do not depend on 1 to 4, so they can run first if you want visible wins early. Steps 2 and 3 can be merged into one session if the first goes smoothly.

## Known open items outside this plan

- The live outline stability work in CLAUDE.md ("Current bug") is separate. Finish or park it before step 7, since ID card capture depends on good detection.
- The ID layout in step 7 now follows OKEN Scan. The reference photo you attached earlier was never available to the analysis, so compare it with step 7 before you start that step.
- `_to_delete/` and `SwidScanner-1.1.0.apk` are untracked in git. Keep them out of commits.
