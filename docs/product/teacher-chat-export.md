# Teacher chat export (BL-043.7 / BL-025.7)

`POST /api/classroom/terms/{termId}/students/{userId}/chat-export?format=json|txt`

Returns a download of one student's **Reading Buddy** messages for one term.

## Scope

- **Reading Buddy only.** This follows the data model's exportable inventory.
- **Character chats are excluded.** My Chats promises students that their character chat sessions
  are private to them and that classroom membership gives teachers no access
  (`my-chats-spec.md` decision 5). Exporting them to teachers needs a product decision, and the
  student-facing wording would have to change first.
- Recap discussion chat is local-only and cannot be exported.
- Messages have no `term_id`, so the term is approximated by its calendar dates: from
  `start_date 00:00` inclusive to the day after `end_date` exclusive. A null date leaves that side open.

## Who may export

- The requester must be an **active teacher or co-teacher on a live term** (`canManageTerm`).
  Exports close when the term ends, which is the conservative reading of the data model.
- The student must have a non-deleted enrollment in that term, in **any status**. A dropped or
  completed student stays exportable ("has or had enrollment"). Deleted enrollments do not.
- Teachers cannot export themselves through this endpoint, and school admins are denied (KD-16).
- Student self-export is not built yet.

## What happens on each export

1. Authorization and validation happen first. Bad format is 400, not signed in is 401, not a
   teacher of the term is 403, student not on the roster is 404, and more than 20,000 messages
   is 413. None of these write anything.
2. A `chat_export_jobs` row is written with status `READY`, format, source `READING_BUDDY`, and
   the term window. Exports are synchronous and streamed, so **no export file is stored**
   (`artifact_storage_key` stays null). That avoids a second copy of student records needing
   its own retention.
3. An `EXPORT_CHAT` access-log row pointing at the job is written, **fail-closed**. If the audit
   write fails, the job row rolls back and nothing is returned.
4. The file is returned as an attachment with `Cache-Control: no-store` and `X-Chat-Export-Id`.

## Formats

- **JSON:** export metadata, then messages with `createdAt`, `bookId`, `bookTitle`, `persona`,
  `role`, `kind`, `chapterIndex`, and `content`, ordered by time and chronology sequence.
- **TXT:** a readable transcript, one line per message.

Both state that character chats are not included.

## Open follow-ups

- Student self-export (FERPA right to inspect), likely from the account area without an access
  log row.
- A teacher UI button on the student overview. This PR adds only the API.
- A product decision on character-chat export, including notice to students.
- Retention of `chat_export_jobs` rows is part of BL-043.6.
