# Studio roster transfer

`RosterTransfer` is a roster-only JDBC transaction shared by the local operator API
and `com.classicchatreader.cli.RosterTransferRunner`. It does not call generation,
CacheTransferRunner portrait import, or file/Spaces operations.

## API

- `GET /api/studio/roster/{source}/{sourceId}`: complete roster, valid chapter/paragraph
  positions, per-character conversation/message counts, and a revision token.
- `POST /api/studio/roster/{source}/{sourceId}/replace`: exact desired roster and explicit
  removals, validated and committed in one transaction; returns the resulting snapshot.

These endpoints require a direct loopback connection and a loopback Host. Forwarded
requests (`Forwarded`, `X-Forwarded-For`, `X-Real-IP`) and foreign browser Origins are
refused. `SensitiveApiRequestMatcher` classifies GET/POST `/api/studio/roster/**` as
ADMIN so public mode still requires an operator API key. They do not add startup feature
flags. Production administration uses the CLI with the operator's database connection.

Replacement JSON:

```json
{
  "source": "gutenberg",
  "sourceId": "17396",
  "expectedRevision": "revision-from-export",
  "rows": [
    {"id": "existing-destination-id", "name": "Reviewed name", "characterType": "PRIMARY",
     "firstChapterIndex": 0, "firstParagraphIndex": 0, "description": null},
    {"id": null, "name": "New character", "characterType": "SECONDARY",
     "firstChapterIndex": 0, "firstParagraphIndex": 0, "description": null}
  ],
  "removeIds": ["explicit-destination-id-to-remove"],
  "confirm": true
}
```

All retained destination IDs must be listed. A null ID creates a new character. Omitted
characters must appear exactly once in `removeIds`. No fuzzy/name matching occurs in the
engine; Studio owns destination mapping and the plan. The destination book and all row
placements must exist. Empty rosters, duplicate names/IDs, foreign IDs, stale revisions,
and PENDING/GENERATING portraits are rejected before mutation. The revision token hashes
roster identity and placements only; live conversation/message counts stay on the export
as advisory consequence data and do not stale confirm.

Renames preserve IDs and image fields. SECONDARY rows lose call voice/provider. Removed
characters cascade saved conversations and messages through existing constraints; the
export exposes those counts so Studio can show the consequence before Confirm. No image
files are deleted. New rows are metadata-only COMPLETED characters without portraits;
explicit portrait generation remains available. Retained FAILED rows are reset to
COMPLETED with lease/error/retry cleared so confirm cannot leave a stuck prefetch latch.
After mutation the engine verifies retained IDs/names/types/placements and removals
before commit. Primary-prefetch is marked complete only when every remaining character
is COMPLETED.

## CLI

Both commands require `--source`, `--source-id`, and `--output`:

```
export --source gutenberg --source-id 17396 --output roster.json
replace --source gutenberg --source-id 17396 --input reviewed-plan.json --output receipt.json --apply
```

Connection: `PDR_DATABASE_URL`, `PDR_DATABASE_USERNAME`, and `PDR_DATABASE_PASSWORD`, or
explicit `--db-url`, `--db-user`, `--db-password`. Studio passes credentials in the child
environment. Explicit URLs do not inherit unrelated ambient usernames/passwords.
No Spaces credentials are used. Exit 2 means a stale/busy plan; other nonzero exits do
not provide a success receipt. Re-export after an uncertain failure rather than replaying
blindly: database commit and filesystem receipt delivery cannot be atomic together.

Each destination transaction is independent. Studio records local and production receipts
separately for Both and does not claim global success after partial failure.

## Validation

Tests cover complete export, rename and name swaps, demotion, explicit removals and chat
cascade, ownership and placement validation, rollback after SQL failure, competing/stale
plans, local API access boundaries, and the CLI round trip. No running CCR or production
DB is used by these tests.
