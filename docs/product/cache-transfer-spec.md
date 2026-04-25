# Cache Transfer Spec (v1)

Last updated: 2026-02-12

## Purpose

Provide a safe export/import workflow for AI-generated cache data between diverged environments (local/server), covering chapter recaps/quizzes and extending to illustrations/portraits.

## Goals

- Support export/import at **book level**.
- Support exporting **all books with cached data**.
- Use **JSON** for transfer metadata and structured payloads.
- Support import conflict handling (`skip`, `overwrite`).
- Keep imports idempotent and auditable with dry-run mode.

## Non-Goals (v1)

- Paragraph-level or chapter-selection export controls.
- Cross-provider payload transformations.
- Automatic book import from Gutenberg during transfer.

## Identity and Matching

Records are matched by stable logical keys, not local UUIDs.

- Book key: `source` + `sourceId` (example: `gutenberg` + `1342`).
- Chapter key: `chapterIndex` within matched book.
- Recap key: `(book key, chapterIndex)`.
- Quiz key: `(book key, chapterIndex)`.

If a source system has a book without `sourceId`, that book is skipped and reported.

## CLI Surface

Runner class: `com.classicchatreader.cli.CacheTransferRunner`

Implementation status (2026-02-14):
- v1 recap + quiz + illustration + portrait metadata transfer is implemented and validated in local + remote flows.
- Operator automation scripts are available for pregen/export/import and remote transfer/deploy orchestration.

Commands:

- `export`
- `import`

Core options:

- `--feature recaps|quizzes|illustrations|portraits` (required)
- `--book-source-id <id>[,<id>...]` (optional)
- `--all-cached` (optional; mutually exclusive with `--book-source-id`)
- `--input <path>` (import)
- `--output <path>` (export)
- `--apply` (default is dry-run)
- `--on-conflict skip|overwrite` (import; default `skip`)

Future options (v2+):

- `--include-assets` for binary media export/import.
- `--assets-root <path>` for unpacked bundle workflows.

## Export Behavior (v1)

- Query selected feature table where `status = COMPLETED`.
- If `--book-source-id` is provided, export only those books.
- If `--all-cached` is provided, export all books that have at least one completed record for the selected feature.
- Write one JSON file containing:
  - format metadata,
  - export timestamp,
  - feature list,
  - per-book feature records.
- Dry-run prints counts only; no file written.

## Import Behavior (v1)

- Read JSON payload and validate format/version.
- For each book entry:
  - Find local book by `source + sourceId`.
  - If missing, skip and report.
- For each selected feature entry:
  - Recaps/quizzes/illustrations: find chapter by `bookId + chapterIndex`, skip if missing.
  - Portraits: find first chapter by `bookId + firstChapterIndex`, skip if missing.
  - Upsert selected feature rows by logical key:
    - recap/quiz/illustration: chapter key
    - portrait: `(bookId, characterName)`
- Conflict policy:
  - `skip`: keep existing row if present.
  - `overwrite`: replace existing payload/status/metadata.
- Dry-run computes planned inserts/updates/skips without mutating DB.

## Transfer JSON Format (v1 excerpt)

```json
{
  "formatVersion": "1.0",
  "exportedAt": "2026-02-11T21:30:00Z",
  "features": ["recaps"],
  "books": [
    {
      "source": "gutenberg",
      "sourceId": "1342",
      "title": "Pride and Prejudice",
      "author": "Jane Austen",
      "recaps": [
        {
          "chapterIndex": 0,
          "status": "COMPLETED",
          "promptVersion": "v1",
          "modelName": "qwen2.5:32b",
          "generatedAt": "2026-02-11T18:09:25Z",
          "updatedAt": "2026-02-11T18:09:25Z",
          "payloadJson": "{\"shortSummary\":\"...\",\"keyEvents\":[\"...\"],\"characterDeltas\":[...]}"
        }
      ]
    }
  ]
}
```

Notes:

- `payloadJson` is preserved as-is from the selected source table payload (`chapter_recaps.payload_json` or `chapter_quizzes.payload_json`).
- `status` is expected to be `COMPLETED` in v1 exports.
- For quiz transfer, each book uses a `quizzes` array with the same per-chapter metadata shape as `recaps`.
- For illustration transfer, each book uses an `illustrations` array keyed by `chapterIndex` with `imageFilename`.
- For portrait transfer, each book uses a `portraits` array keyed by character identity (`name`) + first appearance location.

## Reporting

Both export and import print a summary:

- books scanned, books matched, books missing,
- feature records exported/imported,
- feature records skipped (conflict),
- chapters missing,
- validation errors.

Import exits non-zero on invalid format; data-level misses/conflicts remain non-fatal and are summarized.

## Safety and Idempotency

- Default mode is dry-run.
- `--apply` is required for writes.
- Re-running import with `--on-conflict skip` is idempotent.
- Re-running import with `--on-conflict overwrite` is deterministic.

## Extension Plan (v2+)

- Add `illustrations` and `portraits` modules using same book matching contract.
- Add asset bundle layout:
  - `bundle.json` (metadata + logical records),
  - `assets/...` (binary files),
  - optional zip packaging for transport.
- Add `--feature all` to include recaps + image references + copied asset files.

## Acceptance Criteria (v1)

- Export produces valid JSON for recaps/quizzes by selected books or `--all-cached`.
- Import merges into a diverged DB using `source/sourceId + chapterIndex`.
- Import honors `skip` and `overwrite` conflict modes.
- Dry-run accurately reports planned actions.
- Round-trip test passes for at least one pre-generated book and one multi-book export.
