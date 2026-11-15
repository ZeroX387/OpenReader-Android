# OpenReader — Project Context

Last updated: 2026-11-06

## 1. Project identity

- **Name:** OpenReader
- **Repository:** https://github.com/ZeroX387/OpenReader-Android
- **Owner / product decision-maker:** ZeroX387 (the user)
- **Purpose:** a free, non-commercial, offline-first Android ebook reader.
- **Current Android application ID:** `com.openreader`
- **Current project root name:** `OpenReader`

OpenReader is an existing project. Continue from the current repository; do not replace it with a new application or rewrite working features without a clear reason and prior discussion.

## 2. Product direction

The goal is a comfortable, book-focused reader with a clean library and a distraction-free reading screen. The interface should feel like an ebook reader, not a generic social/content feed.

Target platform:
- Android 8.0 (API 26) and newer.
- Material 3 / Jetpack Compose.

Intended file formats:
- TXT, FB2, EPUB, PDF, DOCX, MOBI, AZW3.
- The import UI currently lists these formats, but listing a format is not proof that its parser and reading experience are reliable. Check the implementation and test real files before describing a format as fully supported.

Important product requirements and desired capabilities:
- Local/offline library with file-picker import and support for opening books shared from other apps.
- Persistent reading progress when leaving and reopening a book.
- Comfortable reading typography, adjustable font size, margins and line spacing.
- Light, dark and sepia/paper reading themes; independent app theme.
- Full-screen reading view: hide system status/navigation bars while a book is open; restore system bars in the library.
- Library organization, sorting, favorites, collections, book metadata and covers.
- Table of contents / navigation, search, bookmarks, highlights, notes, quotes and reading statistics as the project develops.
- Manual and automatic backup are desired future capabilities.
- Language choice should support system language, Russian and English.

## 3. Current technical baseline (verified from repository on 2026-11-06)

- Kotlin Android application.
- Jetpack Compose and Material 3.
- Android Gradle Plugin / Kotlin plugin are applied in `app/build.gradle.kts`.
- `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`.
- Java/Kotlin JVM target 17.
- Application version in the checked-in Gradle file: `0.4.16-beta`.
- Main package: `com.openreader`.
- Main UI and application flow: `app/src/main/java/com/openreader/MainActivity.kt`.
- PDF text extraction helper: `app/src/main/java/com/openreader/PdfReader.kt`.
- PDF dependency: `com.tom-roush:pdfbox-android:2.0.27.0`.
- Legacy DOC support dependency present: `org.apache.poi:poi-scratchpad:5.5.1`.
- Build workflow: `.github/workflows/build-apk.yml`; it builds a debug APK, signs it using repository secrets, uploads an artifact, and publishes versioned APKs for version tags.

## 4. Collaboration model

GitHub is the shared source of truth for code, project status, decisions and task handoffs.

- ChatGPT: helps define scope, inspect architecture, review changes and maintain project documentation.
- Grok: may implement assigned changes, inspect code and propose fixes.
- Project owner: decides priorities, tests APKs on a real device and approves changes to the main branch.

The assistants do not automatically share private chat context or directly message one another. They must read the repository documentation and relevant code before acting.

## 5. Source-of-truth order

When information conflicts, use this order:
1. Current code and build configuration in the repository.
2. Reproducible build/test results and user-confirmed device tests.
3. The five project documentation files in the repository.
4. Older chat summaries and assumptions.

Update the documentation when verified project facts or decisions change. Clearly label unverified behavior and reported bugs; do not present intended features as implemented.
