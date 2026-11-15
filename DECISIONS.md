# OpenReader — Decisions

Last updated: 2026-11-06

This file records decisions that should not be silently reversed. New decisions should include a date and a short reason.

## Product decisions

1. **Free and non-commercial.** OpenReader is being developed as a free, non-commercial reader.
2. **Offline-first.** The user's local library and reading experience should work without requiring an account or a permanent internet connection.
3. **Android compatibility.** Minimum target is Android 8.0 / API 26.
4. **Book-focused interface.** Prefer a calm, familiar ebook-reader design over a generic feed-style interface.
5. **Full-screen reading.** Hide status and navigation bars in the reading view; restore normal system bars in the library.
6. **Language support.** Provide system language, Russian and English choices.
7. **Reading appearance.** Support light, dark, sepia/paper appearance and adjustable typography, margins and spacing.
8. **Formats are not considered supported based on labels alone.** Each format must be verified with real files and documented tests.

## Engineering decisions / current baseline

1. The existing Kotlin + Jetpack Compose + Material 3 application is the project baseline.
2. Keep the current application ID `com.openreader` unless the owner explicitly approves a migration.
3. Preserve the current package/project structure unless a concrete technical reason justifies changing it.
4. Do not replace a working reader/parser wholesale without first identifying the problem and discussing the trade-offs.
5. Treat the repository code and reproducible test results as more authoritative than older chat notes.

## Collaboration and Git decisions

1. GitHub is the shared source of truth for code and project notes.
2. Work on a task-specific branch; do not have two assistants edit the same branch concurrently.
3. Use Pull Requests for review. Do not push directly to `main` for ordinary feature/fix work.
4. The project owner approves merging changes to `main`.
5. Each task should state its scope, files expected to change, verification performed and remaining risks.
6. Update `CURRENT_STATE.md`, `ROADMAP.md` and/or this file when verified status or decisions change.
7. Never commit secrets, signing keys, passwords, private tokens or user documents.

## Decision log

- **2026-11-06:** Created shared project documentation and adopted GitHub as the common handoff point for AI-assisted work.
