# Curated catalog membership (BL-072)

The curated landing catalog (what the landing page's discover and search show when
`library.catalog.mode=curated`) lives in the `curated_books` table. It used to be a Java list in
`CuratedCatalogService`, so every change needed a code change and a deploy.

## What membership controls

A row is one Gutenberg title (`source` + `source_id`) with the landing metadata the old list held:
title, author, `popularity` (the old download-count hint, used for ordering), subjects, bookshelves
and search aliases. `status` is `active` or `inactive`.

**Active** titles:
- appear on the landing page's curated popular list and in curated search, including matches on aliases;
- are what `CuratedCatalogService.isCuratedGutenbergId` reports, so a first import turns on
  read-aloud, illustrations and characters, and read-aloud stays available for older imports;
- are the source list for `PreGenerationBatchRunner`.

**Inactive** means only "not on the curated list". Unlisting never deletes or changes the `books`
row, its feature flags, covers, portraits, illustrations, characters or chapters. Relisting shows
the title again without regenerating anything.

V34 seeds the 91 titles from the former Java list as `active`, so nothing changes on migrate.
The popular list is now ordered by popularity, then title. The old list broke one tie differently
(The Canterbury Tales came before Romeo and Juliet); nothing else moves.

## Membership API (local Studio only)

Only direct loopback requests are accepted (same rule as `/api/studio/roster`: no proxy headers,
a localhost host, and an Origin, if present, of the Studio dev server). In public mode the routes
are also classified `ADMIN`.

| Method | Path | Body | Result |
| --- | --- | --- | --- |
| `GET` | `/api/curated-books` | | every row, active and inactive, most popular first |
| `POST` | `/api/curated-books` | `{source, sourceId, title, author, popularity?, subjects?, bookshelves?, aliases?}` | `201` new row (title and author required) or `200` reactivated existing row; omitted fields keep stored values |
| `PATCH` | `/api/curated-books/{source}/{sourceId}` | `{"status": "active" \| "inactive"}` | `200` updated row, `404` if not curated |

`source` must be `gutenberg` and `sourceId` a Gutenberg number. Text fields are capped at 512
characters; lists at 25 entries of 200 characters.

The service holds active rows in memory. A write through the API refreshes that snapshot at once;
a change made directly in the database shows up within 60 seconds.

## Production

Production listing still needs a transfer path (BL-072.4): export membership rows locally and
apply them to production, like the roster and style transfer runners. Until then, change
production rows deliberately with SQL, or ship the change as a migration.
