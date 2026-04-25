# Discover Affinity Model (BL-018.5)

Last updated: 2026-02-17

## Scope
- Defines deterministic ranking and explanation rules for the landing `Discover` rail.
- Applies when the library search box is empty.
- Search mode (`/api/import/search`) remains query-driven and does not inject affinity explanations.

## Inputs
- Reader intent signals from local books:
- `favorite` state (`My List`)
- Reading state/progress (`maxProgressRatio`, `completed`)
- Recent activity (`lastReadAt`, fallback `lastOpenedAt`)
- Local genre hints from imported book `description` metadata (subject phrases)
- Catalog signals from Gutenberg import payload:
- `author`
- `subjects`
- `bookshelves`
- `downloadCount`
- `alreadyImported`

## Seed Construction
- Build affinity seeds from local books.
- Weight seeds by:
1. Favorite intent (strongest)
2. In-progress/completed state
3. Progress depth
4. Recency rank
- Keep deterministic ordering with title/id tie-breaks.

## Candidate Scoring
For each catalog candidate:
1. Apply strongest affinity match from seeded local history:
- Author exact match (`same author`) has highest score.
- Genre/subject overlap (`similar themes`) adds score.
2. Add popularity score from `downloadCount`.
3. Apply penalty for `alreadyImported` candidates so new discovery options float first.
4. Resolve ties deterministically by:
- total score
- imported state
- popularity
- title
- Gutenberg id

## Reason Text Rules
- Author match: `Because you liked <Book> (same author)`
- Genre match: `Because you liked <Book> (similar themes)`
- Sparse history fallback: `Popular with readers like you`
- Cold start fallback (no local history): `Popular with readers right now`

## Determinism + Coverage
- Ranking is deterministic for the same input set.
- Frontend tests cover cold-start, author affinity, genre affinity, and tie-break stability in:
- `/Users/kevinturner/IdeaProjects/classic-chat-reader/src/test/frontend/library-discover.test.cjs`
- Backend tests cover import payload metadata mapping (`subjects/bookshelves`) in:
- `/Users/kevinturner/IdeaProjects/classic-chat-reader/src/test/java/com/classicchatreader/service/BookImportServiceTest.java`
- `/Users/kevinturner/IdeaProjects/classic-chat-reader/src/test/java/com/classicchatreader/controller/ImportControllerTest.java`
