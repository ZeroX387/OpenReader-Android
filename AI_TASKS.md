# OpenReader — AI Task Handoff

Last updated: 2026-11-06

## Purpose

This file is the handoff board for AI-assisted development. It helps ChatGPT and Grok work from the same repository state without requiring the project owner to repeatedly explain the project.

The assistants do not automatically share chat history or communicate directly. Before starting, each assistant must read this file and the other project documents.

## Roles

### ChatGPT
- Help clarify requirements and break work into reviewable tasks.
- Inspect architecture and identify affected components.
- Review proposed or completed changes for regressions and consistency.
- Help maintain project documentation and test plans.

### Grok
- Inspect the current implementation before editing.
- Implement assigned code changes or investigate bugs.
- Provide a concise summary of changed files, reasoning, tests and known risks.
- Update relevant documentation when the task changes verified project status.

### Project owner
- Sets priorities and approves scope.
- Tests APKs on a real device.
- Decides whether a Pull Request is ready to merge into `main`.

These are collaboration responsibilities, not a claim that either assistant has permanent or automatic repository access. Each tool/account must be explicitly connected or supplied with the relevant files.

## Required workflow

1. Read `PROJECT_CONTEXT.md`, `CURRENT_STATE.md`, `ROADMAP.md`, `DECISIONS.md` and this file.
2. Inspect the current branch and recent changes before editing.
3. Confirm the task's scope. Ask before changing architecture or removing behavior.
4. Create/use a dedicated task branch. Do not edit the same branch concurrently with another assistant.
5. Keep changes focused; avoid unrelated formatting or refactoring.
6. Run the most relevant build/tests available. Never claim a test passed unless it was actually run.
7. Open a Pull Request against `main`; include summary, changed files, tests, screenshots/logs where relevant, and known limitations.
8. Update project documentation to reflect verified results.
9. The project owner reviews and approves the merge.

## Task board

### Active
- No implementation task currently assigned.

### Next recommended task
- **Establish a reproducible baseline and investigate slow/failing PDF and DOC opening.**
- Before changing code: build current `main`, reproduce on the user's test files if available, capture logs and timings, and identify the actual parser/loading path.
- Related reports and test-file details are recorded in `CURRENT_STATE.md`.

### Completed
- **2026-11-06 — Shared project memory files drafted** on branch `docs/shared-project-memory`. Awaiting review/merge.

## Task report template

For every completed task, add or update a short entry with:

- **Task:**
- **Branch / Pull Request:**
- **Root cause (if a bug):**
- **Files changed:**
- **Behavior changed:**
- **Build/tests actually run:**
- **Device and Android version (if device-tested):**
- **Known limitations / risks:**
- **Documentation updated:**
