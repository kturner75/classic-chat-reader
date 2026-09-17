# Studio style transfer

`StyleTransfer` is a book-art-style-only JDBC transaction used by
`com.classicchatreader.cli.StyleTransferRunner` so Studio can ship a reviewed style to
production. It touches only the six `books.illustration_*` style columns: style, prompt
prefix, setting, reasoning, cover subject, and cover focus. It does not regenerate images,
change generation queues, characters, or files, or use Spaces.

Local Studio applies keep using `PUT /api/illustrations/settings/{bookId}`.

## CLI

Both commands require `--source`, `--source-id`, and `--output`:

```
export --source gutenberg --source-id 1342 --output style.json
replace --source gutenberg --source-id 1342 --input reviewed-plan.json --output receipt.json --apply
```

Export returns `{source, sourceId, revision, style}`. Replace JSON:

```json
{
  "source": "gutenberg",
  "sourceId": "1342",
  "expectedRevision": "revision-from-export",
  "style": {"style": "watercolor", "promptPrefix": "warm watercolor,", "setting": "Regency England",
            "reasoning": "Studio confirmed style draft", "coverSubject": "character", "coverFocus": "Elizabeth Bennet"},
  "confirm": true
}
```

Replace sets all six fields exactly. Values are trimmed. Blank optional fields become NULL.
Style and prompt prefix are required. Limits come from `IllustrationSettings`, the same
ones the local settings API uses: style 255, prefix 1000, setting 1000, reasoning 2000,
cover subject 32, and cover focus 4000 (a `TEXT` column since V30). Longer values are
rejected, not clipped, so production can never silently differ from the reviewed draft. The book row is
locked, the revision is compared, and the stored row is read back and must equal the plan
before commit.

Connection: `PDR_DATABASE_URL`, `PDR_DATABASE_USERNAME`, and `PDR_DATABASE_PASSWORD`, or
explicit `--db-url`, `--db-user`, and `--db-password`, the same as `RosterTransferRunner`.
Exit 2 means a stale plan. Any other nonzero exit gives no success receipt. Re-export
after an uncertain failure rather than replaying blindly.

Changing the style does not regenerate existing covers, portraits, or illustrations.
