# BL-025.10 Teacher→Student overview — demo script

Pilot drill-down for Jessica 1:1. Teacher-of-term only; not school-admin / bulk export.

## Accounts

1. Teacher with `CREATE_CLASSROOM` (or existing teacher membership): open `/teacher`.
2. At least one enrolled student on that term’s roster.

Provision teaching access if needed:

```bash
./scripts/manage_teacher_access.sh grant teacher@example.test
```

## Happy path (UI)

1. Sign in as teacher → `/teacher` → select class/term.
2. **Roster** → click a student (or **Overview**).
3. Confirm the panel shows all six sections:
   - Current assignments
   - Completed assignments
   - Progress by book (chapter n/n and %)
   - Quizzes for the book (complete, scores, retries)
   - Opened / not opened on each assignment row
   - Approximate time in reader (heartbeat total + per book)

## Seed mixed student states

Use one class with 2–3 published assignments on the same or different books.

| State | How to produce |
| --- | --- |
| **Not opened** | Publish assignment; student never clicks the Library assignment card. Teacher overview shows **Not opened**. |
| **In progress / opened** | Student opens the assignment card (marks `assignment_progress.first_opened_at`) and reads partway; optional failed quiz attempt under pass rules. |
| **Complete** | Student reaches assigned chapter (or finishes book), passes quiz when required (and character chat if required). |
| **Quiz retries** | Assignment with min correct + max retries (`BL-025.12`). Student fails once, then passes — overview shows attempts used, retries used, best score. |
| **Time in reader** | Student stays in the reader ~1+ minute while enrolled as `STUDENT` (60s heartbeat). Overview **Approximate time in reader** becomes non-zero. |

### Optional SQL checks (local)

```sql
SELECT assignment_id, user_id, first_opened_at FROM assignment_progress;
SELECT event_type, book_id, duration_ms, occurred_at
FROM classroom_usage_events
WHERE user_id = '<studentUserId>' AND event_type = 'READING_HEARTBEAT'
ORDER BY occurred_at DESC;
```

## Authz checks (quick)

- Student calling `GET /api/classroom/terms/{termId}/students/{userId}/overview` → **403**.
- Teacher of another term / non-roster student id → **403** / **404**.
- Heartbeat / opened endpoints require enrolled student on that term.

## FERPA posture (say aloud)

This is a **pilot teacher drill-down** behind class-scoped teacher membership. It is **not** the broad FERPA-gated dashboard rollout (`BL-043` / Discovery exit still apply for school-tier / bulk surfaces). No teacher chat export (`BL-025.7`) in this slice.
