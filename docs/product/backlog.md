# Product Backlog

Last updated: 2026-03-23

Statuses: `Discovery`, `Proposed`, `Ready`, `In Progress`, `Blocked`, `Done`

## Current Delivery State

- Most recent completed slice: `BL-028 - Account auth endpoint hardening` (`Done`, delivered account auth rate limiting with `429` + `Retry-After`, login lockout/backoff persistence, and structured non-PII auth audit events with regression test coverage).
- Most recent shipped hardening (2026-02-24): completed BL-028 account endpoint safeguards, tightened public-mode TTS behavior so cached paragraph audio remains available without collaborator auth while uncached generation remains protected, and finalized compact reader header/menu interactions (logo back-link, desktop shortcuts, keyboard-driven menu navigation).
- Active priority work: `None currently in progress`; next P1 candidate for additional scoping remains `BL-025` (`Discovery`).

## Discovery Epics (Pending Product Discussion)

### BL-018 - Personalized Landing Page Rework
- Type: Improvement
- Priority: P1
- Effort: L
- Status: Done
- Problem: Current library landing does not adapt to reading behavior or intent.
- Scope Buckets:
- Personalization model (continue reading, recommended next action, visible progress state).
- Information architecture for landing sections (`Continue Reading`, `My List`, `In Progress`, `Completed`, `Discover`, optional achievements shelf).
- Lightweight ranking logic using local activity signals.
- Favorite/bookmarking model for library curation (`My List` or favorites).
- Discovery ranking seed model (behavioral + genre/author affinity from reading history and explicit favorites).
- Discovery Questions:
- Should the landing page prioritize "continue reading" over exploration?
- What activity dimensions matter most (recency, completion %, pace, favorites, quiz performance)?
- Should this personalization be local-only initially or account-backed later?
- Should achievements/trophies be visible on the landing page or kept in a profile-only surface?
- Should discover recommendations favor "same genre/style as favorites" or "adjacent exploration" by default?
- Should landing behavior change when a reader is in a class-assigned context?
- Current Direction (2026-02-08):
- Move from generic catalog landing to reader-activity-driven sections:
- User library (books started or explicitly saved).
- In progress.
- Completed.
- Up next queue.
- Keep a discovery rail for new books so exploration remains visible.
- Current Direction (2026-02-15):
- Add `% complete` readouts for in-progress cards and preserve stable tie-break rules.
- Add a `My List` section as explicit user intent signal (favorites/saved for later).
- Keep achievements discoverable from landing (lightweight trophy strip or badge summary) but avoid crowding top priority reading actions.
- Start discover recommendations with deterministic local heuristics (favorites + author/genre affinity) before ML-heavy ranking.
- Exit Criteria for Discovery:
- Ranked section model with tie-break rules.
- Approved landing page layout and interaction flow.
- Decision record for favorites model (`My List`) and achievements placement.
- Recommendation seed strategy documented (data inputs + fallback behavior when user has sparse history).
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-018.1 My List / Favorites Foundation | Done | Add explicit `favorite`/saved-for-later state model and surface `My List` row with add/remove affordances in library + reader | Reader can add/remove favorites and see stable `My List` ordering across sessions |
| BL-018.2 In Progress + Completion Readouts | Done | Standardize per-book `% complete`, chapter position, and completion status signals used by landing cards | `In Progress` and `Completed` rows show consistent progress chips and update as reading advances |
| BL-018.3 Ranking + Continue Reading Tie-Breaks | Done | Formalize deterministic ranking rules (recency, progress depth, favorite intent, completion state) for `Continue Reading` and `Up Next` | Ranking outputs are deterministic/test-covered and documented for product review |
| BL-018.4 Achievements Shelf Integration | Done | Add compact landing-level trophy/achievement summary (not full profile replacement) with drill-in path | Landing exposes recent/next achievement context without displacing primary reading CTA |
| BL-018.5 Discover Affinity v1 | Done | Implement deterministic recommendation seeds using favorites + author/genre affinity + recent activity (with sparse-history fallback) | `Discover` rail explains recommendation basis (for example, "Because you liked X") and handles cold start gracefully |
| BL-018.6 Classroom-Aware Landing Variant | Done | Add class-context landing adjustments (`Assignments`, required quiz status, teacher-controlled feature states) when reader is in an enrolled class | Classroom readers see assignment-first landing behavior while non-class readers keep consumer flow |
- Session Log:
- 2026-02-17: Started BL-018.1 by adding local favorite persistence (`My List`), library card save/remove actions, and reader-level favorite toggles (desktop + mobile menu).
- 2026-02-17: Started BL-018.2 by adding standardized progress chips on local landing cards (`status`, `chapter position`, `% complete`) and unified activity/completion readouts.
- 2026-02-17: Completed BL-018.2 by extracting progress snapshot logic into shared frontend utility (`library-progress.js`) and adding a tiny Node frontend harness (`src/test/frontend/library-progress.test.cjs`) covering not-started/in-progress/completed boundary cases.
- 2026-02-17: Completed BL-018.3 by extracting deterministic ranking comparators into shared frontend utility (`library-ranking.js`), wiring personalized section ordering to those comparators, adding Node ranking tests (`src/test/frontend/library-ranking.test.cjs`), and documenting tie-break rules in `docs/product/landing-ranking.md`.
- 2026-02-17: Completed BL-018.1 by finalizing explicit `My List` persistence and add/remove affordances from both landing cards and reader controls.
- 2026-02-17: Started BL-018.4 by adding a compact landing achievements shelf backed by quiz trophy APIs with recent unlock chips and book drill-in behavior.
- 2026-02-17: Completed BL-018.4 by adding a `View all` achievements modal (with keyboard/backdrop close), full trophy listing with per-book drill-in, and shelf refresh wiring tied to quiz availability + library lifecycle.
- 2026-02-17: Completed BL-018.5 by adding deterministic discover affinity ranking (`library-discover.js`) using favorite intent + author/genre overlap + recent activity with cold-start popularity fallback, surfacing explainable recommendation reasons in the `Discover` rail, extending import catalog payloads with `subjects/bookshelves`, and adding backend/frontend tests for ranking determinism and payload mapping.
- 2026-02-17: Completed BL-018.6 by adding classroom landing context (`/api/classroom/context`), assignment-first landing sections with required-quiz status chips, and classroom feature-state overrides for quiz/recap/read-aloud/illustration/character/chat controls while preserving consumer flow for non-enrolled readers.
- 2026-02-18: Marked BL-018 as `Done` now that all planned slices (`BL-018.1` through `BL-018.6`) and discovery deliverables are complete.

### BL-019 - Gamification and Trophy System
- Type: Feature
- Priority: P2
- Effort: L
- Status: Discovery
- Problem: The product lacks long-term engagement mechanics tied to reading progress.
- Scope Buckets:
- Trophy taxonomy and unlock rules.
- Progress tracking events and persistence model.
- Trophy presentation in UI (profile, chapter pause, library badges).
- Discovery Questions:
- Should trophies emphasize consistency, completion, comprehension, or exploration?
- Are trophies private-only or shareable?
- Should trophy logic be deterministic and transparent to users?
- Current Direction (2026-02-08):
- Support private tracking by default.
- Add optional social sharing entry points for selected trophies for growth experiments.
- Design unlock rules to be deterministic and auditable.
- Exit Criteria for Discovery:
- Initial trophy catalog (v1) with explicit unlock conditions.
- Event tracking schema for unlock evaluation.

### BL-020 - Post-Chapter Pop Quiz
- Type: Feature
- Priority: P2
- Effort: M
- Status: Done
- Problem: Readers may want optional chapter-level comprehension checks and reflection prompts.
- Implementation Plan:
- Phase 1 (Data + API): Add per-chapter quiz persistence with immutable payload storage, generation status, and read/status/generate/grade endpoints.
- Phase 2 (Generation Pipeline): Implement async on-demand quiz generation on chapter load with LLM JSON output and extractive fallback.
- Phase 3 (Reader UX): Add `Quiz` tab in chapter pause overlay with multiple-choice flow, score summary, and wrong-answer citation snippets.
- Phase 4 (Progression): Define difficulty ramp and trophy linkage once quiz completion telemetry is stable.
- Current Direction (2026-02-08):
- Start with factual-only quizzes.
- Present at chapter pause as optional interaction.
- Add citations/snippets for wrong answers as a likely v1 requirement.
- Current Direction (2026-02-11):
- v1 quiz format: factual-only multiple-choice (target 3 questions, allow 2-5 from LLM output).
- v1 generation mode: on-demand async generation with persisted chapter quiz payloads and cache reuse.
- v1 review feedback: grading response includes correct answer and citation snippet for each missed question.
- Acceptance Criteria:
- Quiz API returns stable, static payload for a chapter after first successful generation.
- Quiz grading endpoint returns total score and per-question correctness with citation snippets for missed answers.
- Reader chapter pause UI exposes quiz interaction without blocking continue/skip chapter navigation.
- Quiz difficulty ramps deterministically by chapter progression and returns current difficulty in quiz payload/grade responses.
- Quiz grading records progression attempts and unlocks deterministic quiz trophies for future gamification surfaces.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-020.1 Quiz Data + API | Done | `chapter_quizzes` persistence, async generation queue, `/api/quizzes` status/read/generate/grade endpoints | Controller/service tests pass and generated quiz payload is stable per chapter |
| BL-020.2 Reader Quiz UX | Done | Add `Quiz` tab to chapter pause overlay with answer submission + score/citation feedback | Reader can complete quiz and view missed-answer citations without nav regressions |
| BL-020.3 Difficulty + Trophy Linkage | Done | Add configurable difficulty ramp and integrate quiz outcomes with trophy logic | Difficulty settings and trophy unlock hooks are implemented and validated |
- Session Log:
- 2026-02-11: Started BL-020 by implementing chapter quiz persistence + async generation service and adding `/api/quizzes` read/status/generate/grade endpoints.
- 2026-02-11: Added chapter pause `Quiz` tab in reader overlay with multi-question submission flow, score summary, and missed-answer citation feedback.
- 2026-02-11: Validated BL-020.1/BL-020.2 with passing `ChapterQuizServiceTest` and `ChapterQuizControllerTest` plus recap regression tests.
- 2026-02-11: Completed BL-020.3 by adding chapter-index-based quiz difficulty ramping, persisted quiz attempt/trophy tracking, trophy/readout APIs, and UI feedback for unlocked trophies and streak progress.
- 2026-02-15: Hardened reader chapter navigation against async race conditions by ensuring only the latest chapter-load request can mutate reader state (`reader.js` request sequencing + stale-result guards).
- 2026-02-15: Added diagnostic error logging for `/api/quizzes/chapter/{chapterId}` and `/api/quizzes/chapter/{chapterId}/status` failures (with chapter/book/cache/provider context) and added controller tests for exception->500 behavior.

### BL-021 - User Registration and Account System
- Type: Feature
- Priority: P1
- Effort: XL
- Status: Done
- Problem: User-specific progress and personalization cannot reliably persist across devices without accounts.
- Scope Buckets:
- Authentication and session architecture.
- User data model migration from local-only state.
- Privacy/security controls and account lifecycle operations.
- Discovery Questions:
- What auth modes are required at launch (email/password, OAuth, magic link)?
- What existing local data should be migrated into new accounts?
- Is anonymous mode still supported alongside registration?
- Current Direction (2026-02-08):
- Cost and traction uncertainty are primary constraints.
- Registration remains discovery-phase and should be sequenced after validating engagement loops.
- Any account approach must include cost controls and staged rollout.
- Current Direction (2026-02-18):
- Launch with email/password first; defer OAuth/magic-link until core account flows are stable.
- Keep anonymous reading mode available; account sign-in adds cross-device persistence and classroom eligibility.
- Preserve existing collaborator/admin auth (`/api/auth`) for public-mode operational access; reader accounts ship under separate account endpoints and session model.
- Migrate existing reader state in phases (favorites, progress, preferences, recap opt-out, annotations, quiz outcomes) with deterministic conflict rules.
- Roll out behind feature flags with staged enablement (internal -> optional production -> classroom-required paths).
- Auth architecture and security decision record: `docs/product/bl-021-auth-architecture-adr.md`.
- Proposed Migration Scope (v1):
- Local/browser state: favorites (`My List`), reading progress/position, reader preferences, recap opt-out.
- Server-side reader-scoped state currently keyed by cookie reader id: paragraph annotations/bookmarks.
- Quiz progression/trophy data updated to be per-user (instead of global-by-book) for account-bound persistence.
- Exit Criteria for Discovery:
- Auth architecture decision record.
- User data ownership and retention policy.
- Minimum viable account feature set and rollout plan.
- Acceptance Criteria:
- Reader can register, sign in, sign out, and maintain account session across browser refresh/restart.
- Anonymous users can keep reading without registering; on sign-in, previous local/cookie-scoped state is claimed or merged into the account without data loss.
- Reader-scoped APIs resolve identity via account when authenticated and via anonymous reader cookie otherwise.
- Existing public-mode collaborator auth and sensitive endpoint protections continue working as-is.
- Rollout is feature-flagged and includes migration verification plus E2E coverage for anonymous->account transition.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-021.1 Auth Architecture + Security ADR | Done | Finalize launch auth mode (email/password), session strategy, password hashing, rate limits, and account lifecycle policy | ADR approved; security controls and rollout constraints documented |
| BL-021.2 Account + Session Schema | Done | Add `users` + durable account session tables and migration scripts; add account auth endpoints (`/api/account/*`) without changing collaborator auth | New schema migrates cleanly and account auth endpoints pass controller/service tests |
| BL-021.3 Identity Resolution Layer | Done | Add shared resolver that maps requests to authenticated `userId` or fallback anonymous `readerId` and wire into reader-scoped APIs | Reader-scoped endpoints consistently resolve identity with backward-compatible anonymous behavior |
| BL-021.4 Data Model Migration for User Scope | Done | Add `user_id` ownership to annotations/progress/quiz/trophy persistence paths and update unique/index constraints + repositories/services | Per-user progress/annotations/trophies are isolated and queryable without regressions |
| BL-021.5 Client Sign-In + One-Time Claim/Sync | Done | Add reader account UI flow and one-time local/cookie data claim-sync on first sign-in with deterministic conflict handling | First sign-in migrates user data predictably and preserves existing local experience |
| BL-021.6 Flagged Rollout + Verification | Done | Add feature flags, migration telemetry, and E2E coverage for register/login/logout + anonymous->account migration | Internal rollout succeeds with passing test suite and no critical migration defects |
- Dependency Notes:
- BL-025.2 onward depends on BL-021 foundations (account identity and enrollment-capable user model).
- BL-021 must not regress existing `/api/auth` collaborator access used for public-mode sensitive endpoint control.
- Session Log:
- 2026-02-18: Expanded BL-021 from high-level discovery notes into a phased implementation plan with explicit migration scope, account/identity architecture direction, and staged rollout gates.
- 2026-02-18: Started BL-021.1 by drafting auth/security ADR (`docs/product/bl-021-auth-architecture-adr.md`) with launch auth mode, session model, migration rules, and retention policy decisions.
- 2026-02-18: Marked BL-021.1 `Done` after approving the auth/security ADR and anchoring launch decisions for auth mode, session policy, retention, and rollout gating.
- 2026-02-18: Started BL-021.2 by adding Flyway account auth schema (`V4__account_auth.sql` for `users` + `user_sessions`), backend account endpoints (`/api/account/register|login|logout|status`), account auth service/repositories/entities, and targeted tests (`AccountControllerTest`, `AccountAuthServiceTest`).
- 2026-02-18: Marked BL-021.2 `Done` after validating account schema/auth scaffolding with passing targeted tests (`AccountControllerTest`, `AccountAuthServiceTest`) and collaborator auth regression coverage (`AuthControllerTest`).
- 2026-02-18: Marked BL-021.3 `Done` by introducing shared `ReaderIdentityService` (`account userId` when authenticated, fallback anonymous cookie id), wiring library annotation/bookmark endpoints to that resolver, and validating behavior with `ReaderIdentityServiceTest` + updated `LibraryControllerTest`.
- 2026-02-18: Marked BL-021.4 `Done` by adding `V5__user_owned_reader_data.sql` (`user_id` columns + indexes/constraints), extending annotation/quiz/trophy entities + repositories + services for user-scoped ownership, and wiring quiz + library controllers to identity-aware reads/writes; validated with targeted suites (`ParagraphAnnotationServiceTest`, `LibraryControllerTest`, `QuizProgressServiceTest`, `ChapterQuizServiceTest`, `ChapterQuizControllerTest`) and Flyway/JPA migration sanity (`GenerationLeaseClaimRepositoryTest`).
- 2026-02-18: Marked BL-021.5 `Done` by adding reader account client sign-in/register/logout UI flow (`reader.js` + `index.html`), implementing `POST /api/account/claim-sync` with idempotent anonymous claim + deterministic local state merge (`AccountClaimSyncService` + `V6__account_claim_sync.sql`), and hardening anonymous quiz/trophy scoping with `reader_id` ownership; validated with targeted suites (`AccountClaimSyncServiceTest`, `AccountControllerTest`, `QuizProgressServiceTest`, `ChapterQuizControllerTest`) plus Flyway migration sanity (`GenerationLeaseClaimRepositoryTest`).
- 2026-02-19: Hardened BL-021.5 UX in public mode by ensuring passive/background generation requests do not trigger collaborator auth prompts and by keeping collaborator auth prompts tied to explicit protected actions.
- 2026-02-19: Fixed reader header/account interaction issues by moving reader account modal visibility out of reader-only container scope, reducing redundant auth controls in desktop header, and introducing compact-expand desktop search behavior.
- 2026-02-19: Fixed book deletion reliability by cleaning dependent chapter/book child data (`chapter_recaps`, `chapter_quizzes`, `illustrations`, `quiz_attempts`, `quiz_trophies`, plus other generation records) before deleting books, avoiding FK-blocked deletes for malformed imports.
- 2026-02-19: Removed global `Escape` shortcut that forced back-to-library navigation to prevent accidental reader exits during search/navigation flows.
- 2026-02-19: Completed BL-021.6 by adding staged account rollout controls (`account.auth.rollout.mode` with internal allow-list support), wiring migration/auth telemetry into `/health/details` (`accountMetrics`), extending account status payload with rollout metadata, and adding Playwright E2E coverage for register/login/logout plus anonymous-to-account claim-sync behavior.
- 2026-03-23: Added Google reader account sign-in on top of the existing BL-021 session model by introducing provider-backed identity tables (`user_local_credentials`, `user_auth_identities`), Google OAuth start/callback endpoints, Google-aware account UI, and production/local config support for dedicated Flyway migrator credentials plus Google auth env vars.

### BL-022 - Reader Chapter Summary Feedback (AI Coach)
- Type: Feature
- Priority: P2
- Effort: M
- Status: Discovery
- Problem: Factual quizzes measure recall, but readers may also want qualitative feedback on their own chapter understanding.
- Scope Buckets:
- Reader-written chapter summary capture UI.
- AI rubric scoring against chapter key points.
- Missed-point feedback with spoiler-safe guidance.
- Discovery Questions:
- Should this be optional after quiz completion or standalone?
- Should scoring be numeric, tiered badges, or guidance-only?
- Should feedback include direct quote snippets from chapter text?
- Exit Criteria for Discovery:
- Defined feedback rubric and output format.
- Decision on placement in chapter transition flow.

### BL-025 - Classroom Admin and Assignment Workflows
- Type: Feature
- Priority: P1
- Effort: XL
- Status: Discovery
- Problem: Current product is reader-centric; it lacks teacher/admin controls needed for classroom deployment.
- Scope Buckets:
- Teacher/admin role model (class creation, student roster management, invite/enrollment flow).
- Classroom-level feature controls (enable/disable recap, quiz, AI features, media generation).
- Assignment workflows (assign books/chapters, due dates, required quiz completion).
- Teacher-authored quiz support (custom question sets, override/generated quiz coexistence).
- Classroom progress visibility (student in-progress/completed states, quiz outcomes, activity snapshots).
- Discovery Questions:
- Should teachers create student accounts directly, issue invite codes, or both?
- Which features must be controllable at class-level for pilot safety (for example recap off, quiz on)?
- How should teacher-authored quizzes interact with generated quizzes (replace, merge, or fallback)?
- What minimum reporting is needed for pilot value without overbuilding gradebook integrations?
- Current Direction (2026-02-15):
- Prioritize classroom pilot readiness over broad LMS integrations.
- Treat quiz workflows as classroom-positive and recap as classroom-optional/off by default based on early educator feedback.
- Sequence work so BL-021 account foundations unblock class roster and assignment capabilities.
- Exit Criteria for Discovery:
- Classroom architecture decision (roles, enrollment flow, class ownership boundaries).
- v1 classroom control matrix (which features are class-configurable).
- v1 assignment + quiz authoring scope with acceptance criteria for teacher and student flows.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-025.1 Classroom Domain Model + Roles | Proposed | Define entities/relationships for teacher, class, student enrollment, and role-based access boundaries | ADR + schema draft approved and role checks mapped to API surfaces |
| BL-025.2 Teacher Onboarding + Roster Management | Proposed | Build teacher class setup flow and student registration/invite/import patterns (post-account foundation) | Teacher can create class, enroll students, and manage active roster without manual DB operations |
| BL-025.3 Class Feature Controls | Proposed | Add class-level toggles (quiz/recap/AI/media capabilities) with policy enforcement in UI + API | Teacher settings deterministically govern student feature availability per class |
| BL-025.4 Assignment Workflow v1 | Proposed | Support assigning books/chapters, due windows, and required completion/quiz states | Teacher can publish assignments and students see clear due/required states in app |
| BL-025.5 Teacher-Authored Quiz Authoring | Proposed | Enable teacher custom quiz creation/editing and define coexistence with generated quizzes (override/merge/fallback) | Students receive expected quiz source by class policy and teacher can preview/publish updates |
| BL-025.6 Classroom Progress + Insights | Proposed | Provide teacher dashboard for assignment completion, in-progress state, and quiz outcomes (pilot-level reporting) | Teacher can quickly identify struggling or incomplete students without external tooling |
- Dependency Notes:
- BL-021 (`User Registration and Account System`) is a prerequisite for BL-025.2 onward.
- BL-025.3 and BL-025.4 should extend BL-018.6 classroom context hooks with full class policy + assignment signal integration.

## P0

### BL-001 - Secure and rate-limit generation/chat endpoints
- Type: Tech Debt
- Priority: P0
- Effort: L
- Status: Done
- Problem: Expensive endpoints (`tts`, `illustrations`, `characters`, `pregen`) are callable without auth/rate controls.
- Current Direction (2026-02-14):
- Implement a deployment-mode-aware guardrail layer for non-local profiles first:
- Add request auth gate for sensitive generation/chat endpoints when `deployment.mode=public`.
- Add per-IP rate limiting for generation + chat endpoints with conservative defaults and explicit 429 payloads.
- Keep local/dev behavior unchanged by default to preserve current iteration speed.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-001.1 Public-mode API guardrails | Done | Add centralized endpoint matcher + interceptor enforcing `X-API-Key` in `deployment.mode=public`; add per-IP rate limits for sensitive generation/chat routes with 429 responses | Sensitive generation/chat routes reject missing/invalid key in public mode and enforce configured request limits |
| BL-001.2 Collaborator session auth | Done | Add browser-usable collaborator auth via `/api/auth/login` + HttpOnly session cookie, and allow sensitive public endpoints to authenticate with either API key or collaborator session | Collaborators can authenticate in-app and access protected generation/chat endpoints without exposing server API key in frontend code |
| BL-001.3 Rate-limit model expansion | Done | Add auth-identity-aware limiter keys and per-authenticated-principal limits; evaluate external/durable limiter backing for multi-instance deployments | Limits are aligned to authenticated identity and resilient across replicas |
- Session Log:
- 2026-02-14: Implemented BL-001.1 with `PublicApiGuardInterceptor`, sensitive route matcher, and in-memory per-IP fixed-window limiter; added `deployment.mode`/`security.public.*` properties with local-safe defaults.
- 2026-02-14: Added coverage for route classification and interceptor behavior in `SensitiveApiRequestMatcherTest`, `PublicApiGuardInterceptorPublicModeTest`, and `PublicApiGuardInterceptorLocalModeTest`; validated with full `mvn test`.
- 2026-02-14: Implemented BL-001.2 with `PublicSessionAuthService`, `/api/auth` login/status/logout endpoints, and interceptor support for either `X-API-Key` or collaborator session auth in public mode.
- 2026-02-14: Added collaborator sign-in modal in `reader.js`/`index.html` and validated auth + guardrails with `AuthControllerTest` and `PublicApiGuardInterceptorSessionAuthTest` plus full `mvn test`.
- 2026-02-14: Implemented BL-001.3 identity-aware limiter scope in `PublicApiGuardInterceptor` (API key/session principal scoped keys + authenticated limit properties); added `rateLimit_isScopedPerCollaboratorSession` coverage and validated with full `mvn test`.
- 2026-02-14: Completed BL-001.3 durable limiter backing by adding `DatabaseRateLimiter` + `rate_limit_windows` Flyway migration (`V2__rate_limit_windows.sql`) with `security.public.rate-limit.store=database` in prod profiles; added `DatabaseRateLimiterTest` and validated with full `mvn test`.
- Acceptance Criteria:
- Add authentication/authorization strategy for non-local deployments.
- Add per-IP or per-user rate limits for generation and chat endpoints.
- Add safe defaults when deployment mode is `public`.

### BL-002 - Replace in-memory generation queues with durable job orchestration
- Type: Tech Debt
- Priority: P0
- Effort: XL
- Status: Done
- Problem: Illustration/character work queues run in-process with single-thread executors and are vulnerable to restart loss.
- Current Direction (2026-02-14):
- Deliver BL-002 incrementally while preserving existing generation behavior:
- Add startup recovery that rehydrates queued work from persisted DB state (`PENDING` + stuck `GENERATING`) for illustration/character/recap pipelines.
- Then replace in-memory queue ownership with durable job leasing + retry/backoff policies.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-002.1 Startup queue recovery | Done | Add app-start recovery service that requeues persisted pending/stuck generation work across books | Restarting the app rehydrates generation queues without manual operator requeue |
| BL-002.2 Durable worker leasing | Done | Introduce durable lease claims so multi-instance workers coordinate safely and avoid duplicate processing | Queue ownership survives restarts and prevents duplicate work across replicas |
| BL-002.2a Recap lease claims | Done | Add DB-backed lease claim on chapter recap jobs before processing, with lease release on terminal states | Concurrent workers cannot both process the same recap unless lease expires |
| BL-002.2b Illustration/character lease claims | Done | Extend durable lease claims to illustration and character queues | Illustration/character pipelines have the same cross-instance coordination guarantees as recaps |
| BL-002.3 Retry/backoff + status API | Done | Add explicit retry/backoff policy and aggregate job status endpoint/query model | Retry behavior is explicit/test-covered and job state is queryable without log inspection |
- Session Log:
- 2026-02-14: Added `GenerationQueueRecoveryService` (startup orchestrator) to requeue persisted illustration/portrait/analysis/recap work from DB state and added `GenerationQueueRecoveryServiceTest`; validated with full `mvn test`.
- 2026-02-14: Added recap durable lease claims in `ChapterRecapService`/`ChapterRecapRepository` (`claimGenerationLease`, `leaseOwner`, `leaseExpiresAt`) with worker identity + lease duration config; added coverage in `ChapterRecapServiceTest`.
- 2026-02-14: Added durable lease claims for `IllustrationService` and `CharacterService` pipelines (portrait + chapter analysis) with atomic repository claim queries and lease cleanup on terminal transitions; added `GenerationLeaseClaimRepositoryTest` for illustration/portrait/analysis claim behavior.
- 2026-02-14: Implemented DB-backed retry/backoff metadata (`retryCount`, `nextRetryAt`) for recap/illustration/portrait/analysis jobs, added exponential retry scheduling in generation services, and exposed aggregate status APIs at `/api/generation/status` and `/api/generation/book/{bookId}/status`; added coverage in `GenerationJobStatusServiceTest`, `GenerationStatusControllerTest`, and extended lease-claim/retry tests.
- 2026-02-14: Closed BL-002 after re-validating startup recovery, durable lease claims, retry/backoff behavior, and generation status APIs with targeted BL-002 tests (`GenerationQueueRecoveryServiceTest`, `GenerationLeaseClaimRepositoryTest`, `GenerationJobStatusServiceTest`, `GenerationStatusControllerTest`) plus full `mvn test`.
- Acceptance Criteria:
- Jobs persist across application restarts.
- Retry/backoff policies are explicit and test-covered.
- Job status can be queried without scraping logs.

### BL-003 - Expand automated test coverage for AI/media pipelines
- Type: Tech Debt
- Priority: P0
- Effort: L
- Status: Done
- Problem: Tests focus on import/search/parser; high-risk flows (TTS/illustration/character/pregen) have minimal coverage.
- Acceptance Criteria:
- Add controller tests for `TtsController`, `IllustrationController`, `CharacterController`, `PreGenerationController`.
- Add service-level tests for queue/retry/status transitions.
- Add smoke test profile for end-to-end happy path with mocked providers.
- Session Log:
- 2026-02-14: Added controller coverage for `TtsController`, `IllustrationController`, `CharacterController`, and `PreGenerationController` (including cache-only conflicts + feature gating paths) in `TtsControllerTest`, `IllustrationControllerTest`, `CharacterControllerTest`, `PreGenerationControllerTest`, and `PreGenerationControllerCacheOnlyTest`; added book-scoped retry/pending aggregate coverage in `GenerationJobStatusServiceTest`.
- 2026-02-14: Added `smoke` profile (`application-smoke.properties`) and `AiMediaPipelinesSmokeTest` as an end-to-end happy-path `@SpringBootTest` using mocked providers; validated via targeted smoke run and full `mvn test`.

### BL-004 - Migrate from H2 to production database
- Type: Tech Debt
- Priority: P0
- Effort: M
- Status: Done
- Problem: Runtime still depends on H2 + `ddl-auto=update`, which increases schema drift and production risk for a public deployment.
- Current Direction (2026-02-14):
- Execute immediate DB cutover work now rather than deferring.
- Default target is PostgreSQL unless explicitly switched to MariaDB before implementation starts.
- Keep H2 only for local/dev convenience and selected tests after migration ownership is in place.
- Acceptance Criteria:
- App runs against PostgreSQL or MariaDB in non-local environments with no H2 dependency for production runtime.
- Adopt Flyway or Liquibase migrations and baseline existing schema/history.
- Restrict seed data to explicit dev/test profiles.
- Replace `ddl-auto=update` with migration-owned schema management (`validate` or equivalent) outside local dev.
- Add rollback-safe migration and cutover guidance (backup, restore, verification checklist).
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-004.1 Engine + runtime config | Done | Finalize target engine (default PostgreSQL), add env-driven datasource profiles, and document local vs non-local DB behavior | Non-local profile starts cleanly on target engine using env vars only |
| BL-004.2 Migration baseline | Done | Introduce Flyway/Liquibase and baseline current schema so schema changes are migration-owned | Fresh DB and existing DB both reach expected schema via migrations |
| BL-004.3 Controlled seeding | Done | Move startup sample seed behavior to explicit dev/test profiles only | Production profile does not auto-seed sample books |
| BL-004.4 Cutover + rollback runbook | Done | Add deployment runbook for backup, cutover steps, verification queries, and rollback | Operator can execute and reverse cutover without ad-hoc DB edits |
| BL-004.5 Data transfer tooling | Done | Add one-time CLI to copy persisted app data from H2 into PostgreSQL/MariaDB with dry-run safety checks | Operator can migrate existing H2 data into target DB and verify copied row counts |
- Session Log:
- 2026-02-14: Reframed BL-004 as concrete production DB migration work, set status to `Ready`, and made PostgreSQL the default target unless explicitly changed to MariaDB.
- 2026-02-14: Started implementation: added Flyway baseline migration (`V1__baseline_schema.sql`), added PostgreSQL (`prod`) and MariaDB (`mariadb`) runtime profiles, and removed DB-specific `columnDefinition` defaults on retry fields/character type for portability.
- 2026-02-14: Restricted startup seeding to explicit `dev`/`test`/`smoke` profiles via `DataInitializer` profile gating and added DB cutover + rollback runbook at `docs/operations/db-cutover.md`.
- 2026-02-14: Fixed PostgreSQL 17 startup compatibility by adding `flyway-database-postgresql` and `flyway-mysql` modules; validated local PostgreSQL profile startup and added `DbMigrationRunner` + `DbMigrationRunnerTest` for one-time H2-to-target data copy.
- 2026-02-14: Completed H2 -> PostgreSQL migration on local environment via `DbMigrationRunner`; verified row counts match between source/target and validated app runtime using `prod` profile against PostgreSQL.

## P1

### BL-005 - Add notes, highlights, and bookmarks
- Type: Feature
- Priority: P1
- Effort: L
- Status: Done
- Problem: Reader currently lacks durable annotations/bookmarking despite being a core deep-reading need.
- Acceptance Criteria:
- Users can create/edit/delete highlights and notes per paragraph.
- Users can jump to bookmarks from a reader sidebar/modal.
- Data persists per book across sessions.
- Session Log:
- 2026-02-14: Implemented server-backed paragraph annotations/bookmarks with Flyway migration (`V3__paragraph_annotations.sql`), reader-profile cookie scoping, library annotation/bookmark APIs, and reader UI support for highlight/note/bookmark actions plus bookmark jump overlay and keyboard shortcuts.
- 2026-02-14: Streamlined reader controls by consolidating annotation actions into a single header menu and moving shortcut reference into a keyboard-help overlay (including `?` quick open).

### BL-006 - Reader preferences panel (typography/layout controls)
- Type: Feature
- Priority: P1
- Effort: M
- Status: Done
- Problem: Font size/line-height/theme/column-gap are static in practice.
- Acceptance Criteria:
- Add preferences UI for typography/layout controls.
- Persist preferences and apply on load.
- Re-pagination remains stable after setting changes.
- Session Log:
- 2026-02-14: Added a compact reader preferences gear menu beside search with persisted font size, line height, column gap, and theme controls (including reset), and wired preference changes to re-pagination while preserving current paragraph context.

### BL-007 - Library management UI for local books and feature toggles
- Type: Improvement
- Priority: P1
- Effort: M
- Status: Done
- Problem: Backend supports delete and feature toggles, but those operations must be restricted to admin-only workflows and not exposed to the public reader UI.
- Current Direction (2026-02-14):
- Keep reader-facing library UI read-only for shared/global cached books.
- Restrict library management endpoints to API-key-authenticated admin access in `deployment.mode=public`.
- Consider separate operator tooling (CLI/admin panel) for delete/toggle actions.
- Acceptance Criteria:
- Public reader UI does not expose delete/unimport or feature-toggle controls for shared cached books.
- Library management endpoints (`DELETE /api/library/{bookId}`, `DELETE /api/library`, `PATCH /api/library/{bookId}/features`) require admin API key in public mode.
- Operator/admin workflow for delete/toggle actions is documented (CLI or protected admin surface).
- Session Log:
- 2026-02-14: Re-scoped BL-007 to admin-only operations for shared cached books and added public-mode guardrail enforcement so library delete/feature-toggle endpoints require `X-API-Key` (collaborator session auth is not sufficient).
- 2026-02-14: Added `ADMIN` endpoint classification for library feature/delete routes, enforced API-key-only auth for those routes in `PublicApiGuardInterceptor`, and validated with `SensitiveApiRequestMatcherTest` + `PublicApiGuardInterceptorAdminOnlyTest` (plus targeted guard suite).

### BL-008 - Upgrade in-reader search quality and navigation
- Type: Improvement
- Priority: P1
- Effort: M
- Status: Done
- Problem: Snippets are fixed-length and ranking is generic; navigation context is limited.
- Acceptance Criteria:
- Improve snippet extraction around match location.
- Add optional chapter filter and result grouping.
- Highlight matched terms in displayed paragraph after navigation.
- Session Log:
- 2026-02-14: Upgraded `/api/search` to support optional `chapterId` filtering and context-aware snippets centered around matched terms; updated reader search UI with chapter filter controls and grouped-by-chapter result rendering; added in-paragraph search-term highlighting for selected result navigation. Validated with `SearchServiceTest`, `SearchControllerTest`, and `node --check src/main/resources/static/js/reader.js`.

### BL-009 - Make pre-generation non-blocking with progress API
- Type: Improvement
- Priority: P1
- Effort: L
- Status: Done
- Problem: Current pre-generation endpoint blocks while polling and sleeps in-process.
- Acceptance Criteria:
- Start job endpoint returns job ID immediately.
- Progress endpoint reports counts/state/errors.
- Frontend or CLI can poll/cancel safely.
- Session Log:
- 2026-02-14: Added async pre-generation job API (`POST /api/pregen/jobs/book/{bookId}`, `POST /api/pregen/jobs/gutenberg/{gutenbergId}`, `GET /api/pregen/jobs/{jobId}`, `POST /api/pregen/jobs/{jobId}/cancel`, `DELETE /api/pregen/jobs/{jobId}`) backed by `PreGenerationJobService`; progress snapshots now include generation counts via `GenerationJobStatusService`, and CLI workflow (`scripts/pregen_transfer_book.sh`) now polls/cancels jobs safely.

### BL-010 - Unify user-facing errors and retries
- Type: Improvement
- Priority: P1
- Effort: M
- Status: Done
- Problem: Frontend still relies on `alert()` and scattered error patterns.
- Acceptance Criteria:
- Replace blocking alerts with consistent toast/inline error components.
- Standardize retry affordances for import/search/generation failures.
- Map backend error payloads to clear UX states.
- Session Log:
- 2026-02-14: Added shared frontend error UX primitives (global toast region + inline error blocks), removed all `alert()` usage in reader flows, and introduced backend-aware error mapping (`message`/`error` payload keys + HTTP status) for search/import/illustration generation failures.
- 2026-02-14: Added standardized retry affordances for failed import actions (toast retry), in-reader search failures (inline retry), and illustration generation failures in both modal regeneration and chapter illustration panels (inline retry).
- 2026-02-14: Extended unified error/retry UX to recap and chat flows by adding inline error + retry controls for chapter recap loading failures, recap chat send failures, and character chat send failures with shared backend/status-aware message mapping.
- 2026-02-14: Added Playwright retry-flow coverage (`e2e/retry-flows.spec.js`) with deterministic `/api/*` mocks for recap overlay retry, recap chat retry, and character chat retry, plus local static test server + config (`playwright.config.js`, `e2e/static-server.js`).
- 2026-02-14: Wired Playwright retry-flow suite into CI with GitHub Actions (`.github/workflows/playwright-e2e.yml`) to run on pull requests and pushes to `main`.
- 2026-02-14: Added backend CI workflow (`.github/workflows/maven-test.yml`) to run `mvn test` on Java 21 for pull requests and pushes to `main`.

### BL-011 - Add observability for long-running generation flows
- Type: Tech Debt
- Priority: P1
- Effort: M
- Status: Done
- Problem: Debugging relies mostly on logs; queue/backlog metrics are not first-class.
- Acceptance Criteria:
- Expose metrics for queue depth, success/failure counts, and processing latency.
- Add correlation IDs to generation requests.
- Add health detail endpoint for provider and queue status.
- Session Log:
- 2026-02-15: Added request correlation infrastructure (`X-Request-Id` filter + request attribute + MDC) and included request IDs in quiz/recap endpoint failure diagnostics.
- 2026-02-15: Added `/health/details` with provider availability, queue processor health, per-pipeline queue depths, global generation status snapshot, and recap/quiz metric snapshots.
- 2026-02-15: Added quiz observability metrics (`generationRequested`, `generationCompleted`, `generationFallbackCompleted`, `generationFailed`, `generationAverageLatencyMs`, read failure counters) and exposed quiz queue depth/processor state in `/api/quizzes/status`.

### BL-028 - Account auth endpoint hardening
- Type: Tech Debt
- Priority: P1
- Effort: M
- Status: Done
- Problem: Reader account endpoints currently lack dedicated anti-abuse throttling and structured auth audit events required by the BL-021 security ADR.
- Acceptance Criteria:
- Add per-IP and per-email rate limiting for `/api/account/register` and `/api/account/login` with explicit `429` responses and `Retry-After` headers.
- Add temporary lockout/backoff for repeated invalid account credentials to reduce brute-force risk.
- Emit structured non-PII account auth audit events for register/login/logout/claim-sync outcomes, including rollout-restricted attempts.
- Add controller/service tests that cover throttle/lockout behavior and audit event emission paths.
- Notes/Dependencies:
- Align implementation with `docs/product/bl-021-auth-architecture-adr.md` section `4. Enforce baseline account security controls`.
- Session Log:
- 2026-02-19: Completed BL-028 by adding in-memory per-IP/per-email throttling for `/api/account/register` and `/api/account/login` with explicit `429` + `Retry-After`, persistent login lockout/backoff state on `users` (`failed_login_attempts`, `login_locked_until`) with exponential delay controls, and structured non-PII account auth audit events for register/login/logout/claim-sync outcomes (including rollout-restricted/rate-limited/unauthorized paths); validated with targeted controller/service tests and full `mvn test`.

### BL-023 - Adaptive mobile reader experience
- Type: Feature
- Priority: P1
- Effort: L
- Status: Done
- Problem: Reader interactions assume keyboard + desktop viewport, causing friction and broken affordances on phones.
- Acceptance Criteria:
- Preserve existing desktop keyboard shortcuts and behavior (`h/l`, `j/k`, `H/L`, `/`, `c`) for non-mobile layouts.
- Provide touch-first mobile navigation for page/paragraph/chapter progression without keyboard dependency.
- Add capability checks so desktop-centric features can be disabled on mobile when needed, with clear UI fallback messaging.
- Ensure chapter list and chapter-pause overlays remain usable on common phone breakpoints while keeping continue/skip/submit actions accessible.
- Add a mobile QA checklist (iOS Safari + Android Chrome) and desktop regression checklist for keyboard flows.
- Notes/Dependencies:
- Coordinate with BL-013 so accessibility/focus changes ship alongside mobile interaction updates.
- Session Log:
- 2026-02-15: Implemented BL-023 first slice with capability detection for mobile/touch layouts, mobile-only touch navigation controls (chapter/page/paragraph + chapter list), and responsive reader/chapter-overlay styling tuned for phone breakpoints.
- 2026-02-15: Added mobile fallback messaging by hiding desktop shortcut affordance in touch layout, showing a touch-navigation status hint, and switching chapter-list instructions to tap-first copy.
- 2026-02-15: Added mobile header hamburger menu for reader actions (TTS/speed/illustration/character/settings/annotation/auth/recap controls) and moved icon-heavy header actions behind it on mobile so book title remains visible.
- 2026-02-15: Moved chapter search into the mobile hamburger panel and removed always-visible mobile header search row to reclaim vertical reader space without changing desktop search behavior.
- 2026-02-15: Updated mobile hamburger search behavior to auto-close the menu once a valid query is entered so search results are immediately visible (no manual menu dismissal needed).
- 2026-02-15: Reworked mobile hamburger search UX to explicit submit: typing no longer dismisses the menu; a new `Search` button (and Enter key) runs search and then closes the menu.
- 2026-02-15: Fixed mobile `Reader Preferences` launch reliability after search navigation by forcing the menu action to open settings (instead of toggle) and stopping click propagation that could prematurely close the panel.
- 2026-02-15: Fixed mobile search-result overlap with `Reader Preferences` by preventing auto-search on mobile search-input focus, not restoring stale query text when reopening the hamburger menu, and force-hiding search results when preferences open.
- 2026-02-15: Fixed mobile `Reader Preferences` layering by raising the mobile settings host/panel z-index stack above reader/search/content overlays; this prevents highlighted paragraph/search content from painting over slider rows.
- 2026-02-15: Added BL-023 validation checklist at `docs/product/bl-023-qa-checklist.md` covering iOS Safari + Android Chrome mobile QA and desktop keyboard regression checks.
- 2026-02-15: Addressed iOS QA defects: added compact mobile-landscape styling to preserve reading area, prevented empty first-page pagination when a paragraph exceeds viewport height, introduced smaller mobile default reader preferences, and added touch-action/text-size guards to reduce accidental zoom during repeated touch navigation.
- 2026-02-15: Completed BL-023 after manual checklist validation passed on iOS simulator (portrait + landscape fallback) and desktop keyboard regression flows; no blocking defects remained.
- 2026-02-15: Post-validation mobile nav simplification: removed chapter +/- and paragraph +/- touch buttons, leaving a single-row touch nav (`Page -`, `Chapters`, `Page +`) to reclaim vertical space.

### BL-024 - Cache Transfer + Remote Deploy Automation
- Type: Improvement
- Priority: P1
- Effort: M
- Status: Done
- Problem: Moving pre-generated recap data between local and remote environments required manual, error-prone CLI sequences and ad-hoc deployment/import steps.
- Acceptance Criteria:
- Provide a recap cache transfer CLI with dry-run/apply safety, conflict policy controls, and stable book/chapter matching semantics.
- Provide operator scripts for book-level pregen/export/import flow and local-to-remote transfer orchestration over SSH.
- Support remote execution without requiring Maven when a deployed Spring Boot jar is available.
- Validate transfer/import on the production-like target flow and document usage.
- Scope Notes:
- v1 transfer scope includes recap + quiz metadata (`chapter_recaps`, `chapter_quizzes` payload/status data).
- Binary assets (audio/illustrations/portraits) remain managed via Spaces sync (`scripts/sync_spaces.sh`).
- Session Log:
- 2026-02-12: Implemented `org.example.reader.cli.CacheTransferRunner` with `export`/`import`, `skip|overwrite` conflict handling, format validation, dry-run default, and H2 URL normalization (`DB_CLOSE_ON_EXIT=FALSE`) to avoid exec-classloader shutdown issues.
- 2026-02-12: Added recap transfer coverage in `CacheTransferRunnerTest` including all-cached export, multi-book export, dry-run immutability, and conflict policy behavior.
- 2026-02-12: Added operator scripts `scripts/pregen_transfer_book.sh`, `scripts/transfer_recaps_remote.sh`, and `scripts/deploy_remote.sh`; documented workflows in `README.md`.
- 2026-02-12: Hardened remote transfer script for SSH alias/config usage, strict-mode bash handling, project-root Maven execution, robust remote arg transport, jar-runner fallback (via Spring Boot `PropertiesLauncher`), and remote service stop/start orchestration.
- 2026-02-12: Validated end-to-end transfer flow against remote target with successful dry-run import summary (`21` books, `1768` recaps, `0` validation errors) and successful apply import run.
- 2026-02-12: Added `scripts/pregen_quizzes_book.sh` and `docs/operations/pre-generation-runbook.md` to document and automate quiz pre-generation alongside existing image/portrait/recap workflows.
- 2026-02-12: Added `scripts/pregen_quizzes_top20.sh` to import + pre-generate quizzes for the top-20 Gutenberg set, with server-direct execution guidance in the runbook.
- 2026-02-12: Extended `CacheTransferRunner` and `scripts/transfer_recaps_remote.sh` to support `--feature quizzes` export/import so locally generated quizzes can be promoted to remote DB without paid server-side generation.
- 2026-02-14: Extended cache transfer tooling to support illustration + portrait metadata promotion (`--feature illustrations|portraits`) and updated remote orchestration to run full multi-feature transfers (`--feature all`); added `scripts/publish_book_remote.sh` as a one-command workflow for local pregen + Spaces sync + remote DB promotion.

### BL-017 - Post-Chapter Recap + Discussion Experience
- Type: Feature
- Priority: P1
- Effort: XL
- Status: Done
- Problem: Readers need structured comprehension support between chapters without breaking immersion.
- Implementation Plan:
- Phase 1 (Data + Contracts): Add recap persistence model per `bookId/chapterId` with immutable generated payload, status, timestamps, and prompt/version metadata; add recap retrieval/status APIs and typed frontend response models.
- Phase 2 (Generation Pipeline): Implement hybrid generation path: pre-generate recaps during batch pre-generation for top-N books and generate on-demand fallback on first chapter completion for non-pre-generated books.
- Phase 3 (Reader UX): Add chapter-transition recap screen in `reader.js` with short default recap, expandable detail (key events + character deltas), and explicit continue CTA to next chapter.
- Phase 4 (Discussion Chat): Add chapter-bounded discussion endpoint that only uses text from chapters `<= currentChapterIndex` and recap payload for context; persist thread locally like character chat.
- Phase 5 (Safety + Quality): Add spoiler and hallucination controls (context window hard cap, chapter index guard, refusal/fallback response); add regeneration only via explicit admin/CLI action so recap stays static for readers.
- Phase 6 (Rollout + Ops): Ship behind feature flag, enable first for pre-generated books, then expand to on-demand fallback after latency/error thresholds are met.
- Acceptance Criteria:
- Recap API returns consistent, static recap payload for a chapter after first successful generation.
- Recap payload includes short summary, key events, and character development deltas with structured fields.
- Pre-generation flow queues recap generation alongside existing pipelines and reports recap progress in status output.
- On-demand recap generation is triggered once per chapter when missing and does not block chapter navigation.
- Chapter discussion responses never reference content from future chapters and return a guarded fallback when request context is invalid.
- Reader UI shows recap between chapters with opt-out toggle and allows users to skip directly to next chapter.
- Feature can be disabled globally and per book without breaking existing reader flow.
- Dependencies:
- BL-009 (non-blocking pre-generation progress API) to expose recap generation state cleanly.
- BL-010 (unified error/retry UX) to handle recap/chat failures without `alert()` regressions.
- Existing character analysis/pre-generation queue patterns (`CharacterService`, `PreGenerationService`) reused for recap job orchestration.
- Risks:
- LLM cost/latency spikes from on-demand recap generation on long chapters.
- False-positive spoiler leakage if chapter-bound context checks are incomplete.
- Reader transition fatigue if recap interrupts users who prefer continuous reading.
- Rollout Notes:
- Start with recap format `short + key events + character deltas`; defer alternate formats (timeline/bullets variants) to follow-up.
- Default rollout: enabled for top-N pre-generated books only; on-demand fallback toggled on after one release cycle of metrics.
- Add instrumentation for generation latency, failure rate, spoiler-guard fallback rate, and recap view/skip rate before widening rollout.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-017.1 Recap Data + API | Done | Recap entity/repo/service + read/status endpoints + payload schema | Endpoints return stable recap payload; controller/service tests pass |
| BL-017.2 Generation Integration | Done | Hook recap generation into pre-generation + on-demand fallback | Recaps generate in both paths; progress/state visible |
| BL-017.3 Reader Transition UI | Done | Post-chapter recap screen, skip/continue flow, opt-out toggle | Reader can view recap or skip with no nav regressions |
| BL-017.4 Bounded Discussion Chat | Done | Chapter-bounded chat endpoint + frontend thread persistence | Chat never leaks future-chapter content; fallback responses handled |
| BL-017.5 Guardrails + Rollout | Done | Spoiler guards, feature flags, metrics, rollout toggles | Flags/metrics wired, recap-only pregen stable, and manual spoiler checks passed on tested books |
- Session Log:
- 2026-02-08: Moved BL-017 from `Discovery` to `Ready` with concrete implementation plan.
- 2026-02-08: Added minimal slice tracker and dated log to support pause/resume execution.
- 2026-02-08: Implemented BL-017.1 with `chapter_recaps` persistence, recap payload schema, `/api/recaps` read/status endpoints, and passing controller/service tests.
- 2026-02-08: Implemented BL-017.2 with async recap generation queue, pre-generation integration, recap status metrics in pregen results, and passing recap/pregen tests.
- 2026-02-08: Started BL-017.3 with first-pass chapter recap transition overlay, skip/continue actions, and per-book recap opt-out toggle in reader UI.
- 2026-02-08: Started BL-017.4 with chapter-bounded recap chat API contract (`/api/recaps/book/{bookId}/chat`) and context-guard service tests.
- 2026-02-08: Upgraded recap generation to reasoning-LLM JSON output with extractive fallback and added recap service tests for provider path.
- 2026-02-08: Completed BL-017.4 frontend work by adding recap discussion UI and per-book local thread persistence in reader recap overlay.
- 2026-02-08: Updated recap overlay UX to auto-refresh while status is `MISSING/PENDING/GENERATING`, stopping polling once terminal status is reached or overlay closes.
- 2026-02-08: Refined recap overlay into two tabs (`Recap` default, `Chat`) to improve focus now and leave clean UI space for future `Pop Quiz` expansion.
- 2026-02-08: Started BL-017.5 by adding rollout gating modes (`all`, `allow-list`, `pre-generated`), per-book recap availability endpoint, and recap metrics capture (generation/chat/modal events) with status visibility.
- 2026-02-08: Added recap-specific reasoning provider config so recaps can use Ollama independently of other reasoning tasks (cost-control path for recap generation).
- 2026-02-08: Added recap-only pre-generation mode for batch runner to support top-20 recap generation without triggering illustration/portrait generation.
- 2026-02-11: Stabilized recap pre-generation by preventing duplicate queue entries, skipping already-completed recaps, and resetting only stale `GENERATING` recaps (plus configurable stall thresholds).
- 2026-02-11: Completed recap UX polish with persistent modal chrome + scrollable body, recap/chat tab flow polish, and a reader header control to re-enable per-book recap popups after opt-out.
- 2026-02-11: Hardened recap chat guardrails by enforcing source-only prompt behavior and chapter-scoped local chat history; validated behavior in manual QA on tested books.
- 2026-02-11: Marked BL-017 as Done; next feature work continues under BL-020 (Post-Chapter Pop Quiz).

## P2

### BL-012 - Split `reader.js` into modules and add frontend tests
- Type: Tech Debt
- Priority: P2
- Effort: L
- Status: Proposed
- Problem: Reader logic is monolithic, increasing regression risk and slowing iteration.
- Acceptance Criteria:
- Break reader logic into coherent modules (library/reader/tts/illustration/character).
- Add unit tests for pagination, keyboard handling, and persistence helpers.
- Keep existing keyboard behavior unchanged.

### BL-013 - Accessibility and mobile optimization pass
- Type: Improvement
- Priority: P2
- Effort: M
- Status: Proposed
- Problem: Complex overlays and keyboard flows need explicit a11y and mobile verification.
- Acceptance Criteria:
- Add ARIA labels/focus management for all modals/overlays.
- Validate reader and overlays on common mobile breakpoints.
- Document keyboard shortcuts/help discoverability in UI.

### BL-014 - Automated asset lifecycle management
- Type: Tech Debt
- Priority: P2
- Effort: M
- Status: Proposed
- Problem: Asset cleanup/migration is manual via CLI; no retention or scheduled pruning.
- Acceptance Criteria:
- Define retention policy for stale assets.
- Add safe dry-run + scheduled cleanup workflow.
- Emit cleanup summary metrics/logs.

### BL-015 - API documentation and integration contract
- Type: Improvement
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: API surface is broad and mostly discoverable only from code.
- Acceptance Criteria:
- Publish OpenAPI spec for current controllers.
- Add examples for import/search/tts/illustration/character flows.
- Add versioning/change log policy for API-breaking changes.

### BL-016 - Add secondary import source support (EPUB/Standard Ebooks)
- Type: Feature
- Priority: P2
- Effort: L
- Status: Proposed
- Problem: Import is currently Gutenberg-centric.
- Acceptance Criteria:
- Support one additional source (EPUB or Standard Ebooks).
- Normalize metadata/chapters into existing schema.
- Preserve source attribution and de-dup behavior.

### BL-026 - Site branding logo
- Type: Improvement
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: The site lacks clear visual branding in the primary header and landing context.
- Acceptance Criteria:
- Display a site logo in the header on landing and reader views without breaking responsive layout.
- Support the provided logo image asset with appropriate alt text and loading behavior.
- Keep existing navigation/search/account controls fully functional on desktop and mobile after logo integration.
- Notes/Dependencies:
- Use the provided image file from product/design for final asset integration.

### BL-027 - Reader support CTA (Buy Me a Coffee)
- Type: Feature
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: Readers currently have no direct way to financially support the project from the UI.
- Acceptance Criteria:
- Add a visible `Buy Me a Coffee` CTA that opens the configured support URL in a new tab.
- Ensure CTA placement is accessible and does not interfere with core reading/navigation actions on mobile or desktop.
- Provide a configuration path for support URL/visibility so deployments can enable, disable, or change destination without code edits.

### BL-029 - MLA Citation Button
- Type: Feature
- Priority: P2
- Effort: S
- Status: In Progress
- Problem: Readers need a quick way to produce source citations without leaving the reading flow.
- Implementation Plan:
- Phase 1 (Citation Formatter): Add a small MLA citation formatter utility that builds output from existing book metadata and omits missing fields safely.
- Phase 2 (Reader UI): Add an `MLA Citation` action in the book metadata/detail surface with clipboard copy behavior and inline success/error feedback.
- Phase 3 (Validation): Add focused tests for formatter edge cases and UI copy interaction to avoid regressions.
- Acceptance Criteria:
- Add an `MLA Citation` button on the book detail/metadata surface.
- Clicking the button generates an MLA-formatted citation from available book metadata and copies it to clipboard.
- Show inline success feedback (for example, `Copied`) after copy completes.
- Handle missing metadata gracefully by omitting unavailable fields while preserving a valid citation structure.
- Ensure desktop and mobile behavior work without regressing existing reader controls.
- Notes/Dependencies:
- Final MLA edition target (8th vs 9th) and fallback display mode (copy-only vs copy + modal) will be decided during implementation.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-029.1 Citation formatter utility | Done | Implement deterministic MLA formatter using current metadata model with safe fallbacks for missing fields | Formatter returns stable output for complete and partial metadata inputs |
| BL-029.2 Citation button + clipboard UX | Done | Add `MLA Citation` button to metadata surface; copy generated citation and show inline feedback state | User can copy citation in one click on desktop/mobile with visible success/failure feedback |
| BL-029.3 Tests + QA checks | Done | Add backend/frontend tests for formatting and clipboard interaction, including missing-metadata paths | Targeted tests pass and no regressions in existing reader controls |
| BL-029.4 Citation preview toast | Done | Show a post-copy toast that includes the formatted citation text so users can visually confirm what was copied | Copy flow shows readable citation preview in toast while preserving non-blocking reader interaction on desktop/mobile |
- Session Log:
- 2026-02-26: Completed BL-029.1 by adding `MlaCitationFormatter` with deterministic MLA output for complete/partial metadata, author-name normalization, month formatting for `Accessed` dates, and passing formatter unit tests (`MlaCitationFormatterTest`).
- 2026-02-26: Completed BL-029.2 by adding `GET /api/library/{bookId}/citation/mla`, wiring reader `Copy MLA Citation` action into the reader actions menu, adding `x` keyboard shortcut + shortcut help entry, and surfacing copy success/failure with existing app toast feedback.
- 2026-02-26: Completed BL-029.3 by adding frontend citation utility tests (`src/test/frontend/citation-utils.test.cjs`) for preview formatting and clipboard API/fallback behavior, wiring `citation-utils.js` into reader boot, and validating with `npm run frontend:test` + syntax checks.
- 2026-02-26: Completed BL-029.4 by enhancing citation-copy success toasts with a readable MLA citation preview (with safe truncation for long values) so users can visually confirm clipboard content without blocking reader flow.
