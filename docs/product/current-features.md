# Current Feature Inventory

Last audited: 2026-07-08

This inventory reflects implemented behavior in backend controllers/services and `static/js/reader.js`.

## Reading Experience

- Two-view app: library view and focused reader view.
- No-scroll reading mode with dynamic pagination and two-column layout.
- Keyboard-first navigation:
  - page: `h`/`l`
  - paragraph: `j`/`k`
  - chapter: `H`/`L`
  - chapter list: `c`
- Chapter list overlay with keyboard selection.
- Reader search (`/`) with in-book result navigation.
- Desktop reader header search can run in a compact icon-only state and expand on click or `/`.
- Resume state persisted in `localStorage` (book/chapter/page/paragraph).
- Recently-read list in library.
- In-reader paragraph annotations:
  - highlight toggle (`u`)
  - note edit modal (`n`)
  - bookmark toggle (`b`)
  - bookmark jump overlay (`B`)
- Annotation data persisted server-side per reader profile cookie.
- `Escape` closes active overlays/modals/search focus; it does not force navigation back to library from normal reading state.

## Library and Import

- Browse popular Project Gutenberg catalog.
- Search Gutenberg catalog from library search box.
- Import book by Gutenberg ID.
- Detect and mark already-imported catalog books.
- Library cards support generated or uploaded book cover images with CSS fallback covers when no image is available.
- Library APIs for list/get/delete/delete-all books.
- Book deletion cleans dependent generation/quiz/trophy records before deleting the parent book to avoid FK-blocked deletes.
- Per-book feature flags for:
  - `ttsEnabled`
  - `illustrationEnabled`
  - `characterEnabled`

## Reader Accounts and Identity (BL-021)

- Reader account auth endpoints are available under `/api/account`:
  - `POST /api/account/register`
  - `POST /api/account/login`
  - `POST /api/account/logout`
  - `GET /api/account/status`
  - `POST /api/account/claim-sync`
  - `POST /api/account/google/start`
  - `GET /api/account/google/callback`
- Reader account rollout is configurable by feature flags:
  - `account.auth.rollout.mode=disabled|internal|optional|required`
  - `account.auth.rollout.allowed-emails` (allow-list for `internal` mode)
- Account status payload includes rollout metadata (`rolloutMode`, `accountRequired`) and provider availability (`googleAuthEnabled`) for staged client behavior.
- Reader account UI is available in both library and reader flows, with one-time claim/sync to migrate anonymous/local state into the signed-in account.
- Reader-scoped API behavior resolves identity by authenticated account `userId` when present and falls back to anonymous reader cookie identity when not authenticated.
- Account identity supports both local credentials and provider-backed identities, with Google sign-in issuing the same durable reader account session cookie used by email/password auth.
- User-scoped ownership is implemented for reader data paths used by annotations/bookmarks/progress/quiz attempts/trophies.

## Landing Personalization (BL-018)

- Library landing renders personalized sections from local activity:
  - `Continue Reading`
  - `For You`
  - `In Progress`
  - `Completed`
  - `My List` (favorites/saved-for-later)
- Default landing browsing uses front-facing cover shelves with horizontal scrolling, edge fades, and desktop shelf arrow controls.
- `Continue Reading` renders as a larger resume-focused card, while shelf sections emphasize cover art for browsing.
- Landing ranking is deterministic for active/completed queues (see `landing-ranking.md`).
- `Discover` rail uses deterministic affinity recommendations with user-facing reason chips when library search is empty (see `discover-affinity.md`).
- `Discover` appears as a cover shelf when browsing and falls back to vertical result cards during explicit search.
- Landing search runs on explicit submit only (`Enter` key or `Search` button); it does not fire on every keystroke.
- Landing search has visible in-flight/loading state, a status message, and retry affordance on failures.
- Search mode hides classroom/personalized rails and shows query-driven catalog results.
- Classroom-aware mode is available through `GET /api/classroom/context` and can switch landing to assignment-first behavior with teacher-controlled feature states.
- Teaching access is capability-aware: `GET /api/classroom/capabilities` exposes `canTeach`/`canCreateClass`, `CREATE_CLASSROOM` is stored durably in `account_capabilities`, and existing teacher-like term memberships retain workspace access without granting new-class creation.
- Student accounts do not see Teaching navigation and receive an access-denied state if they navigate directly to `/teacher`; class creation is also enforced server-side.
- Classroom setup/config details are documented in `classroom-landing-usage.md`.

## Search

- Lucene-based full-text search for book metadata and paragraph content.
- Optional `bookId` scoped search for in-reader queries.
- Search index rebuilt at startup from DB records.

## Text-to-Speech (TTS)

- Feature-gated per book.
- Voice settings retrieval and AI-assisted voice analysis.
- Paragraph-level TTS endpoint with:
  - voice/speed/instructions options
  - cache support
  - cache-only mode support
  - CDN redirect support for cached audio
- Browser speech synthesis fallback in frontend when API generation is unavailable.
- TTS prefetch for next paragraph in frontend.
- Cost estimate endpoint.

## Speed Reading

- Feature flag endpoint and frontend availability checks.
- Speed reading overlay with play/pause/exit.
- Adjustable speed (WPM slider), persisted in `localStorage`.
- Chapter-boundary pause/continue flow.

## Illustrations

- Feature-gated per book.
- Book-level style settings retrieval and AI style analysis.
- Chapter illustration request/status/image endpoints.
- Background queue-based illustration generation.
- Prefetch next chapter illustration.
- Prompt inspection and prompt-based regeneration flow (when enabled).
- Regeneration preview/accept flow in frontend modal.
- Retry endpoint for stuck pending illustrations.
- Cache-only mode and CDN redirect support.

## Characters

- Feature-gated per book.
- Character extraction pipeline by chapter.
- Background queue-based portrait generation.
- Character prefetch by book and next-chapter prefetch.
- Character status APIs and portrait retrieval (with CDN support).
- In-reader discovery checks and toast notifications for newly available characters.
- Character browser modal with list/detail views.
- Navigate from character detail to first appearance in text.
- Character chat (primary characters only), with reading-position context guardrails.
- Chat history persisted in `localStorage` per book/character.
- `generation.cache-only` does not disable character chat; chat remains available when `ai.chat.enabled=true` and the chat provider is available.

## Chapter Recaps and Quizzes

- Post-chapter recap overlay with tabbed `Recap` + `Chat` experience.
- Recap payload persistence (`chapter_recaps`) with async generation and status polling in reader UI.
- Chapter-bounded recap chat that blocks future-chapter context.
- Optional post-chapter pop quiz tab with persisted quiz payloads (`chapter_quizzes`) and async generation.
- Quiz grading with score + per-question correctness and citation snippets for missed answers.
- Difficulty progression tied to chapter index, with quiz attempt/trophy progression tracking.
- `generation.cache-only` blocks new recap/quiz generation on misses, while recap chat can still be available when `ai.chat.enabled=true`.

## Reading Buddy Mode

- Optional companion persona (not an in-story character) that can comment while reading and answer chat questions.
- **Off by default** via `reading-buddy.enabled=false`. Production flag-on is gated on the required spoiler acceptance suite (`ReadingBuddySpoilerAcceptanceTest`) and Playwright smoke (`e2e/reading-buddy.spec.js`).
- Availability is reported only by `GET /api/reading-buddy/status` (`available === enabled && ai.chat.enabled && providerAvailable`); not dual-published on `FeatureController`.
- Canned personas (historian, close reader, humorist, encourager) from a code catalog (`GET /api/reading-buddy/personas`).
- Preferences (toggle, frequency rare/occasional/chatty, persona, quiet-for-a-while) at `GET/PUT /api/reading-buddy/preferences`, scoped by reader identity (anonymous cookie or signed-in account).
- Proactive path: client gates + server hard filters (`ReadingBuddyTriggerPolicy`), then LLM decide-or-comment (`COMMENT:` / `NONE:`) via `POST /api/reading-buddy/check-comment`.
- Interactive chat: `POST /api/reading-buddy/chat` uses server memory only (client history ignored for prompts).
- Spoiler safety: position-bounded STORY CONTEXT, future messages filtered from prompts, history `visibleAtPosition` for rewind masking, rolling summary omitted when reader is behind summary watermarks.
- Reader UI: settings toggle/persona/frequency, toast (never auto-opens modal), Talk modal, classroom FE kill-switch via `readingBuddyEnabled` (+ `chatEnabled`).
- The teacher workspace distinguishes the saved classroom policy from effective availability. A global-off deployment leaves the saved policy intact for automatic activation after rollout, but disables the classroom control with an explicit unavailable state; student settings stay hidden and disabled while effective availability is false.
- Design notes: `docs/product/reading-buddy-mode.md`.

## Pre-Generation and Operations

- Pre-generation APIs:
  - by existing `bookId`
  - by `gutenbergId` (import + generation)
- Batch pre-generation runner for curated top books.
- Stall detection and requeue/reset behavior for generation jobs.
- Startup queue recovery requeues persisted pending/stuck generation work for illustration, character analysis/portraits, and chapter recaps.
- Recap, illustration, and character generation pipelines use DB-backed lease claims (`leaseOwner`/`leaseExpiresAt`) to reduce duplicate processing across parallel app instances.
- Retry/backoff metadata is persisted per generation job (`retryCount`, `nextRetryAt`) with exponential retry scheduling for recap/illustration/portrait/analysis pipelines.
- Aggregate generation job status APIs:
  - `GET /api/generation/status` (global)
  - `GET /api/generation/book/{bookId}/status` (book-scoped)
- `/health/details` includes account rollout/migration telemetry under `accountMetrics` for rollout verification.
- Asset key normalization for stable paths.
- CLI utilities for asset migration and orphan cleanup.
- Spaces/CDN sync script for audio/portraits/illustrations.
- Cache transfer CLI (`CacheTransferRunner`) for recap/quiz/illustration/portrait metadata export/import with dry-run and conflict controls.
- Operator scripts for pre-generation, remote transfer, and remote deploy orchestration.

## Persistence and Configuration

- H2 file-backed database (`./data/library`).
- JPA entities for books/chapters/paragraphs/illustrations/characters.
- LLM provider abstraction with configurable reasoning/chat providers.
- Runtime feature and generation behavior configured via `application.properties`.
- `generation.cache-only` is artifact-generation-only (recaps/quizzes/illustrations/character generation pipelines); cached reads still work, and chat is controlled separately by `ai.chat.enabled`.

## API Guardrails (Public Mode)

- Deployment mode switch:
  - `deployment.mode=local`: no auth gate on sensitive generation/chat endpoints.
  - `deployment.mode=public`: sensitive generation/chat endpoints require authentication.
- Public-mode auth options:
  - API key auth via `X-API-Key` + `security.public.api-key` (`PUBLIC_API_KEY` env supported).
  - Collaborator session auth via `/api/auth/login` + HttpOnly cookie (`security.public.collaborator.password`).
- Browser UI support for collaborator sign-in/out via header auth toggle and modal.
- In public mode, collaborator prompts are tied to protected actions; passive background prefetch/generation calls do not force collaborator auth modal display.
- Fixed-window rate limits for sensitive endpoints with explicit `429` payloads and `Retry-After`:
  - generation routes (`tts`, `illustrations`, `characters`, `recaps`, `quizzes`, `pregen`)
  - chat routes (`character chat`, `recap chat`, `reading-buddy chat`)
  - reading-buddy proactive checks (`POST /api/reading-buddy/check-comment`) on a **dedicated** buddy-check bucket (not shared with chat)
- Rate-limit key scope:
  - authenticated requests use principal-scoped keys (`api:<key-hash>` or collaborator session principal)
  - unauthenticated/local-mode traffic falls back to IP-scoped keys
- Rate-limit tuning via:
  - `security.public.rate-limit.window-seconds`
  - `security.public.rate-limit.generation-requests`
  - `security.public.rate-limit.chat-requests`
  - `security.public.rate-limit.buddy-check-requests`
  - `security.public.rate-limit.authenticated-generation-requests`
  - `security.public.rate-limit.authenticated-chat-requests`
  - `security.public.rate-limit.authenticated-buddy-check-requests`
  - `security.public.rate-limit.max-keys`
- Session settings:
  - `security.public.session.cookie-name`
  - `security.public.session.ttl-minutes`
  - `security.public.session.secure-cookie`

## Test Coverage (Current)

- Strong coverage for:
  - import controller/service
  - search controller/service
  - Gutenberg content parser
- Limited coverage for:
  - TTS, illustrations, characters, pre-generation workflows
  - frontend reader logic outside covered E2E slices
  - CLI utilities and operational scripts
- E2E coverage includes:
  - retry/recovery flows for recap + chat surfaces (`e2e/retry-flows.spec.js`)
  - account register/login/logout and anonymous->account claim-sync flow (`e2e/account-auth.spec.js`)
  - initial library load performance guard for the `Loading library...` state (`e2e/initial-load-performance.spec.js`)
  - reading buddy smoke: toggle, Talk modal open/close, optional toast path (`e2e/reading-buddy.spec.js`)
- Reading Buddy spoiler acceptance suite (required before prod flag-on): `ReadingBuddySpoilerAcceptanceTest`
