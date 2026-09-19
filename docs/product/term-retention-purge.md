# Term retention purge (BL-043.6)

A nightly job (`classroom.ferpa.purge-cron`, default `0 30 3 * * *`; disable with
`classroom.ferpa.purge-enabled=false`) removes a term's student education records once the term's
retention period has ended.

## When a term purges

- If `terms.retention_purge_after` is set, the term purges once that time has passed.
- Otherwise, once `end_date + classroom.ferpa.term-retain-days` has passed (default **400**, the
  data model's placeholder until BL-043.3/.13 set the legal duration). The end day itself counts
  as term time.
- Terms with **no end date never purge**. Already `PURGED` or soft-deleted terms are skipped.

Pilot terms ending December 2026 would first purge in January 2028 with the default.

## What is deleted, per term, in one transaction

- `enrollments` (the roster)
- `assignment_progress`
- `classroom_usage_events`

Then the term is marked `PURGED` and `retention_purge_after` is recorded if it was empty. If any
step fails, the whole term rolls back, stays eligible, and is retried on the next run. Other terms
are not affected.

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
`chat_export_jobs` rows older than `classroom.ferpa.access-log-retain-days`. Access logs are never
deleted before their `retain_until`.

## Not done

- Nothing ends terms or sets `retention_purge_after` yet. The term-rollover flow in the data model
  is unbuilt; until it exists, `end_date` drives eligibility.
- Rows are deleted directly, not soft-deleted first. The retention period itself is the grace
  period.
- Follow-up decision: whether quiz attempts on a purged term's assignments should also be purged,
  since they are grade-like class records.
- No batching. Each term is one transaction, which is fine at pilot volumes.
