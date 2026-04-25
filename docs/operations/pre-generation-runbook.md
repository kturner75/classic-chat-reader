# Pre-Generation And Transfer Runbook

Last updated: 2026-02-14

## Scope

This runbook covers:
- Pre-generating illustrations, portraits, and recaps.
- Pre-generating chapter quizzes.
- Syncing binary assets to Spaces/CDN.
- Transferring recap/quiz/illustration/portrait cache metadata from local to remote DB.

## Prerequisites

- App deployed/running for API-driven scripts (`http://localhost:8080` by default).
- `curl` installed.
- `jq` installed (needed for pre-generation/quiz polling scripts).
- For remote transfer:
  - SSH access to target host.
  - Remote host has either:
    - Maven (`mvn`) in PATH, or
    - Java + deployed app jar (`classic-chat-reader-1.0-SNAPSHOT.jar`).

## Production Safety Flags

Recommended baseline when running a public server that should not generate new artifacts on demand:

- `generation.cache-only=true`
- `tts.cache-only=true` (if you also want TTS to be cache-only)
- `ai.chat.enabled=true` (optional; allows character/recap chat while generation remains blocked)

Behavior summary:
- Artifact generation endpoints return conflict when `generation.cache-only=true`.
- Cached recap/quiz/image/audio reads continue to work.
- Character chat and recap chat are still available when `ai.chat.enabled=true` and the configured chat provider is available.

## 1) Deploy Latest Code

```bash
scripts/deploy_remote.sh --ssh-target pdr --ssh-key ~/.ssh/kevin
```

## 2) Pre-Generate Illustrations + Portraits + Recaps (Book-Level)

```bash
scripts/pregen_transfer_book.sh --gutenberg-id 1342 --skip-export
```

Notes:
- This starts `POST /api/pregen/jobs/gutenberg/{id}` and polls `GET /api/pregen/jobs/{jobId}` until completion.
- Cancel safely with `POST /api/pregen/jobs/{jobId}/cancel` (the script does this automatically on Ctrl+C/timeout).
- `--skip-export` keeps this step focused on generation only.

## 3) Pre-Generate Quizzes (Book-Level)

Get book id first:

```bash
curl -s http://localhost:8080/api/library | jq '.[] | {id,title,author}'
```

Then pre-generate quizzes for all chapters in that book:

```bash
scripts/pregen_quizzes_book.sh --book-id <book-id>
```

## 3b) Pre-Generate Quizzes (Top 20 Gutenberg Set)

Run against local app:

```bash
scripts/pregen_quizzes_top20.sh --api-base-url http://localhost:8080
```

Run directly against server app (recommended when your goal is server-side cache):

```bash
ssh pdr 'cd /opt/classic-chat-reader && ./scripts/pregen_quizzes_top20.sh --api-base-url http://localhost:8080'
```

## 4) Sync Assets To Spaces/CDN

```bash
SYNC_DIRECTION=up scripts/sync_spaces.sh
```

This syncs:
- `audio/**`
- `illustrations/**`
- `character-portraits/**`

## 5) Transfer Cache Local -> Remote

Full metadata set (recommended):

```bash
scripts/transfer_recaps_remote.sh \
  --feature all \
  --all-cached \
  --remote pdr \
  --remote-project-dir /opt/classic-chat-reader \
  --remote-db-url "jdbc:h2:file:/var/lib/classic-chat-reader/library;DB_CLOSE_DELAY=-1" \
  --apply-import \
  --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \
  --remote-start-cmd "sudo systemctl start classic-chat-reader"
```

Single-feature examples:

Recaps:

```bash
scripts/transfer_recaps_remote.sh \
  --feature recaps \
  --all-cached \
  --remote pdr \
  --remote-project-dir /opt/classic-chat-reader \
  --remote-db-url "jdbc:h2:file:/var/lib/classic-chat-reader/library;DB_CLOSE_DELAY=-1" \
  --apply-import \
  --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \
  --remote-start-cmd "sudo systemctl start classic-chat-reader"
```

Quizzes:

```bash
scripts/transfer_recaps_remote.sh \
  --feature quizzes \
  --all-cached \
  --remote pdr \
  --remote-project-dir /opt/classic-chat-reader \
  --remote-db-url "jdbc:h2:file:/var/lib/classic-chat-reader/library;DB_CLOSE_DELAY=-1" \
  --apply-import \
  --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \
  --remote-start-cmd "sudo systemctl start classic-chat-reader"
```

Illustrations:

```bash
scripts/transfer_recaps_remote.sh \
  --feature illustrations \
  --all-cached \
  --remote pdr \
  --remote-project-dir /opt/classic-chat-reader \
  --remote-db-url "jdbc:h2:file:/var/lib/classic-chat-reader/library;DB_CLOSE_DELAY=-1" \
  --apply-import \
  --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \
  --remote-start-cmd "sudo systemctl start classic-chat-reader"
```

Portraits:

```bash
scripts/transfer_recaps_remote.sh \
  --feature portraits \
  --all-cached \
  --remote pdr \
  --remote-project-dir /opt/classic-chat-reader \
  --remote-db-url "jdbc:h2:file:/var/lib/classic-chat-reader/library;DB_CLOSE_DELAY=-1" \
  --apply-import \
  --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \
  --remote-start-cmd "sudo systemctl start classic-chat-reader"
```

## 5b) Simplified Book Promotion (Single Command)

```bash
scripts/publish_book_remote.sh \
  --gutenberg-id 1342 \
  --remote pdr \
  --remote-project-dir /opt/classic-chat-reader \
  --remote-db-url "jdbc:h2:file:/var/lib/classic-chat-reader/library;DB_CLOSE_DELAY=-1" \
  --remote-stop-cmd "sudo systemctl stop classic-chat-reader" \
  --remote-start-cmd "sudo systemctl start classic-chat-reader"
```

What this runs by default:
- pre-generation job (illustrations + portraits + recaps),
- quiz pre-generation for all chapters in that book,
- Spaces sync for binary assets,
- remote DB transfer for metadata (`--transfer-feature all`).

## 6) Validate

Check recap/quiz feature status:

```bash
curl -s http://localhost:8080/api/recaps/status | jq
curl -s http://localhost:8080/api/quizzes/status | jq
```

Spot-check a book:

```bash
curl -s http://localhost:8080/api/library | jq '.[] | {id,title,author,chapters: (.chapters | length)}'
```

## Notes On Scope

- Cache transfer CLI supports recap and quiz metadata (`chapter_recaps`, `chapter_quizzes`).
- Asset binaries are distributed through Spaces sync, not recap transfer JSON.
