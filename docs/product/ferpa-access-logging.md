# FERPA education-record access logging (BL-043.5)

Every read of a student's education records **by someone other than that student** writes a row
to `education_record_access_logs` (the V14 hook table) before the data leaves the server.

## What is logged

| Surface | `access_type` | Subjects |
| --- | --- | --- |
| `GET /api/classroom/terms/{termId}/roster` | `VIEW_ROSTER` | one row per student in the returned roster |
| `GET /api/classroom/terms/{termId}/students/{userId}/overview` | `VIEW_STUDENT_OVERVIEW` | the student |
| `POST /api/classroom/terms/{termId}/students/{userId}/chat-export` (BL-043.7) | `EXPORT_CHAT` | the student; `resource_type=CHAT_EXPORT_JOB`, `resource_id` = the export job |

`subject_user_id` is NOT NULL, so a roster read of thirty students writes thirty rows.

Each row stores actor, subject, term, access type, `resource_type`/`resource_id` (`TERM` + term id
today), `occurred_at`, and `retain_until`. Client identifiers are stored **only** as truncated
SHA-256 (`ip_hash`, `user_agent_hash`) — no raw IP or user agent, per the data model's v1 rule.

## What is not logged

- **Self-access.** A student reading their own record is not third-party access; the writer skips
  rows where actor equals subject.
- **Authorization failures.** Denied requests never reach the writer. Authentication and lockout
  events remain in the `account_auth_audit` log.
- Teachers cannot read these rows. The table is write-only in v1; compliance retrieval is an ops
  task (`BL-043` policy).

## Fail-closed

If the audit row cannot be written, the request fails instead of returning the student record.
Disclosure without an audit trail is the failure this table exists to prevent. The write runs in
its own transaction, so an audited access stays recorded even if the response later fails.

## Retention

`retain_until` is `occurred_at + classroom.ferpa.access-log-retain-days` (default **2555** days,
about seven years). **This default is a placeholder** until the legal retention duration is agreed
in BL-043.3 / BL-043.13. Access logs are never soft-deleted with student content, and a purge job
(BL-043.6) must not delete a row before its `retain_until`.

## Open follow-ups

- BL-043.6: retention/purge job and account-delete reconciliation, including these rows.
- Any new teacher-facing surface that returns student records needs a writer call; there is no
  automatic interception.
