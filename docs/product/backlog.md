# Product Backlog

Last updated: 2026-08-26

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
- **Cask / Fortunato never discovered** after a full short-story read (incognito TTS + `demo_teacher` `j`-key) — empty roster. **`SECONDARY` must still discover and appear in the roster**; PRIMARY is a separate chat-lead preference. Tracked in `BL-056`.
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

**Partner schedule (2026-08-18 — Kevin):**
- **Tue 2026-08-18 evening (America/Chicago):** Jessica 1:1 **dry run**. **Went pretty smoothly.**
- **Thu 2026-08-20 noon (America/Chicago):** CCR demo with **Columbia State** — **done; Kevin: everything was smooth.** Deck: `ClassicChatReader-Pilot.pptx`.

**Partner feedback (2026-08-20 Columbia State group demo):**
- **Kevin:** demo went well; no technical snags reported.
- **Jessica Evans** — collaborator (ENGL 1020). In-chat: OER would be all right **so long as they allow adaptation**.
- **Aaron Beshears** — AI Director; **grant-money decision maker**. Said a **pilot this semester is unlikely**. Do not treat Fall 2026 as an institutional/grant-funded cohort unless he reverses that.
- **Thomasanna Hail** (chat display **Hail, Thomasanna Carroll**) — physics. Loved it; asked whether **OER textbooks** (astro/physics) would be OK; later asked whether cost can **slide into student fees** vs “pass a hat”; “definitely interested in thinking about adopting some of this for my classes.”
- **Jonathan Owen** — AI council. Liked it; called out **reading accommodations** and **ELL**. Stepped out for Copilot for Faculty 2.0.
- **Amy McDonald** — leadership. Strong on **accessibility**; “this is amazing”; first thing she clicked was **MLA citation** (`BL-029`). Mentioned **OER** licensing in the non-PD discussion.
- **Alexander Melendez** — AI instructor. Asked whether the reader can take **books outside public domain**. Kevin said he would **research** (no product commitment). Fold under **`BL-071`** Discovery — do not promise copyrighted PDF upload or OpenStax-in-a-paid-app.
- Chat also: “Free textbooks for students!!” (Thomasanna, on OER). Do not equate OER with “free to host in a $750/section product” — license is **per title/edition** (`BL-071`). **NC** is still a Discovery Question (college payment is relevant, not an automatic commercial-use finding). **ND / NC-ND** block distributing adaptations even when commercial reuse is otherwise allowed.

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
7. ~~`BL-025.5` / `.12` / `.13` teacher quiz authoring + pass rules + defaults~~ shipped. Columbia State group demo **2026-08-20 noon** completed (smooth).
8. ~~Roster **display name** (optional student self + teacher override) on `BL-025.2`; `BL-025.10` v1 teacher→student overview~~ **`BL-025.10` pilot drill-down shipped** (roster→student overview + opened timestamps + thin heartbeat); optional display-name edit UX on `BL-025.2` still open (email / existing override OK for demo).
9. **`BL-042` (ops/pricing)** + **`BL-053` (capacity):** still needed for a defendable quote. Room asked **student-fee vs department** path (Thomasanna); Aaron said **grant pilot this semester unlikely**. This-term cut remains **`BL-042.5`**. Do not invent a bursar/fee integration. **`BL-071`:** research-only OER / non-PD ingest (Alexander + Amy + Jessica adaptation caveat) — no build until Kevin decides.
10. ~~**`BL-052`:** verify PG IDs + curated catalog for early short works~~ **Done** (PR #101): assignable PG short works/poetry curated; Chopin / shepherd pair / Brontë poem deferred (not readily on Gutenberg); prod import/pregen is routine ops outside epic scope.
11. Invite redeem rate limits (BL-028 pattern) still open. ~~Invite TTL / max uses / revoke (`BL-043.4` / `BL-025.2`)~~ **Done this PR** (30-day TTL, 40 max uses, revoke API/UI, rotate on create).
12. ~~**`BL-043.1` / `.12` prod auth gate + Secure cookies**~~ **Done** (PR #152; live `/api/auth/status` re-probe after deploy). ~~**`BL-043.2` Google OAuth email auto-link consent**~~ **Done** (PR #153; password re-auth before link; sessions deleted on link). ~~**`BL-043.4` invite lifecycle**~~ **Done this PR**. Remaining **`BL-043` FERPA P0** (`.3` DPA, `.5` access-log writers, `.6` account delete + retention, `.7` server chat-export API).
13. ~~**`BL-054` character-chat conduct guardrails** (college-appropriate)~~ **prompt v1 shipped** (`CharacterPersonaPromptBuilder` CONDUCT block shared by text + voice; golden-set tests). Optional output fallback (`BL-054.5`) remains if prompt-only still leaks in QA.
14. FERPA-gated after Discovery exit + P0 remediations: full usage event platform (`BL-025.6`), teacher chat export (`BL-025.7`), **broad** dashboard rollout beyond pilot teacher drill-down (`BL-025.10`)
15. **`BL-056` Cask Fortunato never discovered** (Week 2 short story; mark PRIMARY + confirm prod rows/QA). Do not block FERPA, but fix before treating Cask as a character-chat demo book.

**Not started:** **FERPA P0 remediations (`BL-043.3`, `.5`–`.7`)** (`BL-043.1` / `.12` **Done** PR #152; `BL-043.2` **Done** PR #153; `BL-043.4` **Done this PR**), roster display-name edit UX (`BL-025.2` remaining), full `BL-025.6` platform (beyond thin heartbeat), **AI cost metering (`BL-042` / this-term `BL-042.5`)**, **classroom concurrent capacity (`BL-053`)**, full character-chat assignment completion tracking / teacher export (`BL-025.11` deeper slices), school-tier admin UI, reader browser-Back convenience (`BL-051`), teacher-defined trophies (`BL-055`), **Cask Fortunato discovery (`BL-056`)**, dedicated assignment page + reduced landing card (`BL-057`), assignment Open / persist leftovers (`BL-058`), landing library load (`BL-059`), arrow-key paragraph nav (`BL-060`), My Chats Open Book skip landing (`BL-061`), character browser detail Esc + list j/k (`BL-062`), character blurb spoilers (`BL-064`), character regen with xAI (`BL-065` — `.1` documented / `.2` In Progress this follow-up after Kevin’s local trial; **do not mark `.3` catalog done**), open general registration after Columbia State approval (`BL-066`), recap chat misses chapter characters (`BL-067`), new-account Completed leak (`BL-068`), iPhone book covers (`BL-069`), teacher workspace first paint (`BL-070`). (`BL-063` Gutenberg library-card cite **In Progress** — landing this PR.) (`BL-025.10` pilot drill-down **In Progress / demo-ready**; broad FERPA-gated dashboard still blocked.) (`BL-052` content-ops **Done** — deferred titles noted under the epic; prod publish when Kevin runs `ccr-production-ops`.) (`BL-054` prompt v1 **Done**; optional output fallback still open.) (`BL-071` OER/non-PD ingest **Discovery** — research only, do not build.) (`BL-072` durable curated membership **Proposed** — studio ST-008 depends on it.) (`BL-073` teacher CREATE_CLASSROOM operator API **Proposed / P3 later** — studio ST-009 stub.) (`BL-074` portrait request/regen **In Progress** — `.1` HTTP seam this PR; studio ST-001/008.) (`BL-075` portrait/illustration upload **Proposed** — studio ST-002 keep/restore.)

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
- **Tue 2026-08-18 evening:** Jessica 1:1 dry run — **done, smooth.**
- **Thu 2026-08-20 noon:** Columbia State group demo — **done, smooth.** Attendees: Jessica Evans, Aaron Beshears (AI Director; grant; **this-semester pilot unlikely**), Thomasanna Hail (physics), Jonathan Owen (AI council), Amy McDonald (leadership / a11y), Alexander Melendez (AI instructor). Open asks: OER/non-PD (`BL-071`), student-fee path (`BL-042`).
- **Fall semester starts 2026-08-24.** Jessica still may want ENGL 1020 use (short stories first, `BL-052`). **Institutional/grant pilot this term is unlikely** per Aaron — do not plan a funded multi-section rollout for Fall 2026 unless he says otherwise.

Statuses: `Discovery`, `Proposed`, `Ready`, `In Progress`, `Blocked`, `Done`

## Current Delivery State

- Most recent completed slice: `BL-028 - Account auth endpoint hardening` (`Done`, delivered account auth rate limiting with `429` + `Retry-After`, login lockout/backoff persistence, and structured non-PII auth audit events with regression test coverage).
- Most recent shipped UI improvement (2026-04-27): cover-forward library shelves with generated book covers, `Continue Reading` feature card, subtler search, horizontal shelf gutters/fades, and desktop shelf arrow controls.
- Most recent shipped hardening (2026-02-24): completed BL-028 account endpoint safeguards, tightened public-mode TTS behavior so cached paragraph audio remains available without collaborator auth while uncached generation remains protected, and finalized compact reader header/menu interactions (logo back-link, desktop shortcuts, keyboard-driven menu navigation).
- Reading Buddy Mode is implemented on `main` (flags → schema/prefs → prompts → chat/history → proactive → UI → rolling summary). Deployment availability and saved classroom policy are represented separately; the default remains `reading-buddy.enabled=false`.
- Active priority work remains the deeper `BL-025` classroom pilot path plus **`BL-043` FERPA/student-PII** after the 2026-08-11 privacy review (P0 pilot blockers trackable in the `BL-043` work tracker; Discovery policy gaps vs V14 schema hooks still open). Parallel ops tracks: `BL-042` AI cost evidence and `BL-053` droplet concurrent capacity (Columbia State group demo **2026-08-20** completed; Aaron: grant pilot this semester unlikely). Classroom character chat **`BL-054` prompt v1** encodes college-appropriate conduct (NSFW/jailbreak refusal without killing fun, engaging, character-aligned tone); optional output fallback remains if QA still sees leaks. `BL-052` short-story curation is **Done** (PR #101; deferred titles + prod publish ops noted on the epic). **`BL-071`** OER/non-PD ingest is Discovery only. **`BL-072`** durable curated membership is Proposed (ccr-studio ST-008 depends on `.3`). **`BL-073`** teacher capability operator API is Proposed / **P3 later** (studio ST-009 stub; flesh out later). General OWASP stays in `docs/SECURITY_AUDIT.md` (do not duplicate here).
- 2026-08-23: First `BL-043` engineering slice — prod auth gate + Secure cookies (`BL-043.1` / `.12`). See epic session log.
- 2026-08-23: `BL-043.2` Google OAuth email auto-link consent — password re-auth before link; sessions deleted on link. See epic session log.
- 2026-08-23: `BL-043.4` classroom invite TTL / max uses / revoke — 30-day default expiry, 40 max uses, revoke API/UI, rotate on create. See epic session log.
- 2026-08-24: `BL-065.2` trial prompt work (code only). Tightened discovery/prefetch + chapter-extraction prompts (named people only; first-appearance blurbs). Shared `CharacterRosterNameFilter` rejects bees / The Moon / The Mule on extraction **and** prefetch. `DELETE /api/characters/book/{bookId}` now clears `character_prefetch_completed` (ADMIN-gated). Pregen image defaults: covers + illustrations → Grok Imagine (`xai` / `grok-imagine-image`); portraits already xAI; Comfy opt-in. No pregen / Imagine / catalog run. `#132` stays separate. See `BL-065` session log.
- 2026-08-24: `BL-065.2` follow-up after Kevin’s local trial (Frankenstein 84 / Dorian 174): every name landed SECONDARY (16 / 21), zero PRIMARY, Creature/Monster missing, then chat/call fallback made every secondary callable. Product rules now: chat/call PRIMARY only (empty PRIMARY = nobody to call); Grok-knowledge prefetch is the PRIMARY list and must persist `PRIMARY`; chapter miner text-scans trusted names only and does not invent SECONDARY; article epithets (The Monster / The Creature / The Turk) pass the roster gate while bees / The Moon / The Mule / maid / `(again)` still fail. No Flyway. No pregen/Imagine/catalog spend. `#132` stays separate. Do not mark `BL-065.3` done.
- 2026-08-25: Added `BL-072` (durable curated-catalog membership). Kevin / ccr-studio design: adding a title to the curated landing list must be a table + API with `active`/`inactive` status, not a Java list edit. Unlisting must not wipe covers, portraits, illustrations, or roster. Studio ST-008 onboarding is blocked on this epic. Docs only.
- 2026-08-25: Added `BL-073` (operator API to grant/revoke `CREATE_CLASSROOM`). Kevin: provision teacher is a studio use case; today `manage_teacher_access.sh` runs SQL. Account must already exist. Studio ST-009 depends on this. Docs only.
- 2026-08-25: Added `BL-074` (per-character portrait request/regen) and `BL-075` (portrait + illustration multipart upload) from ccr-studio `claude_01` review. Kevin: HTTP-only write-back; ask CCR for both seams. Docs only.
- 2026-08-25: `BL-075` spec from ccr-studio `claude_02` (N1): uploads accept `source` + prompt metadata; back-port the same fields to cover `PUT`; PNG only. Docs only.
- 2026-07-09: Backlog updated after an educator partner (college professor) feedback call. `BL-025` (Classroom Admin and Assignment Workflows) expanded with concrete requirements: student roster, instructor-as-admin, shareable classroom-ID join link, per-student usage logging, teacher/student chat history export, Teacher vs. School account tiers, semester-scoped rosters, and a teacher dashboard with student drill-down, independent per-feature class toggles (for example recap off + quiz on), and per-question teacher quiz overrides for a book/chapter. New epics added: `BL-042` (token usage tracking + classroom cost calculator), `BL-043`/`BL-044` (FERPA and ADA compliance, pilot-blocking), and `BL-045` (user guide + classroom onboarding documentation, driven by the partner's college funding a pilot for a couple of classes).
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
- 2026-08-13: Added `BL-054` (college-appropriate character-chat conduct). Today’s persona prompt covers spoiler/in-character voice only; no NSFW or other appropriateness rules. Needed before classroom required-chat / in-class share (`BL-025.11`) so characters stay fun and may be mildly flirty when in-character, but do not play along with explicit or otherwise inappropriate attempts. Shared text + voice path.
- 2026-08-13: Shipped `BL-054` prompt v1: shared CONDUCT block on `CharacterPersonaPromptBuilder` (text + voice), in-character refusal style, golden-set tests. Optional output fallback (`BL-054.5`) remains if prompt-only still leaks in QA.
- 2026-08-12: `BL-025.10` pilot drill-down demo pack for Jessica 1:1 (America/Chicago): teacher roster → student overview with six partner sections; durable assignment opened + thin reader heartbeat; backlog/demo script updated. Quiz soak/bugfixes remain Kevin’s lane unless a showstopper surfaces.
- 2026-08-14: Refined `BL-042` in place (no new theme) with this-term child **`BL-042.5`**: per-student character-chat + voice cost events, teacher/ops rollup vs the **$500** AI envelope, and a noisy first-occurrence alert when SuperGrok OAuth falls back to the xAI API key. Purpose: measure real per-student AI $ this term so the next **$750/section/semester** quote is evidence-based. Docs only.
- 2026-08-15: Added `BL-055` (teacher-defined trophies; Kevin + Boss first pass 2026-08-14). Class/term named awards with one cached AI badge, server-side triggers + backfill + manual award; student sees own trophies, teacher sees them on `BL-025.10` overview; no class leaderboard (FERPA). Kevin add: assignment-wizard optional trophy for on-time 100% completion (`BL-055.6`). Docs only.
- 2026-08-16: Added `BL-056` after Kevin could not discover Fortunato on *Cask of Amontillado* (incognito TTS + `demo_teacher` `j`-key through the whole story). Local row exists as `SECONDARY` at ch 0 / p 0. Correction the same day: **SECONDARY must still discover and show in the roster**; PRIMARY is a separate preference (Kevin still wants Fortunato PRIMARY as the only character). Docs only.
- 2026-08-17: Added `BL-057` (dedicated assignment page + reduced landing card). Student landing cards currently dump the full assignment dashboard and overflow on mobile; CSS wrap (PR #121) is the demo-night bandage. Product direction: compact card (title, 1–2 chips, Open) + dedicated page for progress / quiz / character-chat / late / actions. Out of epic: thin landing-card hotfix already in flight. Cross-links `BL-025.4` / `BL-025.11`. Docs only.
- 2026-08-17: Added `BL-058` (assignment Open / persist leftovers). The #121–#125 soak loop shipped the skinny landing card + Open/resume/persist-suppression; Uncle Bob left a Low / not-blocking chapter-hop persist-before-load leftover on #125 (`0a63b90` / merge `cb04f32`). Docs only.
- 2026-08-17: Added `BL-059` (speed up `GET /api/library`). Kevin E2E / DevTools on prod: ~21.4s total, ~21.2s TTFB — server-side catalog work, not the network. First pass: cache + slim landing DTO + optional public cache headers + timing. Cross-links landing soak / `BL-047` library progress. Docs only.
- 2026-08-17: Added `BL-060` (arrow keys as j/k paragraph navigation). Kevin soak: desktop paragraph nav is `j`/`k` only (`BL-023` checklist); wants **Down Arrow = j** and **Up Arrow = k** when the reader is the focused surface. Keep j/k; do not steal arrows from search, text fields, chapter list, or overlays. Update `?` overlay + BL-023 checklist when implemented. Docs only.
- 2026-08-18: Added `BL-061` (My Chats Open Book should skip landing). Kevin: from My Chats, Open Book (e.g. Daisy Buchanan → *The Great Gatsby*) routes through the landing page (10–20s load) and only then opens the book. Chat already knows the book; it should open the reader directly. Slow landing load is `BL-059`; this item is the unnecessary hop, which also makes `BL-059` worse. Docs only.
- 2026-08-18: Added `BL-062` (character browser detail Esc should return to the list). A11y nit from tonight’s soak after #131 keyboard work. On a character (e.g. Benvolio), Esc closes the whole Characters modal instead of behaving like **← All Characters**. Mouse-only / pressing `m` again are the current workarounds. Docs only.
- 2026-08-18: Expanded `BL-062` in place (no new BL): list-view `j`/`k` should move the Characters roster the same way they move the chapter list (`c` modal). Esc-on-detail → **← All Characters** stays. Docs only.
- 2026-08-18: Added `BL-063` (cite Gutenberg-sourced titles on library book cards). Kevin: show a small `[Gutenberg 1513]` corner link back to Project Gutenberg for Gutenberg imports only — license/attribution + teacher trust for the Jessica demo. Library book cards only; not skinny assignment cards; not reader chrome. In Progress in this PR.
- 2026-08-18: Refined `BL-063` placement (docs only, no new slice): **library page book card only**. Small corner `[Gutenberg 1513]` (ebook number) linking to the Project Gutenberg page. Gutenberg-sourced titles only. Not on the skinny assignment card; not in the reader chrome unless we add that later. Replaces any reader / page-count / cache-only placement.
- 2026-08-18: Added `BL-064` (character blurbs spoil later plot). Kevin, Columbia State demo prep: live *Frankenstein* (Gutenberg 84) character cards show later-plot facts (Henry Clerval “who is murdered by the monster”; Robert Walton “discovers Victor Frankenstein's body”) before the reader reaches those events. Same stored blurb is injected into the chat/voice persona, so chapter gating is not enough. Same class of bug on other books. Related but separate from #132 (name dedupe / junk secondaries) — do not fold. Docs only.
- 2026-08-19: Added `BL-065` (regen character discovery with xAI; replace local Ollama pregen). Kevin 2026-08-18 after Jessica dry run: local Ollama casts are messy — junk secondaries (Frankenstein: 5 primaries + ~60 secondaries including “The Moon”, “The Mule”, “Elizabeth Lavenza (again)”, LLM leftovers), name glitches/duplicates (Northanger triple Sally is the #132 example), spoiler-y blurbs. Redo generation with xAI; per-book trial first (recommend Frankenstein, Gutenberg 84), then curated catalog. Do **not** start before the Thu Aug 20 noon CT Columbia State demo. Distinct from #132 (name-identity dedupe safety net) and `BL-064` (spoiler-safe blurbs — better model helps; it does not replace the spoiler rule). Do not reuse `BL-064`. Docs only.
- 2026-08-24: `BL-065.2` trial prompt work (code only; not stacked on #132). Named-people-only prefetch/extraction prompts + first-appearance blurbs; shared name filter (reject bees / The Moon / The Mule; keep Dorian / Fortunato / Elizabeth Bennet); DELETE latch clears `character_prefetch_completed`; Imagine defaults for covers + illustrations. Do not mark `BL-065.3` done. No pregen/Imagine run.
- 2026-08-24: `BL-065.2` follow-up after local trial (84 / 174): 0 PRIMARY, all SECONDARY, Creature/Monster missing, chat fallback called everyone. PRIMARY-only chat/call; prefetch types knowledge names PRIMARY; miner no longer inserts untrusted SECONDARY; allow The Monster / The Creature / The Turk. Do not mark `BL-065.3` done.
- 2026-08-20: Added `BL-066` (open general registration after Columbia State approval). Kevin 2026-08-19 scripted demo test: Register for `demo_user@example.com` fails with **“Account access is currently limited to internal rollout users.”** Expected today (`account.auth.rollout.mode=internal` + allow-list from `BL-021.6`). Go-live switch after **semester start and** written/approval-to-use at Columbia State — not a Thursday demo change. Do not reopen `BL-021`. Distinct from #132. Docs only.
- 2026-08-20: Added `BL-067` (recap chat misses characters who are in the chapter). Kevin 2026-08-19 scripted demo test; non-critical, will not demo this Thursday. After **Chapter 1** of **The Picture of Dorian Gray**, Chat tab (“Discuss This Chapter”) said Dorian and Basil were not in the chapter (both are). Same overlay: **“Recap chat is unavailable for this book.”** while a thread and compose box stayed active. Quality/regression on Done `BL-017` — do not reopen. Investigate excerpt / Ch-1 mapping / spoiler guard / unavailable stub; keep `BL-017.4`/`.5`. Not Frankenstein / not Thursday demo. Distinct from `BL-064`, `BL-065`, and #132. Do not reuse `BL-066`. Docs only.
- 2026-08-20: Added `BL-068` (new account inherits Completed books it never opened). Kevin 2026-08-19 scripted demo test; backlog, not a Thursday must-fix. He created **`demo_user@example.com`** and cleared **localStorage**; Library **Completed** still showed *The Cask of Amontillado* (Gutenberg **1063**) and *Northanger Abbey* (Gutenberg **121**) — books he has not opened on this account. Do **not** reopen Done `BL-018` (landing shelves / Completed row) or `BL-021.5` (claim-sync). That claim-sync merge is a **likely cause**, not a reason to skip this bug. Clearing localStorage is not enough if an anonymous **`pdr` reader cookie** still claim-syncs prior machine history. Investigate claim-sync on register / leftover cookie / unscoped completed flags; do not prescribe. Out of scope: Thursday demo code/deploy, `BL-066` opening public registration, #132. Do not reuse `BL-067`. Docs only.
- 2026-08-20: Added `BL-069` (book covers do not load on iPhone). Kevin 2026-08-19 scripted demo test; backlog, not a Thursday must-fix unless someone demos on a phone. On iPhone (at least **Simulator** — iPhone 17 Pro / iOS at classicchatreader.com), library **book cover images do not load** — dark gray/black rounded placeholders. Same screenshot: **Continue Reading** *Pride and Prejudice* cover blank (text/chips/Gutenberg #1342 fine); **For You** *Alice*, *Dorian Gray*, *Moby Dick* covers blank; Victor Frankenstein **character portrait** on Continue chat loads; desktop covers work (Cask / Northanger Completed earlier tonight). Investigate Safari/iOS format (webp/avif), CORS, srcset, lazy-load intersection, or a different cover vs portrait URL path; do not prescribe. Out of scope: Thursday desktop Frankenstein demo, `BL-068` Completed leak, `BL-067` recap chat, character regen (`BL-065`), #132. Do not reuse `BL-068`. Docs only.
- 2026-08-20: Added `BL-070` (teacher workspace takes ~10s to open). Kevin 2026-08-20 morning (Columbia State demo day; backlog, not a same-morning fix): opening **Teacher workspace** (`/teacher`) takes several seconds, almost **10 seconds**. Feels really slow. Distinct from `BL-059` (student landing / library catalog TTFB) and `BL-061` (My Chats Open Book hop). This is teacher first paint / first useful UI. Investigate payload size, serial API waterfall, `teacher.js` bundle, classroom context, assignment list, dashboard/overview prefetch; do not prescribe. Out of scope: same-day code/deploy before the noon Columbia State demo, opening public registration (`BL-066`), character regen (`BL-065`), #132. Do not reuse `BL-069`. Docs only.
- 2026-08-18: Recorded named Columbia State demo — **Thu 2026-08-20 noon** (America/Chicago), with Jessica dry run **Tue evening**. Dry run **went pretty smoothly** (Kevin). Thursday deck: `ClassicChatReader-Pilot.pptx`.
- 2026-08-20: Columbia State group demo **done, smooth**. Attendees + asks captured in handoff partner block. **Aaron Beshears:** grant/institutional **pilot this semester unlikely**. New Discovery epic **`BL-071`** (OER / non-PD ingest — research only). Student-fee question folded into `BL-042`. Accessibility/ELL praise folded into `BL-044`. `BL-064` remains character-blurb spoilers — do not reuse. Docs only.

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
- 2026-08-17: Assignment-rail **card density** is no longer a BL-018.6 follow-on. Landing stays assignment-first; the reduced card + dedicated assignment page is `BL-057`. Soak overflow hotfix (PR #121) is a demo-night CSS bandage, not this IA.
- 2026-08-20: New-account **Completed** leak (books the signed-in user never opened on that account) is `BL-068`. Do **not** reopen this Done epic. Landing shelves / Completed row already shipped.
- 2026-08-20: iPhone library **book covers** failing to load (dark placeholders; character portraits still work) is `BL-069`. Do **not** reopen this Done epic. Cover-forward shelves already shipped.

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
- Current Direction (2026-08-15):
- Teacher-defined classroom trophies (named award + cached badge; class/term or assignment-scoped; server-awarded) are `BL-055`, not this consumer catalog.
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
- 2026-08-11: FERPA privacy review notes ADR §6 account hard-delete (24h) and backup retention are **policy-documented but not runtime-complete** (no deleteAccount API; `retention_purge_after` unused). Runtime fulfillment tracked under `BL-043.6` (do not reopen this epic). OAuth silent email auto-link risk tracked under `BL-043.2` / `SECURITY_AUDIT` H-04 (**remediated 2026-08-23**; do not reopen this epic).
- 2026-08-20: General / Columbia State registration go-live is `BL-066` (flip or widen `account.auth.rollout.mode` / allow-list after semester start + written approval). Do **not** reopen this Done epic.
- 2026-08-20: New-account **Completed** leak after register (likely leftover anonymous `pdr` reader cookie / claim-sync into the new user) is `BL-068`. That merge is a **likely cause**, not a reason to skip the bug. Do **not** reopen this Done epic or `BL-021.5`.

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
| BL-025.2 Teacher Onboarding + Roster Management | In Progress | Build teacher class setup flow, shareable classroom-ID join link for student self-registration, and roster management (add/remove/import). Include **optional student display name**: student may set at register/profile; teacher may set a **roster override** so the class list is identifiable when email alone is insufficient. Email remains the required account identifier. **Invite lifecycle (FERPA `BL-043.4`):** default TTL + max uses on create, revoke API/UI, rotate on create (**Done this PR**: 30-day TTL, 40 max uses; `ClassroomAdminService` no longer passes null,null). Roster PII minimization / TA role / `VIEW_ROSTER` logging tracked under `BL-043.5` / `BL-043.8`. | Teacher can create a class, share a join link, and manage an active roster without manual DB operations; roster shows email plus optional name/override; teacher can edit override without changing login email; invites expire / revoke / rotate per `BL-043.4` |
| BL-025.3 Class Feature Controls | In Progress | Add independent class-level toggles (quiz/recap/AI/media capabilities, each settable on its own — for example recap off + quiz on) with policy enforcement in UI + API | Teacher can set recap off and quiz on (or any other independent combination) and student feature availability matches per class |
| BL-025.4 Assignment Workflow v1 | In Progress | Support assigning books/chapters, due windows, and required completion/quiz states (quiz-oriented v1; not character-chat requirements). Pass threshold + retry policy is `BL-025.12` (extends this slice). Durable **assignment opened** / first-interaction timestamps feed `BL-025.10` (`assignment_progress.first_opened_at` + student open API shipped 2026-08-12). Student **landing-card density** / dedicated assignment page is `BL-057` — do not keep adding dashboard chips to the Library card. | Teacher can publish assignments and students see clear due/required states in app |
| BL-025.5 Teacher-Authored Quiz Authoring (MC Wizard) | Done | Class-scoped **multiple-choice** quiz authoring for a book/chapter via a **stepped wizard**: stub N default slots; teacher may manually enter stems/options/correct answer, use AI to suggest questions from chapter content (full override), and/or use AI to generate wrong answers. Layered on generated quiz per design overlay model (not format expansion beyond MC). | Teacher can define or override the effective MC question set for a book/chapter for their class; students in that class receive that set; AI assists never publish without teacher confirmation |
| BL-025.6 Student Usage Logging | In Progress | Persist per-student activity events (books/chapters read, progress %, **session / time-in-book**) scoped to class/term. Powers teacher drill-down time-spent and later cost attribution. **2026-08-12 thin slice:** student `READING_HEARTBEAT` writer (clamped duration, idempotency) feeding approximate “time in reader” on `BL-025.10` — not the full event platform. | Usage events are queryable per student and roll up cleanly per class/term; time-in-book can be shown on `BL-025.10` with defined measurement rules |
| BL-025.7 Chat History Export | Proposed | Add download/export of AI chat history, self-service for students and bulk/per-student for teachers. **Gap (2026-08-11 FERPA review):** V14 `chat_export_jobs` is schema-only; client `character-chat-export.js` is local-only (BL-025.11 Slice A). Server term-scoped export + access logs + artifact expiry tracked as `BL-043.7`. Teacher bulk remains gated by `BL-043` Discovery exit (`BL-043.13`). | Eligible student can server-export their own term-scoped chat history with access logs; teacher can export enrolled students’ history in their class only after FERPA gates; artifacts expire; local-only download is not the sole path |
| BL-025.8 School and Teacher Account Tiers | Proposed | Add account ownership model distinguishing School (multi-teacher) and Teacher (single-class-owner) tiers | A school-tier account can view/manage classes across its teachers; a teacher-only account is scoped to its own classes |
| BL-025.9 Semester/Term-Scoped Rosters | Proposed | Add term boundaries to classes so rosters can change across semesters while preserving historical term data | Teacher can start a new term for a class with a fresh roster without losing prior-term student history/reporting |
| BL-025.10 Teacher Dashboard + Student Drill-Down | In Progress | Teacher dashboard with roster entry into a **Teacher→Student overview**. v1 student overview must list: (1) **current assignments**, (2) **completed assignments**, (3) **time spent in the book** (labeled engagement proxy; partner-requested), (4) **progress by book** (chapter n/n and/or % complete), (5) **quizzes for the book** — N complete, **scores**, **retry attempts**, (6) **assignment progress including opened/not-opened** (whether the student has clicked into the assignment at all). Class aggregate widgets optional if drill-down is complete. **2026-08-12:** pilot drill-down MVP shipped (`GET .../students/{userId}/overview` + `/teacher` roster panel); broad FERPA-gated rollout still blocked. Demo: `docs/product/bl-025-10-demo-script.md`. | Teacher can open any rostered student and see the six overview sections without external tooling; opened vs not-opened is accurate server-side; time-in-book and quiz stats match underlying events/attempts |
| BL-025.11 Character-Chat Assignments + In-Class Share | Proposed | Partner use case (Jessica, 2026-07-16 call confirmed): assignment may **optionally require character chat** for classroom **show-and-tell**; students need a **downloadable conversation artifact** (text/Markdown). Includes requirement/completion modeling and export; student self-serve download first, teacher access behind FERPA. Local-first export can unblock pilot before full server persistence. **Conduct:** chats that may be shared in class must stay college-appropriate — see `BL-054` (do not play along with NSFW; mild character-aligned flirtation OK). Required-chat **chip / Chat CTA on the student landing card** belong on the dedicated assignment page (`BL-057`); this slice remains requirement/completion/export. | Teacher can optionally require character chat; student can download their conversation for class without screenshots; completion/export model agreed — discovery still open on specific character vs any, N-turn completion, and teacher bulk visibility |
| BL-025.12 Assignment Quiz Pass Rules (Min Score + Retries) | Done | On quiz-required assignments, teacher may set **minimum passing score** (e.g. 7/10). When a minimum is set, teacher must set **maximum retry attempts**; **`0` = no retries** (initial attempt only). Student quiz completion for the assignment must honor pass threshold + remaining attempts (not “any attempt exists”). | Teacher can configure min score + max retries on an assignment; student UI/API show attempts remaining and pass/fail against the threshold; exhausting attempts without passing leaves quiz requirement unmet |
| BL-025.13 Teacher Quiz Defaults | Done | Teacher-configurable defaults used when creating quizzes/assignments: **number of questions**, **minimum passing score**, **maximum retry attempts** (same unit as `BL-025.12`; **`0` = no retries** / initial attempt only — do **not** store this as total-attempt count), and **default multiple-choice option count**. Defaults seed the authoring wizard (`BL-025.5`) and pass-rule fields (`BL-025.12`); per-assignment/per-quiz overrides still allowed. | Teacher can save defaults once and see them pre-filled on new quiz authoring and quiz-required assignments using consistent retry units; changing defaults does not rewrite already-published quizzes/assignments unless teacher explicitly re-applies |
- Dependency Notes:
- BL-021 (`User Registration and Account System`) is a prerequisite for BL-025.2 onward.
- BL-025.3 and BL-025.4 should extend BL-018.6 classroom context hooks with full class policy + assignment signal integration. Student assignment **detail UX** (progress, quiz, character-chat required, late/on-time, primary actions) lives on a dedicated page with a reduced landing card — `BL-057`. Do not treat the Library assignment card as the assignment dashboard. Soak overflow hotfix PR #121 is the demo-night CSS bandage only.
- BL-025.6/.7/.10 (usage logging, chat export, dashboard drill-down) should be sequenced after BL-043 (FERPA) Discovery exit criteria are met (`BL-043.13`) and relevant P0 remediations land — see 2026-08-11 FERPA work tracker. Schema hooks alone do not unlock these slices. **Exception (2026-08-12):** a **pilot-only** teacher→student drill-down (`BL-025.10` MVP) + thin heartbeat may ship behind strict teacher-of-term authz for Jessica demos; broad/school-tier dashboard and teacher bulk export remain gated.
- BL-042 (token usage/cost calculator) depends on BL-025.6's usage logging for per-student/per-class token attribution.
- BL-025.11 depends on BL-025.4 foundations and, for durable completion/export, on server-persisted character chat (`BL-049`, not localStorage-only) plus BL-043/BL-025.7 policy if teachers access student–character conversations. **Not required for BL-025.1 data model freeze or pilot assignment v1.** Local download + soft require shipped 2026-07. College-appropriate chat conduct (NSFW/jailbreak refusal, in-character deflection) is `BL-054` prompt v1 (shipped); optional output fallback remains if QA still sees leaks.
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
- 2026-08-13: Classroom character-chat conduct (college-appropriate; refuse NSFW without flattening personality) tracked as `BL-054`, not a BL-025.11 completion-model slice. Cross-link only.
- 2026-08-17: Student landing assignment cards dump the whole dashboard (scope, late, quiz, character-chat required, progress, Chat CTA) and overflow on mobile. Product direction is `BL-057` (reduced card + dedicated assignment page). CSS wrap PR #121 is the soak/demo-night bandage; the thin landing-card hotfix stays out of `BL-057`.
- 2026-08-20: Teacher workspace (`/teacher`) first paint / first useful UI taking ~10s is `BL-070`. Do **not** reopen this In Progress epic as a workspace rewrite. Classroom UI already shipped; this is latency on that surface.

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
- Character chat turns and character voice sessions write per-student rows on existing columns: `user_id`, `class_section_id`, `term_id`, `feature` (`CHAT` for character chat; `VOICE` for Call Character / realtime — existing V14 vocabulary, not `chat|voice`; `VOICE` is not book `TTS`), `model_name`, `input_tokens` / `output_tokens` (chat), `duration_ms` (voice; not minutes; the `[0, 120_000]` clamp is **`READING_HEARTBEAT` only** — do not truncate Call Character), `estimated_cost_micros` (current xAI list rates stored as micros — not a USD column), `occurred_at`.
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
- 2026-08-14: Review nits — mapped `.5` onto V14 `classroom_usage_events` (`estimated_cost_micros`, `duration_ms`, `feature`/`CHAT`/`VOICE`, `model_name`, `AI_TOKEN_USAGE`, `metadata_json` for `billed_via` + cached tokens); slimmed the work-tracker cell to a pointer; full cut lives under Acceptance. Named `VOICE` for Call Character (not in the prior §11 list; no Java enum — `feature` is VARCHAR). `[0, 120_000]` `duration_ms` clamp is `READING_HEARTBEAT` only — do not truncate Call Character.
- 2026-08-20: Columbia State group demo. Thomasanna Hail asked whether the **$750/section** can **slide into student fees** vs faculty passing the hat; she does not know the college business office path. **No product decision** — do not invent bursar/SIS fee integration. Aaron Beshears (grant decision maker) said a **pilot this semester is unlikely**, so metering (`BL-042.5`) is no longer gated on an Aug 17–21 commercial close; still needed before any paid quote. Do not change the **$750** number on this card.

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
| BL-043.1 Prod auth gate for student-data paths | Done (this PR; live re-probe after deploy) | P0 | Live `/api/auth/status` showed `publicMode:false` / `canAccessSensitive:true`; `deployment.mode=local` default and `application-prod.properties` did not force `public`. Affects student chat/quiz pipelines. Overlaps `SECURITY_AUDIT` **C-01** / **H-07**. | Prod forces `deployment.mode=public` + Secure cookies; fail closed without auth material; re-verify status endpoint shows gated sensitive access |
| BL-043.2 Google OAuth email auto-link consent | Done (this PR; password re-auth before link) | P0 | `AccountAuthService.resolveUserForExternalIdentity` linked verified Google email to existing password accounts with no re-auth/consent → account takeover of education records. Overlaps `SECURITY_AUDIT` **H-04**. | Linking requires password re-auth or explicit link step; sessions invalidated on link; regression tests cover no silent takeover |
| BL-043.3 LLM subprocessors / DPA / disclosure | Proposed | P0 | CharacterChatService, ReadingBuddyChatService, OpenAiLlmProvider, XaiLlmProvider, voice/realtime send student content to OpenAI/xAI without in-repo privacy/DPA/subprocessor tracking. | DPAs (no-train/zero-retention where required) tracked; subprocessors disclosed in product docs surface; prompts minimized; voice/realtime held until covered |
| BL-043.4 Classroom invite TTL / max uses / revoke | Done (this PR; 30-day TTL / 40 max uses; revoke API/UI; rotate on create) | P0 | `ClassroomAdminService.createInvite` passed null,null though `InviteLinkService` supports expiry/max/revoke. Overlaps `SECURITY_AUDIT` **M-03** (FERPA elevates). Also `BL-025.2`. | Default expiry + max uses on create; revoke API + UI; rotate on create; leaked codes stop working after TTL/revoke |
| BL-043.5 Education-record access logging runtime | Proposed | P0 | V14 `education_record_access_logs` (and related export/usage tables) are schema-only — no Java writers; `listRoster` does not log `VIEW_ROSTER`. | Access logging on education-record reads/exports (incl. roster view); tests prove rows written; audit retention hook honored |
| BL-043.6 Account delete + retention purge runtime | Proposed | P0 | `BL-021` ADR promises 24h hard-delete; no `deleteAccount` API; `retention_purge_after` unused. | Account delete API + term purge job; honor retention matrix in `bl-025-classroom-data-model.md`; enrollment-hold or export-before-delete decision implemented |
| BL-043.7 Term-scoped server chat-export API | Proposed | P0 | Export jobs schema only; client `character-chat-export.js` is local-only. Student eligible-student export + teacher path per `BL-025.7`; always write access logs; expire artifacts. | Term-scoped server export with authz + access logs; artifacts expire; local-only download is not the sole compliance path |
| BL-043.8 Roster PII minimization + TA role | Proposed | P1 | Roster returns student emails; TA treated as teacher-like; no access log (`ClassroomAdminService.listRoster`). | Roster fields minimized/classified; TA permissions explicit and narrower than teacher where required; `VIEW_ROSTER` logged (`BL-043.5`) |
| BL-043.9 LLM error-log hygiene | Proposed | P1 | OpenAiLlmProvider / XaiLlmProvider `log.error` may capture provider bodies / prompt fragments. Overlaps `SECURITY_AUDIT` **L-05**. | Error logs omit prompt/PII/provider bodies (or redact); tests/docs state allowed log fields |
| BL-043.10 Server-authoritative character chat history | Proposed | P1 | Character chat trusts client `conversationHistory` before third-party LLM. Overlaps `SECURITY_AUDIT` **M-04**; prefer `BL-049` server history pattern (Reading Buddy already ignores client history). | Server loads/clamps history for LLM calls; client-supplied history not authoritative in classroom mode |
| BL-043.11 Privacy / FERPA notice + subprocessor list | Proposed | P1 | No privacy policy / FERPA notice / subprocessor list in product docs. | Tracked notice + subprocessor list published/linked for pilot (legal copy from counsel/partner; engineering owns surface + accuracy checklist) |
| BL-043.12 Session Secure cookies + TTL review | Done (Secure pin this PR; TTL keep 43200 / rotation stays L-02) | P1 | Account session cookies default `Secure=false`; `session.ttl-minutes=43200`. Overlaps `SECURITY_AUDIT` **H-07** / **L-02**. | Prod Secure cookies enforced; TTL/rotation decision documented and applied for classroom accounts |
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
- 2026-08-23: First engineering slice. `prod`/`mariadb` pin `deployment.mode=public` + `security.public.session.secure-cookie=true`. `PublicDeploymentSafetyValidator` refuses to boot those profiles in local mode, with Secure overridden off, or without `PUBLIC_API_KEY` / `PUBLIC_COLLABORATOR_PASSWORD`. Local/dev stays ungated. **TTL decision:** keep account `43200` minutes (30 days) for classroom convenience; session rotation / “sign out everywhere” stays `SECURITY_AUDIT` **L-02**. Do **not** treat C-01 as production-closed until post-deploy `/api/auth/status` shows `publicMode:true` and anonymous `canAccessSensitive:false`. H-01 import/character-admin allowlist is still open.
- 2026-08-23: `BL-043.2` Google OAuth email auto-link consent. Verified Google email no longer silently attaches to an existing password account. Google callback stores a short-lived pending-link cookie and returns `google_link_required`; `POST /api/account/google/link` requires that account’s password, then deletes all sessions for the user before issuing a new one. Regression covers no silent takeover. Overlaps `SECURITY_AUDIT` **H-04**. `.3`–`.7` remain open.
- 2026-08-23: `BL-043.4` classroom invite TTL / max uses / revoke. Create now applies default **30-day** expiry and **40** max uses (`classroom.invite.default-ttl-days` / `default-max-uses`); creating a new code revokes prior active links (`replaced_by_link_id`). Teacher `POST /api/classroom/invites/{id}/revoke` plus `/teacher` Revoke control; redeem already returns `EXPIRED` / `REVOKED` / `MAX_USES`. No new Flyway version (V14 columns already existed; V31/V32 left alone). Overlaps `SECURITY_AUDIT` **M-03**. `.3` DPA and `.5`–`.7` remain open.
- 2026-08-23: `BL-043.4` follow-up — fail-closed redeem. Live rows with null `expires_at` are `EXPIRED`; null `max_uses` is `MAX_USES`. Historical unbounded join codes can no longer enroll. No Flyway (V33+ unused).

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
- **2026-08-20 Columbia State:** Jonathan Owen (AI council) called out **reading accommodations** and **ELL**; Amy McDonald (leadership) was strongest on **accessibility** and MLA citation. Does the next partner conversation need an explicit a11y one-pager (font size, TTS, no-scroll paging, keyboard) vs a VPAT, or is live demo enough until procurement?
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
- Out of scope until requested: non-public-domain stories; custom teacher upload of copyrighted PDFs; reopening deferred titles without a non-PG / better source. **2026-08-20:** non-PD / OER **was requested** at the Columbia State demo — captured as **`BL-071`** (Discovery). Do not reopen this short-story epic for OpenStax / STEM textbooks.
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

### BL-054 - College-Appropriate Character Chat Conduct
- Type: Improvement
- Priority: **P1-timebound** for classroom character-chat / show-and-tell (`BL-025.11`) and fall reading; P2 as ongoing prompt quality
- Effort: M
- Status: **Done** (prompt v1); optional output fallback (`BL-054.5`) remains Proposed
- Problem: Character chat is fun, in-character, and assignment-ready, but the shared persona prompt (`CharacterPersonaPromptBuilder`) only covers spoiler bounds and voice. There is no conduct policy, so a model can play along with NSFW roleplay, jailbreaks, or other inappropriate turns — a real risk for college classroom use, especially when chats may be downloaded and shared in class.
- Audience: **College-age students** (not a K-12 children’s filter). Classics already contain romance, wit, cruelty, and adult themes; the goal is a classroom-safe *conversation*, not a sanitized book.
- Product tension (do not collapse this):
  - **Keep:** fun, engaging, personality-forward replies; **mild flirtation / courtship / charm when that is how the character would behave** (banter, compliments, period-appropriate gallantry). A rakish or witty character should still sound like themselves.
  - **Refuse:** explicit sexual content, erotic roleplay, graphic NSFW, **vulgar swearing / cussing like a sailor**, **taking God's name in vain (including GD)**, and other inappropriate requests (hate, harassment of the reader, self-harm or illegal-activity coaching, sexual content involving underage literary characters, jailbreaks that try to drop the persona or safety rules). Mild period exclamations are fine.
  - **Literary discussion vs enactment:** students may *talk about* adult plot already in the book at a college reading level (affairs, seduction as plot, violence as literature). The character must not *act those scenes out with the student* or continue them as modern NSFW.
- Current Direction (2026-08-13):
  - Encode a **CONDUCT** block in the shared persona builder so **text chat and voice calls** get the same rules (`CharacterChatService` + `CharacterVoiceCallService`).
  - Refusals stay **in character**: shocked, amused, chilly, or scandalized as that person would be; redirect to the story or a proper topic. No “as an AI / classroom policy” lecture, and no breaking character to moralize.
  - Do **not** flatten romance: the prompt must explicitly allow mild character-aligned flirtation so the model does not treat every compliment as disallowed.
  - Provider filters (xAI/Grok) are a backstop, not the product policy. We own in-character refusal even when the provider would have allowed the turn.
  - v1 is prompt + tests. Optional output fallback (replace a still-explicit reply with a canned in-character deflection) is a follow-on slice, not a blocker.
  - Recap chat and Reading Buddy are **out of v1 scope** unless sharing a tiny shared snippet is cheaper than duplicating; those surfaces are not in-story lovers.
- Scope Buckets:
  - Written college-appropriate conduct policy (allowed / disallowed / grey) short enough to live in the prompt and in this epic.
  - Shared prompt rules on `CharacterPersonaPromptBuilder` (text + voice).
  - In-character refusal and redirect behavior.
  - Regression set: jailbreak/NSFW/hate/self-harm *classes* plus **allowed** mild-flirt and literary-discussion cases so we do not over-censor.
  - Optional later: output-side fallback if the model still complies; non-PII abuse metrics.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-054.1 Conduct policy | Done | Short allowed/disallowed/grey list for college classroom character chat; include underage-character rule and literary-discussion vs roleplay | Policy paragraph exists in this epic (or a linked one-pager) and is specific enough to prompt |
| BL-054.2 Shared persona CONDUCT rules | Done | Add CONDUCT block to `CharacterPersonaPromptBuilder.buildPersona` (inherited by voice instructions); cover NSFW, jailbreaks, hate/harassment, self-harm/illegal coaching, and explicit allow for mild in-character flirtation | Text and voice prompts include the same conduct rules; unit tests assert presence and the allow/deny wording |
| BL-054.3 In-character refusal | Done | Refusal style: stay in voice, do not play along, do not lecture, offer a story-appropriate redirect | Prompt (and tests) specify refusal style; a few fixture replies would be accepted as in-character deflections |
| BL-054.4 Golden-set tests | Done | Prompt-assembly + service tests for attack *classes* (do not store explicit sexual text in git — use labeled fixtures / mild stand-ins) and **positive** cases (period courtship, witty banter, discussing a canonical affair as plot) | CI fails if CONDUCT rules are dropped; positive cases assert we did not add a blanket “no romance / no flirtation” ban |
| BL-054.5 Output fallback (optional) | Proposed | If a generated reply still looks disallowed, replace with a short in-character deflection rather than showing the bad turn | Follow-on; ship only if prompt-only still leaks in manual QA |
- Discovery Questions:
  - Where is the line for a canonically seductive character (Wickham, a Restoration rake) between in-character charm and playing along with a student’s explicit prompt?
  - For characters who are minors in the source text, is the rule “no romantic/sexual register at all,” even for college readers discussing the book?
  - Should a refused turn still count toward assignment character-chat completion (`BL-025.11`)?
  - Any non-PII logging of refusal *classes* for QA (not transcript content) — FERPA-sensitive if tied to a student?
  - Does voice need extra “do not moan / do not describe bodies” rules because spoken delivery is more awkward in class?
- Exit Criteria for Discovery:
  - Approved allow/deny/grey list for college classroom (Kevin sign-off on the flirtation line is enough; no separate legal review required for v1 prompt text).
  - Decision: prompt-only v1 vs prompt + output fallback before fall reading.
- Acceptance Criteria:
  - Characters do **not** continue, embellish, or roleplay explicit sexual / NSFW user attempts; they deflect in character and steer back to the book or a proper topic.
  - Other inappropriate attempts (hate, reader harassment, self-harm or crime coaching, jailbreaks, sexual content with underage literary characters) are refused the same way.
  - Mild flirtation, compliments, and period courtship **remain available** when they fit the character; chats still feel fun and engaging.
  - Students can still discuss adult **plot already in the book** at a college level without the character acting it out with them.
  - Text chat and voice calls share the same conduct rules.
  - Automated tests cover both refusal classes and allowed-tone cases; fixtures stay classroom-safe to read in git.
- Dependency Notes:
  - Implementation lives in `CharacterPersonaPromptBuilder` (shared by `CharacterChatService` and `CharacterVoiceCallService`). Do not fork a second policy for voice.
  - Complements `BL-025.11` (required chat + in-class share) and `M-04` in `docs/SECURITY_AUDIT.md` (prompt injection / persona jailbreaks). `BL-054` is content-conduct; `M-04` is spoiler/injection hardening — related but not the same slice.
  - Not a substitute for FERPA (`BL-043`) or teacher bulk export (`BL-025.7`).
  - Do not weaken spoiler rules while adding conduct rules.
- Risks:
  - Over-refusal kills the product magic (Darcy becomes a HR bot). Mitigate with explicit allow-language and positive test cases.
  - Under-refusal on jailbreaks (“ignore previous instructions”, “continue the scene”). Mitigate with CONDUCT as a hard rule and jailbreak class in the golden set.
  - Character descriptions in the DB may themselves be spicy; conduct rules must still bind the *reply to the student*.
- Session Log:
- 2026-08-13: Captured from Kevin: college audience; keep chats fun and engaging; mild character-aligned flirtation OK; do not play along with NSFW or other inappropriate attempts; be careful not to over-censor.
- 2026-08-13: Prompt v1 shipped — CONDUCT block on `CharacterPersonaPromptBuilder` (text + voice), in-character refusal, golden-set tests. `BL-054.5` output fallback still optional.
- 2026-08-13: Softened CONDUCT wording after a local hello to Fortunato hit the provider-error fallback. Keep the same allow/deny rules, but avoid stacking graphic banned terms in the system prompt so ordinary greetings are not refused.

### BL-055 - Teacher-Defined Trophies
- Type: Feature
- Priority: P2 (classroom delight; not fall-start blocking)
- Effort: L
- Status: Proposed
- Problem: Teachers want to reward specific student accomplishments (quiz bar, finish a book, complete an assignment on time at 100%, etc.) with a **named trophy** and a **badge image**. Nothing like this exists — consumer quiz trophies (`BL-019` / `BL-018.4`) are a fixed product catalog, not teacher-authored classroom awards.
- Audience: College classroom (Jessica / ENGL 1020 style). Student sees **own** trophies; teacher sees them on the existing student overview (`BL-025.10`). **No class leaderboard in v1** (FERPA).
- Current Direction (2026-08-14, Kevin + Boss first pass):
- Teacher creates a trophy on a **class/term**: title, short description, **one AI-generated badge image cached on the trophy definition** (not per student).
- Teacher picks a **trigger** (and later a combo).
- Award is **server-side** from events we already trust (quiz attempts, assignment/book completion, thin reading heartbeat). Do **not** trust the client (same lesson as the reading-complete gate).
- **Backfill** students who already qualified when the teacher creates the trophy late.
- Teacher can also **manually award** for things the system cannot see.
- v1 triggers:
  - Min quiz score (optional: first try only)
  - Finish a book / finish the assigned chapters
  - Finish every assignment in the term
  - Thin “time in reader” threshold (`BL-025.6` heartbeat; label approximate)
  - N distinct days with a heartbeat
  - Combo: e.g. finish the book AND pass the quiz
  - Manual teacher award
  - **Assignment-scoped (first-class, not only the class-level catalog):** complete **this assignment on time** and at **100%** (perfect score) — see `BL-055.6`
- Out of v1:
  - Class leaderboards / first-in-class races
  - Chat-quality or “good discussion” trophies
  - Per-student unique badge generation (cost)
  - K-12 / parent view
- Current Direction (2026-08-15, Kevin add — assignment-wizard trophy):
- In the existing assignment **create/edit wizard**, optional **“award a trophy”** for completing **this assignment on time** and at **100%** (perfect score).
- AI may **suggest trophy title + badge** from the assignment theme (book / included chapters). Teacher **always** edits/overrides before save.
- This is a **first-class trigger** (assignment-scoped), not only a class-level trophy catalog.
- Still: **one generated badge per trophy definition**, **server-side award**, **backfill if published late**.
- Cost of suggest title/badge sits with `BL-042` (same family as quiz suggest-*).
- Hard parts to call out:
  - Server evaluation + replay/backfill
  - Badge gen once per definition; store + reuse; content-policy on the image prompt; cost under `BL-042`
  - FERPA: awards are education records; show on `BL-025.10` overview; no bulk public wall
  - A11y: badge needs alt text from title/description (`BL-044`)
- Scope Buckets:
- Trophy definition + award data model (class/term and assignment-scoped definitions; one cached badge per definition).
- Server-side award engine over trusted events (quiz attempts, assignment/book completion, heartbeat); no client-claimed unlocks.
- Teacher create/edit UI + one-shot badge generation with content-policy on the image prompt.
- Student own-trophy display + teacher view on the existing `BL-025.10` overview (no public wall).
- Backfill/replay when a definition is created or published late.
- Manual teacher award for accomplishments the system cannot see.
- Assignment-wizard option + AI title/badge suggest (`BL-055.6`).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-055.1 Data model + award engine | Proposed | Class/term (and assignment-scoped) trophy definitions + award rows; server evaluator over trusted events (quiz attempts, assignment/book completion, thin heartbeat). Do not trust the client. | Definitions persist; awards are written only by the server from events we already trust |
| BL-055.2 Teacher create/edit + badge gen | Proposed | Teacher creates/edits title, short description, trigger (and later combo); **one AI-generated badge cached on the definition** (not per student); content-policy on the image prompt; cost under `BL-042` | Teacher can save a trophy with a reusable badge; regenerating the definition does not mint per-student images |
| BL-055.3 Student + teacher overview display | Proposed | Student sees own trophies; teacher sees them on the existing `BL-025.10` student overview. Badge alt text from title/description (`BL-044`). No class leaderboard / public wall | Student and teacher-of-term can see awarded trophies; no bulk public display |
| BL-055.4 Backfill job | Proposed | When a trophy is created or published late, replay trusted events and award students who already qualified | Late-created trophies award already-qualified rostered students without a client round-trip |
| BL-055.5 Manual award | Proposed | Teacher can grant a defined trophy to a student for accomplishments the system cannot see | Teacher-of-term can award/revoke a manual grant; grant is an education record like automatic awards |
| BL-055.6 Assignment-wizard option + AI title/badge suggest | Proposed | **v1-adjacent.** Optional “award a trophy” on the existing assignment create/edit wizard for completing **this assignment on time** at **100%** (perfect score). AI may suggest title + badge from the assignment theme (book / included chapters); teacher always edits/overrides before save. Assignment-scoped first-class trigger, not only the class-level catalog. One badge per definition; server-side award; backfill if published late. Suggest cost sits with `BL-042` (same family as quiz suggest-*) | Teacher can attach an on-time/perfect-score trophy while creating or editing an assignment; suggested title/badge are optional and never publish without teacher confirmation |
- Discovery Questions:
- Is “100% / perfect score” quiz-only, or every scored checklist item on the assignment?
- What is “on time” when an assignment has no due date — ineligible for this trigger, or treat as always on time?
- Combo triggers: stored as a small expression, or a fixed list of AND-pairs in v1?
- Badge regeneration: replace the cached image in place, or version the definition so already-awarded rows keep the old art?
- Manual award: can the teacher also revoke an automatic award, or only add grants the engine cannot see?
- Should student landing Achievements (`BL-018.4`) show classroom trophies, or keep them on a class-scoped surface only?
- Acceptance Criteria:
- Teacher can define a named trophy on a class/term (and, via `.6`, on an assignment) with title, description, trigger, and one cached badge.
- Awards are written server-side from trusted events or an explicit teacher grant; the client cannot self-award.
- Creating or publishing a trophy late backfills already-qualified students.
- Student sees own trophies; teacher sees them on `BL-025.10`; there is no class leaderboard or public wall in v1.
- Badge images have alt text derived from title/description.
- Assignment-wizard suggest title/badge never publish without teacher confirmation; suggest usage is attributable under `BL-042`.
- Dependency Notes:
- Distinct from consumer quiz trophies (`BL-019` / `BL-018.4` / `BL-020.3`); do not overload that catalog for teacher-authored awards.
- Time-in-reader and distinct-day triggers depend on the thin `BL-025.6` heartbeat; label those awards approximate.
- Teacher display reuses `BL-025.10` overview (pilot drill-down); do not invent a bulk public trophy wall.
- Badge gen + assignment-wizard title/badge suggest are AI cost events under `BL-042` (same family as quiz suggest-*).
- FERPA: awards are education records (`BL-043`); college classroom only in v1 — no K-12 / parent view.
- A11y alt text on badges is `BL-044` / classroom surface hygiene, not a separate VPAT slice.
- Assignment-wizard option (`.6`) extends the existing assignment create/edit path (`BL-025.4`); do not fork a second assignment editor.
- Risks:
- Client-trusted unlocks would repeat the reading-complete-gate failure mode — keep evaluation on the server.
- Per-student badge generation would blow `BL-042` cost; cache one image per definition.
- A class leaderboard or “first to finish” race is a FERPA/public-wall problem; keep v1 private-to-student + teacher overview.
- Session Log:
- 2026-08-14: Captured from Kevin + Boss first pass: teacher-defined named trophies + one cached AI badge; server-side triggers (quiz bar, finish book/chapters, finish all term assignments, approximate time-in-reader, N heartbeat days, combos, manual award); backfill late creates; student own view + teacher `BL-025.10` overview; no leaderboard (FERPA). Out of v1: chat-quality trophies, per-student unique badges, K-12 / parent view.
- 2026-08-15: Kevin add — assignment-wizard optional trophy for completing **this assignment on time** at **100%** (perfect score); AI may suggest title + badge from book/chapters; teacher always overrides before save; assignment-scoped first-class trigger (`BL-055.6`). Docs only.

### BL-056 - Cask of Amontillado Never Discovers Fortunato
- Type: Bug
- Priority: P1 (ENGL 1020 Week 2; do not treat Cask as a character-chat demo book until fixed)
- Effort: S
- Status: In Progress (code fixes on PR #112; prod publish + portrait provider still open)
- Problem: After reading the entire *Cask of Amontillado* (PG **1063**), **Fortunato never appears** in the character roster and is never “discovered.” The Imagine portrait could not be tested because discovery never happened.
- Repro (2026-08-15 night, Kevin):
  - Phone **incognito**, not logged in, **audio playback** through the whole short story → no discovery, empty roster.
  - Signed in **`demo_teacher@example.com`**, **`j`** paragraph-by-paragraph through the book → same.
- Product direction (2026-08-16, Kevin):
  - **`SECONDARY` characters must still be discovered and shown in the roster.** Type is not a roster/discovery filter.
  - Separately, Fortunato should be **`PRIMARY`** because he is the only extracted character (chat-lead / Call Character).
- Local probe (2026-08-16, not a fix):
  - Book `8167f57f-f088-4726-aa50-d63971d1b15d`; `characterEnabled` + `illustrationEnabled` true.
  - Fortunato row `COMPLETED`, `SECONDARY`, `firstChapterIndex=0`, `firstParagraphIndex=0`, `portraitReady=true`.
  - `GET /api/characters/book/{id}/up-to?chapterIndex=0&paragraphIndex=0` returns Fortunato (no type filter).
  - Reader roster/`up-to`/discovery toast do **not** exclude `SECONDARY`. Chat/call is the PRIMARY gate (`isChatEligible`: PRIMARY always; SECONDARY only when the book has no PRIMARY).
  - Reader `j` → `nextParagraph()` → `renderPage()` → `scheduleCharacterDiscoveryCheck()`. Opening page 1 should already discover him if rows+flags+portrait are live.
  - Empty roster is **not** explained by him being SECONDARY, and **not** by a late first-appearance index. Likely **prod missing character rows / flag path**, or discovery not running on the phone/TTS path.
- Root cause (2026-08-16 investigation — three independent defects, two now fixed):
  1. **Prod has no character rows at all.** `application-prod.properties` sets `generation.cache-only=true`, so prod generates nothing; characters must be pregenerated locally and transferred. `data/transfers/` has portrait transfers only for books `121` and `25344` — nothing for `1063`. This is the `BL-052.3` publish gap, and it fully explains the empty roster on the phone. The local probe was correct: not a reader bug, not a SECONDARY filter.
  2. **Extraction only ever saw 3,000 chars of a chapter.** `CharacterExtractionService` truncated chapter text to a hardcoded `3000`. Library median chapter is **12,318** chars (p95 **37,468**), so ~75% of every chapter was discarded on every book. Fatal for single-chapter short works, which have no later chapter to catch stragglers: the Cask window ends at paragraph 13 and Montresor first appears at 17, so he was unreachable. Explains why the Cask had exactly one character.
  3. **Book-level prefetch was hardwired to a dead Ollama and latched failure permanently.** `CharacterPrefetchService` built its own WebClient against `ai.reasoning.ollama.base-url`, ignoring `ai.reasoning.provider` (`xai`). Every call failed, the exception was swallowed, and the empty result was recorded as “model doesn’t know this book” by setting `character_prefetch_completed = true` — a flag nothing ever resets. Explains why Fortunato was SECONDARY and why **10 books** carry zero PRIMARY characters (`1063`, `3189`, `512`, `10623`, `16376`, `8601`, `12242`, `2554`, + 2 CIA World Factbook rows).
- Verified after fix (2026-08-16, local): the Cask went from one SECONDARY character to `Fortunato` (p0) **and `Montresor` (p72)**, both `PRIMARY`. Montresor had never been extracted before at all. `BL-056.2` (ops SQL to mark PRIMARY) is now **moot** — working prefetch types them correctly without manual SQL.
- Scope Buckets:
  - Confirm prod (and the phone path) has the Fortunato row, flags, and portrait — vs local-only metadata.
  - Keep discovery/roster working for SECONDARY; do not “fix” empty roster by only flipping type.
  - ~~Mark Fortunato `PRIMARY` as a separate product preference~~ — resolved by the prefetch fix; no ops SQL needed.
  - QA discovery toast + roster (SECONDARY would be enough) **and** chat after PRIMARY.
- Blocked on (2026-08-16):
  - **Portraits.** Character portraits are hardwired to `ComfyUIService`; only book covers have a provider abstraction (`book-cover.generation.provider` = comfyui|xai|openai). Kevin does not want to run ComfyUI. Regen currently fails with `2 portraits failed` / `comfyuiAvailable: false`. This blocks discovery end-to-end because the reader gates discovery on `portraitReady` in three places and `CharacterService.isAvailable()` requires `comfyUIService.isAvailable()`. **Needs a decision: wire portraits to Grok Imagine / `gpt-image-1`, or decouple discovery from `portraitReady`.**
- Out of scope: re-pregen portrait unless the file is actually missing; Imagine-for-illos wiring.
- Work Tracker:
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-056.1 Confirm env (prod vs local) | Done | Prod is `generation.cache-only=true` and has no transfer artifact for `1063`; empty roster is the `BL-052.3` publish gap, not a reader bug | Confirmed prod has no character rows for the Cask |
| BL-056.2 Mark Fortunato PRIMARY | Done (obsolete) | Superseded by BL-056.5 — working prefetch types both characters PRIMARY with no ops SQL | Fortunato is PRIMARY without manual intervention |
| BL-056.3 QA discovery + roster + chat | Blocked | Full Cask read on the same paths Kevin used (incognito TTS + signed-in `j`) | Blocked on BL-056.6 — no portraits means `portraitReady=false` and discovery never fires |
| BL-056.4 Chapter context budget | Done (PR #112) | Replace hardcoded 3000-char truncation with `character.extraction.max-context-chars` (default 24000); log when the cut applies. Recap/quiz were already configurable at 6000/7000 | Montresor is extracted from the Cask; verified locally 2026-08-16 |
| BL-056.5 Prefetch provider + latch | Done (PR #112) | Route `CharacterPrefetchService` through `reasoningLlmProvider` (xai) instead of hardwired Ollama; only latch `character_prefetch_completed` on a usable answer so failures stay retryable. Adds `CharacterPrefetchServiceTest` (service had none) | Both Cask characters come back PRIMARY; verified locally 2026-08-16 |
| BL-056.6 Portrait image provider | **Ready — decision needed** | Portraits are hardwired to ComfyUI, which Kevin does not want to run. Either extend the `book-cover.generation.provider` pattern (xai `grok-imagine-image` / openai `gpt-image-1`) to portraits, or decouple discovery from `portraitReady` | Portraits generate without ComfyUI, **or** characters are discoverable without a portrait |
| BL-056.7 Reset latched books + regen | Ready | 10 books carry a stuck `character_prefetch_completed=true` with zero PRIMARY. Flags reset 2026-08-16; Cask regenerated. Remaining: `3189`, `512`, `10623`, `16376`, `8601`, `12242`, `1459`, `2554`. Script: `regen_bl052_characters.sh` (untracked, repo root) | Each book has correct PRIMARY/SECONDARY rows under the new budget. Do not run before BL-056.6 — every book will hit the same portrait failure |
| BL-056.8 Possessive/plural name match | Proposed | `buildNamePattern` builds `\bMontresor\b`, which cannot match the family plural `Montresors` at paragraphs 17 and 36, so first-appearance lands at **72 of 77** — discoverable only on the second-to-last page. Allow an optional `'s` / `s'` / `s` suffix | Montresor is discoverable early in the story; affects any book with a family surname |
- Discovery Questions:
  - Should **every** single-character short story auto-PRIMARY, or only Cask for now?
  - If prod rows are missing, is this a `BL-052.3` publish gap (transfer portraits/characters) rather than a reader bug?
  - Does TTS/audio advance fail to schedule discovery on phone even when `up-to` would return the character?
- Acceptance Criteria:
  - After a full Cask read on prod (or the path students will use), Fortunato is **discovered and on the roster regardless of PRIMARY vs SECONDARY**.
  - Fortunato is then `PRIMARY` and chat-eligible (separate product preference).
  - Portrait is reachable once discovered (CDN cache-bust if needed).
- Dependency Notes:
  - Progressive discovery: `ccr-production-ops` / character-progressive-discovery. Same family as Scarlet Letter first-appearance + SECONDARY repair.
  - Week 2 partner title under `BL-052`; publish/transfer is routine ops if rows are missing on prod.
  - Required-chat assignments (`BL-025.11`) cannot use Cask until this is green.
- Session Log:
- 2026-08-16: Logged from Kevin’s Cask audio + `j`-key repro. Local up-to already returns Fortunato as SECONDARY at 0/0. Kevin: he should be PRIMARY as the only character.
- 2026-08-16: Kevin correction — **SECONDARY must still be discovered and shown in the roster.** Empty roster is not “because he is SECONDARY.” PRIMARY remains a separate chat-lead preference. Docs only; no SQL/code change in this capture.
- 2026-08-16: Investigation found three independent defects (see Root cause). Two fixed on PR #112 (`claude/fortunato-discovery-bug-f86ddd`): the 3000-char extraction truncation (`BL-056.4`) and the Ollama-hardwired prefetch with its permanent failure latch (`BL-056.5`). 727 tests pass. Verified locally: the Cask now yields Fortunato **and Montresor**, both PRIMARY — Montresor had never been extracted at all. `BL-056.2` is obsolete; no ops SQL needed.
- 2026-08-16: Local ops during the session — reset `character_prefetch_completed` on 10 latched books; repaired a pre-existing Flyway V25 checksum mismatch (`1700911374` → `-252007371`; DB still carries the older, looser V25 backfill affecting 6 of 13 `quiz_attempts` rows — unrelated to characters); regenerated the Cask. Regen surfaced `2 portraits failed` / `comfyuiAvailable: false`.
- 2026-08-16: **Stopped on the portrait provider question (`BL-056.6`).** Kevin does not want ComfyUI. Portraits have no provider abstraction (covers do), and discovery is gated on `portraitReady`, so the remaining 8 books (`BL-056.7`) should not be regenerated until this is decided. New `BL-056.8` logged for the possessive/plural name-match gap that leaves Montresor at paragraph 72 of 77.

### BL-057 - Dedicated Assignment Page + Reduced Landing Card
- Type: Feature
- Priority: P2 (product direction; overflow hotfix is the demo-night patch)
- Effort: M
- Status: Proposed
- Problem: Student landing assignment cards currently dump the whole assignment dashboard — scope (chapter / whole book), late/on-time, quiz status (pass threshold + attempts left), character-chat required, progress rings / `N/M complete`, and a Chat CTA. On mobile they overflow and overlap. A CSS wrap is only a bandage.
- Current Direction (2026-08-17):
- The landing card is a **reduced surface**: assignment/book **title**, **1–2 status chips**, one **Open** CTA.
- Full detail lives on a **dedicated assignment page**: progress, quiz status, character-chat requirement, late/on-time, and primary actions (open book / take quiz / chat as applicable).
- Do not keep growing the Library card into a dashboard. `BL-018.6` stays assignment-first; density moves off the rail.
- Out of this epic:
  - The **thin/reduced landing-card hotfix** already in flight (separate PR). Do not stack this IA onto that patch.
  - The soak **overflow hotfix** (PR #121, `cursor/fix-assignment-card-mobile-7c87`) — demo-night CSS wrap so chips/CTA stay inside the card border. Bandage only; not the product direction.
- Scope Buckets:
- Dedicated student assignment page (route + IA) that owns dashboard-level detail.
- Reduced landing card that opens that page (title, 1–2 chips, Open) — product surface, not the CSS hotfix.
- Primary actions on the page (open assigned chapter/book, take quiz, character chat) rather than on the rail card.
- Keyboard / assistive labels for the new page and the reduced card (`BL-044` hygiene, not a VPAT slice).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-057.1 Assignment page route + IA | Proposed | Student-only dedicated page for one published assignment: title, progress, quiz status, character-chat requirement, late/on-time, primary actions. Deep-linkable. | Enrolled student can open `/assignment/{id}` (or equivalent) and see the dashboard fields that today live on the landing card |
| BL-057.2 Reduced landing card | Proposed | Library assignment card shows title, at most 1–2 status chips, and one Open CTA that goes to the assignment page. No progress rings, no quiz/chat action row, no full chip dump. Distinct from PR #121 CSS wrap and from any in-flight thin-card hotfix. | Crowded assignment (whole-book + late + quiz + character-chat required) still fits the card on a 390px viewport without overlapping or painting outside the border because the card is *content-reduced*, not only wrapped |
| BL-057.3 Move primary actions off the rail | Proposed | Take Quiz / Chat with Character (and any later trophy/download affordances) live on the assignment page. Landing Open is the only card CTA. | Quiz and character-chat CTAs are not on the landing card; they remain reachable from the assignment page when `BL-025.4` / `BL-025.11` require them |
- Discovery Questions:
- Which 1–2 chips stay on the reduced card — status + late, status + quiz, or a single combined chip?
- Does landing **Open** always go to the assignment page, or may it skip to the book when the assignment is already in progress?
- Path shape: `/assignment/{id}` vs hash/query on the existing Library? Prefer a real page so Back is obvious (related convenience: `BL-051` is reader Back, not this).
- Teacher preview of the same student page, or student-only in v1?
- Acceptance Criteria:
- Landing assignment card shows title, at most two status chips, and one Open CTA.
- Dedicated assignment page shows progress, quiz status, character-chat requirement (`BL-025.11`), late/on-time, and the primary actions that used to sit on the card.
- A CSS-only wrap of the current dashboard card does **not** close this epic.
- Overflow hotfix PR #121 and the in-flight thin-card hotfix stay separate; this epic does not modify those PRs.
- Dependency Notes:
- Extends classroom assignment UX from `BL-025.4` (due/required/opened) and `BL-018.6` (assignment-first landing). Does not replace assignment create/publish.
- Character-chat required chip + Chat CTA move here; requirement/completion/export stay `BL-025.11`.
- Quiz pass/attempt chips on the page reuse `BL-025.12` signals already on context; no new grading model.
- Soak overflow hotfix: PR #121. Cross-link only.
- Risks:
- Shipping only the CSS wrap (or a thin-card hotfix) and calling the overflow “fixed” leaves the dashboard-on-a-card problem for the next crowded assignment.
- Putting Open → book (skipping the page) would hide quiz / character-chat / late detail again.
- Session Log:
- 2026-08-17: Captured as product direction after mobile landing cards overflowed during soak (scope, late, quiz, character-chat required, progress, Chat CTA on one card). CSS wrap is a bandage (PR #121). Dedicated page + reduced card is the IA. Thin/reduced landing-card hotfix stays a separate in-flight PR. Docs only; no UI in this capture.

### BL-058 - Assignment Open / Persist Leftovers (Low)
- Type: Improvement / tech debt
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: The #121–#125 soak loop shipped the skinny landing card + Open/resume/persist-suppression. Reviewers left **Low / not-blocking** items rather than hold the demo.
- Collect these (from Uncle Bob on #125 HEAD `0a63b90` / merge `cb04f32`):
  1. **Chapter hops persist before `loadChapter` resolves.** `nextPage`/`prevPage` last-page branches, `nextChapter`/`prevChapter`, and `selectChapterFromList` still call `allowBookActivityPersist()` before `loadChapter` finishes. A failed hop after a failed Continue can persist that index with stale `totalPages`. Prefer the `navigateToChapterParagraph` pattern (only allow persist after the chapter actually loaded).
- Out of this epic:
  - Skinny card / book subtitle / Open wrap-up (shipped #121–#124).
  - Persist-suppression until nav succeeds (shipped #125).
  - Older #105 security leftovers and open #108 hardening (not this theme).
  - Dedicated assignment page (`BL-057`).
- Scope Buckets:
- Persist-after-successful-load on chapter hops so a failed hop cannot write a new index with stale `totalPages`.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-058.1 Persist after successful load on chapter hops | Proposed | `nextPage`/`prevPage` last-page branches, `nextChapter`/`prevChapter`, and `selectChapterFromList` should follow `navigateToChapterParagraph`: call `allowBookActivityPersist()` only after `loadChapter` succeeds. | A failed chapter hop after a failed Continue does not persist the hop index with stale `totalPages` |
- Acceptance Criteria:
- Chapter-hop paths do not call `allowBookActivityPersist()` before `loadChapter` resolves.
- A failed hop after a failed Continue does not persist that chapter index with stale `totalPages`.
- Search/bookmark jumps that already use `navigateToChapterParagraph` stay unchanged.
- This epic does not reopen skinny-card / Open wrap-up (#121–#124), persist-suppression (#125), #105/#108 hardening, or `BL-057`.
- Dependency Notes:
- Follow-on to shipped #125 persist-suppression (`0a63b90` / merge `cb04f32`). Cross-link only; do not reopen that PR.
- Pattern to copy: `navigateToChapterParagraph` (allow persist only after the chapter actually loaded).
- Distinct from `BL-057` (dedicated assignment page + reduced landing card).
- Risks:
- Leaving hop paths on the old allow-then-load order recreates the failed-Continue stale-`totalPages` persist after a later failed hop.
- Session Log:
- 2026-08-17: Captured Low / not-blocking leftover from Uncle Bob on #125 rather than hold the demo. Docs only; no reader change in this capture.

### BL-059 - Speed up GET /api/library (Landing Library Load)
- Type: Improvement
- Priority: P2 (not Jessica-dry-run blocking)
- Effort: M
- Status: Proposed
- Problem: The landing page waits a long time for the library catalog. Prod `GET https://classicchatreader.com/api/library` measured **~21.4s total, ~21.2s TTFB** (queue/download tiny). This is **server-side work**, not the network.
- Current code: `LibraryController.listBooks()` → `bookStorageService.getAllBooks()` returning `List<Book>`. `getAllBooks()` does `bookRepository.findAll()` then `toBookDto` per title (chapter list + per-book cover lookup). Kevin hypothesis: send **cached responses**.
- Current Direction (first pass):
  1. Cache the catalog list (in-memory + short TTL and/or explicit invalidate on import/delete/feature-toggle). Stale-while-revalidate is fine for the landing list.
  2. Consider a **slim landing DTO** (id, title, author, cover url, flags) instead of hydrating full `Book` graphs if `getAllBooks` is doing heavy IO per title.
  3. HTTP cache headers for anonymous/public catalog if safe (do not cache per-user progress in the same payload).
  4. Measure: log/timing on `getAllBooks` so we know if it is disk scan, DB, or cover/status fan-out.
- Out of this epic:
  - Rewriting storage.
  - CDN for JSON unless cheap.
- Scope Buckets:
- In-process catalog cache with short TTL and/or invalidate on import, delete, and feature-toggle.
- Slim landing payload so the catalog list is not a full `Book` graph per title.
- Optional public HTTP cache headers only when the payload is anonymous catalog (no per-user progress).
- Timing/diagnostics on `getAllBooks` to name the bottleneck before a larger rewrite.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-059.1 Time `getAllBooks` | Proposed | Log/timing around `findAll`, `toBookDto`, chapter hydration, and cover lookup so the 21s TTFB has a named cause | One prod or prod-like measurement says whether the cost is disk scan, DB, or cover/status fan-out |
| BL-059.2 Catalog list cache | Proposed | In-memory cache of the landing catalog with short TTL and/or explicit invalidate on import/delete/feature-toggle; stale-while-revalidate is acceptable | Repeat `GET /api/library` after a warm cache is not ~21s TTFB; a catalog mutation does not serve a permanently stale list |
| BL-059.3 Slim landing DTO | Proposed | Landing list returns id, title, author, cover url, flags — not a full `Book` graph — if hydration is a measured cost | Catalog JSON no longer includes per-title chapter lists (or equivalent heavy fields) unless a later measure says they are cheap |
| BL-059.4 Public cache headers | Proposed | Add HTTP cache headers for anonymous/public catalog only; do not cache per-user progress in the same payload | Unauthenticated catalog may be cached; signed-in progress/assignment fields are not in that cached body |
- Discovery Questions:
- Is the 21s mostly `findAll` + chapter lazy-load, per-book cover queries, or something else (curated-flag checks, serialization)?
- Can the landing list stay a shared public catalog, with progress still coming from classroom context / local activity (`BL-047` / `BL-018`)?
- What invalidate events are required besides import, delete, and feature-toggle (cover ready, curated flag, admin PATCH)?
- Acceptance Criteria:
- Landing `GET /api/library` is no longer ~21s TTFB on a warm catalog (measure before/after; cache and/or slim DTO as the first pass).
- Catalog mutations (import / delete / feature-toggle) do not leave a permanently stale landing list.
- Per-user progress is not cached inside the public catalog payload (`BL-047` assignment progress stays on classroom context / client refresh, not this list).
- Timing exists so a later rewrite is justified by a named bottleneck, not a guess.
- This epic does not rewrite storage or add a JSON CDN unless that path is cheap and already in ops.
- Dependency Notes:
- Observed during Kevin 2026-08-17 landing E2E / DevTools (same session window as the #121–#125 landing soak). Cross-link only; do not reopen those PRs.
- Distinct from `BL-047` (assignment dashboard / Library progress refresh after returning from the reader). That bug is **Done**; this is catalog **latency**.
- Complements `BL-053` (classroom concurrent capacity): a 21s library TTFB will dominate any same-hour class load, but this epic is a single-request catalog fix, not a droplet soak.
- Invalidate path should respect `BL-007` admin-only import/delete/feature-toggle.
- Landing IA stays `BL-018` / `BL-057`; this epic does not change card density or assignment Open.
- Distinct from `BL-061` (My Chats Open Book hop through landing). This epic is catalog **latency**; `BL-061` is the unnecessary hop that currently pays that latency before the reader opens.
- Distinct from `BL-070` (teacher workspace `/teacher` first paint). Student landing TTFB is a separate measurement from teacher first useful UI, even if `/api/library` appears in both bootstraps. Do not fold.
- Risks:
- Caching the current full `Book` graph without a slim DTO still ships a heavy payload and may hide N+1 cover/chapter work behind a TTL.
- HTTP cache on a payload that later grows per-user progress would leak or freeze the wrong student’s assignment state.
- Session Log:
- 2026-08-17: Kevin E2E, DevTools timing on prod `GET /api/library` (~21.4s total, ~21.2s TTFB). Captured as P2 improvement; first pass is cache + slim DTO + optional public headers + measurement. Docs only; no runtime change in this capture.

### BL-060 - Arrow Keys as j/k Paragraph Navigation
- Type: Improvement
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: Desktop reader paragraph navigation is `j` (down) / `k` (up) only (`BL-023` keyboard checklist). Kevin wants **Down Arrow = j** and **Up Arrow = k** as alternatives.
- Current Direction:
- Keep existing `j`/`k`.
- Add `ArrowDown` / `ArrowUp` as aliases when the reader is the focused surface (same `nextParagraph` / `prevParagraph` + TTS interrupt path as `j`/`k`).
- Do **not** steal arrows inside search, text fields, chapter list, or overlays that already use arrows (`BL-023` chapter list `ArrowUp`/`ArrowDown`/`Enter`/`Escape`; also bookmarks list and mobile header menu, which already treat arrows as `j`/`k` list moves).
- Page nav already aliases `←`/`→` to `h`/`l`; this epic is the vertical equivalent for paragraphs, not a new page/chapter binding.
- Update the `?` shortcuts overlay (today lists `j`/`k` only for paragraph next/previous) and the `BL-023` desktop keyboard checklist when implemented.
- Out of this epic:
  - Changing `j`/`k` themselves or remapping page/chapter keys.
  - Binding arrows for page or chapter navigation (page already has `←`/`→`).
  - Mobile touch nav (`BL-023` Done).
- Scope Buckets:
- `ArrowDown`/`ArrowUp` aliases for paragraph next/previous on the focused reader surface.
- Focus/overlay guards so search, inputs, chapter list, and existing arrow overlays keep native/existing behavior.
- `?` shortcuts overlay copy + `docs/product/bl-023-qa-checklist.md` desktop keyboard row.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-060.1 Arrow aliases + focus guards | Proposed | Map `ArrowDown`→`nextParagraph` and `ArrowUp`→`prevParagraph` only when the reader is the focused surface; skip when focus is in search/text fields or when chapter list / bookmarks / other arrow-using overlays are open | Desktop reader: Down = j, Up = k; arrows still move the chapter list (and other existing overlay lists) and still type-navigate in inputs |
| BL-060.2 Shortcuts overlay + BL-023 checklist | Proposed | Document `j`/`k` or `↓`/`↑` on the `?` overlay; add an ArrowUp/Down paragraph-nav row to the BL-023 desktop keyboard checklist without weakening the chapter-list arrow check | Overlay and checklist mention both j/k and arrows; chapter-list ArrowUp/Down/Enter/Escape check remains |
- Acceptance Criteria:
- With the reader focused and no overlay/input capturing keys, **Down Arrow** advances a paragraph like `j` and **Up Arrow** goes back like `k`.
- Existing `j`/`k` behavior is unchanged.
- Arrows are not stolen inside search, text fields, the chapter list, or overlays that already use arrows (`BL-023` chapter list `ArrowUp`/`ArrowDown`/`Enter`/`Escape`).
- `?` shortcuts overlay documents the arrow aliases.
- `docs/product/bl-023-qa-checklist.md` desktop keyboard checklist covers arrow paragraph nav and still verifies chapter-list arrows.
- Dependency Notes:
- Extends shipped `BL-023` desktop keyboard behavior; do not reopen that Done epic except to update the checklist when this ships.
- Distinct from `BL-051` (browser Back / history), `BL-013` (broader a11y pass), and page `←`/`→` aliases already in the overlay.
- Risks:
- Binding arrows globally would break chapter-list and bookmark-list keyboard nav (already ArrowUp/Down + j/k) and native caret movement in search/notes.
- Session Log:
- 2026-08-17: Kevin soak — wants Down Arrow = j and Up Arrow = k as paragraph-nav alternatives. Captured as P2/S. Docs only; no reader change in this capture.

### BL-061 - My Chats Open Book Should Skip Landing
- Type: Improvement
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: From **My Chats**, **Open Book** (e.g. Daisy Buchanan → *The Great Gatsby*) navigates to `/?book=…&chapter=…&paragraph=…`. That is the landing SPA. The landing waits for `GET /api/library` (**~10–20s**, measured ~21s TTFB in `BL-059`) and only then `openBookFromUrlIfPresent()` opens the reader. Chat already has `session.book.id` plus chapter/paragraph. The hop is unnecessary.
- Current code: `buildOpenBookUrl` in `my-chats.js` returns `/?book=&chapter=&paragraph=`. Landing `init` loads the catalog, then `reader.js` `openBookFromUrlIfPresent()` looks up that id in `state.localBooks` before `selectBook`.
- Current Direction:
- Open the reader for the known book without first rendering or waiting on the landing catalog.
- Keep the existing book/chapter/paragraph target (same resume point the chat already has).
- Do not treat this as a `BL-059` catalog-speed fix; that epic stays the landing `GET /api/library` latency work.
- Out of this epic:
  - Speeding up `GET /api/library` (`BL-059`).
  - Changing My Chats conversation resume (`BL-032` / `BL-039` shipped).
  - Assignment Open (`BL-057` / `BL-058`).
- Scope Buckets:
- Direct reader open from My Chats Open Book using the session's known book id + chapter + paragraph.
- Avoid the landing catalog wait as a prerequisite for that navigation.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-061.1 Direct reader open from My Chats | Proposed | Open Book should enter the reader for the known book without waiting on landing `GET /api/library` | Daisy → Gatsby (or any chat with book context) opens the reader at the chat's chapter/paragraph without a 10–20s landing dwell |
- Acceptance Criteria:
- Open Book from a My Chats conversation that already has book/chapter/paragraph opens the reader without first sitting on the landing catalog load.
- Resume target stays the chat's book + chapter + paragraph.
- This epic does not implement `BL-059` cache/slim DTO work.
- Dependency Notes:
- Distinct from `BL-059` (landing library load). `BL-059` is the slow catalog; this is the unnecessary hop. The hop also makes `BL-059` worse: every Open Book pays the ~21s TTFB before reading.
- My Chats surface shipped (`BL-032` / `BL-039`, PR #78). This is a follow-on nav defect, not a reopen of those epics.
- Distinct from assignment Open leftovers (`BL-058`) and dedicated assignment page (`BL-057`).
- Distinct from `BL-070` (teacher workspace `/teacher` first paint). My Chats Open Book hop is a student-reader nav defect, not the teacher workspace load.
- Browser Back after this change should still have a sensible exit (`BL-051`); do not invent a new history story here unless the direct-open path breaks Back.
- Risks:
- If Open Book still goes through `/?…` and `openBookFromUrlIfPresent()` still requires `state.localBooks` from the full catalog, the hop remains even after `BL-059` is faster — and while `BL-059` is open, users wait ~20s for a book the chat already named.
- Session Log:
- 2026-08-18: Kevin: From My Chats, Open Book (Daisy Buchanan → The Great Gatsby) routes through the landing page (10–20s load) and only then opens the book. Chat already knows the book; it should open the reader directly. Slow landing load is `BL-059`; this item is the unnecessary hop, which also makes `BL-059` worse. Docs only; no UI in this capture.

### BL-062 - Character Browser Keyboard: Detail Esc Returns to List; j/k Navigates List
- Type: Improvement / a11y
- Priority: P2
- Effort: S
- Status: Proposed
- Problem: Two leftover keyboard nits in the Characters modal after #131. (1) After opening a character (e.g. **Benvolio**), **Esc** closes the whole modal instead of behaving like **← All Characters** and returning to the list. Keyboard users can Enter into a character, then Esc dumps them out of the browser. Workarounds today: mouse the back button, or press `m` again to reopen. (2) On the character **list**, `j`/`k` do not move the roster. The chapter list (`c` modal) already treats `j` as next and `k` as previous (same as ArrowDown / ArrowUp). Characters already move with arrows; `j`/`k` are swallowed as `block-reader` and never call `move`.
- Current code: `character-browser-keyboard.js` `describeKey` always returns `{ action: 'close' }` for Escape. `handleCharacterBrowserKeydown` maps that to `closeCharacterBrowser()`. Detail view already has `#character-back-btn` (**← All Characters**) calling `showCharacterListView()`. List vs detail is `characterListView` / `characterDetailView` visibility. List movement is `isNavigationKey` → `move` → `moveIndex(...)` for ArrowUp/Down/Left/Right, Home, End only. `j`/`k` sit in `READER_NAV_KEYS` / `READER_SHORTCUT_KEYS` and resolve to `block-reader`. Chapter list (`reader.js` `isChapterListVisible`) maps `j`/`ArrowDown` → `chapterListNavigate(1)` and `k`/`ArrowUp` → `chapterListNavigate(-1)`.
- Current Direction:
- When the detail view is visible, Esc should do the same as **← All Characters** (`showCharacterListView`) and restore list focus.
- When the list view is visible, Esc should still close the modal (existing #131 behavior).
- Close (×) and backdrop click still close the whole modal from either view.
- When the list view is visible, `j`/`k` should navigate the character roster the same way they navigate the chapter list: `j` = next, `k` = previous (alias the existing ArrowDown / ArrowUp `move` path; keep the #131 grid/linear `moveIndex` model).
- On detail view, `j`/`k` stay blocked from reader paragraph nav (do not advance the book behind the modal).
- Out of this epic:
  - Reopening #131 listbox / Arrow / Enter / Tab-trap work except to alias `j`/`k` onto the existing `move` path.
  - Changing `m` toggle or chat-modal Escape (different overlay).
  - Broader overlay a11y (`BL-013`) or reader browser-Back (`BL-051`).
  - Reader paragraph `j`/`k` or arrow aliases (`BL-060`) when the character browser is closed.
- Scope Buckets:
- Detail-view Esc → list (`showCharacterListView`), with focus back on the cards.
- List-view Esc unchanged (close modal).
- List-view `j`/`k` → same `move` path as ArrowDown / ArrowUp (chapter-list parity).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-062.1 Detail Esc returns to list | Proposed | When `characterDetailView` is showing, Escape should call the same path as **← All Characters** and focus the list; list-view Escape still closes | Benvolio (or any character detail): Esc → All Characters list; list Esc / × / backdrop still dismiss the modal |
| BL-062.2 List j/k navigates like chapter list | Proposed | When `characterListView` is showing, `j`/`k` should `move` like ArrowDown / ArrowUp (chapter list `j` next / `k` previous); detail-view `j`/`k` stay `block-reader` | Characters list: `j`/`k` move the focused card; arrows / Enter / Home / End unchanged; detail `j`/`k` do not paginate the reader |
- Acceptance Criteria:
- With a character detail open (e.g. Benvolio), Esc returns to the Characters list like **← All Characters** and does not close the modal.
- With the list visible, Esc still closes the Characters modal and returns focus to the opener (shipped #131).
- Close (×) and backdrop click still close the modal from list or detail.
- With the Characters **list** visible, `j` moves to the next card and `k` to the previous card, same as the chapter list (`c` modal) `j`/`k` (and the existing ArrowDown / ArrowUp move path).
- Arrow / Home / End / Enter list behavior from #131 is unchanged.
- On character **detail**, `j`/`k` do not advance reader paragraphs behind the modal.
- `m` toggle and character-chat Escape are unchanged.
- Dependency Notes:
- Follow-on a11y nits from soak after shipped #131 (keyboard character browser). Cross-link only; do not reopen that PR.
- Distinct from `BL-060` (reader arrow paragraph nav) and `BL-013` (broader a11y pass).
- Overlay Esc-closes-first stays the list-view / other-modal pattern (`BL-051` notes); this epic is nested detail → list inside one modal, plus chapter-list `j`/`k` parity on the roster.
- Risks:
- Treating every Esc as close (current `describeKey`) leaves keyboard users needing the mouse or a second `m` after they open a character.
- Returning to the list without restoring card focus would drop the #131 roving-tabindex model after Esc-from-detail.
- Leaving `j`/`k` as `block-reader` on the list keeps a second keyboard split vs the chapter list: arrows work, vim keys do not.
- Mapping `j`/`k` to `move` on detail (or forgetting `block-reader` there) would either no-op confusingly or leak into reader paragraph nav.
- Session Log:
- 2026-08-18: A11y nit from tonight’s soak after #131 keyboard work. Character detail (e.g. Benvolio): Esc closes the whole Characters modal instead of returning to the list like **← All Characters**. Mouse-only / pressing `m` again are the current workarounds. Docs only; no reader change in this capture.
- 2026-08-18: Follow-up soak: `j`/`k` should navigate the Characters **list** the same way they navigate the chapter browser (`c` modal). Expanded this item in place — no new BL. Keep original Esc-on-detail → list. Docs only; no reader change in this capture.

### BL-063 - Cite Gutenberg Source on Library Book Cards
- Type: Improvement
- Priority: P2
- Effort: S
- Status: In Progress
- Problem: Gutenberg-sourced titles do not show a visible cite back to Project Gutenberg. Teachers (and students) cannot tell a title is a public-domain Gutenberg ebook or jump to the source page. Needed for license/attribution and teacher trust, including the Jessica demo.
- Placement (Kevin 2026-08-18): Library page book card only. Small corner text like **`[Gutenberg 1513]`** (ebook number), linking to the Project Gutenberg page. Gutenberg-sourced titles only. Not on the skinny assignment card, not in the reader chrome unless we add that later.
- Current Direction:
- Library page **book cards only** (local imported titles in Continue Reading / shelves). Do **not** put this near reader page count / cache-only.
- Gutenberg-sourced titles only (`source=gutenberg` + numeric ebook id).
- Small cover-corner text like **`[Gutenberg 1513]`** linking to `https://www.gutenberg.org/ebooks/{id}`.
- Accessible name must say more than the bracket text (e.g. “Project Gutenberg source for Romeo and Juliet, ebook 1513”).
- Clicking the cite must open Gutenberg and must **not** open the book.
- Out of this epic:
  - Skinny assignment cards (`book-item-assignment` / `BL-057`).
  - Reader chrome (page count, cache-only, header) unless a later slice adds it.
  - Catalog / Discover cards (those are already Gutenberg search results).
  - Non-Gutenberg sources (Standard Ebooks, manual).
- Scope Buckets:
- Expose Gutenberg ebook number on the library `Book` DTO.
- Render a small corner cite on local library book cards only.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-063.1 Library book-card Gutenberg cite | In Progress | `GET /api/library` includes `gutenbergId` for Gutenberg imports; local library cards show `[Gutenberg {id}]` linking to the ebook page with an accessible name; assignment cards and reader chrome stay clean | Romeo and Juliet (1513) library card cites Gutenberg; a non-Gutenberg title does not; skinny assignment cards do not grow a source line |
- Acceptance Criteria:
- Gutenberg-sourced library book cards show a small corner `[Gutenberg {ebook id}]` link to `https://www.gutenberg.org/ebooks/{id}`.
- The control has an accessible name beyond the visible bracket text.
- Non-Gutenberg titles do not show a Gutenberg cite.
- Skinny assignment cards do not show the cite.
- Reader chrome does not show the cite unless a later slice adds it.
- Activating the cite does not open the book.
- Dependency Notes:
- Distinct from `BL-057` / `BL-058` assignment-card density. Do not put attribution on the skinny card.
- Distinct from MLA citation (`citation-utils` / reader cite copy). This is a visible source stamp, not a formatted citation.
- Catalog import already stores `source` / `sourceId` on `BookEntity`; this epic surfaces that on library cards.
- Risks:
- Putting the cite on the assignment card re-fattens the soak-thinned landing card.
- Putting it in reader chrome (page count / cache-only) is out unless we add that later; this item is the library-card stamp.
- A link inside the card `role="button"` that does not stop the card click would open the book instead of (or as well as) Gutenberg.
- Session Log:
- 2026-08-18: Kevin: cite the book source back to Project Gutenberg with a link, Gutenberg-sourced titles only. License/attribution plus teacher trust. Do not put it on the skinny assignment card. Prefer a small source line on the library book card (not reader chrome). Captured as `BL-063` and implemented on library cards in this PR.
- 2026-08-18: Placement refined: library page book card only; small corner `[Gutenberg 1513]` (ebook number) linking to the Gutenberg page; Gutenberg-sourced titles only; not on the skinny assignment card; not in the reader chrome unless we add that later. Replaces reader / page-count / cache-only placement. Docs only.

### BL-064 - Character Blurbs Spoil Later Plot
- Type: Bug
- Priority: P1 (Columbia State demo prep; spoiler leak on character cards + chat/voice)
- Effort: M
- Status: Proposed
- Problem: Generated character blurbs include later-plot facts. On live *Frankenstein* (Gutenberg **84**, book `8494cda1-0135-47f0-aaff-6b71c5b2f864`), Kevin (Columbia State demo prep, 2026-08-18) saw:
  - **Henry Clerval:** “who is murdered by the monster”
  - **Robert Walton:** “discovers Victor Frankenstein's body”
  Those strings show on the character card / browser **before** the reader has reached those events. The same stored `description` is injected into the chat/voice persona (`CharacterPersonaPromptBuilder`), so the character can leak spoilers even with chapter-position gating.
- Current code: Prefetch asks for a 2–3 sentence role description and to “avoid major spoilers” (`CharacterPrefetchService.buildPrefetchPrompt`); chapter analysis also persists `ec.description()`. Cards render the stored field as-is (`reader.js` `characterDetailDesc` / toast). `CharacterPersonaPromptBuilder.buildPersona` puts `character.getDescription()` under **CHARACTER DESCRIPTION**, then separately says the character can only discuss events up to the reader’s chapter/paragraph. Discovery/roster gating (`GET .../up-to`) still hides unreached characters; it does not strip future-plot facts from a reached character’s blurb.
- Current Direction:
- Character card / browser blurbs must be spoiler-safe to the reader’s current position, **or** generation-time first-appearance only (no later-plot facts in the stored description).
- Persona prompt must not include future-plot facts (same stored blurb must not leak through text or voice).
- Same class of bug on other books — not Frankenstein-only. Fix the generation / display / persona path; do not special-case Gutenberg 84.
- Keep existing chapter-position gating (`up-to` / story constraints). This epic does not weaken who is discoverable.
- Out of this epic:
  - #132 name-identity dedupe / junk secondaries (`Sally` / `name_key`). Related character-quality work; **do not fold this into #132**.
  - `BL-054` college-appropriate conduct (NSFW/jailbreak). Conduct stays; do not weaken spoiler rules while editing the persona.
  - `BL-056` discovery/roster empty (Fortunato). This is blurb *content*, not whether the character appears.
  - Reading Buddy spoiler suite (`BL-017` / buddy chapter window). Different surface.
  - `BL-067` recap-chat false “character not in this chapter” (Dorian Gray Ch 1). Different surface (chapter-pause Chat tab, not card blurbs).
- Scope Buckets:
- Spoiler-safe character card / browser blurbs (position-aware, or first-appearance-only at generation).
- Persona prompt (text + voice) must not carry future-plot facts from the blurb.
- Apply across titles; regen or rewrite existing spoiling descriptions (Frankenstein examples + other books).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-064.1 Card/browser blurbs spoiler-safe | Proposed | Stored or rendered character description must not reveal events after the reader’s position (or must be first-appearance-only at generation) | Opening Henry Clerval / Robert Walton on live Frankenstein before those deaths never shows “murdered by the monster” / “discovers Victor Frankenstein's body”; same class holds on another title |
| BL-064.2 Persona prompt without future-plot facts | Proposed | `CharacterPersonaPromptBuilder` (text + voice) must not include later-plot facts from the blurb; chapter-position gating stays | Chat/voice for a reached character cannot recite later-plot facts from the blurb/persona; existing `up-to` / story-constraint gating still applies |
- Acceptance Criteria:
- Opening a reached character’s card never reveals events from later chapters.
- Chat/voice for that character cannot recite later-plot facts from the blurb/persona.
- Existing chapter-position gating still applies.
- Not Frankenstein-only: the same class of spoiling blurb does not appear on other books after the fix.
- Dependency Notes:
- Distinct from #132 (character name identity / duplicate Sally / junk secondaries). Do not stack this work on that PR and do not fold spoilers into that epic.
- Distinct from `BL-065` (regen character discovery with xAI). A better model helps blurbs; it does not replace this spoiler rule. Keep both epics.
- Distinct from `BL-067` (recap chat claiming reached characters are absent). Card/persona spoilers vs chapter-pause Chat quality.
- Complements `BL-054` (conduct on the same `CharacterPersonaPromptBuilder`). This epic is plot-spoiler content in DESCRIPTION; `BL-054` is classroom conduct.
- Discovery/roster still uses first-appearance + `up-to` (`BL-056` family). This epic does not change who is listed, only what the blurb/persona may say.
- Risks:
- Soft “avoid major spoilers” in the prefetch prompt is already ignored by the model; prompt-only without stored-description rewrite or regen leaves live Frankenstein rows spoiling.
- Stripping DESCRIPTION from the persona without a spoiler-safe replacement can flatten voice; rewriting only Frankenstein misses the class of bug.
- Position-aware blurbs that still persist a full-plot description will leak if any client or cache shows `description` unfiltered.
- Session Log:
- 2026-08-18: Kevin, Columbia State demo prep. Live *Frankenstein* (Gutenberg 84, book `8494cda1-0135-47f0-aaff-6b71c5b2f864`): Henry Clerval blurb “who is murdered by the monster”; Robert Walton “discovers Victor Frankenstein's body.” Shows on the character card before those events; same text is injected into chat/voice persona so chapter gating is not enough. Same class of bug on other books. Related but separate from #132 (name dedupe / junk secondaries) — do not fold. Docs only; no product code in this capture.

### BL-065 - Regen Character Discovery with xAI (Replace Local Ollama Pregen)
- Type: Improvement / content-ops
- Priority: P2 (post Columbia State demo; do not start before Thu Aug 20 noon CT)
- Effort: M
- Status: In Progress
- Problem: Kevin pre-generated characters with a local Ollama model. The resulting casts are messy: junk secondaries, name glitches/duplicates, and spoiler-y blurbs. He wants to redo generation with xAI for cleaner PRIMARY/SECONDARY casts.
  - Current generated casts have junk secondaries. *Frankenstein* (Gutenberg **84**): 5 primaries + ~60 secondaries including “The Moon”, “The Mule”, “Elizabeth Lavenza (again)”, and LLM leftovers.
  - Name glitches / identity splits (Northanger triple Sally is the #132 example).
  - Blurbs include later-plot facts (see `BL-064`). A better model helps; it does not replace the spoiler rule.
- Timing (Kevin 2026-08-18, after Jessica dry run): Columbia State demo is **Thu Aug 20 noon CT**. Do **not** start this before the demo. Not a live Thursday change.
- Current code: `CharacterPrefetchService` routes through `reasoningLlmProvider` (xAI after `BL-056.5` / PR #112) and is the PRIMARY list (title+author, no chapter text); new rows are constructed as `PRIMARY`. Chat/call eligibility is PRIMARY only — the former “no PRIMARY ⇒ every SECONDARY is callable” fallback is gone. Chapter miner text-scans first-appearance of names already on the roster and does **not** insert new SECONDARY rows. `CharacterRosterNameFilter` still rejects bees / The Moon / The Mule / maid / `(again)` leftovers, but allows person epithets (The Monster / The Creature / The Turk). `DELETE /api/characters/book/{bookId}` clears `books.character_prefetch_completed`. Pregen image defaults unchanged (Imagine for covers/illos/portraits; Comfy opt-in). Prod is `generation.cache-only=true`. Kevin’s local trial (174 + 84) produced 0 PRIMARY / all SECONDARY; this follow-up is the code fix before a re-trial. Do not treat this as `.3` catalog-done.
- Current Direction:
- Switch character pre-generation from local Ollama to xAI.
- Regenerated casts should have a tight primary set and a sane secondary set, without duplicate/glitch names.
- Per-book regen first (trial), then curated catalog.
- Recommend trial books (Kevin 2026-08-24): *The Picture of Dorian Gray* (Gutenberg **174**) and *Frankenstein* (Gutenberg **84**). Code-only first so that regen can be clean.
- Out of this epic:
  - Shipping or running a regen before the Thu Aug 20 Columbia State demo.
  - Merging #132 as part of this item. #132 / name-identity dedupe stays a code safety net — keep that epic.
  - `BL-064` spoiler-safe blurbs + persona injection — keep that epic. Better model helps; it does not replace the spoiler rule.
  - `BL-056.5` prefetch-provider wiring (already Done). This is stored-cast regen, not that code path.
  - `BL-056.6` portrait provider / ComfyUI. Portraits stay on that slice unless a trial is blocked for a documented reason.
  - `BL-067` recap-chat false “character not in this chapter.” Regen does not replace that chapter-context quality fix.
  - iPhone book covers (`BL-069`). Cast regen is not that cover-load bug.
  - Teacher workspace first paint (`BL-070`). Cast regen is not that `/teacher` load.
- Scope Buckets:
- Documented regen path: provider/model + per-book action using xAI.
- Trial book (Frankenstein / Gutenberg 84): clean PRIMARY/SECONDARY split, no duplicate-name cards.
- After the trial, curated-catalog regen (not Thursday).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-065.1 Documented xAI regen path | Done (docs) | Record provider/model and the per-book action so operators regenerate via xAI, not local Ollama. Jobs already existed; this slice records Imagine defaults + Gutenberg **174** + **84** commands | A documented path exists; a single book can be regenerated against xAI without guessing flags |
| BL-065.2 Trial book (Frankenstein 84 + Dorian Gray 174) | In Progress (code; re-trial not run) | After local trial: PRIMARY-only chat/call, prefetch PRIMARY typing, miner does not invent SECONDARY, article-epithet allow-list. Do **not** run pregen/Imagine in CI or prod | Re-trial has a tight PRIMARY set (Creature/Monster allowed), SECONDARY only if already trusted, and chat/call only for PRIMARY |
| BL-065.3 Curated catalog after trial | Proposed | After the trial looks clean, regen the curated catalog the same way. **Not done.** Do not fold into the prompt PR | Curated titles have xAI casts with the same PRIMARY/SECONDARY quality bar; #132 and `BL-064` remain separate |
- Acceptance Criteria:
- A documented regen path (provider/model + per-book action) using xAI.
- Trial book (recommend Frankenstein, Gutenberg 84) has a clean primary/secondary split and no duplicate-name cards.
- #132 and `BL-064` remain separate follow-ups.
- No regen ships or runs before the Thu Aug 20 noon CT Columbia State demo.
- Dependency Notes:
- Distinct from #132 (name-identity dedupe / junk-secondary safety net). Keep that epic; do not merge it as part of this item and do not stack this PR on #132.
- Distinct from `BL-064` (spoiler-safe blurbs + persona injection). Regen with a better model is not a substitute for the spoiler rule.
- Distinct from `BL-067` (recap chat claiming reached characters are absent). Cast regen is not that chapter-pause Chat quality fix.
- Distinct from `BL-069` (iPhone book covers do not load). Regen is not that Safari/iOS cover-load bug.
- Distinct from `BL-070` (teacher workspace `/teacher` first paint). Regen is not that workspace-load bug.
- Complements `BL-056.5` (prefetch already on `reasoningLlmProvider` / xAI). Existing Ollama-era rows still need a documented regen; do not treat `.5` as this epic being Done.
- Curated-catalog membership stays `BL-052` **Done**; this is character-cast quality on those titles, not a new catalog.
- Risks:
- Running regen before the Thursday demo can replace a known-messy-but-demoed cast with an unreviewed one mid-walkthrough.
- Treating this as “#132 will clean it up” leaves Ollama junk in the stored rows; the code safety net is not a replacement for a better generation pass.
- Treating this as “`BL-064` will rewrite blurbs” still leaves junk secondaries and split identities; spoiler-safe copy does not shrink the roster.
- Session Log:
- 2026-08-19: Captured from Kevin 2026-08-18 (after Jessica dry run). Local Ollama pregen is messy: Frankenstein 5 primaries + ~60 secondaries (The Moon / The Mule / Elizabeth Lavenza (again) / LLM leftovers); Northanger triple Sally is the #132 example; blurbs spoil later plot (`BL-064`). Redo with xAI; per-book trial then curated catalog. Do not start before Thu Aug 20 noon CT Columbia State demo. Distinct from #132 and `BL-064`; do not reuse `BL-064`. Docs only; no product code and no regen in this capture.
- 2026-08-24: `BL-065.2` code-only prompt work (off current `main`; not stacked on #132; no Flyway; no pregen/Imagine/catalog spend). Tightened prefetch + extraction prompts (named people only; first-appearance / no later-plot facts in stored description; do not weaken chapter gating). Expanded `isClearlyNamed` into shared `CharacterRosterNameFilter` — reject bees / The Moon / The Mule / glitch leftovers on extraction **and** prefetch; keep Dorian / Fortunato / Elizabeth Bennet. `deleteCharactersForBook` now clears `character_prefetch_completed` (DELETE was ungated; now ADMIN). Image defaults: cover provider default `xai`; new illustration Imagine seam (`illustration.generation.provider=xai`, `grok-imagine-image`, `ImageGenerationHttpClient` + `ImagePromptSafety`); portraits left as-is (`xai` / `grok-imagine-image`); Comfy remains opt-in. Trial regen still Kevin-local: Gutenberg **174** then **84**. Do **not** mark `BL-065.3` done.
- 2026-08-24: Follow-up after Kevin’s local trial (Frankenstein 84 = 16 SECONDARY / 0 PRIMARY; Dorian 174 = 21 SECONDARY / 0 PRIMARY; no bees/Moon/Mule; Creature/Monster missing; chat/call fallback then made every secondary callable). Cause: blanket “The …” reject dropped epithets; miner invented the roster as SECONDARY; empty PRIMARY then unlocked chat. Fixes (off current `main` after #155; not stacked on #132; no Flyway; no pregen/Imagine/catalog): PRIMARY-only eligibility; prefetch constructs `PRIMARY` (incl. `characterName`/`fullName` aliases); miner only refines first-appearance of trusted names; article strip for denylist so The Monster / The Creature / The Turk pass while bees / The Moon / The Mule / maid / `(again)` still fail. Do **not** mark `BL-065.3` done.

### BL-066 - Open General Registration after Columbia State Approval
- Type: Improvement / ops
- Priority: P1 (semester go-live; gated — not Thursday-demo work)
- Effort: S
- Status: Proposed
- Problem: Registering a new user fails with **“Account access is currently limited to internal rollout users.”** Kevin 2026-08-19 (scripted demo test) reproduced this on the Sign In modal with email `demo_user@example.com`. That message is **expected today**: production is on the `BL-021.6` internal allow-list lock. Columbia State students and teachers will need Register + Google sign-in once the semester begins **and** they have written/approval-to-use at Columbia State Community College.
- Timing (Kevin 2026-08-19, scripted demo test): Columbia State demo is **Thu Aug 20 noon CT**. Do **not** start this before the demo / semester approval. Not a live Thursday change.
- Current code: `BL-021` is **Done**. `BL-021.6` shipped `account.auth.rollout.mode` (`disabled` | `internal` | `optional` | `required`) plus `account.auth.rollout.allowed-emails`. Prod defaults to `internal` via `ACCOUNT_AUTH_ROLLOUT_MODE` / `ACCOUNT_AUTH_ROLLOUT_ALLOWED_EMAILS` (`application-prod.properties`). In `internal` mode, `AccountAuthService` returns `ROLLOUT_RESTRICTED` with that error unless the email is on the allow-list. This epic is the **go-live switch**, not a new auth system.
- Current Direction:
- Flip or widen `account.auth.rollout.mode` (and the allow-list) so new Columbia State students/teachers can Register and use Google sign-in without the internal-rollout error.
- Gate: **semester start and** written/approval-to-use at Columbia State. Do not flip for the Thursday demo.
- Keep existing anti-abuse (`BL-028` register/login rate limits + lockout) and FERPA gates (`BL-043`). Do not weaken those to open signup.
- Demo / internal accounts (`demo.teacher`, `demo.student`, current allow-list users) stay valid after the switch.
- **Decision to record before flipping:** school-scoped signup vs fully public register. Do not make the site fully public-anonymous-register if policy still wants school-scoped signup. Existing modes: stay `internal` and widen `allowed-emails` (school-scoped; current code is per-email, not domain), or set `optional` (ADR Stage 2 — anyone can register; anonymous reading remains). `required` is classroom-path-only (ADR Stage 3) and is not the default go-live.
- Out of this epic:
  - Anything before the Thu Aug 20 Columbia State demo.
  - Reopening `BL-021` / `BL-021.6`. The lock already shipped; this is the operator switch.
  - Merging #132. Name-identity dedupe stays a separate code PR.
  - New-account Completed leak (`BL-068`). Opening registration is not that identity/shelf bug.
  - Teacher workspace first paint (`BL-070`). The go-live switch is not that `/teacher` load.
  - New auth, OAuth providers, password-reset, or classroom entitlement work.
  - Changing `BL-028` rate limits / lockout or `BL-043` FERPA remediations.
- Scope Buckets:
- Document the exact property/mode change and who flips it.
- Execute the go-live switch only after both gates (semester start + written Columbia State approval).
- Confirm Register + Google sign-in for a user **not** on the internal allow-list.
- Confirm existing demo.teacher / demo.student / internal users still work.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-066.1 Record flip + school-vs-public decision | Proposed | Write the exact prod change (`ACCOUNT_AUTH_ROLLOUT_MODE` / `ACCOUNT_AUTH_ROLLOUT_ALLOWED_EMAILS`) and who operates it; decide school-scoped (`internal` + widened allow-list) vs `optional` | Decision and operator note exist; no silent Thursday flip |
| BL-066.2 Go-live switch after both gates | Proposed | Flip/widen only after semester start **and** written Columbia State approval-to-use | A new user not on the prior allow-list can Register + Google sign-in without the internal-rollout error; demo/internal accounts still work |
- Acceptance Criteria:
- A new user (not on the internal allow-list) can register and sign in after the go-live switch, without **“Account access is currently limited to internal rollout users.”**
- The exact property/mode change and who flips it are documented (prod env: `ACCOUNT_AUTH_ROLLOUT_MODE`, optionally `ACCOUNT_AUTH_ROLLOUT_ALLOWED_EMAILS`; operator is Kevin / production ops — not an in-repo properties commit unless a later slice says so).
- Existing `demo.teacher` / `demo.student` / internal users still work.
- `BL-028` rate limits / lockout and `BL-043` FERPA gates remain in place.
- School-scoped vs fully public is an explicit decision, not an accidental `optional` flip.
- Dependency Notes:
- Distinct from `BL-021` (Done). `.6` shipped the lock that produces this message. Do not reopen that epic.
- Distinct from #132 (character name-identity dedupe). Do not stack this work on that PR and do not merge #132 as part of this item.
- Distinct from `BL-068` (new account inherits Completed books). The go-live switch is not that claim-sync / shelf-scoping bug.
- Distinct from `BL-070` (teacher workspace `/teacher` first paint). Opening registration is not that workspace-load bug.
- Keep `BL-028` (account auth rate limits / lockout) and `BL-043` (FERPA). This switch must not weaken those gates.
- Complements ADR §7 Stage 2 (optional production sign-up) in `docs/product/bl-021-auth-architecture-adr.md`. Stage 3 `required` is out unless classroom policy demands it.
- Risks:
- Flipping before Thursday’s demo or before written approval opens registration while the walkthrough still assumes an internal allow-list.
- Flipping to `optional` without the school-vs-public decision makes the site fully public-register even if policy wanted Columbia State–scoped signup. Current `internal` allow-list is per-email, not domain — a whole-college roster via allow-list alone is operationally heavy.
- Treating this as “reopen `BL-021`” invites a new auth system instead of the existing mode switch.
- Session Log:
- 2026-08-20: Captured from Kevin 2026-08-19 (scripted demo test; Columbia State demo Thu Aug 20 noon CT). Sign In modal, email `demo_user@example.com`: **“Account access is currently limited to internal rollout users.”** Expected today (`BL-021.6` internal allow-list). Open Register + Google after semester start **and** written/approval-to-use at Columbia State. Keep `BL-028` / `BL-043`; decide school-scoped vs fully public; demo/internal accounts stay valid. Do not reopen `BL-021`; do not start before the demo; do not merge #132. Docs only; no product code and no rollout-flag change in this capture.

### BL-067 - Recap Chat Misses Characters Who Are in the Chapter
- Type: Bug
- Priority: P2 (quality/regression; non-critical; not Thursday-demo work)
- Effort: M
- Status: Proposed
- Problem: After **Chapter 1** of **The Picture of Dorian Gray**, recap chat claims named characters who appear in that chapter are absent. Kevin 2026-08-19 (scripted demo test; non-critical, will not demo this Thursday) used the chapter-pause overlay **Chat** tab (“Discuss This Chapter”) and asked about Dorian and Basil. Replies:
  - “The current chapter context does not mention Dorian at all, so nothing is established about him yet.”
  - “The provided Chapter 1 excerpt does not mention Basil at all. I cannot answer yet.”
  Both are false. Dorian and Basil are in Chapter 1.
  Related symptom in the same UI: status line **“Recap chat is unavailable for this book.”** while a thread is still shown and the compose box is active.
- Timing (Kevin 2026-08-19, scripted demo test): Columbia State demo is **Thu Aug 20**. Do **not** start this before the demo. Not Frankenstein / not the Thursday demo path. Not a live Thursday change.
- Current code: `BL-017` is **Done** (bounded recap discussion + source-only / chapter-scoped guards). `ChapterRecapChatService.chat` builds `SOURCE CONTEXT` from chapter paragraphs (first-N snippets per chapter, `recap.chat.max-source-chars`) and a source-only / chapter-index prompt (`BL-017.4` / `BL-017.5`). Overlay Chat tab is “Discuss This Chapter” (`index.html`). `reader.js` `setRecapChatControls` can show **“Recap chat is unavailable for this book.”** when `!state.recapAvailable` while the compose box is gated on `state.recapChatAvailable`. This epic is a **quality/regression** on that surface, not a new feature. Do **not** reopen `BL-017`.
- Current Direction:
- Recap chat must answer from the actual chapter the reader just finished.
- Named characters who appear in that chapter must be discussable.
- Investigate (do **not** prescribe a cause): empty/wrong excerpt, Gutenberg preface mapped as Ch 1, over-aggressive spoiler/context guard, or “unavailable” path still serving a stub model.
- Keep `BL-017.4` / `BL-017.5` future-chapter spoiler guards.
- “Unavailable” and a live compose box cannot both be true.
- Out of this epic:
  - Anything before the Thu Aug 20 Columbia State demo.
  - Character-card spoiler blurbs (`BL-064`).
  - Character regen with xAI (`BL-065`).
  - Merging #132. Name-identity dedupe stays a separate code PR.
  - New-account Completed leak (`BL-068`). Recap-chat quality is not that identity/shelf bug.
  - iPhone book covers (`BL-069`). Recap-chat quality is not that cover-load bug.
  - Reopening `BL-017`. The recap + bounded-chat feature already shipped; this is quality/regression.
  - Frankenstein / the Thursday demo path.
- Scope Buckets:
- Recap chat answers from the finished chapter (Dorian Gray Ch 1 as the reported case).
- Named characters who appear in that chapter are discussable.
- Status “unavailable” vs a live thread + compose box must not contradict.
- Investigate excerpt / chapter-mapping / guard / unavailable-stub (do not pick a cause in this capture).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-067.1 Finished-chapter characters discussable | Proposed | Recap chat must use the actual chapter just finished; named characters who appear there must be discussable | After Dorian Gray Chapter 1, asking about Dorian or Basil does not get “not mentioned” / “cannot answer yet” for absence |
| BL-067.2 Unavailable vs live compose | Proposed | Status **“Recap chat is unavailable for this book.”** and an active thread + compose box cannot both be true | Overlay never shows that unavailable line while the thread is shown and compose is active |
| BL-067.3 Keep spoiler guards | Proposed | Future-chapter spoilers stay blocked (`BL-017.4` / `BL-017.5`) | Recap chat still refuses later-chapter facts after the quality fix |
- Acceptance Criteria:
- After Dorian Gray Chapter 1, recap chat can discuss Dorian and Basil from that chapter without claiming they are absent.
- “Unavailable” and a live compose box cannot both be true.
- Still no future-chapter spoilers (keep `BL-017.4` / `BL-017.5` guards).
- Dependency Notes:
- Distinct from `BL-017` (Done). `.4` / `.5` shipped bounded recap discussion + source-only / chapter-scoped guards. Do **not** reopen that epic; this is quality/regression on that surface.
- Distinct from `BL-064` (character-card spoiler blurbs + persona injection). Different surface.
- Distinct from `BL-065` (regen character discovery with xAI). Cast regen is not this chapter-context quality fix.
- Distinct from #132 (character name-identity dedupe). Do not stack this work on that PR and do not merge #132 as part of this item.
- Distinct from `BL-068` (new account inherits Completed books). Different surface.
- Distinct from `BL-069` (iPhone book covers do not load). Different surface.
- Not Frankenstein / not the Thursday demo path.
- Risks:
- Weakening source-only / chapter-index guards to “fix” Dorian/Basil would reintroduce future-chapter spoilers.
- Treating the unavailable status line as the only bug can leave the false-absence replies in place.
- Treating this as a new recap feature reopens `BL-017` instead of a bounded quality fix.
- Session Log:
- 2026-08-20: Captured from Kevin 2026-08-19 (scripted demo test; non-critical, will not demo this Thursday). After Dorian Gray Chapter 1, Chat tab (“Discuss This Chapter”): Dorian “not mentioned at all”; Basil “excerpt does not mention… I cannot answer yet.” Both false — they are in Chapter 1. Same UI: **“Recap chat is unavailable for this book.”** while a thread and compose box stayed active. Investigate empty/wrong excerpt, Gutenberg preface as Ch 1, over-aggressive spoiler/context guard, or unavailable path serving a stub; do not prescribe a cause. Keep `BL-017.4`/`.5`. Do not reopen `BL-017`; do not start before the demo; not Frankenstein / not Thursday demo; do not fold into `BL-064` / `BL-065` / #132; do not reuse `BL-066`. Docs only; no product code in this capture.

### BL-068 - New Account Inherits Completed Books It Never Opened
- Type: Bug
- Priority: P2 (backlog; not a Thursday must-fix)
- Effort: M
- Status: Proposed
- Problem: A brand-new account’s Library **Completed** shelf shows books the signed-in user has never opened on that account. Kevin 2026-08-19 (scripted demo test; backlog, not a Thursday must-fix) created **`demo_user@example.com`** and cleared **localStorage**. Completed still listed:
  - *The Cask of Amontillado* (Gutenberg **1063**)
  - *Northanger Abbey* (Gutenberg **121**)
  He has not opened those titles on this account. The same leak likely applies to **In Progress** / **Continue Reading**.
- Timing (Kevin 2026-08-19, scripted demo test): Columbia State demo is **Thu Aug 20**. Do **not** start this before the demo. Not a live Thursday change. If `demo_user` is used for the general-reader beat, **avoid the Completed shelf** or use a **fresh browser profile**.
- Current code: `BL-018` is **Done** (landing shelves including **Completed**). `BL-021.5` is **Done**: `POST /api/account/claim-sync` (`AccountClaimSyncService`) claims anonymous `reader_id` rows into the signed-in user and merges `AccountStateSnapshot` (book activity / completed flags) from the client (`reader.js` `collectLocalAccountStateSnapshot` / `applyAccountStateSnapshot`). Anonymous identity lives on the **`pdr_reader_profile`** cookie (`ReaderProfileService`, 5-year TTL) — clearing **localStorage** does not clear that cookie. Landing Completed uses `activity.completed` (`library-progress` / `library-ranking`). This epic is a **scoping/identity bug** on that merge, not a new shelf. Do **not** reopen `BL-018` or `BL-021.5`. The claim-sync merge is a **likely cause**, not a reason to skip this bug.
- Current Direction:
- A brand-new account’s **Completed** (and probably **In Progress** / **Continue Reading**) must reflect **that account’s** reading only.
- Clearing localStorage is not enough if an anonymous **`pdr` reader cookie** (`pdr_reader_profile` / session) still claim-syncs prior machine history into the new user.
- Investigate (do **not** prescribe a cause): claim-sync on register, leftover reader cookie after localStorage clear, or completed flags that are not user-scoped.
- Existing users must keep their real Completed history.
- Out of this epic:
  - Thursday demo code or deploy. Workaround: skip Completed for `demo_user`, or use a fresh browser profile.
  - Opening public / general registration (`BL-066`). That go-live switch is separate.
  - Merging #132. Name-identity dedupe stays a separate code PR.
  - Reopening `BL-018` (landing shelves / Completed row already shipped).
  - Reopening `BL-021` / `BL-021.5`. Claim-sync already shipped; this is a leftover-identity leak on that path.
  - iPhone book covers (`BL-069`). Shelf identity is not that cover-load bug.
- Scope Buckets:
- New-account Completed (and likely In Progress / Continue Reading) is empty until that user actually reads / finishes.
- Logout / login as that user stays empty until they complete something.
- Existing users do not lose real Completed history.
- Investigate claim-sync / leftover `pdr` cookie / unscoped completed flags (do not pick a cause in this capture).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-068.1 New-account shelves account-scoped | Proposed | Brand-new account Completed (and probably In Progress / Continue Reading) must show only that account’s reading | Register a new allow-listed user on a browser that previously completed books (even after localStorage clear): Completed is empty until that user finishes a book |
| BL-068.2 Empty after logout/login | Proposed | The empty Completed state must survive session recycle for that user | Logout / login as that user still empty until they complete something |
| BL-068.3 Preserve real Completed history | Proposed | Existing users keep the books they actually finished | Existing accounts still show their real Completed titles after the fix |
- Acceptance Criteria:
- Register a new allow-listed user on a browser that previously completed books (even after localStorage clear): Completed is empty until that user actually finishes a book.
- Logout / login as that user still empty until they complete something.
- Existing users do not lose their real Completed history.
- Dependency Notes:
- Distinct from `BL-018` (Done). Landing shelves / Completed row already shipped. Do **not** reopen that epic.
- Distinct from `BL-021.5` (Done). Claim-sync of anonymous cookie/local state into a new account already shipped. That merge is a **likely cause**, not a reason to skip this bug. Do **not** reopen `BL-021`.
- Distinct from `BL-066` (open general registration after Columbia State approval). Do not fold this into the go-live switch.
- Distinct from #132 (character name-identity dedupe). Do not stack this work on that PR and do not merge #132 as part of this item.
- Distinct from `BL-067` (recap chat claiming reached characters are absent). Different surface. Do not reuse `BL-067`.
- Distinct from `BL-069` (iPhone book covers do not load). Different surface. Do not reuse `BL-068` for that cover bug.
- Not Thursday-demo work. If `demo_user` is used for the general-reader beat, avoid the Completed shelf or use a fresh browser profile.
- Risks:
- Treating this as “clear localStorage” leaves the anonymous `pdr_reader_profile` cookie in place and claim-sync still copies prior machine history into the new account.
- Treating this as “reopen `BL-018`” or “reopen `BL-021.5`” invites a shelf rewrite or a new claim-sync feature instead of scoping the existing merge.
- A fix that wipes Completed for every account (or that stops claiming on every login) would drop real history for existing users.
- Session Log:
- 2026-08-20: Captured from Kevin 2026-08-19 (scripted demo test; backlog, not a Thursday must-fix). Created **`demo_user@example.com`**, cleared **localStorage**; Library **Completed** still showed *The Cask of Amontillado* (Gutenberg 1063) and *Northanger Abbey* (Gutenberg 121) — titles he has not opened on this account. Do not reopen `BL-018` or `BL-021.5`; claim-sync merge is a likely cause, not a skip. Investigate claim-sync on register, leftover `pdr` reader cookie after localStorage clear, or unscoped completed flags; do not prescribe a cause. Out of scope: Thursday demo code/deploy (`demo_user` workaround: skip Completed or fresh profile), `BL-066` opening public registration, #132. Do not reuse `BL-067`. Docs only; no product code in this capture.

### BL-069 - Book Covers Do Not Load on iPhone
- Type: Bug
- Priority: P2 (backlog; not a Thursday must-fix unless someone demos on a phone)
- Effort: S
- Status: Proposed
- Problem: Library **book cover images do not load on iPhone**. Kevin 2026-08-19 (scripted demo test; backlog, not a Thursday must-fix unless someone demos on a phone) saw this on iPhone (at least **Simulator** — iPhone 17 Pro / iOS shown at classicchatreader.com). Cards show **dark gray/black rounded placeholders** instead of covers. Same screenshot confirmed:
  - **Continue Reading:** *Pride and Prejudice* cover blank; title/text, chips, and Gutenberg **#1342** cite are fine
  - **For You:** *Alice*, *The Picture of Dorian Gray*, *Moby Dick* covers blank
  - **Character portrait loads:** Victor Frankenstein circle on the Continue chat card is fine
  - Desktop covers work (Cask / Northanger **Completed** cards earlier tonight)
- Timing (Kevin 2026-08-19, scripted demo test): Columbia State demo is **Thu Aug 20**. Do **not** start this before the demo. Not a live Thursday change unless someone demos Library on a phone. Thursday path is **desktop Frankenstein**.
- Current code: Cover-forward library shelves already shipped (2026-04-27). Cards use `book.coverUrl` via `renderBookCover` (`reader.js`): when a URL is present, `.book-cover.has-image` + `<img src="…" loading="lazy" decoding="async">`. Empty `.has-image` is `#2c2c2c` (matches the dark rounded placeholders). Cover bytes are `GET /api/library/{bookId}/cover` (`LibraryController`) — local `image/png` or 302 to CDN when `book-cover.cdn.enabled`. Character portraits use a **different** path: `GET /api/characters/{id}/portrait` (`CharacterController`). This epic is an **iOS Safari cover-load** bug on those shelves, not a missing-cover generation gap and not a shelf-IA rewrite. Do **not** reopen `BL-018`.
- Current Direction:
- Library covers must render on iPhone Safari (device or current Simulator) for **Continue Reading** and **For You** / **Completed** shelves.
- Character portraits must keep working.
- Desktop covers must stay unchanged.
- Investigate (do **not** prescribe a cause): Safari/iOS image format (webp/avif), CORS, srcset, lazy-load intersection, or a different URL path for covers vs portraits.
- Out of this epic:
  - Thursday desktop Frankenstein demo.
  - New-account Completed leak (`BL-068`).
  - Recap chat misses chapter characters (`BL-067`).
  - Character regen with xAI (`BL-065`).
  - Merging #132. Name-identity dedupe stays a separate code PR.
  - Reopening `BL-018` (cover-forward landing shelves already shipped).
- Scope Buckets:
- Continue Reading + For You / Completed covers render on iPhone Safari (device or current Simulator).
- Character portraits stay working (Victor Frankenstein circle already does).
- Desktop covers unchanged.
- Investigate format / CORS / srcset / lazy-load / cover-vs-portrait URL (do not pick a cause in this capture).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-069.1 iPhone library covers render | Proposed | Continue Reading and For You / Completed covers must load on iPhone Safari (device or current Simulator) | Pride and Prejudice (Continue Reading) and Alice / Dorian Gray / Moby Dick (For You) show real covers, not dark placeholders; Completed covers likewise |
| BL-069.2 Portraits stay working | Proposed | Character portrait path must not regress while fixing covers | Victor Frankenstein (or any ready portrait) still loads on Continue chat / character surfaces |
| BL-069.3 Desktop covers unchanged | Proposed | Desktop cover rendering stays as-is | Cask / Northanger (and other) desktop Completed / shelf covers still load |
- Acceptance Criteria:
- Library covers render on iPhone Safari (device or current Simulator) for Continue Reading and For You / Completed shelves.
- Character portraits keep working.
- Desktop covers unchanged.
- Dependency Notes:
- Distinct from `BL-018` (Done). Cover-forward landing shelves already shipped. Do **not** reopen that epic.
- Distinct from `BL-068` (new account inherits Completed books). Shelf identity is not this cover-load bug. Do not reuse `BL-068`.
- Distinct from `BL-067` (recap chat claiming reached characters are absent). Different surface.
- Distinct from `BL-065` (regen character discovery with xAI). Cast regen is not this Safari/iOS cover-load bug.
- Distinct from #132 (character name-identity dedupe). Do not stack this work on that PR and do not merge #132 as part of this item.
- Distinct from `BL-056.6` (portrait generation provider). Portraits already load in the reported screenshot; this epic is cover *display* on iOS, not portrait generation.
- Distinct from `BL-070` (teacher workspace `/teacher` first paint). iPhone cover-load is not that workspace-load bug. Do not reuse `BL-069` for the teacher slowness.
- Not Thursday-demo work unless someone demos Library on a phone. Thursday path is desktop Frankenstein.
- Risks:
- Treating this as “covers were never generated” ignores that desktop covers work and that `.has-image` already has a `coverUrl`.
- Changing the shared portrait path to “fix” covers can break the Victor Frankenstein circle that already loads.
- A desktop-only format or CDN change can “fix” Simulator while leaving device Safari broken, or the reverse.
- Session Log:
- 2026-08-20: Captured from Kevin 2026-08-19 (scripted demo test; backlog, not a Thursday must-fix unless someone demos on a phone). iPhone Simulator (iPhone 17 Pro / iOS at classicchatreader.com): book covers do not load — dark gray/black rounded placeholders. Continue Reading *Pride and Prejudice* cover blank (text/chips/Gutenberg #1342 fine); For You Alice / Dorian Gray / Moby Dick covers blank; Victor Frankenstein portrait on Continue chat loads; desktop Cask / Northanger Completed covers worked earlier tonight. Investigate Safari/iOS format (webp/avif), CORS, srcset, lazy-load intersection, or a different cover vs portrait URL path; do not prescribe a cause. Out of scope: Thursday desktop Frankenstein demo, `BL-068` Completed leak, `BL-067` recap chat, character regen (`BL-065`), #132. Do not reuse `BL-068`. Docs only; no product code in this capture.

### BL-070 - Teacher Workspace Takes ~10s to Open
- Type: Improvement
- Priority: P2 (backlog; not a same-morning Thursday fix)
- Effort: M
- Status: Proposed
- Problem: Opening **Teacher workspace** (`/teacher`) takes several seconds, almost **10 seconds**. Kevin 2026-08-20 morning (Columbia State demo day; backlog, not a same-morning fix) said it feels really slow. This is the **teacher workspace first paint / first useful UI**, not the student library.
- Timing (Kevin 2026-08-20 morning, Columbia State demo day): Demo is **Thu Aug 20 noon CT**. Do **not** start a same-morning code/deploy before the demo. Not a live Thursday change.
- Current code: `teacher.html` shows `#loading-state` (spinner) until `teacher.js` `initialize()` finishes. First wave is `Promise.all` of `/api/account/status`, `/api/classroom/capabilities`, `/api/library`, `/api/reading-buddy/status`. Then serial `/api/classroom/classes`. Then if a class exists, `selectClass` → roster / assignments / features. `teacher.js` is the workspace bundle. `BL-025.10` overview is roster-click `GET .../students/{userId}/overview`. This epic is **teacher first paint / first useful UI** on the shipped `/teacher` surface, not a `BL-025` feature rewrite. Do **not** reopen `BL-025`.
- Current Direction:
- `/teacher` should open to a usable workspace without a multi-second blank/spinner.
- Investigate (do **not** prescribe a cause): payload size, serial API waterfall, `teacher.js` bundle, classroom context, assignment list, dashboard/overview prefetch.
- Measure p50/p95 on prod as a teacher. Kevin’s current feel is ~10s.
- Student landing (`BL-059`) is a **separate measurement**, even if `/api/library` appears in both bootstraps.
- Out of this epic:
  - Same-day code or deploy before the noon Columbia State demo.
  - Opening public / general registration (`BL-066`).
  - Character regen with xAI (`BL-065`).
  - Merging #132. Name-identity dedupe stays a separate code PR.
  - Student landing / library catalog TTFB (`BL-059`). Separate measurement.
  - My Chats Open Book hop (`BL-061`).
  - Reopening `BL-025` as a workspace rewrite. Classroom UI already shipped.
- Scope Buckets:
- First useful `/teacher` UI without a multi-second blank/spinner.
- Investigate payload size / serial waterfall / `teacher.js` bundle / classroom context / assignment list / dashboard/overview prefetch (do not pick a cause in this capture).
- Documented smaller time budget with p50/p95 on prod as a teacher.
- Student landing stays `BL-059`.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-070.1 Measure teacher first useful UI | Proposed | Time `/teacher` on prod as a teacher to first interactive workspace (p50/p95). Kevin’s current feel is ~10s. Keep student landing (`BL-059`) as a separate measurement | A documented before-number exists for teacher first useful UI; student landing TTFB is not reused as this number |
| BL-070.2 Interactive workspace in a smaller budget | Proposed | Opening `/teacher` as a teacher reaches a usable workspace without a multi-second blank/spinner, against the documented budget from `.1` | Prod teacher open hits the documented smaller p50/p95 budget; first useful UI is interactive |
- Acceptance Criteria:
- Opening `/teacher` on prod as a teacher reaches an interactive workspace in a documented, much smaller budget (measure p50/p95; Kevin’s current feel is ~10s).
- Student landing (`BL-059`) is a separate measurement.
- This epic does not implement `BL-059` catalog work, `BL-061` Open Book hop, `BL-066` registration go-live, or character regen.
- Dependency Notes:
- Distinct from `BL-059` (landing library load / student catalog TTFB). That epic is the ~10–20s student landing `GET /api/library`. This is teacher first paint. Do not fold even if `/api/library` is in the teacher bootstrap.
- Distinct from `BL-061` (My Chats Open Book hop through landing). That hop is a student-reader nav defect, not `/teacher`.
- Distinct from `BL-025` (In Progress). Classroom admin / `/teacher` UI already shipped. Do **not** reopen that epic as a workspace rewrite.
- Distinct from `BL-066` (open general registration after Columbia State approval). The go-live switch is not this load.
- Distinct from `BL-065` (regen character discovery with xAI). Cast regen is not this workspace-load bug.
- Distinct from `BL-069` (iPhone book covers do not load). Different surface. Do not reuse `BL-069`.
- Distinct from #132 (character name-identity dedupe). Do not stack this work on that PR and do not merge #132 as part of this item.
- Not same-morning Thursday-demo work. Do not code or deploy this before the noon Columbia State demo.
- Risks:
- Treating this as “`BL-059` will fix it” leaves `/teacher` unmeasured if the teacher spinner waits on more than the catalog, or if the catalog is only one slice of the waterfall.
- Treating this as “reopen `BL-025`” invites a workspace rewrite instead of first-paint / first-useful-UI work on the shipped surface.
- A student-landing-only cache/slim DTO can improve `BL-059` without making `/teacher` interactive if the blank/spinner still waits on classes, roster, assignments, or the bundle.
- Session Log:
- 2026-08-20: Captured from Kevin 2026-08-20 morning (Columbia State demo day; backlog, not a same-morning fix). Opening **Teacher workspace** (`/teacher`) takes several seconds, almost **10 seconds**. Feels really slow. Distinct from `BL-059` (student landing / library catalog TTFB) and `BL-061` (My Chats Open Book hop). This is teacher first paint / first useful UI. Investigate payload size, serial API waterfall, `teacher.js` bundle, classroom context, assignment list, dashboard/overview prefetch; do not prescribe a cause. Out of scope: same-day code/deploy before the noon Columbia State demo, opening public registration (`BL-066`), character regen (`BL-065`), #132. Do not reuse `BL-069`. Docs only; no product code in this capture.

### BL-071 - Non-Gutenberg / OER / Non-Public-Domain Ingest (Discovery)
- Type: Feature / Content policy
- Priority: P2 (asked 2026-08-20; **not** a build commitment)
- Effort: L if ever built; **S** for the research answer Kevin promised Alexander
- Status: Discovery
- Problem: Columbia State group demo (Alexander Melendez, Amy McDonald, Thomasanna Hail, Jessica Evans) asked whether CCR can host **books outside Project Gutenberg / public domain**, including **OER textbooks** (astro/physics). Today the product is a **public-domain literature** reader with Gutenberg ingest + character chat. STEM OER and copyrighted literature are a different rights + ingest + product shape.
- Context (2026-08-20):
- Kevin told Alexander he would **research** — this epic is that research bucket, not a roadmap item.
- Jessica: OER is OK **if adaptation is allowed**.
- Amy named **OER licensing**. Thomasanna wants **free textbooks** and named astro/physics texts.
- `BL-052` already deferred Chopin until a rights-cleared **non-PG OER / public-domain** source exists; do not overload `.052` with OpenStax.
- Scope Buckets:
- **License taxonomy:** public domain / CC BY / CC BY-SA / CC BY-ND / CC BY-NC / CC BY-NC-SA / CC BY-NC-ND / all-rights-reserved. Adaptation ≠ commercial-use permission. **ND / NC-ND** forbid distributing adaptations (Jessica’s “must allow adaptation” fails even if attribution/commercial terms are otherwise OK).
- **STEM OER vs literature:** textbook-shaped OER (including many OpenStax titles) has no “talk to the character” hook; would be a textbook reader, not CCR’s literary product. **Do not classify OpenStax as NC** — record license **per book/edition** (e.g. Astronomy 2e is **CC BY 4.0**).
- **Character chat on non-PD fiction:** Creative Commons texts are still copyrighted. **CC BY / CC BY-SA** novels stay on the yes-path only if Discovery also clears **character voice / derivative dialogue** (share-alike may apply). **All-rights-reserved** novels are out without a publisher deal — do not treat “copyrighted” as automatically ARR.
- **Teacher PDF upload:** still out unless Kevin explicitly opens it (copyright, malware, parse quality, FERPA if student-authored).
- **Attribution / provenance (gate on any yes-path):** today’s `BookEntity` stores title/author/source/sourceId only; `BookStorageService` attribution is **Gutenberg-specific**. A CC BY / CC BY-SA title cannot ship on that stack without stored license + creator + license URI + modification notice, and **user-visible** attribution (plus share-alike notice when SA applies). Distinct from `BL-063` Gutenberg cite.
- Discovery Questions:
- Is CCR a **commercial** use of the text under CC NC clauses if the college pays $750/section? Keep this **open** — do not screen out all NC titles until the operating/payment model is reviewed.
- Would a **non-commercial campus-only** mode (no fee, attribution, no remix beyond display) ever be worth it, or does it break the pricing lean?
- Are there **CC BY / CC BY-SA** (not NC, not ND) literature/OER titles that fit ENGL 1020 leftover gaps (Chopin) without a new ingest stack?
- Is **CC BY-SA** share-alike compatible with CCR character chat (a derivative)? If not, say so explicitly; do not silently drop BY-SA from the yes-path.
- Does Alexander mean “upload any PDF” or “support Creative Commons textbooks”?
- Current Direction (2026-08-20):
- **Do not build.** Answer Alexander with: Gutenberg/public-domain remains the v1 corpus; OER is **not automatically OK**; **ND** blocks adaptation (Jessica’s bar); **NC** stays a Discovery Question (not a categorical reject); copyrighted books need rights we do not have.
- Cheap yes-path if Kevin later wants one: **public-domain / CC BY / CC BY-SA HTML** we already know how to parse — not PDF upload, not “the publisher is NC.” Check the specific title (OpenStax Astronomy 2e is CC BY, not NC). BY-SA stays on this path unless Discovery concludes share-alike is incompatible with character chat. **Do not implement the yes-path** until license/provenance is stored and shown (see Exit Criteria).
- Exit Criteria for Discovery:
- Written license answer Kevin can send (PD vs CC BY vs CC BY-SA vs CC BY-ND vs CC BY-NC vs CC BY-NC-SA vs CC BY-NC-ND vs all-rights-reserved; check **per title/edition**, not publisher).
- Explicit no on copyrighted PDF upload unless Kevin reverses.
- Decision whether Chopin/HathiTrust-style public-domain non-PG ingest is in or stays deferred on `BL-052`.
- If any CC yes-path is accepted: required **license/provenance fields** (creator, license URI, modification notice, SA terms when applicable) plus a **user-visible attribution** surface. Gutenberg-only cite (`BL-063`) is not enough.
- Dependency Notes:
- Distinct from `BL-052` (Gutenberg short-story catalog — **Done**).
- Distinct from `BL-063` (Gutenberg cite on library cards).
- Distinct from `BL-064` (character blurbs spoil later plot). Do **not** reuse `BL-064` — that ID is taken.
- Distinct from `BL-065`–`BL-070` (demo-week bugs / regen / registration / recap / Completed leak / iPhone covers / teacher first paint).
- Character-chat conduct (`BL-054`) does not grant rights to copyrighted characters.
- Session Log:
- 2026-08-20: Opened after Columbia State demo. Alexander: books outside public domain? Amy: OER. Jessica: adaptation required. Thomasanna: astro/physics OER + free textbooks. Research only.

### BL-072 - Durable Curated Catalog Membership (Table + API)
- Type: Feature / content-ops
- Priority: P2 (unlocks ccr-studio ST-008; not a student-facing UI rewrite)
- Effort: M
- Status: Proposed
- Problem: The curated landing list is a hardcoded Java list in `CuratedCatalogService`. Adding or removing a Gutenberg title requires a code change and a deploy. ccr-studio needs to curate a new book and later unlist one **without** deleting generated covers, portraits, illustrations, roster, or book text. Today those two concerns are glued together: “curated” is both “show on the landing page” and “this ID lives in source.”
- Current code:
  - `CuratedCatalogService.CURATED_BOOKS` is the membership source for `library.catalog.mode=curated` search/popular, `isCuratedGutenbergId`, and first-import feature flags (`tts` / `illustration` / `character` enabled) in `BookImportService`.
  - `BookStorageService` also treats curated Gutenberg IDs as TTS-capable even when `tts_enabled` is still false.
  - Membership is **not** a `books` column. The landing list includes titles that may not be imported yet (title/author/subjects/aliases live on the Java record).
- Current Direction:
  - Add a CCR table (e.g. `curated_books`) keyed by logical book identity `source` + `source_id` (Gutenberg number). Store the landing metadata that the Java record holds today (title, author, subjects, bookshelves, aliases, sort/popularity hint).
  - **Status** (`active` | `inactive`) controls landing/search membership only. Inactive means “not on the curated list.” It must **not** delete or regenerate artifacts, and must **not** flip `books` feature flags or wipe `book_covers` / `characters` / `illustrations`.
  - Seed the table from the current Java list so prod/local behavior is unchanged on migrate.
  - `CuratedCatalogService` reads **active** rows instead of the constant list. Keep the same search/alias behavior.
  - Admin/local API (not student UI): list, add-or-reactivate, set status. Studio will call this after `BL-072` ships; do not put the API on an unauthenticated public path.
  - Transfer later: membership rows should be promotable (export/import) so prod listing does not require a jar change. That can be a follow-on slice; first cut is local DB + API.
- Out of this epic:
  - ccr-studio UI / onboard pipeline (studio ST-008). This epic is the CCR store + API only.
  - Regenerating casts or art (`BL-065.3`). Unlist ≠ regen.
  - Reopening `BL-052` (short-story catalog content). That epic is Done; this is how membership is stored.
  - `BL-071` OER / non-PD ingest.
  - Student `/admin` or teacher catalog editor.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-072.1 Table + seed | Proposed | Flyway `curated_books` (`source`, `source_id`, landing metadata, `status`); unique `(source, source_id)`; seed from today’s Java list as `active` | Local/prod migrate; `GET` of active IDs matches the current hardcoded catalog |
| BL-072.2 Read path | Proposed | `CuratedCatalogService` (and import feature-flag check) uses active rows only | Landing curated search/popular and `isCuratedGutenbergId` ignore `inactive`; imported artifacts unchanged |
| BL-072.3 Membership API | Proposed | `GET /api/curated-books`, `POST /api/curated-books`, `PATCH /api/curated-books/{source}/{sourceId}` `{ "status": "active"\|"inactive" }`; local/ADMIN only | Studio (or curl) can list, add, and unlist without a code edit; unlist leaves covers/portraits/illos/roster intact |
| BL-072.4 Transfer (optional follow-on) | Proposed | Export/import membership rows via CacheTransferRunner (or a documented SQL/JSON path) | A newly curated local title can be listed on prod without editing Java |
- Acceptance Criteria:
  - Curated membership lives in the database, not a Java `List.of(...)`.
  - Setting a title `inactive` removes it from curated landing/search and from `isCuratedGutenbergId`, and does **not** delete book rows or generated artifacts.
  - Setting a title `active` (new or returning) lists it without regenerating art.
  - Seeded catalog after migrate matches today’s list.
  - API is not a student/teacher classroom feature.
- Dependency Notes:
  - **ccr-studio ST-008** (curated book onboarding) **depends on `BL-072.3`**. Studio must not edit `CuratedCatalogService` and should not pretend a local onboard job lists the title on the landing page until this API exists.
  - Distinct from `BL-052` (which titles were added for ENGL 1020). Do not reopen.
  - Distinct from `BL-065.3` (regen casts on curated titles). Membership ≠ regen.
  - Distinct from `BL-071` (non-Gutenberg rights). This epic is Gutenberg curated membership only.
  - Complements later studio pointer columns on `books` / artifacts (ccr-studio data-model). Those are not required for `.1`–`.3`.
- Risks:
  - Putting a `curated` boolean on `books` only would drop pre-import landing metadata (aliases/subjects) and could not unlist a title that was never imported.
  - Tying unlist to `character_enabled=false` or a roster delete would destroy artifacts — that is the failure mode this status exists to avoid.
  - Dual-read (Java list + table) after migrate will drift; cut over in `.2`, do not leave two sources of truth.
- Session Log:
- 2026-08-25: Opened from ccr-studio design. Kevin: pull curated membership into a CCR table + endpoint; status to pull books in/out of the curated list without impacting generated artifacts. Studio ST-008 depends on this. Docs only; no schema or API in this capture.

### BL-073 - Operator API to Grant/Revoke Teacher CREATE_CLASSROOM
- Type: Feature / ops
- Priority: P3 (later; studio ST-009 is a stub and needs more design)
- Effort: S
- Status: Proposed (not first-cut; do not start ahead of catalog/studio pipeline work)
- Problem: Provisioning a teacher is “account already registered, then insert/upsert `account_capabilities`.” The operator path is `scripts/manage_teacher_access.sh grant|revoke|status <email>`, which SSHs for prod credentials and runs raw SQL. There is no HTTP API. `GET /api/classroom/capabilities` only reports the **current** user’s own flags. `ClassroomTeacherCapabilityService` can read and forbid create-class; it cannot grant.
- Current code:
  - Table `account_capabilities`: unique `(user_id, capability)`; grant upserts `CREATE_CLASSROOM` / `ACTIVE`; revoke sets `REVOKED` and does **not** remove term teacher memberships.
  - Account must already exist (`users.email`). The script fails if no row.
  - No student/teacher PII is created by the script; it only flips a capability on an existing account.
- Current Direction:
  - Add an **operator/ADMIN** API (not teacher-self-serve, not classroom UI): status / grant / revoke `CREATE_CLASSROOM` by email.
  - Same semantics as the script: grant upserts ACTIVE; revoke leaves `class_role_memberships` alone; 404 if the account does not exist (do not auto-register).
  - Point `manage_teacher_access.sh` at the API once it exists (SQL remains a documented emergency fallback only).
  - Local and prod both use the API. Do not put this on an unauthenticated public path.
- Out of this epic:
  - Creating the user account (they Register / Google sign-in first). Opening public registration is `BL-066`.
  - Student roster, invite links, or teacher-adding-a-co-teacher by email (`BL-025.2` / KD-19).
  - ccr-studio UI (studio ST-009). This epic is the CCR API (+ script cutover).
  - FERPA student-record APIs (`BL-043`).
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-073.1 Grant/revoke/status API | Proposed | `GET/POST/PATCH` (or DELETE) operator routes by email; ADMIN/local only; same upsert/revoke rules as the script | Curl can grant Jessica (existing account) without SQL; missing email is 404; revoke does not drop term memberships |
| BL-073.2 Script uses API | Proposed | `manage_teacher_access.sh` calls the API; SQL path only if API unreachable and explicitly forced | README grant path no longer requires operators to think in SQL |
- Acceptance Criteria:
  - An existing account can be granted or revoked `CREATE_CLASSROOM` without a SQL file.
  - Missing account is a clear not-found, not a silent insert of a user.
  - Revoke does not delete classes or enrollments.
  - Students cannot call these routes.
- Dependency Notes:
  - **ccr-studio ST-009** (provision teacher account) **depends on `BL-073.1`** when we pick this up. Both are lower priority. Keep `manage_teacher_access.sh` until the use case is fleshed out.
  - Distinct from `BL-025.2` (teacher onboarding UI / roster). This is operator privilege, not class setup.
  - Distinct from `BL-066` (open registration). Grant does not create the login.
  - Distinct from `BL-072` (curated books).
- Risks:
  - Auto-creating a user from studio would skip Register/Google and FERPA-adjacent account rules — do not do that.
  - Exposing grant on a public unauthenticated route would let anyone become a teacher.
  - Treating revoke as “delete the teacher user” would destroy classroom history.
- Session Log:
- 2026-08-25: Opened from ccr-studio design. Kevin: provision teacher account is a studio use case; today it is SQL via `manage_teacher_access.sh`. Need an endpoint so studio (and the script) are not raw inserts. Docs only.
- 2026-08-25: Kevin: lower priority; flesh out later. Do not start ahead of catalog/studio pipeline work. Script remains the path.

### BL-074 - Per-character Portrait Request and Regenerate APIs
- Type: Feature / engine gap
- Priority: P2 (unlocks ccr-studio ST-001 per-character portraits and onboard step 4)
- Effort: S
- Status: In Progress
- Problem: Studio needs to generate or regen **one** character’s portrait (PRIMARY first, prompt edit, N samples). Today `CharacterController` only has `GET /{characterId}/portrait` and `GET .../portrait/status`. `CharacterService.generatePortrait()` is private and runs from prefetch / extraction. The only kick paths are `POST /api/characters/book/{bookId}/prefetch` (whole book, no job handle) or whole-book pregen.
- Current Direction:
  - Add the pair illustrations already have: `POST /api/characters/{characterId}/portrait/request` and `POST /api/characters/{characterId}/portrait/regenerate` with `{ "prompt" }`.
  - Gate prompt regen the same way as `illustration.allow-prompt-editing` (local on, not a prod Imagine path).
  - Keep GET image/status as they are. Do not add a product `/admin` UI.
- Out of this epic:
  - Multipart **upload** of a studio-chosen winner (that is `BL-075`).
  - Character PATCH for name/type/firstChapter (ST-004; still do not add until asked).
  - ccr-studio UI.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-074.1 Request + regenerate | Done | `POST .../portrait/request` and `POST .../portrait/regenerate { prompt }`; local/generation-gated; cache-only 409 | Studio can kick one character without whole-book prefetch |
- Acceptance Criteria:
  - A single PRIMARY can be requested or prompt-regenerated without prefetching the whole roster.
  - Prod `generation.cache-only=true` still 409s these routes.
- Dependency Notes:
  - **ccr-studio ST-001 / ST-002 / ST-008 step 4** depend on this for per-character portraits.
  - Complements `BL-075` (upload/keep). This epic generates into CCR’s live slot; upload is how studio restores a previous winner or pushes a chosen sample.
  - Distinct from `BL-065` (cast quality). This is an HTTP seam, not a regen campaign.
- Session Log:
- 2026-08-25: Opened from ccr-studio design review (`claude_01` B1). Kevin chose: ask CCR for the illustration-shaped portrait request/regen pair. Docs only.
- 2026-08-26: BL-074.1 HTTP seam — `POST /api/characters/{id}/portrait/request` and `.../regenerate { prompt }` for one PRIMARY; same generation-gate + cache-only 409 + `illustration.allow-prompt-editing` as illustrations. No Flyway, upload, PATCH, or /admin.
- 2026-08-26: Codex P1/P2 — queue request/regen afterCommit; skip cache restore when a stored prompt is pending; 409 regenerate while GENERATING.
- 2026-08-26: Codex follow-up — 409 regenerate while PENDING (second custom job before claim); 400 when prompt exceeds `portrait_prompt` VARCHAR(2000). No Flyway.
- 2026-08-26: Codex follow-up — `/portrait/request` skips cache restore when a directed prompt is already pending, so the old stable-key image cannot complete over an unclaimed regen.
- 2026-08-26: Codex follow-up — atomic `claimPortraitRegeneration` from COMPLETED/FAILED only (no Flyway). Directed-prompt guard is PENDING-only so failed auto-portraits stay requestable. GET cache headers left illustration-parity.
- 2026-08-26: Codex P2 #3 — local `GET .../portrait` revalidates with `no-cache` + ETag/Last-Modified from `completed_at` so a successful regen is not cached for 7 days. CDN `?v=` path unchanged.
- 2026-08-26: Codex follow-up — persist directed regen via `portrait_filename = __directed_regen__` (no Flyway) so recovery does not restore cache over a claimed custom job or replay a failed auto prompt as directed.
- 2026-08-26: Codex follow-up — cache restore and failed-state retry use conditional updates so they cannot overwrite a concurrent regen claim. `/portrait/request` requeues a failed directed job and keeps its prompt.
- 2026-08-26: Codex follow-up — startup recovery enqueues directed/stuck portraits from `afterCommit` so the worker does not see a still-committed GENERATING row.
- 2026-08-26: Codex follow-up — `retryFailedPortraitsForBook` also enqueues from `afterCommit`. Book delete removes the stable cache-key file for directed slots, not the marker.
- 2026-08-26: Codex follow-up — failed-portrait pregen retry uses the same conditional FAILED→PENDING claims so it cannot overwrite a concurrent regen.
- 2026-08-26: Codex follow-up — successful regen claim deletes the exact previous portrait file (canonical or legacy alias) so the marker overwrite does not orphan it.
- 2026-08-26: Codex follow-up — `/portrait/request` atomically resets COMPLETED→PENDING and requeues when the portrait file is gone, without overwriting a concurrent directed claim.
- 2026-08-26: Codex follow-up — regen deletes the prior portrait file from `afterCommit` with the enqueue, so a rolled-back claim cannot leave a 404.

### BL-075 - Portrait and Illustration Multipart Upload (Winner Write-back)
- Type: Feature / engine gap
- Priority: P2 (unlocks HTTP-only multi-sample keep/restore)
- Effort: S
- Status: Proposed
- Problem: Studio’s winner-only rule and R3 require writing bytes **into** CCR live storage (keep a sample; restore the previous winner after each sample). Covers already have `PUT /api/library/{bookId}/cover` (multipart). Portraits and illustrations have **no** upload route. Without this, a multi-sample loop leaves the last CCR overwrite live — the failure R3 exists to prevent.
- Current Direction:
  - HTTP only. Studio will **not** write CCR’s filesystem or Postgres directly.
  - Add multipart PUT/POST upload for a character portrait and a chapter illustration, same idea as cover upload: replace live bytes + metadata, leave generation status COMPLETED.
  - Accept **`source`** (studio keep/restore writes `studio`, not `manual_upload`) and prompt fields (`generated_prompt` / `prompt_override`) alongside the bytes. Optional `studio_job_id` / `studio_artifact_id` when those pointer columns exist.
  - **Content type:** `image/png` only (magic-byte check). Studio history is PNG. Do not inherit JPEG/WebP by accident; add types later if asked.
  - **Back-port the same `source` + prompt fields to `PUT /api/library/{bookId}/cover`.** Today `saveManualCover` always stamps `cover_source = "manual_upload"` and never writes prompts — provenance noise, and the transfer bundle’s cover prompt is blank. Real operator file picks can still use `manual_upload`.
  - Local/generation-gated; cache-only 409.
- Out of this epic:
  - Portrait **generation** request/regen (`BL-074`).
  - CDN/Spaces sync (still `sync_spaces.sh`).
  - Prod Imagine.
- Work Tracker (suggested):
| Slice | Status | Scope | Done When |
| --- | --- | --- | --- |
| BL-075.1 Portrait upload | Proposed | Multipart write of PNG + `source` + prompt metadata onto the character’s live portrait | Studio keep/restore of `prt-*` is HTTP-only |
| BL-075.2 Illustration upload | Proposed | Same for a chapter illustration | Studio keep/restore of `ill-*` is HTTP-only |
| BL-075.3 Cover PUT provenance | Proposed | Cover upload accepts `source` + prompt fields; studio keep is not stamped `manual_upload` | Kept covers carry the Imagine prompt and a studio source |
- Acceptance Criteria:
  - Studio can replace the live portrait or illustration without touching CCR disk/SQL itself.
  - Upload does not enqueue a new Imagine job.
  - cache-only still 409s.
  - Cover / portrait / illustration upload of a studio winner stores `source = studio` and the prompt studio sent, not a blank `manual_upload` row.
  - Non-PNG bodies are 415 / 400.
- Dependency Notes:
  - **ccr-studio ST-002** multi-sample for portraits/illustrations is **cover-only until this ships**.
  - Distinct from `BL-074` (generate). This is write-back of bytes studio already has.
- Session Log:
- 2026-08-25: Opened from ccr-studio design review (`claude_01` B2). Kevin chose: HTTP only; ask CCR for portrait/illustration upload. Docs only.
- 2026-08-25: `claude_02` N1 — cover keep already works, but stamps `manual_upload` and drops prompts. Spec `source` + prompt on BL-075 uploads and back-port to cover PUT. PNG only. Docs only.

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
- 2026-08-20: Recap-chat quality/regression (Dorian Gray Ch 1: false “Dorian/Basil not in chapter”; “unavailable” while compose stays live) is `BL-067`. Do **not** reopen this Done epic. Keep `.4` / `.5` spoiler guards.

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
