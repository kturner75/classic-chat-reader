# Classroom Landing Usage (BL-018.6)

Last updated: 2026-02-17

This guide explains how to enable and use the classroom-aware landing behavior added in BL-018.6.

## What This Feature Does

- Keeps default consumer landing behavior when classroom mode is disabled.
- Switches to classroom-aware landing when enabled:
  - Shows class banner (`className`, optional `teacherName`).
  - Shows `Assignments` rail above the normal personalized sections.
  - Shows assignment chips for due date and quiz requirement status.
- Applies classroom feature-state overrides in reader UI availability checks for:
  - quiz
  - recap
  - read-aloud (TTS)
  - illustration
  - character
  - chat
  - speed reading

## Enable Classroom Demo Mode

In `/Users/kevinturner/IdeaProjects/classic-chat-reader/src/main/resources/application.properties`:

```properties
# Classroom Demo Context (BL-018.6)
classroom.demo.enabled=true
classroom.demo.class-id=lit-101
classroom.demo.class-name=Literature 101
classroom.demo.teacher-name=Ms. Rivera

classroom.demo.features.quiz-enabled=true
classroom.demo.features.recap-enabled=true
classroom.demo.features.tts-enabled=true
classroom.demo.features.illustration-enabled=true
classroom.demo.features.character-enabled=true
classroom.demo.features.chat-enabled=true
classroom.demo.features.speed-reading-enabled=true
```

Then restart the app.

## Configure Assignments

Add one or more assignments using indexed properties:

```properties
classroom.demo.assignments[0].assignment-id=assign-1
classroom.demo.assignments[0].title=Read Chapter 1
classroom.demo.assignments[0].book-id=<local-book-id>
classroom.demo.assignments[0].chapter-index=0
classroom.demo.assignments[0].due-at=2026-02-20T23:59:00Z
classroom.demo.assignments[0].quiz-required=true

classroom.demo.assignments[1].assignment-id=assign-2
classroom.demo.assignments[1].title=Read Chapter 2
classroom.demo.assignments[1].book-id=<local-book-id>
classroom.demo.assignments[1].chapter-id=<chapter-id>
classroom.demo.assignments[1].due-at=2026-02-24T23:59:00Z
classroom.demo.assignments[1].quiz-required=false
```

Notes:

- `book-id` is required for each assignment.
- Chapter resolution order:
  - `chapter-id` (if provided) is used first and must belong to the same `book-id`.
  - otherwise `chapter-index` is used (`0` = first chapter).
- If both chapter values are missing or unresolved:
  - assignment still appears
  - quiz status becomes `UNKNOWN` when `quiz-required=true`
- Use ISO-8601 timestamps with timezone for `due-at` (for example `2026-02-20T23:59:00Z`).

## Search Behavior On Landing

- Landing catalog search runs only on explicit submit:
  - press `Enter` in the library search input, or
  - click the `Search` button.
- While search is running, the UI shows a loading state and status message.
- On search failure, inline error + retry action are shown.
- While search query is non-empty, classroom and personalized rails are hidden and query-driven results are shown.

## API Contract

Endpoint:

- `GET /api/classroom/context`

Response shape:

```json
{
  "enrolled": true,
  "classId": "lit-101",
  "className": "Literature 101",
  "teacherName": "Ms. Rivera",
  "features": {
    "quizEnabled": true,
    "recapEnabled": true,
    "ttsEnabled": true,
    "illustrationEnabled": true,
    "characterEnabled": true,
    "chatEnabled": true,
    "speedReadingEnabled": true
  },
  "assignments": [
    {
      "assignmentId": "assign-1",
      "title": "Read Chapter 1",
      "bookId": "book-1",
      "bookTitle": "Treasure Island",
      "bookAuthor": "Robert Louis Stevenson",
      "chapterId": "chapter-1",
      "chapterIndex": 0,
      "chapterTitle": "Chapter One",
      "dueAt": "2026-02-20T23:59:00Z",
      "quizRequired": true,
      "quizStatus": "PENDING",
      "bookAvailable": true
    }
  ]
}
```

`quizStatus` values:

- `NOT_REQUIRED`: assignment does not require quiz.
- `PENDING`: quiz required and no attempt exists for the resolved chapter.
- `COMPLETE`: quiz required and an attempt exists for the resolved chapter.
- `UNKNOWN`: quiz required but chapter was not resolved.

When `classroom.demo.enabled=false`, response returns `enrolled=false` and empty assignments.
