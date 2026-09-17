# Disposable style previews

CCR exposes `GET /api/style-previews` with `{ "available": true, "version": 1 }`
unless existing cache-only mode is enabled. Studio uses this to detect support.

`POST /api/style-previews` accepts JSON with `family` (`cover`, `portrait`, or
`illustration`) and `prompt` (1–12000 characters). It returns image bytes with
`Cache-Control: no-store`. Invalid input returns 400; cache-only mode returns 409.
Provider failures use the normal server error response. Studio handles individual
failures and retains successful preview images.

The service uses existing provider generators and the shared provider request
limiter. It generates a unique `style-preview-<UUID>` cache key, reads the image,
and deletes the local cached preview in a finally block. It does not reference
book, character, or chapter repositories, update settings or live image slots,
create generation queue rows, or upload preview files to the CDN.

Studio stores returned images separately from candidate artifacts and Keeps.
Applying the draft is a separate action. No new environment flags are needed;
upgrading a running CCR instance requires a restart to load the endpoint.
