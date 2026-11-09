# OpenReader — Roadmap

This roadmap is provisional. Priorities can be changed by the project owner.

## Priority 0 — Establish a reliable baseline

- [ ] Build the current `main` revision and record the workflow result.
- [ ] Install it on a physical Android device.
- [ ] Create a repeatable test set for each intended file format.
- [ ] Record device model, Android version, file size, page count, loading time and result.
- [ ] Verify full-screen reading and system-bar restoration.

## Priority 1 — Stabilize book opening and rendering

- [ ] Reproduce the reported slow/failing PDF and DOC opening.
- [ ] Inspect parsing and loading paths for large files; avoid blocking the UI thread.
- [ ] Add clear loading, progress and error states where needed.
- [ ] Investigate memory use and safe handling of large documents.
- [ ] Re-test EPUB and FB2 parsing, paragraph formatting, chapter headings and pagination.
- [ ] Do not mark a format as supported until it passes a documented test.

## Priority 2 — Protect core reading experience

- [ ] Verify reading position persistence across app restarts.
- [ ] Verify font size, margins, line spacing and themes.
- [ ] Verify library metadata, favorites, sorting and collections.
- [ ] Improve error messages for unsupported, encrypted, scanned or malformed files.
- [ ] Add regression tests for fixes where practical.

## Priority 3 — Complete reader functionality

- [ ] Table of contents and chapter navigation.
- [ ] In-book text search.
- [ ] Bookmarks, highlights, notes and quotes.
- [ ] Reading statistics and progress display.
- [ ] Cover and metadata handling improvements.

## Priority 4 — Reliability and release readiness

- [ ] Manual and automatic local backup/restore.
- [ ] Review permissions and URI persistence for imported/shared files.
- [ ] Accessibility, localization and UI polish.
- [ ] Verify signed APK upgrade path between releases.
- [ ] Prepare release notes and a reproducible release checklist.

## Working rule

Fix and verify existing core behavior before adding new features. Keep each task small enough to review and test independently.
