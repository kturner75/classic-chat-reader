# Product Tracking

This folder holds product specs, ADRs, and runbooks. The living backlog is **not** in this repo.

## Living backlog

Use **Notion Tasks** for project **classic-chat-reader**:

- Notion project: https://app.notion.com/p/7446c6580e2045e1827cf5c9b83b6e18
- Tasks database: https://app.notion.com/p/3d0064dd143280c1a8d4d070d92fbf3f

`backlog.md` is a short pointer so existing links keep working. Do not add new `BL-*` items there. Historical epic text through 2026-09-08 remains in git history.

## Files

- `current-features.md`: implemented capabilities verified against code.
- `backlog.md`: pointer to the Notion Tasks backlog (not a living work queue).
- `bl-023-qa-checklist.md`: mobile QA + desktop regression checklist for adaptive reader behavior, plus core chat, voice call, and My Chats checks.
- `bl-021-auth-architecture-adr.md`: auth and security decision record for user registration/account rollout.
- `account-deletion.md`: Download my data + account deletion (BL-043.6) — export-first notice, re-auth, teacher block, what is deleted vs pseudonymized.
- `teacher-chat-export.md`: teacher Reading Buddy chat export API (BL-043.7) — scope, authorization, audit, formats.
- `term-retention-purge.md`: nightly FERPA term retention purge (BL-043.6) — eligibility, what is deleted vs kept, compliance-row expiry.
- `ferpa-access-logging.md`: implemented FERPA education-record access audit (BL-043.5) — surfaces, fail-closed rule, retention placeholder.
- `bl-025-classroom-data-model.md`: classroom domain model + FERPA schema hooks / companion checklist (runtime policy owned by `BL-043` in Notion).
- `landing-ranking.md`: deterministic ranking rules for personalized landing queues.
- `discover-affinity.md`: deterministic recommendation model for the `Discover` rail.
- `my-chats-spec.md`: implemented behavior, privacy rules, resume semantics, and API contract for **My Chats** (`BL-032`, `BL-039`, `BL-049`).
- `classroom-landing-usage.md`: setup and usage guide for classroom-aware landing mode (`BL-018.6`).
- `classroom-pilot-pitch.md`: partner/grant-facing classroom pilot pitch and demo storyboard (Jessica Evans / multi-class pilot).
- `reading-buddy-mode.md`: design document for Reading Buddy Mode (canned personas, sparse commentary, memory, PR plan).

Related (outside this folder): `docs/SECURITY_AUDIT.md` is the OWASP security-audit finding list. FERPA / student-PII work is tracked under `BL-043` in Notion Tasks, not as a second OWASP list.

## Backlog Workflow

Capture, triage, refine, execute, and close work in Notion Tasks for project classic-chat-reader. Do not add new items to `backlog.md`.
