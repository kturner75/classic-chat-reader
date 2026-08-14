# Product Backlog

Last updated: 2026-08-14

## Implementation handoff (classroom)

**Active branch (BL-025 foundation):** work lands via PR from `feature/classroom-data-model-design` (or successor). Design: `docs/product/bl-025-classroom-data-model.md`.

**Done in first code slice (safe / additive):**
- Flyway `V14__classroom_domain.sql` + JPA entities/repos for pilot tables
- Authz, invite issue/redeem, class bootstrap, feature + assignment APIs, context dual-read (`classroom.mode`)
- Default behavior unchanged when no DB membership and demo off: `GET /api/classroom/context` still returns not enrolled
- Additive JSON fields on context: `termId`, `role` (preserved by the frontend normalizer)

**Done in classroom UI demo slice (2026-07-13):**
- Reusable Library/Teaching workspace navigation for signed-in accounts
- Dedicated `/teacher` workspace with first-class onboarding and class/term selection
- Teacher UI for class creation, student join-link copy, active roster, independent feature controls, and assignment create/edit/publish/archive
- Student `Join a class` flow accepts either an invite code or copied join URL and refreshes classroom-aware Library assignments after redeem
- Roster API returns account email as a teacher-readable label; classroom context frontend now preserves `termId` and `role`
- Pilot class creation seeds the partner-requested combination `quiz=true`, `recap=false` while retaining independent controls
- Manual teacher-to-student walkthrough verified class creation, invite redemption, roster visibility, assignment publishing, and published assignment visibility in the student's Library

**Done (2026-07-11 / context reload fix):**
- Classroom context + Library now reload after account login/logout/register identity changes (`reader.js`), so enrolled students see published assignments without a hard refresh. Playwright coverage in `e2e/account-auth.spec.js`.
- Partner/grant-facing pilot pitch doc: `docs/product/classroom-pilot-pitch.md`.

**Done (2026-07-14 / teacher capability gate):**
- Flyway V15 adds durable account-level capabilities, keeping global `CREATE_CLASSROOM` authorization separate from contextual term teacher memberships.
- `GET /api/classroom/capabilities` drives Library/Teaching surfaces; students no longer see Teaching navigation and direct `/teacher` access shows a clear denial state.
- Class creation is enforced server-side before any classroom data is written. Existing active teacher-like memberships retain workspace access but do not independently grant creation of additional classes.
- `scripts/manage_teacher_access.sh grant|revoke|status <email>` provisions existing demo accounts without storing email addresses in source or deployment properties.

**Known demo issues found during walkthrough (2026-07-13 / 2026-07-16 partner call):**
- ~~Signing into a reader page that is already open does not reload classroom context~~ **Fixed** (see above).
- ~~Assignment Library cards opened via resume progress instead of the assigned chapter~~ **Fixed** on `fix/classroom-assignment-open-chapter`: assignment cards now open the teacher-targeted chapter (`chapterId` preferred, then `chapterIndex`) instead of the student's last resume position.
- ~~Assignment completion can temporarily show **2/3 complete** and **Quiz required** after the student completes reading, quiz, and character chat.~~ **Fixed in BL-047:** refreshed classroom context rerenders the visible Library immediately, so the first return shows **3/3 complete**.
- ~~The teacher workspace can show **Reading Buddy enabled** while the deployment-wide rollout is off without explaining that students cannot use it.~~ **Fixed in BL-048:** the saved policy remains intact, while the control clearly shows deployment unavailability and student settings remain unusable.
- The local Library contains duplicate/malformed **Pride and Prejudice** imports (3 chapters and 59 chapters rather than the expected 61); tracked in `BL-046`. Use the fuller edition for the demo and avoid presenting the current chapter list as production-ready.
- Assignment v1 is a working pilot path, not a complete LMS workflow: creation/edit/publish and student due/quiz signals work, while submission/grading, durable assignment-specific completion, notifications, and teacher reporting remain future work.

**Partner feedback (2026-07-16 educator call — Jessica):**
- Classroom setup + assignments landed well; partner was impressed with the demo path.
- **Bug confirmed in local demo:** assignment open could land on the wrong chapter when the student already had progress in the book (e.g. assigned Chapter 1 of Pride and Prejudice, opened Chapter II via resume). Fix above.
- **Product ask (elevates BL-025.11):** optionally **require character chat** on an assignment for classroom **show-and-tell**; students need a **downloadable conversation artifact** (text/Markdown export) so they can bring the chat to class without screenshots. Prefer student self-serve download first; teacher bulk view can follow FERPA path.
- Continue fleshing the pilot path; first-pass demo slice is the right track.
- **Broader demo interest:** another teacher + an AI-committee administrator want to meet for a demo later. Kevin will **not** set a multi-person demo date until classroom is more fully fleshed and known bugs are fixed. Next 1:1 partner check-in: **Tuesday 6pm** (America/Chicago) after the 2026-07-16 call (not Thursday that week).

**Partner feedback (2026-07-22 educator call — Jessica):**
- Demo path (require character chat + transcript download + assignment progress) landed well enough to continue pilot conversation.
- **My Chats surface:** wants a **My Chats** entry on the signed-in landing near **Achievements** — either a recent-chats list or a link to a dedicated My Chats page. Elevates `BL-039` / `BL-032` (use product name **My Chats**).
- **Cross-device chat:** character chats must be **server-persisted** so students can resume regardless of device. Today remains localStorage-only. New epic `BL-049` (prerequisite for durable assignment completion + teacher export path).
- **Bug:** increasing **font size** in reader preferences updates text but **paragraph content can clip**. New bug `BL-050` (regression on `BL-006` preferences/re-pagination).
- **Multi-teacher demo readiness:** Jessica believes with this round (including My Chats + server chat persistence + font-clip fix) the product is **good enough to show other teachers**. Target: next 1:1 **Tuesday 2026-07-28 6pm America/Chicago**, with those items addressed if possible before she coordinates the broader teacher demo.

**Partner feedback (2026-08-06 educator call — Jessica):**
- Call went well; **My Chats** walkthrough was the centerpiece (shipped on prod). Partner feedback from this call is captured below (this block is the full 2026-08-06 set unless later notes are appended).
- **Teacher quiz authoring (`BL-025.5` expanded):** teacher must be able to **override and define their own quiz questions** for a book/chapter (class-scoped). **Multiple choice only** for now (no free-response / short answer in this slice).
- **Authoring UX:** prefer a **wizard / stepped process**. First step stubs **N** default quiz question slots (N from teacher defaults — see `BL-025.13`). For each question the teacher may:
  - **Manually enter** the question stem, options, and correct answer; and/or
  - **Use AI to suggest** questions from chapter content, with full teacher override of stems and answers; and/or
  - **Use AI to generate wrong (distractor) answers** while the teacher controls the stem and correct answer.
- **Assignment pass rules (`BL-025.12`):** teacher may set a **minimum quiz score** (e.g. **7/10**). When a minimum is required, teacher must also set **maximum retry attempts** (`0` = no retries after the first attempt).
- **Teacher quiz defaults (`BL-025.13`):** teacher-configurable defaults for number of questions, minimum passing score, **maximum retry attempts** (same unit as `BL-025.12`; **`0` = no retries** / initial attempt only), and default count of multiple-choice options/answers.
- **Student display name (`BL-025.2` / roster identity):** optionally track a **student name** in addition to **email** (email remains required for the account). Name may be entered **optionally at registration** and/or set by the teacher as a **roster override** when email alone is not enough to identify the student in class.
- **Teacher → student overview (`BL-025.10` expanded):** from the teacher dashboard, drill into a student and show at least:
  1. **Current (active/open) assignments**
  2. **Completed assignments**
  3. **Time spent in the book** (partner-suggested engagement proxy; product caveat: weak engagement signal — keep measurable but labeled carefully; discuss better signals later)
  4. **Progress by book** (e.g. chapter *n/n* and/or **% complete**)
  5. **Quizzes for the book:** count complete, **scores**, and **retry attempts**
  6. **Assignment progress / open state:** teacher must see whether the student has **even opened/clicked into** the assignment (not only finished checklist items)
- **Multi-teacher / AI council demo window:** teachers meet the week of **2026-08-17 – 2026-08-21**; expect to schedule a **group demo** with Jessica + other teachers + **AI council** members — classroom + assignment walkthrough on prod. Need **reasonable pricing that at least covers costs** for that conversation (not necessarily a polished storefront).
- **AI usage + cost metrics (`BL-042`, lower product priority than classroom UX but time-bound for pricing):** track **user activity** with emphasis on **AI usage** so cost estimates are evidence-based. Fixed monthly floor is known (DigitalOcean **droplet**, **Spaces**, **managed DB**); **variable cost is AI tokens/requests** (chat, voice, quiz gen, etc.). Need real measured usage to support **per-term / seat + pooled AI budget** pricing lean before the group demo.
- **Fall 2026 pilot timing:** semester starts **2026-08-24**. Jessica would **like** to use the site at the **beginning of the semester**, but if not fully ready that is OK — the class **does not start reading books until mid-semester**; they **begin with short stories**.
- **Short stories for curated catalog (`BL-052`):** Kevin asked Jessica for the **list of short stories scheduled for her class** so they can be added to the **curated** list (import + flags + assets as needed). **Schedule received 2026-08-11**; **Done** after PR #101 (2026-08-12): curated + aliases shipped for assignable PG short works/poetry; three titles deferred (not readily clean Gutenberg ebooks — see epic Remaining). Prod publish is routine `ccr-production-ops`, not open epic scope.
- **Classroom scalability / droplet capacity (`BL-053`):** Kevin concern (also reflected in pricing lean): will the current **1GB-class DO droplet** hold a real classroom concurrent load? Need a measured answer for **how many concurrent users** before performance degrades (and a scale-up path). Complements `BL-042` cost work; this is **capacity/latency**, not token $.
- **Misc convenience (`BL-051`):** while in the **reader**, override the **browser Back** button so it returns to the **previously accessed in-app page** when available, otherwise **Library** by default (instead of leaving the site / unexpected history).

**Done (2026-07-17 / BL-025.11 Slice A — student character-chat download):**
- Character chat modal **Download** button exports the current conversation as Markdown from localStorage (client-only Blob download).
- Empty history keeps Download disabled; helper module + Node frontend unit tests in `character-chat-export.js` / `character-chat-export.test.cjs`.
- Show-and-tell path no longer depends on screenshots for the student-held artifact. Teacher consent/view and `characterChatRequired` remain follow-on slices.

**Done (2026-07-17 / BL-025.11 Slice B — characterChatRequired):**
- Flyway `V16__assignment_character_chat_required.sql` + entity/API/context field `characterChatRequired`.
- Teacher assignment modal checkbox; rejected when class character/chat features are off.
- Student Library chip: **Character chat required** (soft; pairs with Download for show-and-tell).

**Done (2026-07-18 / assignment progress UX):**
- Classroom assignment cards no longer show whole-book % / Chapter X/Y book progress.
- Status is assignment-scoped: **Not started / In progress / Complete** with `N/M complete` checklist (reading, quiz, character chat).
- Chapter-targeted work completes when the student reaches the assigned chapter (or quiz is complete); whole-book assignments still require full-book completion.

**Next when resuming (suggested order):**
1. ~~Assignment open-chapter / chat download / characterChatRequired / assignment progress UX~~ shipped (`main`).
2. ~~`BL-047` stale assignment quiz completion rerender; `BL-048` Reading Buddy classroom toggle vs global flag~~ shipped on `main` (PRs #85 and #84).
3. ~~`BL-050` font-size preference paragraph clipping~~ shipped on `main` (PRs #77 and #79; responsive re-pagination plus split-paragraph navigation coverage).
4. ~~`BL-049` character chat server persistence~~ shipped on `main` (PR #82; cross-device history and database synchronization).
5. ~~`BL-039` / `BL-032` **My Chats** recent-chat landing slice + dedicated page~~ shipped on `main` (PR #78).
6. ~~Capture 2026-08-06 partner feedback into backlog~~ recorded (quiz, dashboard/roster, AI cost/`BL-042`, Back/`BL-051`, fall timing, short stories/`BL-052`, capacity/`BL-053`).
7. ~~`BL-025.5` / `.12` / `.13` teacher quiz authoring + pass rules + defaults~~ shipped locally (2026-08-07): PR-0 question ids, effective-quiz overlays, teacher wizard + AI assist, assignment min-correct/max-retries, teacher quiz defaults. Demo walkthrough before Jessica / Aug 17–21 group.
8. ~~Roster **display name** (optional student self + teacher override) on `BL-025.2`; `BL-025.10` v1 teacher→student overview~~ **`BL-025.10` pilot drill-down shipped** (roster→student overview + opened timestamps + thin heartbeat); optional display-name edit UX on `BL-025.2` still open (email / existing override OK for demo).
9. **`BL-042` (ops/pricing)** + **`BL-053` (capacity):** AI usage metering / cost model and droplet concurrent-load answer before multi-teacher week (**2026-08-17**) and fall start (**2026-08-24**) where practical. This-term cut for the **$750/section** quote is **`BL-042.5`** (chat + voice events, rollup vs **$500** AI envelope, noisy API-key fallback alert) — not a new theme.
10. ~~**`BL-052`:** verify PG IDs + curated catalog for early short works~~ **Done** (PR #101): assignable PG short works/poetry curated; Chopin / shepherd pair / Brontë poem deferred (not readily on Gutenberg); prod import/pregen is routine ops outside epic scope.
11. Invite redeem rate limits (BL-028 pattern) + invite TTL / max uses / revoke (`BL-043.4` / `BL-025.2`)
12. **`BL-043` FERPA P0 pilot blockers** (2026-08-11 privacy review): prod auth gate, OAuth link consent, LLM DPA/subprocessors, invite lifecycle, access-log writers, account delete + retention purge, server chat-export API — triage from `BL-043` work tracker before fall start (**2026-08-24**)
13. FERPA-gated after Discovery exit + P0 remediations: full usage event platform (`BL-025.6`), teacher chat export (`BL-025.7`), **broad** dashboard rollout beyond pilot teacher drill-down (`BL-025.10`)

**Not started:** **FERPA P0 remediations (`BL-043.1`–`.7`)**, roster display-name edit UX (`BL-025.2` remaining), full `BL-025.6` platform (beyond thin heartbeat), **AI cost metering (`BL-042` / this-term `BL-042.5`)**, **classroom concurrent capacity (`BL-053`)**, full character-chat assignment completion tracking / teacher export (`BL-025.11` deeper slices), school-tier admin UI, reader browser-Back convenience (`BL-051`). (`BL-025.10` pilot drill-down **In Progress / demo-ready**; broad FERPA-gated dashboard still blocked.) (`BL-052` content-ops **Done** — deferred titles noted under the epic; prod publish when Kevin runs `ccr-production-ops`.)

**Done (2026-08-12 / BL-025.10 pilot teacher→student overview):**
- Roster row opens class-scoped student overview (current/completed assignments, progress by book, quizzes with scores/retries, opened vs not-opened, approximate time in reader).
- Flyway `V23__assignment_progress.sql` + student `POST .../assignments/{id}/opened`; thin `READING_HEARTBEAT` writer on existing `classroom_usage_events`.
- Authz: teacher-of-term only for overview; enrolled student for opened/heartbeat. Demo script: `docs/product/bl-025-10-demo-script.md`.
- FERPA posture: pilot drill-down only — not school-admin, not bulk chat export, not broad dashboard rollout.

**Done (2026-08-07 / BL-025.5 + .12 + .13 teacher quiz demo pack):**
- PR-0 stable `id` on quiz questions with lazy backfill; `EffectiveQuizAssembler` + `quiz_question_overrides` JPA.
- Flyway `V21__quiz_defaults_and_pass_rules.sql`: term quiz defaults on `class_feature_settings`; assignment `quiz_pass_min_correct` / `quiz_max_retries`.
- Teacher `/teacher` quiz defaults, assignment pass-rule fields, stepped MC authoring wizard (manual + AI suggest/distractors), replace-set override publish.
- Students receive effective class quiz; grade gated by attempt budget; Library chips show pass threshold + attempts left; COMPLETE requires meeting min correct when set.
- Pilot note: attempt budget is chapter+user scoped (not assignment-scoped).

**External milestones:**
- Multi-teacher + AI council demo target window **week of 2026-08-17 – 2026-08-21** (date TBD with Jessica); prep = stable classroom demo path + cost-backed pricing sketch + capacity honesty.
- **Fall semester starts 2026-08-24.** Partner wants early use if ready; **books mid-semester** — **short stories first** (ENGL 1020 schedule under `BL-052` **Done** on curated catalog; deferred titles + routine prod publish noted on the epic).

Statuses: `Discovery`, `Proposed`, `Ready`, `In Progress`, `Blocked`, `Done`

## Current Delivery State

- Most recent completed slice: `BL-028 - Account auth endpoint hardening` (`Done`, delivered account auth rate limiting with `429` + `Retry-After`, login lockout/backoff persistence, and structured non-PII auth audit events with regression test coverage).
- Most recent shipped UI improvement (2026-04-27): cover-forward library shelves with generated book covers, `Continue Reading` feature card, subtler search, horizontal shelf gutters/fades, and desktop shelf arrow controls.
- Most recent shipped hardening (2026-02-24): completed BL-028 account endpoint safeguards, tightened public-mode TTS behavior so cached paragraph audio remains available without collaborator auth while uncached generation remains protected, and finalized compact reader header/menu interactions (logo back-link, desktop shortcuts, keyboard-driven menu navigation).
- Reading Buddy Mode is implemented on `main` (flags → schema/prefs → prompts → chat/history → proactive → UI → rolling summary). Deployment availability and saved classroom policy are represented separately; the default remains `reading-buddy.enabled=false`.
- Active priority work remains the deeper `BL-025` classroom pilot path plus **`BL-043` FERPA/student-PII** after the 2026-08-11 privacy review (P0 pilot blockers trackable in the `BL-043` work tracker; Discovery policy gaps vs V14 schema hooks still open). Parallel ops tracks: `BL-042` AI cost evidence and `BL-053` droplet concurrent capacity before multi-teacher week (**2026-08-17**) / fall start (**2026-08-24**). `BL-052` short-story curation is **Done** (PR #101; deferred titles + prod publish ops noted on the epic). General OWASP stays in `docs/SECURITY_AUDIT.md` (do not duplicate here).
- 2026-07-09: Backlog updated after an educator partner (college professor) feedback call. `BL-025` (Classroom Admin and Assignment Workflows) expanded with concrete requirements: student roster, instructor-as-admin, shareable classroom-ID join link, per-student usage logging, teacher/student chat history export, Teacher vs. School account tiers, semester-scoped rosters, a teacher dashboard with student drill-down, independent per-feature class toggles (for example recap off + quiz on), and per-question teacher quiz overrides for a book/chapter. New epics added: `BL-042` (token usage tracking + classroom cost calculator), `BL-043`/`BL-044` (FERPA and ADA compliance, pilot-blocking), and `BL-045` (user guide + classroom onboarding documentation, driven by the partner's college funding a pilot for a couple of classes).
- 2026-07-10: Captured partner assignment use case under `BL-025.11` (not in the immediate data-model / v1 assignment slice): teacher may **require students to chat with a book character**, and may use student–character conversations as a **fun in-class share/discussion activity**. At capture time chat was client-local; server persistence later shipped in `BL-049`, while teacher export remains deferred.
- 2026-07-10: BL-025 first implementation slice (schema + APIs, no FE). See **Implementation handoff (classroom)** above for resume checklist.
- 2026-07-11: Fixed classroom context stale-after-login defect: `accountCheckStatus` now reloads classroom context and re-renders Library when account identity changes (login/logout/register), with Playwright regression coverage. Partner/grant-facing pilot pitch doc added at `docs/product/classroom-pilot-pitch.md`.
- 2026-07-13: Added the classroom demo UI slice: `/teacher` workspace, class setup, invite link, roster, feature controls, assignment management, workspace navigation, and student invite redemption. Full Maven suite green (536 tests); BL-025.2–.4 remain in progress for rate limits, roster removal/import, entitlement, and wider enforcement/polish.
- 2026-07-13: Added `BL-046` after classroom assignment QA exposed duplicate local editions and incomplete chapter extraction for Pride and Prejudice; investigation covers import identity/de-duplication, parser completeness, safe cleanup, and selector disambiguation.
- 2026-07-13: Completed a manual teacher-to-student demo walkthrough. Published assignments correctly appear in the enrolled student's Library, but account switching left stale classroom context until hard refresh — that defect is now fixed on this branch ahead of the Thursday educator demo.
- 2026-07-14: Added the database-backed teacher capability gate ahead of the educator demo: durable `CREATE_CLASSROOM` grants, capability-aware navigation/direct-access handling, backend enforcement, and operator provisioning by existing account email.
- 2026-07-16: Educator partner call (Jessica) went well — classroom setup and assignments impressed. Captured assignment open-chapter bug (Library used resume progress, so students with prior reading landed on the wrong chapter) and elevated BL-025.11 with optional character-chat requirement + student downloadable transcript for in-class show-and-tell.
- 2026-07-22: Educator partner call (Jessica) — positive on require-chat + download demo path. New asks: **My Chats** landing surface near Achievements (`BL-039`/`BL-032`), **server-persisted character chat** for cross-device access (`BL-049`), and **font-size preference clipping** bug (`BL-050`).
- 2026-07-23: Reconciled the partner-readiness slice after merge and regression verification: My Chats, cross-device chat persistence, font-size re-pagination, BL-047 assignment progress refresh, and BL-048 Reading Buddy availability are shipped on `main`; 596 backend, 91 frontend, and 24 Playwright tests pass locally.
- 2026-08-06: Educator partner call (Jessica) — My Chats demo on prod went well. Full call capture in handoff partner block: quiz authoring/pass rules/defaults (`BL-025.5`/`.12`/`.13`); roster display name + teacher→student overview (`BL-025.2`/`.10`/`.6`); multi-teacher + AI council week of **2026-08-17–21** with cost-backed pricing (`BL-042`); fall semester **2026-08-24** with early optional use and **short stories before mid-semester books** (`BL-052`); droplet **concurrent classroom capacity** concern (`BL-053`); reader browser-Back convenience (`BL-051`).
- 2026-08-07: Implemented teacher quiz demo pack (`BL-025.5` / `.12` / `.13`): stable question ids, effective-quiz assembler/overrides, V21 defaults + pass rules, teacher wizard with AI assist, student effective quiz + attempt gating, Library pass/attempt chips. Ready for Jessica 1:1 and Aug 17–21 group demo after deploy + manual walkthrough.
- 2026-08-11: Completed a FERPA / student-PII privacy review of classic-chat-reader. Findings folded into `BL-043` (work tracker + Discovery gaps vs schema) with cross-links to `BL-025.2`/`.7`, `BL-021` retention, `docs/product/bl-025-classroom-data-model.md`, and overlapping `docs/SECURITY_AUDIT.md` IDs. Docs/tracking only — no runtime remediations in that update.
- 2026-08-11: Jessica’s ENGL 1020 Fall 2026 weekly reading schedule received (docx). Unblocked `BL-052`: recorded already-curated vs missing titles + suggested PG container IDs; early-semester short fiction remains P1 for curation/import.
- 2026-08-12: `BL-052` **Done** — PR #101 merged (curated + aliases for Cask 1063, Jumping Frog/Sketches 3189, Rappaccini/Mosses 512, Trifles/Plays 10623, poetry 1041/8601/16376/12242/1459). Gutendex re-check confirms three deferrals (Chopin “Story of an Hour”, Marlowe/Raleigh shepherd pair, Brontë “The night is darkening round me”); `.3`/`.4` are routine prod ops outside epic scope.
- 2026-08-12: `BL-025.10` pilot drill-down demo pack for Jessica 1:1 (America/Chicago): teacher roster → student overview with six partner sections; durable assignment opened + thin reader heartbeat; backlog/demo script updated. Quiz soak/bugfixes remain Kevin’s lane unless a showstopper surfaces.
- 2026-08-14: Refined `BL-042` in place (no new theme) with this-term child **`BL-042.5`**: per-student character-chat + voice cost events, teacher/ops rollup vs the **$500** AI envelope, and a noisy first-occurrence alert when SuperGrok OAuth falls back to the xAI API key. Purpose: measure real per-student AI $ this term so the next **$750/section/semester** quote is evidence-based. Docs only.

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
- 2026-08-11: FERPA privacy review notes ADR §6 account hard-delete (24h) and backup retention are **policy-documented but not runtime-complete** (no deleteAccount API; `retention_purge_after` unused). Runtime fulfillment tracked under `BL-043.6` (do not reopen this epic). OAuth silent email auto-link risk tracked under `BL-043.2` / `SECURITY_AUDIT` H-04.

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
- Status: In Progress
- Problem: Current product is reader-centric; it lacks teacher/admin controls needed for classroom deployment.
- Scope Buckets:
- Teacher/admin role model (class creation, student roster management, invite/enrollment flow).
- Instructor-as-administrator permissions (instructor is the class-level admin; distinct from app-level collaborator/operator auth).
- Classroom-level feature controls (enable/disable recap, quiz, AI features, media generation), controllable per-class and confirmed independent of each other (for example recap off + quiz on simultaneously, not an all-or-nothing AI toggle).
- Assignment workflows (assign books/chapters, due dates, required quiz completion; later: required character-chat and other non-quiz completion types — see `BL-025.11`).
- Character-chat assignment activities (partner use case): require or encourage chatting with a book character; optional in-class share of a conversation as a classroom exercise (tracked in `BL-025.11`, not required for BL-025.1 / assignment v1).
- Teacher-authored quiz support: class-scoped **multiple-choice** authoring for a book/chapter via a stepped wizard (stub N slots → manual and/or AI-assisted stems + correct answers + AI distractors; teacher always overrides). Coexists with generated quizzes per existing overlay model (`BL-025.5`). See also assignment pass rules (`BL-025.12`) and teacher quiz defaults (`BL-025.13`).
- Assignment quiz pass policy: optional **minimum score** (e.g. 7/10) and **maximum retry attempts** when a minimum is required (`0` = first attempt only) — `BL-025.12`.
- Teacher quiz defaults: reusable defaults for question count, min passing score, **max retry attempts** (same unit as `BL-025.12`; `0` = no retries), and default MC option count — `BL-025.13`.
- Classroom progress visibility (student in-progress/completed states, quiz outcomes, activity snapshots).
- Teacher dashboard with per-student drill-down into activity (readable detail view, not just aggregate class stats): current vs completed assignments, book progress (chapter n/n and/or %), time spent in book, quiz completion counts/scores/retries, and whether each assignment was opened — see `BL-025.10`.
- Optional **student display name** alongside required email (self-entered at register and/or teacher roster override) so teachers can identify students when email is opaque — see `BL-025.2`.
- Student usage logging (what was read, chapter/page progress, time spent per session/book).
- Chat history export/download, available to both the student (their own history) and the teacher (their students' history).
- Account ownership model at Teacher level and School/Institution level (a school admin can own/see multiple teacher classes).
- Semester/term-scoped classes: roster changes over time, class tied to a defined term window, historical rosters remain queryable after a term ends.
- Discovery Questions:
- Should teachers create student accounts directly, issue invite codes, or both?
- What does the classroom-ID registration link actually grant (join a specific class only, or also imply a role/account type)? Does it expire, and can it be regenerated/revoked by the instructor?
- Which features must be controllable at class-level for pilot safety (for example recap off, quiz on)?
- Are feature toggles class-wide only, or does a teacher ever need per-student overrides within a class (for example an accommodation)?
- How should teacher-authored quizzes interact with generated quizzes (replace, merge, or fallback)? Specifically: can a teacher add/edit individual questions within an otherwise-generated quiz, or is authoring all-or-nothing per book/chapter?
- Do teacher-added/overridden questions need review or regeneration when the underlying generated quiz changes (for example after a recap/content pipeline update)?
- For the authoring wizard: default path = stub N empty MC slots vs seed from generated quiz vs AI-suggest-all-first? Can AI suggest run per-question or batch? Must distractors be unique/non-overlapping with the correct answer?
- When minimum score is set on an assignment: is pass based on latest attempt, best attempt, or first passing attempt? Does exhausting retries fail the assignment quiz requirement or leave it PENDING?
- Are quiz defaults teacher-account-global, per-class, or both (account defaults with class override)?
- Display-name precedence on roster/dashboard: teacher override always wins vs student self-name wins unless override set? Can students see/edit the teacher override?
- What counts as “opened” an assignment (first Library card click, first reader open for target chapter, first any checklist interaction)? Idle time vs active reading for time-in-book?
- Time-in-book is a weak engagement proxy (partner-requested; product caveat) — ship as labeled metric; which better signals (quiz attempts, pages advanced, chat turns) belong in v1 vs later?
- What minimum reporting is needed for pilot value without overbuilding gradebook integrations?
- Is "School" a distinct account tier above "Teacher," or is it a v2 concept (single-teacher classes only at launch)?
- How does a semester/term boundary work operationally: does a new term spawn a new class instance, or does the same class get a new roster snapshot? What happens to a student's history when they roll off a roster?
- What format(s) should chat history export use (plain text, PDF, CSV), and does a teacher's bulk export differ from a student's single-conversation export?
- For character-chat assignments (`BL-025.11`): is chat **required for completion** or optional/fun only? Any character vs a specific character? Completion = any message, N turns, or teacher-reviewed? Share with whole class, teacher-only, or export file? Must character/chat class feature toggles be on when an assignment requires chat?
- Current Direction (2026-02-15):
- Prioritize classroom pilot readiness over broad LMS integrations.
- Treat quiz workflows as classroom-positive and recap as classroom-optional/off by default based on early educator feedback.
- Sequence work so BL-021 account foundations unblock class roster and assignment capabilities.
- Current Direction (2026-07-09, after educator partner call):
- Confirmed requirements from the professor partner to fold into v1 scope: student roster, instructor-as-admin, shareable classroom-ID join link, per-student usage logging (reading + time spent), chat history download (student- and teacher-initiated), Teacher- and School-level account tiers, semester-scoped rosters, and a teacher dashboard with student drill-down.
- Compliance requirements (FERPA, ADA) are significant enough to track as their own epics rather than buried acceptance criteria: see `BL-043` (FERPA) and `BL-044` (ADA/accessibility for classroom). BL-025 should treat their exit criteria as blocking for any real school pilot, not just polish.
- Cost/rate-limit planning for classroom AI usage (token usage tracking, cost calculator, subscription tiering) is tracked separately in `BL-042` since it is a pricing/ops concern shared with `BL-038`, not a classroom UI feature per se.
- Confirmed concrete example from the partner for `BL-025.3`: her pilot class wants recap disabled for students but quiz enabled — validates that feature toggles must be independent, not a single "AI features on/off" switch.
- Confirmed concrete need for `BL-025.5`: teacher wants to add or override individual quiz questions for a specific book/chapter for her class, on top of (not only instead of) the generated quiz.
- Current Direction (2026-08-06, educator partner call — Jessica; quiz authoring):
- Expand `BL-025.5` from thin overlay wording into a **teacher quiz authoring wizard** for book/chapter at class scope.
- **Format lock for this slice: multiple choice only** (stem + options + single correct index). Free-response / short answer explicitly out of scope until a later epic.
- Wizard / stepped flow (product preference):
  1. Choose book/chapter (and class/term context already selected in Teaching workspace).
  2. Stub **N** question slots (N from `BL-025.13` defaults; teacher can change count for this quiz).
  3. Per question (or batch): **manual authoring** and/or **AI suggest from chapter content** and/or **AI generate distractors** only; teacher may override any stem, option, or correct answer before publish.
  4. Review/publish effective quiz for the class (merge/overlay semantics remain as in `docs/product/bl-025-classroom-data-model.md` PR-0 + overrides; do not invent a second parallel quiz store without updating that design).
- Pair authoring with **assignment pass rules** (`BL-025.12`): optional minimum score (e.g. 7 of 10); when min score is required, teacher must set **max retry attempts** where **`0` means no retries** (only the initial attempt).
- Pair with **teacher quiz defaults** (`BL-025.13`): default question count, default min passing score, default **max retry attempts** (same unit as `BL-025.12`; **`0` = no retries** — not “max total attempts”), default number of MC answer choices.
- Stable generated-question `id`s (design **PR-0**) remain a blocking prerequisite for OVERRIDE/DISABLE layered overrides; pure teacher-ADD / full-replacement paths should still use stable ids for grading and regen drift.
- Do **not** block roster identity / drill-down work on shipping the full wizard, but treat quiz authoring + pass rules as high partner-value pilot depth once core assignment path is stable.
- Current Direction (2026-08-06, educator partner call — Jessica; teacher dashboard + roster identity):
- Expand `BL-025.2` roster identity: **email required**; optional **display name** from (a) student at registration/profile and/or (b) **teacher roster override** when email is not recognizable.
- Expand `BL-025.10` teacher→student overview to the partner checklist (current assignments, completed assignments, time-in-book, book progress chapter n/n and/or %, quiz N complete + scores + retries, assignment opened/not-opened). Class-level dashboard summary can stay thin in v1 if drill-down is strong.
- **Assignment opened** is a first-class signal distinct from checklist completion — needs durable server state (likely assignment_progress / first_opened_at), not inferred only from book resume.
- **Time-in-book** depends on `BL-025.6` (or a minimal session heartbeat subset). Label as “time in reader” / similar; do not market as rigorous engagement proof. Kevin note: discuss better engagement measures; Jessica requested time spent.
- FERPA (`BL-043`) still gates broad dashboard/PII surfaces; pilot internal teacher views should still avoid over-collecting and document access.
- Current Direction (2026-07-10, partner assignment use case — backlog only, not immediate build):
- Partner may require students to **chat with a book character** as an assignment exercise, and may have students **share a conversation** they had with a character as a fun in-class activity.
- Do **not** block BL-025.1 domain model or BL-025.4 assignment v1 (book/chapter/due/quiz) on this. Track as `BL-025.11` after core pilot path.
- Prerequisites when prioritized: durable character-chat persistence (`BL-049`; today character chat is localStorage-only), assignment requirement/completion model beyond quiz, FERPA alignment with `BL-025.7` / `BL-043` if teachers can view or export student–character chats.
- Exit Criteria for Discovery:
- Classroom architecture decision (roles, enrollment flow, class ownership boundaries, School vs. Teacher account tiers).
- v1 classroom control matrix (which features are class-configurable).
- v1 assignment + quiz authoring scope with acceptance criteria for teacher and student flows.
- Semester/term model decision (how rosters version across terms, retention of past-term data).
- Decision on classroom-ID join-link lifecycle (issuance, expiration, revocation).
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-025.1 Classroom Domain Model + Roles | In Progress | Define entities/relationships for teacher (admin), school, class, semester/term, student enrollment, and role-based access boundaries | ADR + schema draft approved and role checks mapped to API surfaces |
| BL-025.2 Teacher Onboarding + Roster Management | In Progress | Build teacher class setup flow, shareable classroom-ID join link for student self-registration, and roster management (add/remove/import). Include **optional student display name**: student may set at register/profile; teacher may set a **roster override** so the class list is identifiable when email alone is insufficient. Email remains the required account identifier. **Invite lifecycle (FERPA `BL-043.4`):** default TTL + max uses on create, revoke API/UI, rotate on create (InviteLinkService already supports model fields; ClassroomAdminService currently passes null,null). Roster PII minimization / TA role / `VIEW_ROSTER` logging tracked under `BL-043.5` / `BL-043.8`. | Teacher can create a class, share a join link, and manage an active roster without manual DB operations; roster shows email plus optional name/override; teacher can edit override without changing login email; invites expire / revoke / rotate per `BL-043.4` |
| BL-025.3 Class Feature Controls | In Progress | Add independent class-level toggles (quiz/recap/AI/media capabilities, each settable on its own — for example recap off + quiz on) with policy enforcement in UI + API | Teacher can set recap off and quiz on (or any other independent combination) and student feature availability matches per class |
| BL-025.4 Assignment Workflow v1 | In Progress | Support assigning books/chapters, due windows, and required completion/quiz states (quiz-oriented v1; not character-chat requirements). Pass threshold + retry policy is `BL-025.12` (extends this slice). Durable **assignment opened** / first-interaction timestamps feed `BL-025.10` (`assignment_progress.first_opened_at` + student open API shipped 2026-08-12). | Teacher can publish assignments and students see clear due/required states in app |
| BL-025.5 Teacher-Authored Quiz Authoring (MC Wizard) | Done | Class-scoped **multiple-choice** quiz authoring for a book/chapter via a **stepped wizard**: stub N default slots; teacher may manually enter stems/options/correct answer, use AI to suggest questions from chapter content (full override), and/or use AI to generate wrong answers. Layered on generated quiz per design overlay model (not format expansion beyond MC). | Teacher can define or override the effective MC question set for a book/chapter for their class; students in that class receive that set; AI assists never publish without teacher confirmation |
| BL-025.6 Student Usage Logging | In Progress | Persist per-student activity events (books/chapters read, progress %, **session / time-in-book**) scoped to class/term. Powers teacher drill-down time-spent and later cost attribution. **2026-08-12 thin slice:** student `READING_HEARTBEAT` writer (clamped duration, idempotency) feeding approximate “time in reader” on `BL-025.10` — not the full event platform. | Usage events are queryable per student and roll up cleanly per class/term; time-in-book can be shown on `BL-025.10` with defined measurement rules |
| BL-025.7 Chat History Export | Proposed | Add download/export of AI chat history, self-service for students and bulk/per-student for teachers. **Gap (2026-08-11 FERPA review):** V14 `chat_export_jobs` is schema-only; client `character-chat-export.js` is local-only (BL-025.11 Slice A). Server term-scoped export + access logs + artifact expiry tracked as `BL-043.7`. Teacher bulk remains gated by `BL-043` Discovery exit (`BL-043.13`). | Eligible student can server-export their own term-scoped chat history with access logs; teacher can export enrolled students’ history in their class only after FERPA gates; artifacts expire; local-only download is not the sole path |
| BL-025.8 School and Teacher Account Tiers | Proposed | Add account ownership model distinguishing School (multi-teacher) and Teacher (single-class-owner) tiers | A school-tier account can view/manage classes across its teachers; a teacher-only account is scoped to its own classes |
| BL-025.9 Semester/Term-Scoped Rosters | Proposed | Add term boundaries to classes so rosters can change across semesters while preserving historical term data | Teacher can start a new term for a class with a fresh roster without losing prior-term student history/reporting |
| BL-025.10 Teacher Dashboard + Student Drill-Down | In Progress | Teacher dashboard with roster entry into a **Teacher→Student overview**. v1 student overview must list: (1) **current assignments**, (2) **completed assignments**, (3) **time spent in the book** (labeled engagement proxy; partner-requested), (4) **progress by book** (chapter n/n and/or % complete), (5) **quizzes for the book** — N complete, **scores**, **retry attempts**, (6) **assignment progress including opened/not-opened** (whether the student has clicked into the assignment at all). Class aggregate widgets optional if drill-down is complete. **2026-08-12:** pilot drill-down MVP shipped (`GET .../students/{userId}/overview` + `/teacher` roster panel); broad FERPA-gated rollout still blocked. Demo: `docs/product/bl-025-10-demo-script.md`. | Teacher can open any rostered student and see the six overview sections without external tooling; opened vs not-opened is accurate server-side; time-in-book and quiz stats match underlying events/attempts |
| BL-025.11 Character-Chat Assignments + In-Class Share | Proposed | Partner use case (Jessica, 2026-07-16 call confirmed): assignment may **optionally require character chat** for classroom **show-and-tell**; students need a **downloadable conversation artifact** (text/Markdown). Includes requirement/completion modeling and export; student self-serve download first, teacher access behind FERPA. Local-first export can unblock pilot before full server persistence. | Teacher can optionally require character chat; student can download their conversation for class without screenshots; completion/export model agreed — discovery still open on specific character vs any, N-turn completion, and teacher bulk visibility |
| BL-025.12 Assignment Quiz Pass Rules (Min Score + Retries) | Done | On quiz-required assignments, teacher may set **minimum passing score** (e.g. 7/10). When a minimum is set, teacher must set **maximum retry attempts**; **`0` = no retries** (initial attempt only). Student quiz completion for the assignment must honor pass threshold + remaining attempts (not “any attempt exists”). | Teacher can configure min score + max retries on an assignment; student UI/API show attempts remaining and pass/fail against the threshold; exhausting attempts without passing leaves quiz requirement unmet |
| BL-025.13 Teacher Quiz Defaults | Done | Teacher-configurable defaults used when creating quizzes/assignments: **number of questions**, **minimum passing score**, **maximum retry attempts** (same unit as `BL-025.12`; **`0` = no retries** / initial attempt only — do **not** store this as total-attempt count), and **default multiple-choice option count**. Defaults seed the authoring wizard (`BL-025.5`) and pass-rule fields (`BL-025.12`); per-assignment/per-quiz overrides still allowed. | Teacher can save defaults once and see them pre-filled on new quiz authoring and quiz-required assignments using consistent retry units; changing defaults does not rewrite already-published quizzes/assignments unless teacher explicitly re-applies |
- Dependency Notes:
- BL-021 (`User Registration and Account System`) is a prerequisite for BL-025.2 onward.
- BL-025.3 and BL-025.4 should extend BL-018.6 classroom context hooks with full class policy + assignment signal integration.
- BL-025.6/.7/.10 (usage logging, chat export, dashboard drill-down) should be sequenced after BL-043 (FERPA) Discovery exit criteria are met (`BL-043.13`) and relevant P0 remediations land — see 2026-08-11 FERPA work tracker. Schema hooks alone do not unlock these slices. **Exception (2026-08-12):** a **pilot-only** teacher→student drill-down (`BL-025.10` MVP) + thin heartbeat may ship behind strict teacher-of-term authz for Jessica demos; broad/school-tier dashboard and teacher bulk export remain gated.
- BL-042 (token usage/cost calculator) depends on BL-025.6's usage logging for per-student/per-class token attribution.
- BL-025.11 depends on BL-025.4 foundations and, for durable completion/export, on server-persisted character chat (`BL-049`, not localStorage-only) plus BL-043/BL-025.7 policy if teachers access student–character conversations. **Not required for BL-025.1 data model freeze or pilot assignment v1.** Local download + soft require shipped 2026-07.
- BL-025.5 depends on stable question ids (design **PR-0** in `docs/product/bl-025-classroom-data-model.md`) for OVERRIDE/DISABLE merge; MC-only wizard can ship ADD/manual paths earlier if product accepts temporary constraints — prefer PR-0 first.
- BL-025.12 extends BL-025.4 quiz-required completion: today’s “any attempt exists ⇒ COMPLETE” is insufficient once min score / retries exist. Coordinate attempt counting with existing `quiz_attempts` / grade APIs.
- BL-025.13 seeds BL-025.5 and BL-025.12 UIs; store scope (teacher account vs class) is an open discovery question — default lean: **teacher-account defaults** with optional later class override.
- BL-025.10 v1 student overview depends on: roster identity/name (`BL-025.2`), assignment list + **opened** timestamps (`BL-025.4`), reading progress (existing reader progress APIs), quiz attempts/scores/retries (`quiz_attempts` + `BL-025.12` when present), and time-in-book from `BL-025.6` (or a minimal heartbeat slice). Broad PII dashboard rollout remains gated by `BL-043` draft exit criteria.
- BL-025.2 display name: prefer storing student self-name on the user/profile and teacher override on **membership/roster** (class-scoped), not overwriting login email.
- Session Log:
- 2026-07-10: Design doc written and reviewed (`docs/product/bl-025-classroom-data-model.md`). First code slice: V14 schema, entities/repos, authz, invite redeem, bootstrap, features/assignments APIs, context dual-read. **API/backend only** (no teacher/student UI yet). Default consumer flow unchanged when demo off and no DB enrollment. Resume via backlog **Implementation handoff (classroom)** section.
- 2026-07-13: Classroom demo UI implemented and manually verified through the core teacher-to-student path: create class → share/redeem invite → confirm roster → publish assignment → view assignment in the student's Library. Known defect: a student who signs in on an already-loaded reader page must hard-refresh before classroom context and assignments appear. BL-025.2–.4 remain `In Progress` pending the follow-ups documented in the implementation handoff.
- 2026-07-14: Teacher access moved from “any authenticated account” to a durable capability model. V15 stores `CREATE_CLASSROOM`; the capability endpoint gates Library/Teaching UI, direct student access is denied, and class creation requires the capability server-side. Existing teacher memberships still grant access to their Teaching workspace.
- 2026-07-16: Partner call confirmed assignment/chapter open bug under real progress (resume path). Product: optional character-chat requirement + downloadable student transcript for show-and-tell elevates BL-025.11.
- 2026-08-06: Partner call (Jessica) expanded quiz depth: MC-only teacher authoring wizard (`BL-025.5`), assignment min score + max retries with `0` = no retries (`BL-025.12`), and teacher quiz defaults (`BL-025.13`). Same call: optional student display name + teacher roster override (`BL-025.2`); teacher→student overview checklist on `BL-025.10` (current/completed assignments, time-in-book, book progress, quiz scores/retries, assignment opened state) with `BL-025.6` for time metrics. More call notes still incoming.
- 2026-08-11: FERPA / student-PII privacy review cross-linked invite lifecycle hardening and server chat-export gaps into `BL-025.2` / `BL-025.7` (owned for triage under `BL-043`); usage/export/dashboard gates unchanged until `BL-043.13` + P0 remediations.
- 2026-08-12: **BL-025.10 pilot teacher→student overview** for Jessica 1:1 — roster drill-down covering current/completed assignments, book progress, quiz scores/retries, durable opened state (`assignment_progress`), and approximate time-in-reader via thin heartbeat (`BL-025.6` minimum). Class-scoped teacher authz only; no bulk chat export / school-admin. Demo script at `docs/product/bl-025-10-demo-script.md`. Broad dashboard rollout remains FERPA-gated.

### BL-030 - Registered User Home and Account Landing
- Type: Feature
- Priority: P1
- Effort: L
- Status: Discovery
- Problem: Signed-in readers gain persistence today, but they do not yet get a clearly better home experience than anonymous readers.
- Scope Buckets:
- Signed-in landing page information architecture.
- Cross-device reading continuity (`Continue Reading`, `In Progress`, `My List`, recent activity).
- Registered-only modules for trophies, recent chats, and challenge progress.
- Discovery Questions:
- Should the signed-in landing replace the current generic library landing or layer on top of it?
- Which modules deserve top billing for signed-in readers: continue reading, favorites, challenges, trophies, or chats?
- How much of the landing should be account-backed versus device-local fallback?
- Current Direction (2026-04-06):
- Use account presence to make the home screen feel meaningfully more personal on return visits.
- Start with modules backed by existing signals before adding heavier recommendation or social systems.
- Favor fast resume actions over dashboard density.
- Exit Criteria for Discovery:
- Approved signed-in landing layout with priority ordering for modules.
- Decision on which modules are account-only versus available to anonymous users.
- Acceptance Criteria:
- Signed-in readers see a dedicated landing/home experience with `Continue Reading`, `In Progress`, `My List`, and recent trophy/activity context.
- Landing modules are stable across devices for account-backed data paths.
- Signed-in home creates at least one clear, immediate value proposition for registration beyond raw persistence.

### BL-031 - Reading Challenges and Streaks
- Type: Feature
- Priority: P2
- Effort: L
- Status: Discovery
- Problem: The product has progress tracking and trophies, but lacks ongoing goals that create return habits for registered readers.
- Scope Buckets:
- Challenge taxonomy (streaks, completion, exploration, comprehension).
- Progress event model and persistence for account-backed challenge state.
- Landing/reader UI for active challenges and progress.
- Discovery Questions:
- Which challenge types should launch first: daily reading streaks, chapter counts, book counts, author diversity, quiz completion?
- Should challenges be private-only at launch or eventually shareable?
- How should challenges handle long books, missed days, and partial progress resets?
- Current Direction (2026-04-06):
- Start with deterministic private challenges tied to existing activity signals.
- Avoid over-weighting only book completion; balance consistency, completion, and exploration goals.
- Make challenge progress visible from the signed-in landing and light-touch in the reader.
- Exit Criteria for Discovery:
- v1 challenge catalog with explicit unlock/progress rules.
- Clear rules for streak resets, grace windows, and monthly challenge boundaries.
- Acceptance Criteria:
- Registered readers can see active challenge progress and completed challenges.
- At least one streak-based challenge and one non-streak challenge are supported.
- Challenge progress updates without requiring manual user management.

### BL-032 - Character Chat Hub and Full-Page Conversations (**My Chats**)
- Type: Feature
- Priority: P1
- Effort: L
- Status: Done (v1 shipped on `main`, PR #78)
- Problem: Character chat exists today, but it is still a book-adjacent interaction rather than a first-class destination for signed-in readers. Partner (Jessica, 2026-07-22) explicitly asked for a **My Chats** surface students can find without reopening a book.
- Scope Buckets:
- Signed-in **My Chats** hub listing recent and resumable character conversations.
- Dedicated full-page chat experience beyond modal/in-reader entry points.
- Conversation context, resume affordances, and spoiler boundary communication.
- Landing placement: near **Achievements** on the signed-in home (partner preference); may start as a compact recent list or a link into the hub page.
- Discovery Questions:
- Should chat history be organized by character, by book, or both?
- What new capabilities belong in full-page chat v1: pinned chats, suggested prompts, conversation search, richer history?
- Should full-page chat remain strictly character-scoped or expand later into broader book discussion threads?
- Compact landing shelf vs link-only to dedicated page for v1?
- Current Direction (2026-07-22):
- Product name for the surface: **My Chats**.
- First make chat re-entry easy for registered users before expanding the conversation feature set.
- Treat a chat hub as the bridge between account value and a fuller conversational reading experience.
- Keep spoiler guardrails explicit and tied to reading progress.
- **Depends on `BL-049`** for truthful multi-device history; localStorage-only hub is a temporary demo bridge at best.
- v1 behavior, session identity, resume semantics, privacy boundaries, and shared landing/dedicated-page API contract are defined in [`my-chats-spec.md`](my-chats-spec.md).
- Delivered (2026-07-22): authenticated `/my-chats` history, search/filtering, cursor pagination, full-page transcript/resume, unavailable-character handling, and owner-isolation coverage.
- Exit Criteria for Discovery:
- Approved chat hub IA and full-page chat entry flow.
- Decision on v1 history model and spoiler-context UX.
- Acceptance Criteria:
- Signed-in readers can view recent character chats and resume them from a dedicated landing or **My Chats** hub surface near Achievements.
- Full-page chat supports longer-form sessions without depending on the in-reader modal flow.
- Existing character progress guardrails continue to apply.

### BL-033 - Favorite Characters
- Type: Feature
- Priority: P2
- Effort: M
- Status: Discovery
- Problem: Readers can revisit character details and chats, but cannot explicitly curate the characters they care about most.
- Scope Buckets:
- Favorite/save model for characters.
- Landing and character-browser surfaces for favorites.
- Personalization hooks for recommendations, resume-chat, and future notifications.
- Discovery Questions:
- Should favorites be book-scoped, globally account-scoped, or both?
- What is the minimum useful action set: save/unsave only, or save plus notes/tags?
- How should favorite characters influence landing recommendations and chat resurfacing?
- Current Direction (2026-04-06):
- Start with a lightweight account-backed save/unsave model.
- Use favorite characters primarily to improve landing personalization and chat re-entry rather than as an isolated collection feature.
- Exit Criteria for Discovery:
- Data model decision for character favorites and scope.
- Agreed initial surfaces where favorites appear.
- Acceptance Criteria:
- Registered readers can favorite and unfavorite characters.
- Favorite characters appear in at least one signed-in landing surface and one in-reader/character-browser surface.
- Favorite-character state persists across devices for signed-in users.

### BL-034 - Library Genre and Mood Shelves
- Type: Improvement
- Priority: P2
- Effort: M
- Status: Discovery
- Problem: The cover-forward library now feels more browsable, but readers still need stronger thematic paths than a single `Discover` shelf.
- Scope Buckets:
- Genre/mood shelf taxonomy using existing catalog metadata (`subjects`, `bookshelves`, author/title signals) with curated overrides where metadata is weak.
- Shelf ordering rules for anonymous readers, signed-in readers, and sparse-history states.
- Mobile shelf density and horizontal-scroll affordances.
- Discovery Questions:
- Which shelves should launch first: genre (`Gothic`, `Adventure`, `Romance`), mood (`Short and gripping`, `Beautiful prose`, `Big epics`), or classroom-friendly groupings?
- Should shelves be deterministic and curated, personalized per reader, or a hybrid?
- How many shelves should appear before the page feels cluttered?
- Exit Criteria for Discovery:
- Approved v1 shelf taxonomy and ordering rules.
- Decision on metadata source and fallback strategy for books with weak subjects.
- Acceptance Criteria:
- Library browsing shows multiple themed shelves beyond `For You`, `In Progress`, `My List`, `Completed`, and `Discover`.
- Shelves remain stable, explainable, and usable on mobile.
- Search mode remains focused on query results rather than shelf browsing.

### BL-035 - Book Preview and Detail Drawer
- Type: Improvement
- Priority: P2
- Effort: M
- Status: Discovery
- Problem: Cover shelves make books inviting, but selecting a book immediately opens/imports without a richer preview moment for browsing decisions.
- Scope Buckets:
- Lightweight preview drawer or modal with larger cover, title/author, progress state, description, recommendation reason, and primary action.
- Different actions for local books (`Resume`, `Start`, `Save`) versus catalog books (`Import`, `Save for later` if supported).
- Keyboard and mobile interaction model.
- Discovery Questions:
- Should clicking a shelf card open the preview first, or should preview be reserved for a secondary action/long press?
- What metadata is reliable enough for preview copy today?
- Should preview include generated cover variants or manual cover replacement controls for admins only?
- Exit Criteria for Discovery:
- Approved click/tap behavior and preview content model.
- Decision on whether preview ships for local books only, catalog books only, or both.
- Acceptance Criteria:
- Readers can inspect a book without losing their place on the library shelf.
- Primary actions are clear and no slower for the common resume-reading path.
- Preview works across desktop keyboard, desktop pointer, and mobile touch.

### BL-036 - Book Cover Curation Console
- Type: Improvement
- Priority: P2
- Effort: M
- Status: Discovery
- Problem: Generated covers dramatically improve the library, but operators need a first-class way to retry, override, and audit cover choices without Postman or direct DB access.
- Scope Buckets:
- Admin/collaborator UI for current cover preview, regenerate, custom prompt regenerate, manual upload, and clear override.
- Provider choice surfaced from configured generation providers (`comfyui`, `openai`, `xai`) where appropriate.
- Status/history view for cover source, generated prompt/provider, upload time, and cache/CDN state.
- Discovery Questions:
- Should this live behind collaborator auth in the app, or remain a separate operator-only tool?
- Which controls are safe for production use versus local-only experimentation?
- Should cover prompts be saved and editable as durable metadata?
- Exit Criteria for Discovery:
- Approved operator surface and auth boundary.
- Decision on persisted prompt/history fields needed for useful auditing.
- Acceptance Criteria:
- Operator can replace or regenerate a cover from the UI.
- Cover changes update cached/CDN URLs predictably and avoid stale browser images.
- Manual upload validates file type/size and preserves existing fallback behavior.

### BL-037 - Richer Library Personalization Explanations
- Type: Improvement
- Priority: P3
- Effort: M
- Status: Discovery
- Problem: The `For You` and `Discover` shelves can recommend books, but the reason behind a recommendation is still lightweight and easy to miss in cover-first browsing.
- Scope Buckets:
- Short explanation surfaces for recommendations without cluttering cover cards.
- More useful recommendation inputs: favorite books, completed genres, recent authors, challenge goals, classroom context.
- Optional "more like this" and "less like this" signals for future ranking.
- Discovery Questions:
- Should explanations appear inline, in a preview drawer, or on hover/focus only?
- What recommendation reasons are trustworthy enough for v1?
- Should users have direct feedback controls, or should ranking stay passive initially?
- Exit Criteria for Discovery:
- Approved recommendation explanation UX.
- Ranked list of v1 explanation types and data dependencies.
- Acceptance Criteria:
- Readers can understand why a book is recommended without visual clutter.
- Recommendation copy is deterministic and avoids overclaiming.
- Explanation UX works for keyboard and touch users.

### BL-038 - Public Character Chat Access and Cost Controls
- Type: Feature
- Priority: P1
- Effort: L
- Status: Discovery
- Problem: Character chat is one of the app's most distinctive experiences, but public access needs cost controls, abuse throttling, and a path to paid usage before it can be opened broadly.
- Scope Buckets:
- Anonymous chat access with conservative limits by IP, reader cookie, and app-wide spend/usage caps.
- Signed-in free tier with more generous limits and identity-backed abuse controls.
- Pay-as-you-go credit model for readers who exceed free limits or want heavier chat usage.
- Provider/cost guardrails for xAI-backed chat, including daily budget caps, emergency kill switches, and clear user-facing limit states.
- Discovery Questions:
- What is the right anonymous daily allowance that lets users feel the magic without inviting runaway cost?
- Should signed-in users receive a higher free quota, bonus credits, or both?
- Should pay-as-you-go credits be chat-only at launch or become a general AI credit balance for chat/media features?
- What operational dashboards or alerts are needed before opening public chat access?
- Current Direction (2026-04-27):
- Let everyone experience character chat, but keep anonymous limits strict enough to protect cost.
- Use registration as the first upgrade step by raising limits for signed-in readers.
- Explore pay-as-you-go before subscription, because chat can be cheap enough for lightweight credit bundles.
- Favor prepaid credit packs over monthly metered invoicing for v1, so usage can be blocked before cost runs away.
- Initial paid pack candidates: `$5`, `$10`, and `$20`, with `$5` intended to feel useful because chat is relatively inexpensive on xAI.
- Suggested phased rollout:
- Phase 1: public/free chat with anonymous and signed-in throttles only.
- Phase 2: account-backed credit ledger with manual/admin credit grants for testing.
- Phase 3: Stripe Checkout credit packs plus webhook-based credit granting.
- Phase 4: usage dashboard, alerts, refunds/chargeback handling, and pricing refinement.
- Exit Criteria for Discovery:
- Approved free-tier quotas for anonymous and signed-in readers.
- Decision on PAYG credit scope, purchase unit, and balance/ledger model.
- Cost-control checklist covering user limits, provider spend caps, abuse signals, and emergency disable behavior.
- Acceptance Criteria:
- Anonymous readers can send a limited number of character chat messages without collaborator auth.
- Signed-in readers receive a clearly higher quota and understandable limit/reset messaging.
- Signed-in readers can buy prepaid chat credits in `$5`, `$10`, and `$20` packs.
- App enforces per-reader/per-IP/app-wide throttles with user-friendly upgrade or wait states.
- PAYG credit accounting is auditable and prevents chat usage beyond available balance.

### BL-039 - Character Chat Home and Discovery Surfaces (**My Chats** landing slice)
- Type: Improvement
- Priority: P1
- Effort: M
- Status: In Progress (recent-chat landing slice shipped on `main`, PR #78)
- Problem: Character chat is compelling, but the library/home experience does not yet make it obvious, resumable, or easy for new users to try. Partner (Jessica, 2026-07-22) asked for **My Chats** near **Achievements** on the landing page (recent list and/or link to dedicated page).
- Scope Buckets:
- **My Chats** shelf or module with character portraits, book context, last-message snippet, and `Continue chat` action — placement toward the top of signed-in landing near Achievements.
- Optional link into full **My Chats** page (`BL-032`) if the landing module is compact.
- `Characters You've Met` shelf based on discovered characters from reader progress.
- First-run/public `Try Character Chat` module with a few curated characters and starter prompts from popular books.
- Optional featured-character panel for the strongest resume or discovery opportunity.
- Discovery Questions:
- Should recent chats appear above or below `Continue Reading` / Achievements?
- Compact recent list on landing vs link-only to dedicated My Chats page for v1?
- Should anonymous users see starter chat prompts before reading progress creates discovered characters?
- Which characters/books are safe and compelling defaults for a public try-chat module?
- How should spoiler boundaries be communicated from home-page chat entry points?
- Current Direction (2026-07-22):
- Treat chat as a first-class home-page hook, not only an in-reader modal.
- Partner label: **My Chats**; place near Achievements for signed-in readers.
- For signed-in/readers with history, prioritize resumable recent chats and discovered characters.
- For new/anonymous readers, show a small curated try-chat surface that demonstrates the feature quickly.
- Full multi-device truth requires `BL-049`; landing can prototype from local history only as a short-lived bridge.
- Delivered (2026-07-22): signed-in **My Chats** shelf near Achievements with up to four server-backed recent conversations, retry/empty/unavailable states, and a link to the dedicated page. Curated first-run discovery and `Characters You've Met` remain future slices.
- Exit Criteria for Discovery:
- Approved home-page placement and priority order for chat modules.
- Decision on data requirements for recent chats, discovered characters, and starter prompt curation.
- Acceptance Criteria:
- Readers can resume recent character chats from the library/home **My Chats** surface.
- Readers can discover chat-capable characters without already knowing where the feature lives.
- New users see at least one low-friction path to try character chat.
- Home-page chat entry points preserve existing spoiler guardrails.
- Dependency Notes:
- Complements `BL-032` (full hub/page). Prefer implementing landing `BL-039` after or alongside persistence `BL-049`.
- Classroom show-and-tell download remains `BL-025.11` Slice A (local export); My Chats is the ongoing re-entry surface.

### BL-040 - Personal Editions and Paid Creative Customization
- Type: Feature
- Priority: P1
- Effort: XL
- Status: Discovery
- Problem: Book lovers may value a personalized reading experience where they can shape the visual and audio interpretation of a classic, but the app currently only exposes default generated media and voices.
- Scope Buckets:
- Account-backed user media library for custom covers, chapter illustrations, character portraits, and selected active variants.
- Shared AI credit usage model for paid creative actions, linked to `BL-038` credit ledger/payment work.
- Custom-prompt illustration and portrait regeneration with literary context and safety guardrails.
- Premium scene video generation for selected passages/chapters as a later high-credit creative action.
- TTS voice selection, saved per-reader/per-book voice preferences, and optional premium voice usage controls.
- `My Edition` UI for selecting active cover/illustration/portrait/voice variants and reviewing generated assets.
- Scene gallery/short-film assembly concepts if users generate multiple videos from a book.
- DigitalOcean Spaces/CDN storage strategy for user-owned media using opaque UUID object keys and DB-backed ownership metadata.
- Discovery Questions:
- Which customization actions should be paid in v1: custom chapter illustration, custom character portrait, custom book cover, TTS voice selection, or all of them?
- Should user-generated media be private-only, shareable by link, or eligible for curated public promotion later?
- How many variants should a user be able to keep per book/chapter/character before storage cleanup or archiving is required?
- What prompt controls are appropriate: free-form prompt, guided style presets, prompt templates, or a hybrid?
- Should custom TTS voices be selected from provider voices only, or should the app support saved voice direction/prompting as a premium feature?
- What level of character consistency is required before scene videos feel good enough, and should consistency rely on saved character portraits, style references, provider-specific reference images, or curated prompts?
- Should multiple generated scene videos be composable into a simple user-owned short film or scene reel?
- Current Direction (2026-04-27):
- Position paid usage as `Personal Editions`, not simply more tokens.
- Let free users read with default generated covers/illustrations/portraits and default TTS.
- Let paid/account users spend AI credits on creative customization that makes the book feel like their own edition.
- Keep custom media private by default, with opaque storage keys in DigitalOcean Spaces and DB-mediated ownership/selection.
- Start with guided customization presets plus optional prompt text rather than unconstrained prompt-only flows.
- Treat short scene video as a later premium/high-credit extension after image customization and credit accounting are stable.
- If scene videos move forward, prioritize character/style consistency and storage lifecycle rules before broader sharing.
- Exit Criteria for Discovery:
- Approved v1 customization surface and priority order.
- Credit cost model for chat versus image/TTS actions, including storage overhead.
- Data model for user media ownership, active variant selection, storage keys, and cleanup policy.
- Safety/guardrail plan for custom prompts and generated outputs.
- Acceptance Criteria:
- Signed-in reader can spend AI credits to create at least one custom media variant tied to a book/chapter/character.
- User media is stored with opaque UUID object keys and ownership metadata, and is not discoverable by listing.
- Reader can select which generated/default variant is active in their personal edition without changing the global/default book media.
- Custom prompt flow includes guardrails and preserves book/character context.
- Reader can select and persist preferred TTS voice behavior at least at the book level.
- Later video slice: reader can spend credits to generate a short private scene video tied to a passage/chapter, with clear cost disclosure and consistency safeguards.

### BL-041 - Multi-Provider Voice-to-Voice (Character Calls)
- Type: Tech Debt
- Priority: P3
- Effort: M
- Status: Discovery
- Problem: Character voice calls are hardwired to xAI's Realtime API end-to-end (session minting, voice roster, and the browser's WebSocket connection). OpenAI's Realtime API (`gpt-realtime-2.1`/`-mini` as of 2026-07-06) is now a mature, production-grade equivalent, and depending on a single voice provider carries cost, quality, and reliability risk with no fallback.
- Context: As of 2026-07-07, voice selection was upgraded to reason over xAI's full voice roster (fetched live via `GET /v1/tts/voices`, cached, with a deterministic heuristic fallback) and persist the chosen voice per character (`characters.call_voice` / `call_voice_provider`, Flyway `V11`). The persistence layer already stores a provider tag and re-selects automatically on provider mismatch, so this epic is additive to that work, not a rework of it.
- Scope Buckets:
- Provider abstraction extracted from `XaiRealtimeSessionService` (ephemeral token minting, `isAvailable()`, voice roster, websocket/connection parameters) with an OpenAI Realtime implementation added behind it.
- Frontend connection adapter in `reader.js` (currently xAI-specific: raw WebSocket with `xai-client-secret.<token>` subprotocol, `grok-transcribe` transcription model) to support OpenAI's differing connection model (WebRTC-preferred) without duplicating the whole call flow per provider.
- `voice.call.provider` config switch (mirroring the existing `ai.chat.provider` pattern in `LlmProviderConfig`) plus provider-specific voice catalog sources for the LLM voice-selection prompt.
- Decision on default/fallback behavior when the configured provider is unavailable (hard fail vs. automatic fallback to a secondary provider).
- Discovery Questions:
- Is there a concrete driver to prioritize this (cost, latency, voice quality, xAI reliability incidents) or does it stay speculative until one appears?
- Should provider selection be a single global config value, or configurable per-deployment/per-book/per-character?
- Does OpenAI's WebRTC-first browser connection model change the shape of the server response (`VoiceCallSession`) enough to warrant a versioned contract change?
- Should the LLM voice-selection prompt be provider-agnostic (one prompt, swappable roster) or provider-specific (tuned per roster's actual voice descriptions)?
- Current Direction (2026-07-07):
- Do not build speculatively; the persistence/selection layer already accommodates a second provider cleanly (provider tag stored, automatic re-select on mismatch).
- The abstraction is best designed once a second concrete implementation exists, since WebRTC vs. WebSocket differences could reshape the interface in ways that are hard to predict in advance.
- Trigger to move this from Discovery to Ready: a concrete business/technical driver (pricing, quality, or reliability), not the mere existence of a competing API.
- Exit Criteria for Discovery:
- Decision on trigger conditions for building this (see Current Direction).
- Approved provider interface boundary (what `XaiRealtimeSessionService` becomes an implementation of) and confirmation that WebRTC-based providers fit that interface without a rewrite.
- Decision on config granularity (global vs. per-book/character) and fallback behavior when a provider is degraded.

### BL-042 - Token Usage Tracking and Classroom Cost Calculator
- Type: Feature / Ops
- Priority: **P2 for classroom product UX**; **P1-timebound for commercial readiness** ahead of multi-teacher demo (**week of 2026-08-17 – 2026-08-21**) and this-term **$750/section** quote evidence
- Effort: L
- Status: Discovery
- Problem: Classroom pilots and group demos need **pricing that at least covers costs**. Fixed monthly infrastructure is knowable (DigitalOcean **droplet**, **Spaces**, **managed database**). **AI spend is the variable risk** and today there is little/no durable per-user / per-feature AI metering, so cost estimates and seat/term prices are under-supported. Need real activity + AI usage metrics to back a pricing conversation (Jessica + other teachers + AI council), not only a polished in-app teacher dashboard.
- Context (2026-08-06 partner call):
- Group demo expected after teachers meet **2026-08-17 – 2026-08-21**; Kevin will walk classroom + assignment use on the site and present **reasonable cost-covering pricing**.
- Pricing lean remains: **fixed per-term (or monthly) price**, **up to N seats**, **pooled AI token budget / rate limits** so variable AI does not blow the fixed fee.
- This epic can stay **lower priority than classroom feature depth** for day-to-day build order, but a **minimum internal metering + cost model** should land in time to inform the pricing one-pager before that meeting.
- Context (2026-08-14 — refine in place, not a new theme):
- Kevin is quoting a community-college pilot as a **fixed $750/section/semester** (~20 students, ~5 months). He wants **real per-student AI cost data this term** so the next quote is measured, not guessed.
- **SuperGrok Heavy OAuth** will subsidize usage while it lasts; the **xAI API key** is the fallback (already in `XaiLlmProvider` / `XaiRealtimeSessionService`; OAuth 401/402/403 retries with the key).
- Existing hook: `classroom_usage_events` + `ClassroomUsageService` today only persist **`READING_HEARTBEAT`** and **`ASSIGNMENT_VIEW`**. V14 already has `AI_TOKEN_USAGE` + token/cost column hooks (`feature`, `input_tokens` / `output_tokens`, `estimated_cost_micros`) — extend that store; do not invent a second ledger.
- This-term cut is child **`BL-042.5`**. Parent slices `.1`–`.4` stay; `.5` is the acceptance-ready subset, not a duplicate epic. Full cut under Acceptance (V14 `classroom_usage_events` field map).
- Scope Buckets:
- **User activity metrics (supporting):** sessions, assignment opens, reading progress events, quiz attempts — enough context to explain AI spikes (ties to `BL-025.6`; avoid duplicate event stores if possible).
- **AI usage metering (primary):** durable records for provider calls with at least: timestamp, feature/surface (character chat, Call Character / realtime voice, quiz generation/suggest, recap, Reading Buddy, illustrations/portraits/TTS if billed, etc.), model id, input/output token counts (or provider billable units), estimated $ at recorded unit prices, optional `userId` / `termId` / `classId` when known, success/error. **This-term (`BL-042.5`):** character chat + character voice only; columns under Acceptance.
- **Operator reporting (v1):** internal/admin or SQL-friendly rollups — daily/weekly AI $ by feature; per-user and per-class totals; p50/p95 student AI cost; top consumers. Teacher-facing “% of allotment” can wait. **This-term rollup:** see `BL-042.5` Acceptance.
- **Cost calculator / pricing support:** combine (a) fixed monthly infra floor (droplet + Spaces + DB + misc) amortized per class/term, (b) measured or scenario AI $/student at light/typical/heavy classroom patterns, (c) target margin → suggested **term price**, **seat cap N**, and **pooled token/rate limits**. Do **not** change the current **$750** quote on this card.
- **Rate-limit / budget hooks (optional same epic or follow-on):** enforce or soft-warn against pooled class/term AI budget once metering exists (align with `BL-038` credit ideas; do not require full PAYG storefront for pilot pricing).
- Work Tracker (suggested slices):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-042.1 AI call metering (write path) | Proposed | Instrument LLM/realtime/image/TTS providers to persist usage rows (tokens/units, model, feature, user when available) without blocking request path on report UI. Broader than this-term; **chat+voice this term is `BL-042.5`**. | Prod (or staging with prod-like traffic) produces queryable AI usage rows for major AI features used in classroom demos |
| BL-042.2 Operator rollups | Proposed | Daily/feature/user/class aggregates + simple export or admin query docs. This-term student/week + **$500** envelope view is **`BL-042.5`**. | Kevin can answer “$ AI last 7/30 days by feature” and “approx $/active student” from real data |
| BL-042.3 Cost model + pricing sketch | Proposed | Spreadsheet or small doc: fixed DO costs + AI scenarios + recommended term/seat/pool limits for group demo. **Do not revise the $750 quote here** — measure first (`BL-042.5`). | One-pager pricing recommendation ready before multi-teacher meeting; assumptions and measurement gaps explicit |
| BL-042.4 Classroom allotment UX (later) | Proposed | Teacher/school view of pool remaining; optional hard limits | Deferred unless pilot contract needs it; not required for first group demo |
| BL-042.5 This-term chat/voice cost evidence | Proposed | This-term cut of this epic (not a new theme): chat + voice `AI_TOKEN_USAGE` on V14 `classroom_usage_events`. Full cut under Acceptance. | Kevin can defend the next section quote from measured per-student chat/voice $ this term |
- Discovery Questions:
- Which activities need metered AI tracking first for the **Aug group demo** (character chat + voice call + quiz gen minimum?) vs later (illustrations, TTS, Reading Buddy)? **2026-08-14 lean:** this-term (`BL-042.5`) is **chat + voice only**.
- Should token accounting live per-request (raw provider usage) or be normalized into an internal "credit" unit shared with `BL-038`?
- What provider pricing sources should the calculator use, and how should it stay current as provider prices change? **This-term:** `estimated_cost_micros` at **current xAI** list rates.
- Should rate limits be enforced per-student, per-class, or **pooled at subscription/term** (lean: pooled)?
- Is v1 calculator **operator-only** (yes for Aug) vs teacher-facing usage later? **This-term:** teacher/ops-readable rollup is in scope; polished allotment UX stays `.4`.
- How to attribute **shared/cache hits** (e.g. pregenerated quiz/illustration) so classroom AI $ is not double-counted or under-counted? **This-term:** cached tokens in `metadata_json` (no dedicated column).
- Voice/realtime billing units may not be simple chat tokens — how do we normalize Call Character minutes/units into the same cost model? **This-term:** `duration_ms` on the same `AI_TOKEN_USAGE` row.
- Current Direction (2026-07-09):
- Grew directly out of educator partner feedback: before committing to a classroom subscription price, need real cost-per-student modeling based on actual token usage, not guesswork.
- Reuse `BL-038`'s credit ledger/ threading rather than building a second, separate accounting system if the data shapes are compatible.
- Start with a planning/reporting tool (internal), then decide whether usage/limit visibility needs to surface to teachers/schools.
- Current Direction (2026-08-06):
- **Priority framing:** product build order still favors classroom UX (quiz authoring, roster name, student drill-down); **BL-042 is the commercial evidence track** — ship a thin internal metering + cost model before the **week of Aug 17** group demo if possible.
- Cost structure to model explicitly: **fixed** = droplet + Spaces + DB (+ domain/misc); **variable** = AI (and any egress if material). Pricing must cover fixed floor at small N seats and not go underwater on heavy AI classes via **pooled budgets / rate limits**.
- Prefer append-only usage events from provider responses (actual tokens when APIs return them) over client-estimated tokens.
- Group demo success metric for this epic: Kevin can defend a **term + seats + AI pool** number with “based on measured X, assumed Y” — not a full self-serve billing product.
- Current Direction (2026-08-14):
- **Refined in place** — `BL-042` already covered usage/cost; do **not** open a sibling epic. Child **`BL-042.5`** is the this-term cut; full acceptance (V14 field map) below.
- Quote in market: **$750/section/semester** (~20 students, ~5 months). Next quote waits on measured per-student chat/voice $. **Do not change the $750 number on this card.**
- Subsidy: SuperGrok Heavy OAuth while it lasts; xAI API key is the fallback already in `XaiLlmProvider` / `XaiRealtimeSessionService`.
- Exit Criteria for Discovery:
- Decision on tracked AI activity scope for v1 metering (feature list). **This-term signed:** character chat + character voice (`BL-042.5`); other surfaces remain `.1`.
- Decision on internal accounting unit (raw tokens/units vs. normalized credits) and relationship to `BL-038`.
- v1 cost calculator inputs/outputs defined (fixed infra + usage volume → estimated cost → suggested plan tiers/limits).
- Explicit “good enough for Aug group demo” vs “full classroom allotment product” cut line. **This-term add:** “good enough to re-quote $750 from measured chat/voice $ vs the $500 envelope.”
- Acceptance Criteria:
- AI/provider usage is recorded for the v1 feature set with enough fields to roll up by time, feature, and user (when authenticated).
- Operator can produce per-student and per-class (or cohort) AI cost estimates from tracked usage and current model pricing.
- Cost model documents fixed monthly infra and combines it with AI scenarios to recommend a rate/limit shape for a target subscription price and margin.
- Pricing sketch suitable for multi-teacher / AI council conversation exists and cites measurement sources + gaps.
- **This-term (`BL-042.5`) — already covered by the parent theme; refined here, not duplicated:**
- Reuse V14 `classroom_usage_events` (`docs/product/bl-025-classroom-data-model.md` §11). Do not invent a second ledger or new column names. `event_type` = `AI_TOKEN_USAGE`. Today `ClassroomUsageService` only writes `READING_HEARTBEAT` and `ASSIGNMENT_VIEW`.
- Character chat turns and character voice sessions write per-student rows on existing columns: `user_id`, `class_section_id`, `term_id`, `feature` (V14 values: `CHAT` for character chat; Call Character / realtime uses that same vocabulary — not `chat|voice`), `model_name`, `input_tokens` / `output_tokens` (chat), `duration_ms` (voice; not minutes), `estimated_cost_micros` (current xAI list rates stored as micros — not a USD column), `occurred_at`.
- Cached tokens and `billed_via` (`oauth` | `api_key`): `metadata_json` (no dedicated columns). SuperGrok Heavy OAuth subsidizes while it lasts; xAI API key is the fallback already in `XaiLlmProvider` / `XaiRealtimeSessionService`.
- Prompt and completion text are **not** persisted on these events.
- A teacher/ops-readable rollup shows spend and minutes/tokens **by student and by week** (minutes derived from `duration_ms`), plus class totals vs the **$500 AI envelope**.
- Any request that actually bills the API key (OAuth 401/402/403 fallback) alerts; the **first** occurrence is noisy.
- **Out of scope:** voice minute caps, SuperGrok ToS, changing the $750 quote, rkj.
- Dependency Notes:
- Benefits from `BL-025.6` (student activity logging) for non-AI engagement context and class-scoped rollups; AI metering should still work if activity logging is partial (attribute what we can). **`BL-042.5` extends the same `classroom_usage_events` writer** (today heartbeat + assignment view only).
- Shares accounting foundations with `BL-038` (Public Character Chat Access and Cost Controls); avoid building a duplicate ledger long-term.
- Does **not** block BL-025 classroom UX slices; schedule as parallel ops work before commercial meetings.
- Session Log:
- 2026-07-09: Epic created from educator partner pricing/cost concerns.
- 2026-08-06: Expanded after Jessica call — multi-teacher + AI council demo target week of Aug 17–21; need cost-covering pricing; fixed DO costs known; prioritize real AI usage metrics for estimates; keep epic lower than classroom feature priority but time-bound for pricing prep.
- 2026-08-14: Refined in place (parent already covered usage/cost). Added child **`BL-042.5`** for this-term per-student chat/voice events, rollup vs **$500** AI envelope, and noisy first API-key bill after SuperGrok OAuth fallback. Commercial driver: measure real $/student this term before re-quoting the **$750/section/semester** community-college pilot. Docs only.
- 2026-08-14: Review nits — mapped `.5` onto V14 `classroom_usage_events` (`estimated_cost_micros`, `duration_ms`, `feature`/`CHAT`, `model_name`, `AI_TOKEN_USAGE`, `metadata_json` for `billed_via` + cached tokens); slimmed the work-tracker cell to a pointer; full cut lives under Acceptance.

### BL-043 - FERPA Compliance for Classroom Data
- Type: Tech Debt
- Priority: P0
- Effort: XL
- Status: Discovery
- Problem: Classroom deployment involves students' education records (roster PII, reading activity, quiz results, chat history); FERPA-relevant controls were incomplete, and a 2026-08-11 privacy review found pilot-blocking gaps between V14 schema hooks and runtime policy/enforcement.
- Scope Buckets:
- Data classification: identify which stored/API fields (roster PII, usage logs, chat history, quiz results) qualify as education records vs directory information under FERPA.
- Access controls: ensure only the enrolled student, their instructor(s) of record, and authorized school officials (when policy allows) can view a student's records.
- Consent/disclosure model: college-age pilot first; parental/guardian consent model for any later K-12 partners.
- Data retention and deletion policy for student records after a semester/term ends, a student leaves a class, or an account is deleted (`BL-021` 24h hard-delete promise).
- Audit logging for access to student education records (who viewed/exported what, and when) — schema exists; writers must run.
- Third-party subprocessors (OpenAI/xAI and voice/realtime): DPAs, notice, minimization; hold uncovered voice until covered.
- Prod deployment fail-closed for student-data paths (auth gate, classroom mode pins, Secure cookies) — FERPA acceptance; overlapping OWASP detail stays in `docs/SECURITY_AUDIT.md`.
- Privacy Review (2026-08-11) — already good (do not re-litigate):
- Deny-by-default `ClassroomAuthorizationService`; school-admin education-record access denied until this epic’s policy (`KD-16` in `bl-025-classroom-data-model.md`).
- Roster gated; owner-scoped account chats; Reading Buddy server history; hashed auth-audit email/IP; hashed invite codes.
- V14 schema hooks designed (`education_record_access_logs`, `chat_export_jobs`, `classroom_usage_events`, `retention_purge_after`, soft-delete columns).
- No product analytics SDKs found in client; teacher bulk export already backlog-gated behind this epic.
- Out of scope for this epic:
- General OWASP Top 10 remediations (Sentry / `docs/SECURITY_AUDIT.md`) except where a finding is restated here for FERPA/student-PII acceptance.
- ADA / accessibility (`BL-044`, `BL-013`).
- Inventing legal policy text — track work items and acceptance criteria only; counsel/partner supply DPA and notice language.
- Discovery Questions:
- Is the initial partner/pilot audience college-level (FERPA applies, but student is the rights-holder at 18+) or does K-12 need to be supported later (parental consent implications)?
- What is the minimum data retention window needed for legitimate educational/reporting purposes, and when must data be purged?
- Does chat history export (`BL-025.7`) need additional access logging or restrictions beyond normal read access?
- Do we need a signed data processing agreement / district agreement template for school customers?
- Account-delete vs active-enrollment hold: block delete while ACTIVE enrollment, or force export-before-delete notice?
- School-admin “school official” education-record access: remain deny-by-default for pilot, or document a narrow allow?
- Discovery Answers / lean (2026-08-11 review — still Discovery until product signs exit criteria):
- Pilot audience is **college-age** (student rights-holder). **No parental/guardian model** in schema — **block K-12** until designed (`BL-043.20`).
- Chat export and roster/education-record reads **do** need access logging for v1 (schema-only today is insufficient) — see `BL-043.5` / `BL-043.7`.
- DPAs / subprocessor disclosure for OpenAI/xAI (and voice when enabled) are **required before** treating classroom AI chat as pilot-safe — see `BL-043.3`.
- Retention duration default, purge job owner, and account-delete-vs-enrollment hold remain **open** (companion checklist in `bl-025-classroom-data-model.md`); schema hooks alone are not compliance.
- Current Direction (2026-07-09):
- Raised directly by the educator partner as a hard requirement for any real classroom pilot, not a later nice-to-have.
- Treat this as blocking for BL-025 slices that touch student-identifiable data (usage logging, dashboard drill-down, chat export) until an access-control and retention model is agreed.
- Current Direction (2026-08-11 FERPA / student-PII privacy review):
- Review completed; findings captured in the Work Tracker below. **Priority elevated to P0** for fall pilot urgency (~**2026-08-24** semester start; multi-teacher week **2026-08-17–21**).
- Status remains **Discovery** because policy exit criteria (retention duration, school-admin allow, account-delete hold, notice/DPA ownership) are not signed — but engineering gaps vs schema are inventoried and triageable.
- Prefer remediating overlapping auth/cookie/invite items once and accepting them against both `SECURITY_AUDIT` and this epic’s criteria; do not fork a second OWASP backlog.
- Finish Discovery exit criteria (`BL-043.13`) **before** shipping usage-event ingestion, teacher drill-down PII surfaces, or teacher bulk chat export (keep existing gates on `BL-025.6` / `.7` / `.10`).
- Exit Criteria for Discovery:
- Data classification of which classroom-related fields are FERPA-covered education records vs directory information (draft table exists in `bl-025-classroom-data-model.md`; finalize API-field gaps in `BL-043.17`).
- Documented access-control model (who can see/export a given student's data) — teacher/student matrix largely designed; school-admin + eligible-student export rules still open.
- Retention/deletion policy decision for post-term and post-enrollment student data, plus account-delete reconciliation with `BL-021` 24h hard-delete.
- Decision that audit logging for student-record access/export is **required for v1** (not optional follow-on).
- DPA / subprocessor notice path agreed for pilot (no legal text invented in-repo; tracking + disclosure surface only).
- Work Tracker:
| Slice | Status | Priority | Scope | Done When |
| --- | --- | --- | --- | --- |
| BL-043.1 Prod auth gate for student-data paths | Proposed | P0 | Live `/api/auth/status` showed `publicMode:false` / `canAccessSensitive:true`; `deployment.mode=local` default and `application-prod.properties` does not force `public`. Affects student chat/quiz pipelines. Overlaps `SECURITY_AUDIT` **C-01** / **H-07**. | Prod forces `deployment.mode=public` + Secure cookies; fail closed without auth material; re-verify status endpoint shows gated sensitive access |
| BL-043.2 Google OAuth email auto-link consent | Proposed | P0 | `AccountAuthService.resolveUserForExternalIdentity` links verified Google email to existing password accounts with no re-auth/consent → account takeover of education records. Overlaps `SECURITY_AUDIT` **H-04**. | Linking requires password re-auth or explicit link step; sessions invalidated on link; regression tests cover no silent takeover |
| BL-043.3 LLM subprocessors / DPA / disclosure | Proposed | P0 | CharacterChatService, ReadingBuddyChatService, OpenAiLlmProvider, XaiLlmProvider, voice/realtime send student content to OpenAI/xAI without in-repo privacy/DPA/subprocessor tracking. | DPAs (no-train/zero-retention where required) tracked; subprocessors disclosed in product docs surface; prompts minimized; voice/realtime held until covered |
| BL-043.4 Classroom invite TTL / max uses / revoke | Proposed | P0 | `ClassroomAdminService.createInvite` passes null,null though `InviteLinkService` supports expiry/max/revoke. Overlaps `SECURITY_AUDIT` **M-03** (FERPA elevates). Also `BL-025.2`. | Default expiry + max uses on create; revoke API + UI; rotate on create; leaked codes stop working after TTL/revoke |
| BL-043.5 Education-record access logging runtime | Proposed | P0 | V14 `education_record_access_logs` (and related export/usage tables) are schema-only — no Java writers; `listRoster` does not log `VIEW_ROSTER`. | Access logging on education-record reads/exports (incl. roster view); tests prove rows written; audit retention hook honored |
| BL-043.6 Account delete + retention purge runtime | Proposed | P0 | `BL-021` ADR promises 24h hard-delete; no `deleteAccount` API; `retention_purge_after` unused. | Account delete API + term purge job; honor retention matrix in `bl-025-classroom-data-model.md`; enrollment-hold or export-before-delete decision implemented |
| BL-043.7 Term-scoped server chat-export API | Proposed | P0 | Export jobs schema only; client `character-chat-export.js` is local-only. Student eligible-student export + teacher path per `BL-025.7`; always write access logs; expire artifacts. | Term-scoped server export with authz + access logs; artifacts expire; local-only download is not the sole compliance path |
| BL-043.8 Roster PII minimization + TA role | Proposed | P1 | Roster returns student emails; TA treated as teacher-like; no access log (`ClassroomAdminService.listRoster`). | Roster fields minimized/classified; TA permissions explicit and narrower than teacher where required; `VIEW_ROSTER` logged (`BL-043.5`) |
| BL-043.9 LLM error-log hygiene | Proposed | P1 | OpenAiLlmProvider / XaiLlmProvider `log.error` may capture provider bodies / prompt fragments. Overlaps `SECURITY_AUDIT` **L-05**. | Error logs omit prompt/PII/provider bodies (or redact); tests/docs state allowed log fields |
| BL-043.10 Server-authoritative character chat history | Proposed | P1 | Character chat trusts client `conversationHistory` before third-party LLM. Overlaps `SECURITY_AUDIT` **M-04**; prefer `BL-049` server history pattern (Reading Buddy already ignores client history). | Server loads/clamps history for LLM calls; client-supplied history not authoritative in classroom mode |
| BL-043.11 Privacy / FERPA notice + subprocessor list | Proposed | P1 | No privacy policy / FERPA notice / subprocessor list in product docs. | Tracked notice + subprocessor list published/linked for pilot (legal copy from counsel/partner; engineering owns surface + accuracy checklist) |
| BL-043.12 Session Secure cookies + TTL review | Proposed | P1 | Account session cookies default `Secure=false`; `session.ttl-minutes=43200`. Overlaps `SECURITY_AUDIT` **H-07** / **L-02**. | Prod Secure cookies enforced; TTL/rotation decision documented and applied for classroom accounts |
| BL-043.13 Finish Discovery policy gates | Proposed | P1 | Retention, school-admin education-record policy, parent/eligible-student rules still Discovery. | Exit criteria above signed; unlocks broad `BL-025.6` / `.7` teacher bulk / `.10` rollout (keep gates until then) |
| BL-043.14 Pin classroom mode in prod | Proposed | P1 | Hybrid default risk: pin `classroom.mode=database` and `classroom.demo.enabled=false` in prod. | Prod properties fail closed to database mode with demo off; tests/config review prevent demo/hybrid prod drift |
| BL-043.15 Production DB backup custody | Proposed | P1 | `scripts/backup_production_db.sh` → `~/Backups/classic-chat-reader/` needs FERPA custody (encrypt, access, retention). | Backup runbook documents encryption, access control, and retention aligned with education-record policy |
| BL-043.16 Hash/restrict auth-audit userId | Proposed | P1 | Auth audit logs plaintext `userId` (email/IP already hashed). | userId hashed or access-restricted consistently with other auth-audit fields |
| BL-043.17 Directory-info vs education-record classification | Proposed | P2 | API fields not classified; teacher email exposed as `teacherName`. | Field-level classification table finalized; directory vs education-record handling documented for roster/context APIs |
| BL-043.18 Classroom-mode claim/persistence gate | Proposed | P2 | Anonymous reader claim can move quiz/buddy history onto an account. | Classroom mode requires account before persistence of quiz/buddy education-adjacent history (or equivalent control) |
| BL-043.19 CSRF defense-in-depth (classroom/account) | Proposed | P2 | Cookie mutating classroom/account routes rely on SameSite=Lax. Overlaps `SECURITY_AUDIT` **L-01**. | Additional CSRF control on cookie-authenticated mutating classroom/account routes |
| BL-043.20 Block K-12 until guardian model | Proposed | P2 | Pilot assumes college-age; no parental/guardian model. | Product/docs explicitly limit pilot to college-age; K-12 blocked until guardian/consent design lands |
| BL-043.21 Server log-sink retention policy | Proposed | P2 | No client analytics SDKs found (good); still need server log-sink retention. | Ops retention policy for server logs/sinks documented and applied |
- Acceptance Criteria:
- Kevin can triage FERPA/student-PII work from this epic’s Work Tracker without a parallel spreadsheet.
- P0 slices (`BL-043.1`–`.7`) are clearly marked as fall-pilot blockers relative to **2026-08-24**.
- Discovery exit criteria remain the gate for broad usage events, teacher bulk export, and dashboard PII drill-down.
- Schema-vs-runtime gap is explicit: V14 hooks exist; writers, purge, delete, and notice/DPA work are tracked here.
- Dependency Notes:
- Gates `BL-025.6` (usage logging), `BL-025.7` (chat export — especially teacher bulk), and `BL-025.10` (dashboard drill-down) — those slices should not ship broadly until Discovery exit criteria (`BL-043.13`) are met and relevant P0 remediations land.
- `BL-025.2` invite lifecycle (expiry/max/revoke) implements `BL-043.4`.
- `BL-021` ADR retention/delete promises are fulfilled via `BL-043.6` (do not reopen BL-021 as In Progress solely for delete).
- Companion checklist + lifecycle matrix: `docs/product/bl-025-classroom-data-model.md` § Privacy / FERPA hooks.
- Overlapping OWASP IDs: `docs/SECURITY_AUDIT.md` (**C-01**, **H-04**, **H-07**, **M-03**, **M-04**, **L-01**, **L-02**, **L-05**) — implement once; accept against FERPA criteria here.
- Session Log:
- 2026-07-09: Epic opened from educator partner FERPA requirement; gates usage/export/dashboard.
- 2026-08-11: FERPA / student-PII privacy review completed. Elevated priority to P0; added Work Tracker for P0–P2 findings; recorded what’s already good; cross-linked schema companion checklist and SECURITY_AUDIT overlaps. Docs/tracking only.

### BL-044 - ADA/Accessibility Compliance for Classroom Deployment
- Type: Tech Debt
- Priority: P1
- Effort: M
- Status: Discovery
- Problem: School and institutional customers typically require ADA/WCAG-conformant software as a procurement condition; the general product accessibility pass (`BL-013`) is not scoped or verified against that bar.
- Scope Buckets:
- Formal WCAG 2.1/2.2 AA conformance target for classroom-facing surfaces (reader, teacher dashboard, roster/enrollment flows).
- Screen reader and keyboard-only verification for classroom-specific UI (join-link enrollment, teacher dashboard, student drill-down, assignment views).
- Documentation deliverable schools typically require during procurement (for example a VPAT-style accessibility conformance statement).
- Discovery Questions:
- Is WCAG 2.1 AA sufficient for the target partner/pilot, or does the institution require 2.2 or Section 508 alignment?
- Does the teacher dashboard (new surface from `BL-025.10`) get built accessibility-first, or audited after v1 ships?
- Who produces/maintains a VPAT if a school procurement process requires one?
- Current Direction (2026-07-09):
- Raised directly by the educator partner alongside FERPA as a pilot-blocking requirement, not general product polish.
- Scope this specifically to classroom-facing surfaces first (dashboard, roster/enrollment, assignments) rather than re-auditing the entire reader, since `BL-013` already covers general product accessibility.
- Exit Criteria for Discovery:
- Target conformance level agreed (for example WCAG 2.1 AA).
- Decision on whether a formal VPAT/conformance statement is needed for the pilot or can wait for broader school sales.
- Verification plan for new classroom surfaces (dashboard, join-link flow, drill-down) before they ship broadly.
- Dependency Notes:
- Builds on `BL-013` (Accessibility and mobile optimization pass) rather than duplicating it; classroom surfaces introduced by `BL-025` should be verified against this epic's conformance target before wide rollout.

### BL-045 - User Guide and Classroom Onboarding Documentation
- Type: Feature
- Priority: P1
- Effort: M
- Status: Discovery
- Problem: The product has no end-user-facing documentation today (only internal `docs/product/*` engineering/product notes). A funded college pilot means real teachers and students will need a reliable, current guide rather than word-of-mouth onboarding.
- Scope Buckets:
- General reader user guide: account sign-in, library/navigation, reading features (recap, quiz, chat, TTS, illustrations), settings/preferences.
- Classroom-specific guide: instructor setup (create class, share classroom-ID join link, configure feature toggles, manage roster/semester), student joining/using a class, teacher dashboard and student drill-down, chat history export, assignment workflow.
- Keeping the guide current as features ship: a lightweight process so guide updates are part of "done" for classroom-facing feature slices, not a one-off writing pass.
- Publication surface: where the guide lives and how teachers/students reach it (in-app help link, hosted docs site, or both).
- Discovery Questions:
- Should the general and classroom guides be one document with role-based sections, or two separate documents?
- Where should the guide be hosted/published, and does it need versioning as features change pre- and post-pilot?
- Does the pilot college need a lightweight printable/PDF version for instructor onboarding, or is a web page sufficient?
- Who owns keeping the guide updated as classroom features (`BL-025`) ship — is this a checklist item on each classroom slice's Definition of Done?
- Current Direction (2026-07-09):
- Driven directly by the funded college pilot: the professor's institution is funding a pilot for a couple of classes, which means real teacher/student onboarding needs to work without hand-holding.
- Prioritize the classroom-specific guide first since pilot classes are the near-term forcing function; general reader guide can follow or be built in parallel using the same structure.
- Treat guide accuracy as a rollout gate for classroom features going into the pilot, not a nice-to-have.
- Exit Criteria for Discovery:
- Decision on document structure (unified vs. split by audience) and hosting/publication surface.
- Decision on the update process tying guide maintenance to classroom feature delivery.
- Acceptance Criteria:
- A published classroom guide covers instructor class setup, join-link roster enrollment, feature toggle configuration, assignments, teacher dashboard/drill-down, and chat history export.
- A published general user guide covers account sign-in, core reading features, and settings for non-classroom readers.
- Guide is reachable from within the app (help link) for the relevant audience.
- Guide content is verified against the shipped state of `BL-025` slices before the pilot begins, and updates are captured as part of future classroom-facing feature work.
- Dependency Notes:
- Sequenced closely with `BL-025` since most of its content depends on classroom features actually existing; classroom guide sections should be drafted/updated as each `BL-025` slice ships rather than written all at once at the end.
- Should reflect final decisions from `BL-043` (FERPA) and `BL-044` (ADA) where they affect user-facing behavior (for example data retention/export behavior, accessibility features).

### BL-046 - Imported Book Data Quality and Duplicate Edition Cleanup
- Type: Tech Debt
- Priority: P1
- Effort: M
- Status: Proposed
- Problem: Classroom assignment QA found two indistinguishable local copies of **Pride and Prejudice**: one with 3 chapters and another with 59 chapters. The fuller copy should contain 61 chapters and is missing Chapters XXVII and XXVIII. Duplicate titles are currently indistinguishable in book selectors, so teachers can accidentally assign an abbreviated or malformed copy.
- Investigation Scope:
- Trace how the 3-chapter copy was created (sample seed, manual import, or interrupted/partial import) and determine whether the same path can affect production data.
- Re-run Gutenberg source `1342` through the current parser and identify why the fuller copy has 59 rather than 61 chapters.
- Define canonical import identity and duplicate policy using source + source ID where available, with an explicit policy for manual/alternate editions.
- Inventory existing duplicate books and chapter-index gaps without mutating data; design a dry-run cleanup/reconciliation path that preserves dependent recaps, quizzes, progress, annotations, and classroom assignments.
- Add interim selector disambiguation using author/source/chapter count so duplicate editions are visibly different before cleanup is complete.
- Add parser/import validation for suspicious chapter gaps, implausibly small chapter counts, and partial import failures.
- Acceptance Criteria:
- Root causes are documented for both the 3-chapter duplicate and missing Pride and Prejudice chapters XXVII/XXVIII.
- A regression fixture for Gutenberg `1342` produces all 61 chapters in the correct order.
- Re-importing the same source identity cannot silently create another duplicate local book.
- Existing duplicates and malformed imports can be reported and safely reconciled through a dry-run-first workflow.
- Book selectors clearly distinguish remaining legitimate alternate editions and expose chapter count.
- Dependency Notes:
- Coordinate cleanup with `BL-007` library administration and the existing dependent-record deletion safeguards; do not auto-delete or merge books without preserving reader/classroom references.
- This improves `BL-025.4` assignment reliability but does not block the basic classroom demo flow.

### BL-047 - Assignment Dashboard Shows Stale Quiz Completion After Returning from Reader
- Type: Bug
- Priority: P1
- Effort: S
- Status: Done
- Problem: After a student completes a chapter, its required quiz, and a required character chat, the first return to the Library can still show **Quiz required** and **2/3 complete**. Opening the book and returning again changes the same assignment to **3/3 complete** without additional work.
- Reproduction:
  1. Assign a chapter with both quiz and character chat required.
  2. As the student, complete the chapter and end-of-chapter quiz.
  3. Complete a character chat and download the transcript.
  4. Return to the Library; observe **Quiz required** and **2/3 complete**.
  5. Reopen the book and return to the Library; observe **3/3 complete**.
- Confirmed Root Cause:
- `backToLibrary()` renders immediately from the existing classroom context, where the server-backed quiz status can still be `PENDING`, while locally persisted character chat is already recognized.
- The subsequent `loadClassroomContext()` receives the completed quiz status but only rerenders when `!state.currentBook`. Returning to the Library does not clear `state.currentBook`, so the refreshed state is retained but not displayed until a later render.
- Acceptance Criteria:
- A single return to the Library after quiz completion refreshes the assignment card to **Quiz complete** and **3/3 complete** without reopening the book.
- The post-refresh rerender occurs only while the Library remains visible; a fast return into a book must not overwrite the reader view.
- Add an end-to-end regression test that returns `PENDING` on the initial classroom context request and `COMPLETE` on the refresh, then verifies the first Library return shows **3/3 complete**.
- Preserve immediate local recognition of character chat and existing reading-progress behavior.
- Related Note: Character chat completion currently means any nonempty locally stored chat history; downloading the transcript is not independently tracked or required by the completion calculation.

### BL-048 - Reading Buddy Classroom Toggle Misrepresents Global Availability
- Type: Bug
- Priority: P1
- Effort: S
- Status: Done
- Problem: The teacher workspace allows `readingBuddyEnabled=true` to be saved for a class even when the deployment-wide Reading Buddy rollout flag is off. This presents the feature as enabled to the teacher, but the reader never loads personas/preferences or sends proactive `check-comment` requests because `/api/reading-buddy/status` reports `available=false`.
- Confirmed Evidence (2026-07-21):
- The deployed status endpoint reported `enabled=false`, `available=false`, `providerAvailable=true`, and `chatEnabled=true`.
- Runtime access logs showed repeated page-position activity during reproduction but no Reading Buddy persona, preference, or `check-comment` requests.
- The classroom feature setting and deployment availability are currently independent; the teacher UI renders and saves the classroom checkbox without consulting global Reading Buddy availability.
- Acceptance Criteria:
- Teacher workspace clearly distinguishes the saved classroom policy from current deployment availability.
- When Reading Buddy is globally unavailable, the classroom control is disabled or accompanied by an explicit **Unavailable in this deployment** state; saving the form must not imply that students can currently use it.
- Decide and document whether the stored classroom preference remains enabled for automatic activation after rollout, or must be explicitly re-enabled by the teacher.
- Student settings must not expose an apparently usable Reading Buddy configuration when effective availability is false.
- Add frontend coverage for global-off/classroom-on and global-on/classroom-on combinations.
- Retain privacy-safe diagnostics for availability, client gate skips, backend silence reasons, provider errors, and successful comments without logging paragraph or generated-comment text.
- Resolution (2026-07-22): The saved classroom preference remains enabled during a global outage/rollout-off state and activates automatically when effective deployment availability returns. The teacher control is disabled with an explicit unavailable message while global status is off; student controls are hidden and disabled when the combined global and classroom gates are false.

### BL-049 - Character Chat Server Persistence (Cross-Device History)
- Type: Feature
- Priority: P1
- Effort: L
- Status: Done (shipped on `main`, PR #82)
- Problem: Character chat (and related show-and-tell completion signals) are **localStorage-only**. Students cannot resume conversations on another device/browser, and classroom completion/export cannot rely on durable history. Partner (Jessica, 2026-07-22) explicitly needs chats accessible regardless of device.
- Scope Buckets:
- Account-scoped server schema for character conversation threads + messages (book + character keys; timestamps; role/content).
- API to list recent threads, load history, append messages (and optionally claim-sync migrate existing localStorage threads on first signed-in open).
- Reader client: prefer server history when signed in; keep anonymous/local path; graceful offline/fallback policy.
- Retention/privacy: ownership by `user_id`; classroom teacher access remains out of scope until FERPA/`BL-025.7` policy (default student-only).
- Performance: message page size, last-N for model context, rate limits aligned with chat endpoints.
- Discovery / design decisions:
- Thread identity: `(userId, bookId, characterId)` vs multiple threads per character.
- Migrate-once localStorage claim vs dual-write period.
- Whether voice-call transcript turns use the same store (recommended: yes).
- Acceptance Criteria:
- Signed-in student can chat on device A, sign in on device B, and resume the same conversation.
- In-reader character chat loads server history when available; new turns persist server-side.
- Optional one-time migration of existing local character chat keys into the account store without duplicate spam.
- No teacher bulk read path in this epic (track under `BL-025.7` / FERPA).
- Dependency Notes:
- Unblocks truthful **My Chats** (`BL-039`/`BL-032`) and durable `BL-025.11` completion beyond local heuristics.
- Align retention/access notes with `BL-043` before any non-student consumers. Server export path: `BL-043.7` / `BL-025.7`. Client-history trust gap for LLM prompts: `BL-043.10` (prefer this epic’s server history as authoritative).
- Reading Buddy already has server message persistence patterns to reuse conceptually (do not couple schemas).
- Delivered (2026-07-22): authenticated owner-scoped load/send APIs, database-backed client synchronization, retry-safe idempotency, cross-device restoration, and isolation/order/rollback regression coverage. Legacy account-less localStorage transcripts are discarded rather than automatically claimed because ownership cannot be established safely.

### BL-050 - Reader Font Size Preference Clips Paragraph Text
- Type: Bug
- Priority: P1
- Effort: S
- Status: Done
- Problem: When the user increases **font size** in reader preferences (`BL-006`), text size updates but **paragraph content can be clipped** (content cut off / not fully visible within the page/column layout).
- Reported: Educator partner call (Jessica), 2026-07-22 — observed during product walkthrough.
- Investigation Scope:
- Re-pagination path after font-size change (`reader.js` preferences → measure → reflow pages) while preserving current paragraph context.
- Column height / overflow / line-clamp CSS interactions with larger type and line-height.
- Interaction with multi-column layout, page padding, and mobile vs desktop reader chrome.
- Acceptance Criteria:
- Increasing font size through preferences fully reflows so no paragraph body is clipped mid-glyph or cut by the page box at usable sizes in the control range.
- Decreasing font size and Reset preferences also reflow cleanly without leftover overflow.
- Current reading position (paragraph identity) remains stable across the reflow when possible.
- Add a regression check (Playwright visual or layout assertion, or unit coverage of pagination bounds) for a large font-size setting.
- Dependency Notes:
- Regression on shipped `BL-006` preferences panel; fix in reader pagination/layout, not a new preferences feature.
- Resolution (2026-07-22): Reader pages remeasure and repaginate immediately after font-size changes while preserving the active paragraph. Follow-up navigation coverage verifies all fragments of split paragraphs are shown before advancing chapters. Shipped in PRs #77 and #79 with desktop, laptop, tablet, and split-paragraph Playwright regressions.

### BL-051 - Reader Browser Back Goes to Prior In-App Page or Library
- Type: Improvement
- Priority: P3
- Effort: S
- Status: Proposed
- Problem: While reading, the browser **Back** button often leaves the app or walks opaque history (external referrer, intermediate redirects, or in-reader hash/state churn) instead of a sensible in-product exit. Users expect Back to return to **where they were in the site** (e.g. Library, My Chats, teacher workspace) or, if unknown, **Library**.
- Context: Miscellaneous convenience captured 2026-08-06 (Kevin). Related shipped behavior: logo/header back-link and removal of global Escape→Library (Escape closes overlays only — see `current-features.md`). This item is specifically **browser Back / history**, not Escape.
- Scope Buckets:
- While the **reader view is active**, intercept or shape history so Back prefers:
  1. **Previously accessed same-origin app page** when that history entry exists and is safe; else
  2. **Library** (`/` or established library route) as default.
- Open reader with a deliberate history entry (e.g. `pushState`/`replaceState` strategy) so Back does not immediately exit the site after Library → book.
- Preserve overlay-first behavior: if a modal/overlay is open, Back or Escape should close it first when that matches existing patterns (do not fight Escape semantics).
- Mobile Safari / Android Chrome gesture-back parity as much as History API allows.
- Out of scope unless cheap: full SPA router rewrite; deep multi-step undo of in-book page turns via Back (chapter/page Back is a separate product decision — default is **exit reader**, not reverse pagination).
- Discovery Questions:
- Should in-reader page/chapter changes push history entries, or only Library↔Reader?
- If user landed on a deep reader URL from an external link, should first Back go to Library or allow leaving the site?
- Interaction with My Chats full-page resume URLs and assignment deep links?
- Acceptance Criteria:
- From Library (or another in-app surface) → open book → browser Back returns to that prior in-app surface when it was same-origin app navigation.
- From a cold/deep reader entry with no usable in-app prior page, Back lands on **Library** rather than a blank/external surprise when the app can still handle the navigation.
- Open overlays continue to dismiss via existing Escape/close controls; Back does not discard unsaved overlay work without the same safeguards used elsewhere (if any).
- Add a focused Playwright (or equivalent) case for Library → reader → Back → Library (or prior page).
- Dependency Notes:
- Touch `reader.js` history / view-switching only; keep framework-free static app constraints.
- Do not reintroduce Escape-as-leave-reader without an explicit product decision (previously removed to avoid accidental exits).

### BL-052 - Partner Short-Story List on Curated Catalog (Fall Early Path)
- Type: Feature / Content ops
- Priority: P1 (time-bound to early fall if partner wants day-one use; otherwise P2 before mid-semester books)
- Effort: M (depends on list length and public-domain availability)
- Status: **Done** — content-ops slice complete on `main` (PR #101): assignable PG short works/poetry curated with dedicated `aliases`; three titles explicitly deferred (not readily clean Gutenberg ebooks). Prod import/pregen/Spaces is routine `ccr-production-ops` when Kevin publishes — not open epic scope.
- Problem: Partner’s fall class **starts with short stories**, not full novels. Early-semester classroom use needs those titles **discoverable and classroom-ready** (curated catalog + import + feature flags + assets), not only long-form classics already prepped for demos.
- Context (2026-08-06):
- Fall semester starts **2026-08-24**.
- Jessica would like to use the site at semester start if ready; if not, **books begin mid-semester** anyway — short stories are the early unit.
- Kevin requested her **scheduled short-story list** to add them to the **curated** list.
- Context (2026-08-11):
- Jessica’s **ENGL 1020 Fall 2026** weekly reading schedule received (docx; OER / public domain). Readings are to have been read **before** the day listed. Short stories / drama first; *Northanger Abbey* later mid-semester.
- List recorded below with **Already curated** vs **Missing** status against `CuratedCatalogService`, plus suggested Project Gutenberg container IDs for missing items (**verify title/author before import** — IDs are suggestions, not sacred).
- **CCR imports whole Gutenberg books** (often anthologies/collections). Short assigned works may therefore land as **multi-work volumes** rather than a single short story/poem — treat anthology UX as a documented product gap (see Acceptance Criteria).
- Week-by-week order (prioritization): Week1 Story of an Hour → Week2 Cask of Amontillado + Rip Van Winkle → Week3 Jumping Frog → Week4 Rappaccini’s Daughter → Week5 Trifles + Hamlet Act1 → Weeks5–7 Hamlet → Week7 shepherd poems → Week8 sonnets → Week9 Ulysses + My Last Duchess → Week10 Dickinson + Brontë poem → Week10 Prufrock → Weeks11–14 Northanger Abbey. Early-semester short fiction remains **P1**.
- Context (2026-08-12 verification + PR #101 ship):
- Verified candidate IDs against Project Gutenberg **RDF + offline `pg_catalog.csv` + full-text**; gutendex re-check **2026-08-11** for remaining gaps.
- Shipped on `main` via **PR #101**: verified curated + `aliases` for Cask (**1063**), Jumping Frog/Sketches (**3189**), Rappaccini/Mosses (**512**), Trifles/Plays (**10623**), plus poetry containers **1041**, **8601**, **16376**, **12242**, **1459**. Dedicated `aliases` field (subjects stay LCSH-like).
- **Wrong-container catch:** suggested Chopin PG **160** (*The Awakening, and Selected Short Stories*) does **not** contain “The Story of an Hour” (TOC/text confirmed). Kate Chopin’s other PG volumes (**23724**, **23810**, **46650**, **63025**) also lack it. No PG catalog title matches “Story/Dream of an Hour”. Standard Ebooks sources the story from **HathiTrust**, not Gutenberg.
- **Trifles edition choice:** PG **10623** (*Plays*, Glaspell sole author; Trifles leads; recently updated) preferred over **59432** (larger 1916-era collection with Cook collaborations). Both contain Trifles.
- Anthology → assigned-work map (for operators / partner):
  - “Cask of Amontillado” → standalone PG **1063**
  - “Jumping Frog…” → inside PG **3189** *Sketches New and Old*
  - “Rappaccini’s Daughter” → inside PG **512** *Mosses from an Old Manse*
  - *Trifles* → inside PG **10623** *Plays*
  - Sonnets 18/73/116/130 → inside PG **1041**
  - “Ulysses” → inside PG **8601**
  - “My Last Duchess” → inside PG **16376**
  - Dickinson pair → inside PG **12242**
  - “Prufrock” → inside PG **1459**
- Fall 2026 reading list checklist:
  - **Already in CCR curated catalog** (3; unchanged):
    - [x] *Rip Van Winkle* — Washington Irving — PG **64636**
    - [x] *Hamlet* — William Shakespeare — PG **2265**
    - [x] *Northanger Abbey* — Jane Austen — PG **121**
  - **Short fiction / drama:**
    - [x] “The Cask of Amontillado” — Edgar Allan Poe — PG **1063** (standalone; verified title/author/HTML)
    - [x] “The Celebrated Jumping Frog of Calaveras County” — Mark Twain — PG **3189** (*Sketches New and Old*; story present in text)
    - [x] “Rappaccini’s Daughter” — Nathaniel Hawthorne — PG **512** (*Mosses from an Old Manse*; story present in text)
    - [x] *Trifles* — Susan Glaspell — PG **10623** (*Plays*; chose over 59432)
  - **Poetry:**
    - [x] Shakespeare Sonnets 18, 73, 116, 130 → PG **1041** (*Shakespeare’s Sonnets*; texts verified)
    - [x] Tennyson “Ulysses” → PG **8601** (*The Early Poems of Alfred Lord Tennyson*; poem verified)
    - [x] Browning “My Last Duchess” → PG **16376** (*Browning’s Shorter Poems*; poem verified)
    - [x] Dickinson “Because I could not stop for Death” and “I’m Nobody! Who are you?” → PG **12242** (*Poems by Emily Dickinson, Three Series, Complete*; poems verified)
    - [x] T. S. Eliot “The Love Song of J. Alfred Prufrock” → PG **1459** (*Prufrock and Other Observations*; verified)
- Remaining (deferred) — not readily on Project Gutenberg as assignable single works (gutendex re-check 2026-08-11); out of scope until a non-PG or better source appears:
  - [ ] “The Story of an Hour” — Kate Chopin — **DEFERRED**: no Gutenberg ebook contains this story (suggested PG **160** was wrong; searches don’t surface a Chopin volume with it). Defer until a rights-cleared non-PG OER source or teacher-provided public-domain text path exists.
  - [ ] Marlowe “The Passionate Shepherd…” + Raleigh “The Nymph’s Reply…” — **DEFERRED**: no clean dedicated PG hits for the pair; not curating a giant verse anthology just for two lyrics.
  - [ ] Emily Brontë “The night is darkening round me” — **DEFERRED**: no clean PG poem collection hit for this title.
- Scope Buckets:
- Track partner reading list (title/author/edition notes) — **received 2026-08-11**; checklist above current for shipped + deferred.
- Resolve each missing item to a **correct Gutenberg (or other approved) ID** via gutendex/PG metadata (do not trust unverified suggestions — see Scarlet Letter 25344 lesson + Chopin 160 wrong-container catch) — **complete** for assignable PG works; remaining three deferred with reasons.
- Curated-catalog membership — **Done** on `main` (PR #101); prod import/pregen/Spaces is routine ops (`ccr-production-ops`), not open epic work.
- Content readiness: chapter/structure quality for short works, progressive character discovery if chat is used, quiz/pregen only where it makes sense for single-sitting texts.
- Teaching path: confirm assignments can target a short work / its “chapters” without novel-centric assumptions breaking UX.
- Out of scope until requested: non-public-domain stories; custom teacher upload of copyrighted PDFs; reopening deferred titles without a non-PG / better source.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-052.1 Resolve / verify PG IDs | Done | Confirmed containers for P1 short fiction/drama + poetry; chose Trifles **10623**; deferred Chopin / shepherd pair / Brontë with reasons | Each checklist row has verified ID or explicit TBD/defer |
| BL-052.2 Curated catalog | Done | Added verified titles to `CuratedCatalogService`; dedicated `aliases` for assigned short-work names inside anthologies; `pregen_missing_books.sh` ID list updated | Missing must-have early-semester works (except deferred titles) are curated |
| BL-052.3 Import / pregen / transfer | Deferred (ops follow-up) | Catalog membership on `main` is Done; live publish follows `ccr-production-ops` when Kevin runs prod publish (flags + DB + Spaces) — routine ops, not open epic scope | Titles importable on the pilot path without manual DB surgery |
| BL-052.4 Prod verify | Deferred (ops follow-up) | Spot-check title/author, structure, and assignability after prod publish — outside BL-052 closure | Partner can assign Week2+ early short works on prod; Week1 Chopin gap documented |
- Acceptance Criteria:
- Each list item is either **curated/importable with verified ID and title/author match**, or explicitly **deferred** with reason (rights, missing text, bad parse) — **met** (shipped on `main` + Remaining deferrals).
- Partner can assign at least the must-have early-semester stories without manual DB surgery (after routine prod publish of curated IDs).
- Document short-story / poetry product gaps: CCR imports **whole Gutenberg books**, so many short assigned works will appear as **anthology / multi-work volumes** (not a single-story book); note single-chapter and assignment-target UX implications.
- Dependency Notes:
- Follows `ccr-production-ops` publish path (curated ≠ pregen; flags + DB rows + Spaces) for `.3`/`.4` ops follow-up.
- Unblocks early-fall pilot narrative even if full BL-025 quiz authoring / dashboard is incomplete.
- Session Log:
- 2026-08-06: Created; blocked on partner list.
- 2026-08-11: Partner ENGL 1020 Fall 2026 weekly schedule received (docx). Status → **Ready**. Recorded already-curated (3: Rip Van Winkle 64636, Hamlet 2265, Northanger Abbey 121) vs missing (12: 5 short fiction/drama + 7 poetry items) with suggested PG container IDs; noted whole-book/anthology import gap; prioritized early-semester short fiction.
- 2026-08-11 evening / after PR #101 merge: Gutendex re-check confirms remaining titles are **not readily identifiable as clean Gutenberg ebooks**. Explicit deferrals recorded (Chopin “Story of an Hour”; Marlowe/Raleigh shepherd pair; Brontë “The night is darkening round me”). Status → **Done** — content-ops slice complete; deferred items out of scope until a non-PG or better source appears; `.3`/`.4` folded to routine prod publish under `ccr-production-ops` (not open epic scope).
- 2026-08-12: Catalog PR work landed on `main` as **PR #101** (verified curated + dedicated `aliases` for 1063, 3189, 512, 10623, poetry 1041/8601/16376/12242/1459; subjects stay LCSH-like). Epic closed as **Done** with Remaining (deferred) subsection; backlog handoff pointers updated so BL-052 is no longer active/Ready/In Progress.

### BL-053 - Classroom Concurrent Load / Droplet Capacity Validation
- Type: Ops / Tech Debt
- Priority: **P1-timebound** for multi-teacher demo week and fall pilot honesty; P2 as ongoing capacity program
- Effort: M
- Status: Proposed
- Problem: Production runs on a small DigitalOcean droplet (historically **~1 vCPU / 1GB** class for `public-domain-reader`). Kevin’s open concern: **will it hold a real classroom concurrent load?** Unknown **concurrent user / session thresholds** before latency, 502s during Spring boot pressure, or AI fan-out makes the product unusable. Scalability already called out in pricing lean; this epic is the **measured capacity** answer.
- Context:
- Related: fixed host cost in `BL-042`; boot lag and small-droplet behavior already noted in deploy ops.
- Pilot shape: one or a few college classes (not district-wide), but **same-hour concurrent readers + chat/quiz** is the stress case.
- Scope Buckets:
- Define pilot load hypotheses (e.g. 25 / 50 / 100 concurrent students; mix of read-only vs chat vs quiz vs voice).
- Baseline prod metrics: CPU, memory, GC, request latency, error rate, DB connections, AI outbound concurrency.
- Load/soak test plan against staging or carefully scheduled prod-like env (prefer not melting live pilot mid-class without a plan).
- Identify first bottlenecks: JVM heap on 1GB, Tomcat threads, DB pool, blocking LLM calls, static/asset path, Spaces.
- Scale-up options with cost deltas: resize droplet, add swap (mitigation only), separate DB already in play, read replicas later, queue/limit AI, CDN for static.
- Write a short **capacity note** for Kevin (and optional AI council): “comfortable concurrent N”, “degraded at M”, “scale action if pilot grows”.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-053.1 Baseline + hypotheses | Proposed | Document current droplet size, observed prod headroom, pilot concurrency targets | One-page baseline exists |
| BL-053.2 Load exercise | Proposed | Run controlled concurrent scenarios (library, reader turn pages, character chat, quiz submit) | Numbers for p95 latency / error rate vs concurrent users |
| BL-053.3 Scale recommendation | Proposed | Thresholds + cheapest next resize/architecture step before fall classes | Kevin can answer “will one class fit?” with evidence |
- Discovery Questions:
- What is Jessica’s expected class size and simultaneous online fraction?
- Is voice Call Character in-scope for early pilot load (much heavier) or chat-text only?
- Acceptable p95 for page turn vs chat completion during class?
- Acceptance Criteria:
- Documented concurrent-user bands (OK / watch / fail) for the current droplet under a stated scenario mix.
- Explicit recommendation: stay / resize / rate-limit AI / other, with rough monthly $ impact.
- Monitoring checklist for live class sessions (what to watch on the box during pilot).
- Dependency Notes:
- Pairs with `BL-042` (cost) but measures **performance capacity**, not token spend.
- May motivate AI concurrency limits even before full metering UI.
- Session Log:
- 2026-08-06: Captured from Kevin post-call concern (scalability / DO droplet concurrent classroom load).

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
- 2026-07-22: Partner-reported regression — large font size can clip paragraph text; tracked as `BL-050` (does not reopen BL-006).

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
- 2026-02-12: Implemented `com.classicchatreader.cli.CacheTransferRunner` with `export`/`import`, `skip|overwrite` conflict handling, format validation, dry-run default, and H2 URL normalization (`DB_CLOSE_ON_EXIT=FALSE`) to avoid exec-classloader shutdown issues.
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

### Reading Buddy Mode
- Type: Feature
- Priority: P1
- Effort: L
- Status: Done (implementation); **prod flag-on Blocked** on merge to `main` + public deploy verification (local spoiler suite + E2E smoke green)
- Problem: Readers want optional companion commentary/chat without spoiling future plot or leaving the book-like experience.
- Scope: Canned personas, position-bounded story context, server memory, proactive hard filters + LLM decide, reader UI (toggle/toast/modal), rolling summary watermarks, public-mode auth/rate limits, classroom FE kill-switch.
- Design: `docs/product/reading-buddy-mode.md`
- Branch: `feature/reading-buddy` (execute-plan PR1–PR6 tip + backlog/status sync)
- Default: `reading-buddy.enabled=false` (do not enable in prod until gates below pass).
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| PR1 Flags + personas | Done | `reading-buddy.*` properties, canned persona catalog | Status API reflects flag + chat provider |
| PR2 Schema + prefs + claim | Done | Messages/memory/prefs tables; prefs API; claim-sync merge | Controller/service tests pass |
| PR3a Prompt + story window | Done | Spoiler-safe prompt builder + position-bounded paragraphs | Prompt tests for boundary + watermark omit |
| PR3b Chat + history | Done | Chat/history endpoints with server memory | Chat ignores client history for prompts |
| PR3c Proactive + hard filters | Done | Trigger policy, check-comment, buddy-check rate limit | Silence/COMMENT paths tested |
| PR4 Reader UI + classroom | Done | Toggle, toast, modal, classroom FE gates | UI wired; toast never auto-opens modal |
| PR5 Summary refresh | Done | Rolling summary + fail-closed watermark omit | Summary ch10 omitted at ch3 |
| PR6 E2E + spoiler gate + docs | Done | Spoiler acceptance suite, Playwright smoke, product docs, rollout comments | Suite + e2e green; docs list Reading Buddy |
- Rollout gate (required before `reading-buddy.enabled=true` in prod):
  1. `ReadingBuddySpoilerAcceptanceTest` green (P&P / Frankenstein mid-book deflection, historian prefer-NONE, ch10 message filter, history visibility, summary watermark omit). **Local green 2026-07-09.**
  2. Playwright smoke `e2e/reading-buddy.spec.js` green. **Local green 2026-07-09.**
  3. Public-mode auth/rate-limit verification for `/api/reading-buddy/chat` and `/check-comment` (unit coverage present; re-check on real public deploy).
  4. Merge `feature/reading-buddy` to `main` before any prod flag-on.
- Session Log:
- 2026-07-08: PR6 landed required spoiler acceptance suite, public-mode buddy route guard tests, Playwright reading-buddy smoke, product inventory/backlog updates, and `application.properties` rollout comments. Default remains off.
- 2026-07-09: Consolidated execute-plan PR1–PR6 tip onto `feature/reading-buddy` for local testing. Verified: frontend tests (49), ReadingBuddy* Maven suite (142), spoiler acceptance suite, Playwright reading-buddy smoke (3), full `mvn test`. Updated design doc status to Implemented (prod flag-on gated).

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
