# Project Brief: Public Domain Classics Reader

A distraction-free, web reader for public domain literature.

Core Purpose  
Create a focused deep-reading experience for classic books.

Key Principles
- Minimize ui controls, avoid scrolling
- Dynamic viewport-fitted "pages" — exactly two columns, top-to-bottom flow
- High-legibility typography (EB Garamond or similar serif)
- Seamless keyboard navigation and page turning
- The text should be the focus. Everything else should be understated.

Core Features (Phase 1)
- Library of public domain books (Project Gutenberg / Standard Ebooks)
- Book selection → chapter navigation
- No-scroll, two-column layout with perfect page fitting
- Smart full-text search
- LocalStorage: resume last position, font size
- Server-backed per-reader notes/highlights/bookmarks (cookie-identified profile)

Tech Stack
- Frontend: Pure vanilla HTML/CSS/JS
- Backend: Java Spring Boot + in-memory Lucene (or lightweight SQLite for book storage)
- Data: EPUB/HTML from Standard Ebooks or Gutenberg

Non-Negotiables
- No scrolling ever
- Feels like turning pages in a physical book
- Optional features stay hidden until invoked

## Operational Scripts

- `scripts/pregen_transfer_book.sh`: one workflow for a single Gutenberg book:
  - start async pre-generation + poll progress/cancel safely (`/api/pregen/jobs/*`)
  - export recap transfer JSON (`CacheTransferRunner export`)
  - optionally import into a target DB (`CacheTransferRunner import`)
  - optionally sync assets to Spaces (`scripts/sync_spaces.sh`)
- `scripts/transfer_recaps_remote.sh`: local export + remote import orchestration over SSH:
  - export cache transfer JSON locally (`--feature recaps|quizzes|illustrations|portraits|all`)
  - upload JSON to server via `scp`
  - run remote import dry-run
  - optionally stop service, run apply import, then restart service
- `scripts/publish_book_remote.sh`: one-command promotion workflow for a Gutenberg book:
  - optional local pre-generation job start/poll
  - optional local quiz pre-generation (book-wide)
  - optional Spaces sync for binary assets
  - remote DB promotion over SSH for cache metadata (`--transfer-feature all` by default)
- `scripts/deploy_remote.sh`: deploy helper that mirrors the current jar deployment flow:
  - `mvn clean package`
  - `scp` jar to server
  - `ssh` and run remote deploy command (default: `/root/deploy.sh`)
- `scripts/pregen_quizzes_book.sh`: queue + poll quiz generation for every chapter in a book.
- `scripts/pregen_quizzes_top20.sh`: import + pre-generate quizzes for the top-20 Gutenberg set.

Runbook:
- `docs/operations/pre-generation-runbook.md`: end-to-end generation + transfer workflow.

Example:

```bash
scripts/pregen_transfer_book.sh \
  --gutenberg-id 1342 \
  --import-db-url "jdbc:h2:file:/absolute/path/to/library-transfer-test;DB_CLOSE_DELAY=-1" \
  --sync-assets
```

Remote import orchestration example:

```bash
scripts/transfer_recaps_remote.sh \
  --feature recaps \
  --book-source-id 1342 \
  --remote ubuntu@reader-host \
  --remote-project-dir /opt/public-domain-reader \
  --remote-db-url "jdbc:h2:file:/opt/public-domain-reader/data/library;DB_CLOSE_DELAY=-1" \
  --apply-import \
  --remote-stop-cmd "sudo systemctl stop public-domain-reader" \
  --remote-start-cmd "sudo systemctl start public-domain-reader"
```

Deploy helper example:

```bash
scripts/deploy_remote.sh --ssh-target pdr --ssh-key ~/.ssh/kevin
```

## Configuration Matrix

Use these settings as baseline profiles in `src/main/resources/application.properties` (or env overrides).

| Profile | `deployment.mode` | `generation.cache-only` | `tts.cache-only` | `ai.chat.enabled` | Intended behavior |
|---|---|---:|---:|---:|---|
| `local-dev` | `local` | `false` | `false` | `true` | Full generation + chat for development and feature testing. |
| `public-cache-only-with-chat` | `public` | `true` | `true` | `true` | No new artifact generation on cache misses; cached assets still serve; character/recap chat still works when provider is available. |
| `full-generation` | `public` | `false` | `false` | `true` | Generate artifacts on demand and keep chat enabled (with auth/rate limits in public mode). |

Notes:
- `generation.cache-only=true` blocks artifact generation workflows (recaps/quizzes/illustrations/character generation pipelines) but does not disable chat.
- Chat availability is controlled by `ai.chat.enabled` plus chat provider availability/configuration.
- Keep `tts.cache-only=true` on public environments when TTS generation costs should be avoided.
- When `deployment.mode=public`, sensitive generation/chat APIs require authentication:
  - `X-API-Key` matching `security.public.api-key` (or env `PUBLIC_API_KEY`), or
  - collaborator session login via `/api/auth/login` using `security.public.collaborator.password` (or env `PUBLIC_COLLABORATOR_PASSWORD`).
- Public-mode rate limits are controlled by `security.public.rate-limit.window-seconds`, `security.public.rate-limit.generation-requests`, and `security.public.rate-limit.chat-requests`; authenticated requests can use separate limits via `security.public.rate-limit.authenticated-generation-requests` and `security.public.rate-limit.authenticated-chat-requests`.
- Queue-backed generation services can recover persisted pending/stuck work on startup when `generation.queue.recovery.enabled=true`.
- Recap, illustration, and character workers use DB lease claims for cross-instance coordination; tune with `recap.generation.lease-minutes`, `illustration.generation.lease-minutes`, `character.analysis.lease-minutes`, `character.portrait.lease-minutes`, and optional worker IDs (`recap.generation.worker-id`, `illustration.generation.worker-id`, `character.generation.worker-id`).
- Retry/backoff is explicit and persisted for recap/illustration/portrait/analysis jobs (`retryCount`, `nextRetryAt`) with configurable limits via `generation.retry.max-attempts`, `generation.retry.initial-delay-seconds`, and `generation.retry.max-delay-seconds`.
- Aggregate generation status can be queried without log inspection via `GET /api/generation/status` and `GET /api/generation/book/{bookId}/status`.

## Frontend E2E (Playwright)

- Install dependencies: `npm install`
- Install browser: `npm run e2e:install`
- Run tests: `npm run e2e:test`

Notes:
- Tests run against a local static server (`node e2e/static-server.js`) and mock all `/api/*` traffic in Playwright.
- Current coverage includes retry UX for recap overlay load errors, recap chat send failures, and character chat send failures (`e2e/retry-flows.spec.js`).
- CI runs this suite via GitHub Actions workflow `/Users/kevinturner/IdeaProjects/public-domain-reader/.github/workflows/playwright-e2e.yml`.
- Backend CI test coverage runs via `/Users/kevinturner/IdeaProjects/public-domain-reader/.github/workflows/maven-test.yml` (`mvn test` on Java 21).

## PostgreSQL Roles (Flyway + Runtime)

For PostgreSQL environments, use separate DB roles:
- `pdr_migrator`: Flyway migration user (must own tables/sequences Flyway alters).
- `pdr_app`: runtime application user (DML only).

Why:
- Prevents startup failures like `ERROR: must be owner of table ...` during Flyway migrations.
- Keeps runtime permissions minimal while migrations remain reliable.

Application properties (recommended in local-dev/prod profile overrides):

```properties
spring.datasource.username=${DB_APP_USER:pdr_app}
spring.datasource.password=${DB_APP_PASSWORD:CHANGE_ME_APP}

spring.flyway.user=${DB_MIGRATOR_USER:pdr_migrator}
spring.flyway.password=${DB_MIGRATOR_PASSWORD:CHANGE_ME_MIGRATOR}

# For shared/prod DBs, avoid runtime schema mutation
spring.jpa.hibernate.ddl-auto=validate
```

Operational notes:
- Ensure `pdr_migrator` owns existing objects in `public` before running new migrations.
- Grant `pdr_app` CRUD on tables and sequence usage/select/update.
- Set default privileges from `pdr_migrator` so future tables/sequences are automatically usable by `pdr_app`.
- In production, set either `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` or `DB_MIGRATOR_USER` / `DB_MIGRATOR_PASSWORD` so Flyway does not fall back to the runtime app role.

## Local Env File

For local development, the app reads an optional `.env.local` file from the repo root.

Example:

```properties
ACCOUNT_AUTH_GOOGLE_ENABLED=true
ACCOUNT_AUTH_GOOGLE_CLIENT_ID=your-google-client-id
ACCOUNT_AUTH_GOOGLE_CLIENT_SECRET=your-google-client-secret
ACCOUNT_AUTH_GOOGLE_REDIRECT_URI=http://localhost:8080/api/account/google/callback
```

Notes:
- `.env.local` is already ignored by git via `.env.*`.
- Use simple `KEY=value` lines. Do not prefix with `export`.
- Start the app with the `local-dev` profile so the PostgreSQL local-dev settings are active.

## Production Auth Env

For production deployments using Google sign-in and a dedicated Flyway role, set:

```properties
DATABASE_URL=jdbc:postgresql://...
DATABASE_USERNAME=pdr_app
DATABASE_PASSWORD=CHANGE_ME_APP
DB_MIGRATOR_USER=pdr_migrator
DB_MIGRATOR_PASSWORD=CHANGE_ME_MIGRATOR

ACCOUNT_AUTH_GOOGLE_ENABLED=true
ACCOUNT_AUTH_GOOGLE_CLIENT_ID=your-google-client-id
ACCOUNT_AUTH_GOOGLE_CLIENT_SECRET=your-google-client-secret
ACCOUNT_AUTH_GOOGLE_REDIRECT_URI=https://your-domain.example/api/account/google/callback
```

Notes:
- `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` also work if you prefer canonical Spring env names.
- The Flyway role must own tables it alters, including existing auth tables such as `users`.
- The Google redirect URI must exactly match the URI configured on the Google OAuth client.
