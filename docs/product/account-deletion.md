# Account deletion and Download my data (BL-043.6)

## Policy

Decided 2026-09-18 (Kevin): **enrolled students may delete their account.** Deletion is not blocked;
the account modal shows an **export-first notice** that names the student's classes and offers
"Download my data" before the irreversible step.

## Download my data

`GET /api/account/export` returns the signed-in account's own data as JSON. See
`AccountDataExportService` for the sections. It excludes credentials, sessions, sign-in identities,
capability grants, internal reader ids, and other people's data. It is self-access, so no FERPA
access-log row is written.

## Deleting an account

`GET /api/account/delete-preview` → `{email, passwordRequired, classes[], blockedReason}`

`POST /api/account/delete` with JSON `{confirm: true, email, password?}`

1. The requester must be signed in; `confirm` must be true (else 400).
2. **Re-authentication:** the typed email must match the account. Accounts with a password must
   also enter it, and failures count toward the normal sign-in lockout (401, or 429 with
   `Retry-After`). Google-only accounts confirm with the typed email and their live session.
   The session cookie is `SameSite=Lax` and the body must be JSON, so cross-site forgery is not
   possible.
3. **Teacher accounts are blocked (409).** An account that owns a class, holds a class role,
   authored assignments, quizzes, invites, or overrides, changed class settings, or granted
   capabilities is refused. Other people's classes depend on those rows. Teacher deletion needs a
   handover process (follow-up).
4. Otherwise everything is removed **in one transaction, immediately**, which is well inside
   BL-021's 24-hour rule:
   - sessions, credentials, sign-in identities, pending links, reader claims and state;
   - annotations and bookmarks, quiz attempts and trophies;
   - character chats, Reading Buddy messages, memories, and preferences;
   - the student's classroom records: enrollments, assignment progress, usage events, and
     school memberships;
   - capability grants to the account, then the `users` row.
5. **Compliance records are kept but pseudonymized.** `education_record_access_logs` and
   `chat_export_jobs` rows about or by the account are kept (FERPA audit, `retain_until`), with the
   user id replaced by `deleted:<hash>`: a stable, non-reversible pseudonym, so all rows for one
   deleted person still correlate. V33 drops those tables' foreign keys to `users` to allow this.
6. The session cookie is cleared, and an `account_delete` auth-audit event is logged with the
   pseudonym, not the old user id.

Backups may retain deleted data up to 30 days (BL-021).

## Open follow-ups

- Teacher account deletion with class handover.
- Legal hold (no mechanism yet; add before a school requests one).
- BL-043.6 part 3: term retention purge job.
