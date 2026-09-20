# Term retention purge (BL-043.6)

A nightly job (`classroom.ferpa.purge-cron`, default `0 30 3 * * *`) removes a term's student
education records once the term's retention period has ended.

**It is off by default and enabled in production only** (`classroom.ferpa.purge-enabled`, `false` in
`application.properties`, `true` under the `prod` profile via `CLASSROOM_PURGE_ENABLED`). Deleting
student records is irreversible, so no local, test, or ad-hoc environment purges unless someone
turns it on deliberately.

## When a term purges

- If `terms.retention_purge_after` is set, the term purges once that time has passed.
- Otherwise, once `end_date + classroom.ferpa.term-retain-days` has passed (default **400**, the
  data model's placeholder until BL-043.3/.13 set the legal duration). The end day itself counts
  as term time.
- Terms with **no end date never purge**. Already `PURGED` terms are skipped.
- **Soft-deleted terms still purge.** Hiding a term (`deleted_at`) must not keep its student records
  forever; eligibility ignores `deleted_at`, and the term is marked `PURGED` as usual.
- The cutoff uses the classroom calendar zone (`classroom.calendar-zone`), matching the school
  calendar dates stored on terms, rather than the server's UTC date.

Pilot terms ending December 2026 would first purge in January 2028 with the default.

## What is deleted, per term, in one transaction

- `enrollments` (the roster)
- `assignment_progress`
- `classroom_usage_events`

The term is marked `PURGED` **first**, in the same transaction, and `retention_purge_after` is
recorded if it was empty. Once that commits, `ClassroomAuthorizationService` sees a non-ACTIVE term
and refuses new writes. The term row is locked (`FOR UPDATE`) during the purge, so two runs cannot
overlap on it. If any step fails, the whole term rolls back, stays eligible, and is retried on the
next run. Other terms are not affected.

**Late writes.** A request that passed its term check just before the term was marked can still
insert a row. Each run therefore sweeps rows whose term is already `PURGED` and deletes them, so
nothing survives retention because of that window.

## Deliberately kept

- **Student-owned data:** Reading Buddy and character chats, and quiz attempts, including attempts
  on the term's assignments. Kevin decided 2026-09-18 that term purge does not touch chats; students
  delete their own data by deleting their account (`account-deletion.md`). Quiz attempts follow the
  same rule; see the follow-up below.
- **Teacher content and roles:** assignments, quizzes, overrides, invite links, feature settings,
  and class role memberships. These are not student records.
- The term row, now `PURGED`, and the users themselves.

## Compliance rows

Each run also deletes `education_record_access_logs` rows whose `retain_until` has passed, and
`chat_export_jobs` rows whose `expires_at` has passed. Export records get `expires_at` when created
(`created_at + classroom.ferpa.access-log-retain-days`); rows written before that behaviour existed
have no `expires_at` and fall back to the same age rule. Access logs are never deleted before their
`retain_until`.

## Not done

- Nothing ends terms or sets `retention_purge_after` yet. The term-rollover flow in the data model
  is unbuilt; until it exists, `end_date` drives eligibility.
- Rows are deleted directly, not soft-deleted first. The retention period itself is the grace
  period.
- Follow-up decision: whether quiz attempts on a purged term's assignments should also be purged,
  since they are grade-like class records.
- No batching. Each term is one transaction, which is fine at pilot volumes.
