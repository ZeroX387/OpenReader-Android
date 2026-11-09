# OpenReader — Current State

Last checked: 2026-11-06  
Repository: https://github.com/ZeroX387/OpenReader-Android  
Branch used as baseline: `main`

## 1. Version and build configuration

- Gradle `versionName`: `0.4.16-beta`.
- `minSdk = 26` (Android 8.0).
- `compileSdk = 35`, `targetSdk = 35`.
- JVM target: 17.
- Application ID: `com.openreader`.
- GitHub Actions workflow: `.github/workflows/build-apk.yml`.

The build workflow contains steps for building, signing and uploading an APK. A successful workflow run should still be checked before treating a particular source revision as a verified installable build.

## 2. Implementation confirmed in source

The following is visible in the checked-in code:

- Main application and Compose UI are in `MainActivity.kt`.
- Material 3 and Jetpack Compose are used.
- The app can receive book URIs through Android `ACTION_VIEW` and `ACTION_SEND` intents.
- A file-picker import entry point is present; the UI lists TXT, FB2, EPUB, PDF, DOCX, MOBI and AZW3.
- Library metadata is stored locally in SharedPreferences as JSON.
- Book metadata model includes URI, title, format, author, cover URI, favorite/finished flags, genre, collection, added date and length.
- Reading themes include light, dark, sepia and paper.
- Reader font size, line spacing and margins are persisted in preferences.
- The reader hides system bars while a book is open and restores them when returning to the library.
- A system/Russian/English language choice is present.
- Chapter-heading styling and text-based pagination helpers are present.
- PDF text extraction is implemented in `PdfReader.kt` with PDFBox Android. It rejects encrypted PDFs, empty documents, textless/scanned PDFs and extracted text over the configured size limit.
- The Gradle file includes Apache POI Scratchpad, intended for legacy DOC handling.

**Important:** source presence does not establish that every format imports, parses, paginates and renders correctly on a physical device. Verify each format separately.

## 3. Known user-reported problems / test observations

These are reports to reproduce, not yet confirmed root causes:

- On 2026-11-05, the user reported that test DOC and PDF files still failed after a long loading period.
- The test DOC was approximately 5.72 MB, around 1,600 pages and roughly 332,000 words.
- The test PDF was approximately 2.86 MB, 596 pages, PDF 1.4.
- PDF opening/extraction was also reported as taking a long time.
- Earlier development reports mentioned EPUB/FB2 display and formatting problems. Re-test them against the current `main` branch before assuming the old symptoms remain.
- The user specifically requires the reading view to be full-screen while the library keeps normal system bars.

Do not guess at the cause. Capture logs, reproduce with small and large samples, measure load time and memory use, and inspect the relevant parsing/rendering path before changing code.

## 4. Previously reported baseline history

Earlier development checkpoints included:
- TXT opened quickly and reading position persisted after returning to the library.
- A prior 0.2-era checkpoint had fixes for Compose state/build errors, a top gap, font-control placement and loading flicker.
- A later checkpoint was described in chat as a successful green build, but the current source revision and current CI result must be checked independently.

These historical notes are not a substitute for testing the current revision.

## 5. Current verification checklist

- [ ] Build the current `main` revision successfully.
- [ ] Install the resulting APK on a device running Android 8.0 or newer.
- [ ] Confirm library navigation and system bars.
- [ ] Confirm full-screen reader and system-bar restoration on exit.
- [ ] Test TXT, FB2, EPUB, PDF, DOCX, MOBI and AZW3 separately.
- [ ] Test both small and large documents; record open time and failures.
- [ ] Confirm reading progress persists after closing and reopening each test book.
- [ ] Check logcat for parser exceptions, memory pressure and main-thread work.

Update this file whenever a test is completed. Record device/Android version, file size/page count, result and relevant commit.
