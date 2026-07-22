# My Chats v1 Behavior and Data Contract

Last updated: 2026-07-22
Status: Implemented on `main` (My Chats PR #78; cross-device synchronization PR #82)
Backlog: `BL-032`, `BL-039`, `BL-049`

Implementation note: the shipped authenticated experience follows this contract. The final BL-049 client intentionally discards legacy account-less localStorage transcripts instead of claiming them, because those transcripts cannot be safely attributed to an account.

## Purpose

Define the signed-in reader experience and API contract for **My Chats**. The same account-owned, server-persisted data powers:

- a compact recent-chats section on the signed-in landing page; and
- the dedicated My Chats page where readers search, filter, browse, and resume conversations.

This specification covers character chats only. Reading Buddy and chapter-recap conversations are separate products and do not appear in My Chats v1.

## Product decisions

1. The product name is **My Chats** everywhere. Do not use “Chat Hub,” “Recent Conversations,” or “Chat History” as navigation or page labels.
2. My Chats and durable character chat are available only to authenticated reader accounts. Anonymous readers do not see a My Chats surface and cannot load or continue an account-owned transcript.
3. A v1 session is the single durable conversation for one `(userId, bookId, characterId)` tuple. Reopening that character resumes the same session; v1 does not create multiple named threads with the same character.
4. A session appears only after at least one user message has been durably accepted. Opening an empty composer does not create a list item.
5. Sessions are private to their owning user. Classroom membership does not grant teachers, classmates, or administrators access through these endpoints.
6. Default ordering is most recently active first, using `lastMessageAt DESC`, then `sessionId ASC` as the deterministic tie-break.
7. The landing page shows at most 4 sessions and links to the dedicated page. The dedicated page uses cursor pagination with a default of 20 and maximum of 50 sessions per request.
8. Resuming opens the dedicated full-page conversation, not the in-reader modal. The reader can follow a separate “Open book” action from the conversation.
9. Server history is authoritative for signed-in users. Device-local history is not merged implicitly on every read; BL-049 migration/claim must be explicit and idempotent.

## Scope and non-goals

### In scope

- account-owned character-chat session summaries and messages;
- landing recent-chats section near Achievements;
- dedicated My Chats list and full-page conversation route;
- deterministic search, filters, sorting, and cursor pagination;
- resume behavior and spoiler-context display;
- authorization, privacy, major UI states, and API errors.

### Not in v1

- multiple threads with the same book character;
- user-created session titles, pinning, folders, tags, sharing, or deletion/archive UI;
- teacher access or bulk classroom export;
- semantic/vector search;
- Reading Buddy, recap chat, or voice-call-only history;
- anonymous My Chats or a localStorage-only My Chats page.

## Session inclusion and lifecycle

A listable session must satisfy all of the following:

- `ownerUserId` equals the authenticated account;
- the session has at least one persisted message with `role = USER`;
- the session is not deleted;
- its book and character records still exist.

Feature availability does not erase history. If character chat is temporarily disabled globally, for the book, or by classroom policy:

- the session remains visible and readable;
- `resume.available` is `false`;
- the UI explains why sending is unavailable; and
- no continuation request may append a message until the applicable policy permits it.

If a book or character becomes unavailable without being deleted, return the stored identity snapshot and `resume.available = false`. Hard-deleted parent records should be prevented while owned sessions exist or handled by a tombstone/snapshot strategy; list queries must never expose another user's data or fail an entire page because one catalog record is missing.

### Session activity timestamps

- `createdAt`: time the first user message was accepted.
- `lastMessageAt`: time of the newest persisted user or character message.
- `updatedAt`: time any session metadata changed; not used for default ordering.
- Failed sends, loading placeholders, and synthetic client fallbacks do not update `lastMessageAt`.

## Landing-page behavior

The signed-in landing page places **My Chats** near Achievements.

- Request `GET /api/account/chats?limit=4`.
- Show up to 4 cards in API order.
- Each card shows portrait, character name, book title, preview text, and relative `lastMessageAt`.
- Selecting a card follows `resume.url`.
- “View all” opens `/my-chats` and is shown whenever at least one session exists.
- Do not paginate, search, or filter within the landing section.
- A session without a portrait uses the standard character placeholder; portrait failure must not hide the card.

### Landing states

- **Signed out:** omit the entire section. Do not show an empty-state registration advertisement in v1.
- **Loading:** show the section heading and up to 4 stable-size skeleton cards; preserve surrounding layout.
- **Empty:** show “Your character conversations will appear here after you start chatting.” and a “Find a character” action that opens the Library.
- **Error:** show “My Chats couldn’t load.” with Retry. Other landing modules remain usable.
- **Loaded:** replace skeletons atomically; do not briefly render the empty state.

## Dedicated My Chats page

Route: `/my-chats`

The page requires an authenticated account. If account status is not yet known, render the loading shell. If unauthenticated, show the existing sign-in flow and preserve `/my-chats` as the safe same-origin return target.

### List controls

- Search input label: “Search My Chats”.
- Search runs against character name, book title, book author, and message text owned by the current user.
- Search is case-insensitive and whitespace-normalized.
- Debounce client requests by 250–400 ms; Enter submits immediately.
- Filters:
  - `bookId`: one exact book;
  - `characterId`: one exact character;
  - `activeAfter`: sessions whose `lastMessageAt` is on or after the instant;
  - `activeBefore`: sessions whose `lastMessageAt` is before the instant.
- Character choices are constrained by the selected book when `bookId` is present.
- Filter choices are alphabetized by their visible label. Searchable selectors use accessible combobox/listbox semantics and an explicit no-results state per repository UI rules.
- “Clear filters” clears search and all filters, resets pagination, and restores default ordering.
- v1 exposes only the default newest-first sort. The API reserves `sort=recent`; unsupported values return `400` rather than silently changing behavior.

Changing search, filters, or sort discards the current cursor and starts from the first page. “Load more” appends results while preserving focus and scroll position.

### Dedicated-page states

- **Initial loading:** heading and controls remain visible; show 6 list-row skeletons.
- **Loading more:** keep existing rows and place an inline spinner in the disabled “Load more” button.
- **Empty account:** show “You haven’t started any character chats yet.” and “Find a character.”
- **No results:** show “No chats match your search and filters.” and “Clear filters.”
- **Initial error:** show “My Chats couldn’t load.” with Retry; retain entered controls.
- **Load-more error:** retain existing rows and show an inline retry action. Never replace loaded rows with a full-page error.
- **Session removed between pages:** omit it; cursor pagination must not duplicate adjacent items.

## Exact resume behavior

A card's primary action follows the server-provided relative `resume.url`, currently `/my-chats?session={sessionId}`. Clients must not construct a URL from character or book IDs.

On the conversation route:

1. Fetch `GET /api/account/chats/{sessionId}`.
2. Verify the response belongs to the active account through normal server authorization; ownership is never inferred client-side.
3. Render the stored messages oldest first.
4. Restore the persisted spoiler boundary (`context.chapterIndex` and `context.paragraphIndex`) and display its chapter label before sending is enabled.
5. Compare that boundary with current account-backed reading progress for the same book:
   - if current progress is later, offer “Update chat to my current reading position”;
   - do not silently advance the boundary, because doing so changes future prompt context;
   - never move the boundary backward.
6. Focus the composer only after history and availability are loaded. On small screens, do not force the viewport past the conversation heading.
7. A successful send appends the persisted user message and persisted character response returned by the write endpoint, updates `lastMessageAt`, and moves the session to the top on subsequent list loads.
8. If the character reply fails after the user message is accepted, retain the accepted user message and show a retryable failed-turn state. Retrying must use the same idempotency key and must not duplicate the user message.
9. Browser Back returns to the prior My Chats list state when available. The client may preserve list query/cursor/scroll in history state; the API contract does not encode this state in `resume.url`.

Secondary actions:

- **Open book:** use `/?book={bookId}&chapter={context.chapterId}&paragraph={context.paragraphIndex}` when the chapter still exists. This action opens the exact stored context and must not substitute generic resume-reading progress.
- **Download:** reuse the existing Markdown transcript behavior, using server-fetched messages. Empty sessions cannot occur in the list contract.

## API contract

All timestamps are UTC ISO-8601 instants (for example, `2026-07-21T23:14:35Z`). IDs are opaque strings. All endpoints below require the account session cookie and return `Cache-Control: private, no-store`.

### List sessions

`GET /api/account/chats`

Query parameters:

| Parameter | Type | Default | Rules |
| --- | --- | --- | --- |
| `limit` | integer | `20` | `1..50`; landing uses `4` |
| `cursor` | string | none | Opaque server-issued cursor; cannot be reused with changed filters |
| `q` | string | none | Trimmed, max 100 characters; character/book/author/message text |
| `bookId` | string | none | Exact accessible book ID |
| `characterId` | string | none | Exact character ID; must belong to `bookId` when both are supplied |
| `activeAfter` | instant | none | Inclusive lower bound on `lastMessageAt` |
| `activeBefore` | instant | none | Exclusive upper bound on `lastMessageAt`; must be after `activeAfter` |
| `sort` | enum | `recent` | v1 supports only `recent` |

Example request:

```http
GET /api/account/chats?limit=20&q=elizabeth&bookId=book-1342&sort=recent
```

Response `200`:

```json
{
  "items": [
    {
      "sessionId": "chat-7d247d",
      "character": {
        "id": "character-42",
        "name": "Elizabeth Bennet",
        "portraitUrl": "/api/characters/character-42/portrait"
      },
      "book": {
        "id": "book-1342",
        "title": "Pride and Prejudice",
        "author": "Jane Austen"
      },
      "previewText": "I think first impressions can be rather misleading…",
      "previewRole": "CHARACTER",
      "messageCount": 12,
      "createdAt": "2026-07-18T01:42:10Z",
      "lastMessageAt": "2026-07-21T23:14:35Z",
      "updatedAt": "2026-07-21T23:14:35Z",
      "context": {
        "chapterId": "chapter-8",
        "chapterIndex": 7,
        "chapterTitle": "Chapter VIII",
        "paragraphIndex": 14
      },
      "resume": {
        "available": true,
        "url": "/my-chats?session=chat-7d247d",
        "unavailableReason": null
      }
    }
  ],
  "page": {
    "limit": 20,
    "nextCursor": "opaque-cursor-or-null",
    "hasMore": true
  }
}
```

Field rules:

- `previewText` is the newest nonblank persisted message, normalized to one line and truncated server-side to 160 Unicode code points without adding HTML. The client may visually clamp but must not derive a different preview from hidden history.
- `previewRole` is `USER` or `CHARACTER`.
- `messageCount` counts persisted user and character messages, not failed/loading placeholders.
- `portraitUrl` may be `null`.
- `resume.unavailableReason` is `null` when available; otherwise one of `CHAT_DISABLED`, `BOOK_DISABLED`, `CLASSROOM_POLICY`, `CHARACTER_UNAVAILABLE`, or `BOOK_UNAVAILABLE`.
- Search matches do not alter the preview or reveal match snippets in v1.

Cursor ordering encodes `(lastMessageAt DESC, sessionId ASC)` plus a fingerprint of the normalized filters. Cursors are opaque, URL-safe, and must not contain readable user or message data. A mismatched, malformed, or expired cursor returns `400 INVALID_CURSOR`.

### Get one session and messages

`GET /api/account/chats/{sessionId}`

Response `200`:

```json
{
  "session": {
    "sessionId": "chat-7d247d",
    "character": {
      "id": "character-42",
      "name": "Elizabeth Bennet",
      "portraitUrl": "/api/characters/character-42/portrait"
    },
    "book": {
      "id": "book-1342",
      "title": "Pride and Prejudice",
      "author": "Jane Austen"
    },
    "createdAt": "2026-07-18T01:42:10Z",
    "lastMessageAt": "2026-07-21T23:14:35Z",
    "context": {
      "chapterId": "chapter-8",
      "chapterIndex": 7,
      "chapterTitle": "Chapter VIII",
      "paragraphIndex": 14
    },
    "resume": {
      "available": true,
      "url": "/my-chats?session=chat-7d247d",
      "unavailableReason": null
    }
  },
  "messages": [
    {
      "messageId": "message-a1",
      "role": "USER",
      "content": "Were your first impressions of Darcy fair?",
      "createdAt": "2026-07-21T23:14:21Z"
    },
    {
      "messageId": "message-a2",
      "role": "CHARACTER",
      "content": "I think first impressions can be rather misleading…",
      "createdAt": "2026-07-21T23:14:35Z"
    }
  ]
}
```

v1 caps a session at the server retention limit. The endpoint returns the complete retained transcript oldest first; it does not silently page only the newest messages. If retention later exceeds a practical response size, introduce explicit message pagination in a versioned follow-up rather than changing this response silently.

### Continue a session

`POST /api/account/chats/{sessionId}/messages`

Headers:

```http
Content-Type: application/json
Idempotency-Key: 78d96f8d-6d4a-46c8-a772-8c76a62da2bb
```

Request:

```json
{
  "content": "Would you trust him now?",
  "context": {
    "chapterId": "chapter-10",
    "chapterIndex": 9,
    "paragraphIndex": 3
  }
}
```

Rules:

- `content` is trimmed, nonblank, and at most the same server-defined character limit as the existing character-chat endpoint.
- `context` may equal the stored boundary or advance it to verified account reading progress for this book; it may not move backward or exceed verified progress.
- The idempotency key is required and scoped to `(userId, sessionId)`. Replays return the original successful result.
- The server builds model context from persisted messages only. Client-supplied conversation history is not accepted.

Response `200`:

```json
{
  "sessionId": "chat-7d247d",
  "userMessage": {
    "messageId": "message-a3",
    "role": "USER",
    "content": "Would you trust him now?",
    "createdAt": "2026-07-21T23:20:01Z"
  },
  "characterMessage": {
    "messageId": "message-a4",
    "role": "CHARACTER",
    "content": "Trust is not restored in an instant…",
    "createdAt": "2026-07-21T23:20:05Z"
  },
  "lastMessageAt": "2026-07-21T23:20:05Z",
  "context": {
    "chapterId": "chapter-10",
    "chapterIndex": 9,
    "chapterTitle": "Chapter X",
    "paragraphIndex": 3
  }
}
```

Creating the first session remains part of the character-chat persistence work in BL-049. It must upsert by `(userId, bookId, characterId)` and return the same session/detail shapes so in-reader and full-page chat converge on one durable thread.

### Error envelope and status codes

Error response:

```json
{
  "error": {
    "code": "INVALID_CURSOR",
    "message": "The page cursor is invalid for this request."
  }
}
```

- `400`: invalid parameter, range, timestamp, context, or cursor.
- `401`: no valid account session. The browser may open sign-in; APIs do not fall back to anonymous `readerId`.
- `404`: session not found **or not owned by the caller**. Do not return `403` for another user's session, because that reveals existence.
- `409`: idempotency-key conflict or context moved backward/stale in a way that cannot be applied.
- `429`: rate limit exceeded; include `Retry-After`.
- `503`: chat generation unavailable. A detail/list read should remain available when generation is down.

## Authorization and privacy constraints

- Every repository query includes `ownerUserId = authenticatedUserId`; never fetch by `sessionId` and authorize only after serialization.
- Account endpoints must not use anonymous `readerId` fallback from `ReaderIdentityService`.
- User-controlled `q` is parameterized and scoped to the owner before full-text matching.
- Responses contain no email, classroom membership, teacher identifiers, raw model prompts, token usage, or internal provider metadata.
- Session IDs and cursors are opaque and non-sequential.
- Another user's session and a nonexistent session are indistinguishable (`404`).
- Logs may include session ID and non-PII user ID, but not message content or search text.
- Teacher visibility/export is a separate FERPA-gated contract under BL-025.7/BL-043 and must not reuse these account endpoints with elevated-role bypasses.
- Account deletion follows the approved BL-021 policy: sessions/messages are account-owned reader data and are hard-deleted from the primary database within 24 hours; backup expiry remains up to 30 days.
- HTML is never accepted or returned as trusted markup. Clients render `previewText` and `content` as text.

## Persistence requirements for BL-049

The implementation may choose table names, but it must enforce these logical constraints:

- session ownership and unique key `(owner_user_id, book_id, character_id)`;
- message ownership through a required session foreign key;
- immutable message ID, role, content, and creation timestamp;
- indexes supporting owner-scoped recent ordering and owner-scoped filters;
- an owner-scoped text-search strategy for character/book/author/message content;
- transactional write of successful turns and deterministic idempotency records;
- stored character/book identity snapshots sufficient to render unavailable history safely;
- persisted spoiler boundary validated against account-backed reading progress.

The legacy localStorage key (`reader_characterChat_{bookId}_{characterId}`) and 50-message device limit are implementation history, not the server contract. The shipped client discards those account-less transcripts after a successful server load rather than importing them, because ownership cannot be established safely.

## Accessibility and responsive behavior

- “My Chats,” search, filters, list, errors, and loading status use semantic headings/labels and announced status regions.
- Every card is reachable and resumable by keyboard; the card does not contain conflicting nested interactive elements.
- Relative times have an accessible absolute timestamp.
- Focus moves to the conversation heading after route navigation, except the composer focus behavior described above.
- Skeletons are hidden from assistive technology; loading status is announced once.
- Search/filter updates announce the result count without moving focus.
- Mobile list rows keep character name, book title, and primary resume action visible without horizontal scrolling.

## Acceptance test matrix

### API

- An authenticated user sees only sessions they own.
- Another user's and nonexistent session IDs both return `404`.
- Anonymous list/detail/write requests return `401` and do not create reader cookies as ownership fallback.
- Default ordering and the session-ID tie-break are deterministic.
- Limits `1`, `4`, `20`, and `50` work; `0` and `51` return `400`.
- Cursor pagination has no duplicates across adjacent pages and rejects changed-filter reuse.
- Search covers character, book title, author, and owned message content without cross-user leakage.
- All filters and time-boundary inclusivity/exclusivity behave as documented.
- Preview normalization, truncation, role, and message count are server-derived.
- Read endpoints remain usable when generation is disabled or unavailable.
- Resume context cannot move backward or beyond verified progress.
- Idempotent send retry does not duplicate the user or character message.

### UI

- Signed-out landing omits My Chats; signed-in landing requests 4 and shows at most 4.
- Landing loading, empty, error/retry, and loaded states match this specification.
- Dedicated page distinguishes empty-account from no-results states.
- Search/filter changes reset pagination; load-more errors retain existing rows.
- Keyboard and assistive-technology users can operate search, filters, cards, retry, and load more.
- Resume loads the exact owned session and stored spoiler boundary.
- A later reading position is offered, never silently adopted; an earlier position cannot replace the boundary.
- “Open book” targets the stored chapter/paragraph rather than generic reading resume.
- Chat-disabled sessions remain readable and explain why sending is unavailable.

## Implementation sequence

1. BL-049 persistence schema, owner-scoped repository/service, local-message migration decision, and durable in-reader writes.
2. List/detail/continue account APIs and authorization tests.
3. Dedicated `/my-chats` list and conversation route using server contracts.
4. Landing recent-chats section using `limit=4` and the same summary DTO.
5. Cross-device, accessibility, failure-state, and privacy regression tests.

Do not ship the landing section against localStorage as if it were complete My Chats. If a temporary demo bridge is required, label it device-only and remove it when BL-049 lands.
