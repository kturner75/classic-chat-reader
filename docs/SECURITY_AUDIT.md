# Security Audit — classic-chat-reader

**Date:** 2026-08-11  
**Target repo:** `kturner75/classic-chat-reader`  
**Live target:** https://classicchatreader.com  
**Scope:** OWASP Top 10–aligned review of authentication, access control, injection/XSS, SSRF, misconfiguration, secrets, client-side risks, and dependency CVEs.  
**Method:** Static review of controllers/services/config/frontend plus non-destructive live probes. No broad cleanup; this document is the deliverable backlog.

**FERPA / student-PII tracking:** A separate FERPA privacy review (same date) is tracked in product backlog epic **`BL-043`** (`docs/product/backlog.md`) with companion checklist updates in `docs/product/bl-025-classroom-data-model.md`. Do **not** duplicate that FERPA work tracker here. Overlapping findings that matter for both lanes — especially **C-01**, **H-04**, **H-07**, **M-03**, **M-04**, **L-01**, **L-02**, **L-05** — should be remediated once and accepted against both this audit and `BL-043` criteria.

---

## Executive summary

The highest-priority issue is **confirmed on production**: https://classicchatreader.com reports `publicMode: false` / `authRequired: false` with neither collaborator password nor public API key configured, so the public-mode auth interceptor never gates sensitive routes. Combined with allowlist gaps (notably unauthenticated Gutenberg import), missing security headers, trusted client `X-Forwarded-For` for rate limits, Google OAuth email auto-linking, and several `innerHTML` sinks that skip escaping, the deployment is exposed to unauthorized library mutation, cost abuse, information disclosure, and XSS. Classroom/account IDOR paths and password hashing look solid; no live secrets were found in git.

---

## Findings (severity-sorted)

| ID | Severity | Title | Backlog |
|----|----------|-------|---------|
| C-01 | Critical | Production runs with `deployment.mode=local` (auth gate disabled) | P0 |
| H-01 | High | Sensitive allowlist gaps: import + character admin routes ungated even in public mode | P0 |
| H-02 | High | SSRF via Gutendex HTML URL fetch with unrestricted redirects | P0 |
| H-03 | High | Stored/DOM XSS: book paragraphs, character names, chapter titles via `innerHTML` | P0 |
| H-04 | High | Google OAuth silently links to existing password accounts by email | P0 |
| H-05 | High | Client-controlled `X-Forwarded-For` / `X-Real-IP` bypasses rate limits | P0 |
| H-06 | High | Collaborator password login has no rate limiting / lockout | P0 |
| H-07 | High | Session cookies default to `Secure=false` | P0 |
| M-01 | Medium | Unauthenticated `/health/details` information disclosure | P1 |
| M-02 | Medium | Missing security headers (CSP, frame denial, nosniff, HSTS) | P1 |
| M-03 | Medium | Classroom invites have no expiry, max uses, or revoke API | P1 |
| M-04 | Medium | Client-supplied character chat history / reading position (prompt injection) | P1 |
| M-05 | Medium | Costly generation/TTS paths depend entirely on deployment mode + cache flags | P1 |
| M-06 | Medium | Public collaborator sessions store raw tokens in memory | P2 |
| M-07 | Medium | Anonymous reader-profile claim is first-claimer-wins on known cookie | P2 |
| L-01 | Low | No CSRF tokens; cookie APIs rely on SameSite=Lax only | P2 |
| L-02 | Low | Long-lived account sessions (~30 days) without rotation / global logout | P2 |
| L-03 | Low | Public S3/Spaces read policy covers entire bucket | P2 |
| L-04 | Low | `illustration.allow-prompt-editing` defaults to true | P2 |
| L-05 | Low | Upstream LLM error response bodies logged | P3 |
| I-01 | Informational | Spring Boot 3.4.1 is behind patched 3.4.x; most 2026 CVEs do not apply here | P2 |
| I-02 | Informational | jsoup 1.18.3 CVE-2026-71497 does not apply (no custom Safelist Cleaner) | P3 |
| I-03 | Informational | No committed secrets; `.gitignore` covers `.env*` | — |

---

### C-01 — Production runs with `deployment.mode=local` (auth gate disabled)

- **Severity:** Critical  
- **OWASP / CWE:** A01 Broken Access Control, A05 Security Misconfiguration / CWE-1188, CWE-306  
- **Location:**
  - `src/main/resources/application.properties` (~253–259): default `deployment.mode=local`, `secure-cookie=false`
  - `src/main/resources/application-prod.properties`: sets cache-only flags and account rollout, **does not** set `deployment.mode=public` or Secure cookies
  - `src/main/java/com/classicchatreader/config/PublicApiGuardInterceptor.java` (~80–150): auth only when `isPublicMode()`
- **Evidence (live, 2026-08-11):**
  - `GET https://classicchatreader.com/api/auth/status` →  
    `{"publicMode":false,"authRequired":false,"authenticated":false,"canAccessSensitive":true,"collaboratorAuthConfigured":false,"apiKeyAuthConfigured":false,...}`
  - Unauthenticated `POST /api/import/gutenberg/1` → `200` / “Book already imported”
  - Unauthenticated `POST /api/library/{bookId}/cover/request` → `202`
  - Unauthenticated `POST /api/tts/speak` reached controller (`400 No text to speak`)
  - Unauthenticated `POST /api/quizzes/chapter/{id}/generate` reached controller (`409`)
- **Impact:** Anyone who can reach the origin can invoke admin/generation/chat routes that the interceptor would otherwise protect (library delete/feature/cover mutation, pregen, TTS spend, etc.). This is an active production misconfiguration, not only a code smell.
- **Remediation:**
  1. **Immediate ops:** set `deployment.mode=public`, configure `PUBLIC_API_KEY` / collaborator password, set `security.public.session.secure-cookie=true` in `/opt/public-domain-reader/app.env` (or equivalent), restart, re-verify `/api/auth/status`.
  2. **Code fail-closed:** put `deployment.mode=public` and `security.public.session.secure-cookie=true` in `application-prod.properties`; refuse to boot public HTTPS without auth material configured.
- **Backlog priority:** P0

---

### H-01 — Sensitive allowlist gaps: import + character admin routes ungated even in public mode

- **Severity:** High  
- **OWASP / CWE:** A01 Broken Access Control / CWE-862, CWE-306  
- **Location:**
  - `src/main/java/com/classicchatreader/config/SensitiveApiRequestMatcher.java` (~62–134) — classify allowlist
  - `src/main/java/com/classicchatreader/controller/ImportController.java` (~37–48)
  - `src/main/java/com/classicchatreader/controller/CharacterController.java` (~139–183) — `DELETE /book/{bookId}`, `POST /reindex`, `POST /book/{bookId}/reindex`
- **Evidence:** Matcher returns `NONE` for import POST and character delete/reindex. Interceptor returns early for `NONE` with **no auth and no rate limit**. Live import succeeded without credentials (see C-01). Unit tests treat import as `NONE`.
- **Impact:** Unauthenticated library pollution / storage-CPU DoS via import; mass character deletion and reindex abuse even after public mode is enabled.
- **Remediation:** Classify import + character delete/reindex as `ADMIN` (or at least `GENERATION` with auth). Prefer admin API key for destructive ops. Optionally restrict imports to curated IDs when `library.catalog.mode=curated`.
- **Backlog priority:** P0

---

### H-02 — SSRF via Gutendex HTML URL fetch with unrestricted redirects

- **Severity:** High  
- **OWASP / CWE:** A10 SSRF / CWE-918  
- **Location:**
  - `src/main/java/com/classicchatreader/gutendex/GutendexClient.java` (~66–82)
  - `src/main/java/com/classicchatreader/gutendex/GutendexBook.java` (~34–82) — URL taken from third-party `formats` map
  - `src/main/java/com/classicchatreader/service/BookImportService.java` (~254–262)
- **Evidence:** `fetchContent` builds `HttpClient` with `followRedirects(ALWAYS)` and fetches the Gutendex-supplied URL with **no host allowlist**, no private-IP block, and no redirect-target validation.
- **Impact:** Server-side request to attacker-influenced URLs (metadata poisoning / open redirects), including potential access to link-local/cloud metadata or internal services. Amplifies H-01/C-01 when import is unauthenticated.
- **Remediation:** Allowlist hosts (e.g. `*.gutenberg.org`, `www.gutenberg.org`); HTTPS only; limit redirect hops and re-validate each hop; block RFC1918/link-local after DNS resolve; enforce size/timeout caps.
- **Backlog priority:** P0

---

### H-03 — Stored/DOM XSS: book paragraphs, character names, chapter titles via `innerHTML`

- **Severity:** High  
- **OWASP / CWE:** A03 Injection / CWE-79  
- **Location:**
  - `src/main/resources/static/js/reader.js` (~5177–5188) — paragraph HTML without `escapeHtml`
  - `src/main/resources/static/js/reader.js` (~8511–8527) — `renderCharacterCard` interpolates `char.name` raw (element text + `alt`)
  - `src/main/resources/static/js/reader.js` (~6479–6489) — `renderChapterList` interpolates `chapter.title` raw
  - Content source: `src/main/java/com/classicchatreader/gutendex/GutenbergContentParser.java` (~511–525) uses Jsoup `.text()` (entity-decoded plain text that may still contain `<...>`)
- **Evidence:** Chat/recap/library card paths correctly call `escapeHtml`; reading surface, character cards, and chapter list do not. Search highlighter (`highlightTermsInHtml`, ~6205) also treats existing `<...>` segments as markup.
- **Impact:** Script execution in readers’ browsers (session cookie theft for non-HttpOnly is N/A for account cookies which are HttpOnly; still enables UI hijack, credential phishing overlays, and abuse of same-origin APIs using the victim’s cookies).
- **Remediation:** Escape all dynamic text before `innerHTML` (or build with `textContent`/DOM APIs). Attribute-escape names in `alt`/`data-*`. Add CSP `script-src 'self'` (see M-02). Regression tests for `<img onerror=...>` in titles/names/paragraphs.
- **Backlog priority:** P0

---

### H-04 — Google OAuth silently links to existing password accounts by email

- **Severity:** High  
- **OWASP / CWE:** A07 Identification and Authentication Failures / CWE-287  
- **Location:** `src/main/java/com/classicchatreader/service/AccountAuthService.java` (~214–241, ~389–422)  
- **Evidence:** `resolveUserForExternalIdentity` finds-or-creates user by email and attaches a new Google identity with no password re-auth / consent step when a local credential already exists. Covered by test `signInWithExternalIdentity_existingEmail_linksIdentityAndCreatesSession`.
- **Impact:** Account takeover of any password-registered email for which an attacker can complete Google sign-in with a verified email claim (classroom teacher/student data, chats, claims).
- **Remediation:** If local credentials exist and no Google identity is linked, require password confirmation (or email magic-link) before linking. Log/alert on unexpected link creation. Consider blocking auto-link in `internal`/`optional` rollout until explicit linking UX exists.
- **Backlog priority:** P0

---

### H-05 — Client-controlled `X-Forwarded-For` / `X-Real-IP` bypasses rate limits

- **Severity:** High  
- **OWASP / CWE:** A04 Insecure Design / CWE-807  
- **Location:**
  - `src/main/java/com/classicchatreader/config/PublicApiGuardInterceptor.java` (~161–178)
  - `src/main/java/com/classicchatreader/service/AccountAuthRateLimiter.java` (~103–122)
- **Evidence:** Both take the first `X-Forwarded-For` hop (then `X-Real-IP`) with no trusted-proxy gate.
- **Impact:** Attackers rotate spoofed IPs to bypass public API and account register/login rate limits → credential stuffing and paid-API abuse.
- **Remediation:** Only honor forwarded headers behind a known proxy (`server.forward-headers-strategy` + trusted IPs), or strip client XFF at nginx and use the connection peer. Prefer Spring’s forwarded-header filter with explicit trust.
- **Backlog priority:** P0

---

### H-06 — Collaborator password login has no rate limiting / lockout

- **Severity:** High  
- **OWASP / CWE:** A07 / CWE-307  
- **Location:**
  - `src/main/java/com/classicchatreader/controller/AuthController.java` (~37–59)
  - `SensitiveApiRequestMatcher` does not classify `/api/auth/*`
- **Evidence:** `/api/auth/login` compares a shared password and issues a session cookie; no `AccountAuthRateLimiter`-style limiter or lockout. Live status showed collaborator auth not configured today; risk applies once enabled in public mode.
- **Impact:** Online brute-force of `PUBLIC_COLLABORATOR_PASSWORD` yields access to all session-gated generation/chat routes.
- **Remediation:** IP (+ global) rate limits and progressive delay/lockout on `/api/auth/login`; high-entropy secret; constant-time compare (already present in session service path for API key—ensure password path matches).
- **Backlog priority:** P0

---

### H-07 — Session cookies default to `Secure=false`

- **Severity:** High (when HTTPS is served without override)  
- **OWASP / CWE:** A02 Cryptographic Failures / CWE-614  
- **Location:**
  - Defaults: `application.properties` (~259, ~283)
  - Writers: `AccountAuthService`, `PublicSessionAuthService`, `GoogleAccountOAuthService`, `ReaderProfileService` cookie builders (`secure(secureCookie)` with default false)
- **Evidence:** HttpOnly + SameSite=Lax are set correctly; Secure depends on config defaulting to false. Prod profile does not override.
- **Impact:** Session/profile cookies may be issued without the Secure flag on HTTPS deployments that forget the env override, enabling interception on any HTTP downgrade/mixed path.
- **Remediation:** Default Secure to true in prod; or set Secure dynamically when `request.isSecure()` / `X-Forwarded-Proto=https`.
- **Backlog priority:** P0 (with C-01)

---

### M-01 — Unauthenticated `/health/details` information disclosure

- **Severity:** Medium  
- **OWASP / CWE:** A01 / CWE-200  
- **Location:** `src/main/java/com/classicchatreader/controller/HealthController.java` (~85–130)  
- **Evidence (live):** Public JSON includes provider readiness, queue depths/processor state, generation totals, quiz/recap metrics, and account auth rollout flags (`accountAuthEnabled`, `rolloutMode`, etc.). `/health` alone is appropriate.
- **Impact:** Recon for attack timing, feature posture, and auth rollout state.
- **Remediation:** Require admin API key or network allowlist for `/health/details`; keep `/health` as the public probe.
- **Backlog priority:** P1

---

### M-02 — Missing security headers (CSP, frame denial, nosniff, HSTS)

- **Severity:** Medium  
- **OWASP / CWE:** A05 Security Misconfiguration / CWE-693  
- **Location:** No Spring Security filter chain (`pom.xml` only has `spring-security-crypto` + `oauth2-jose`); nginx responses on classicchatreader.com lack CSP / `X-Frame-Options` / `X-Content-Type-Options` / HSTS.
- **Evidence (live):** `curl -sI https://classicchatreader.com/` shows none of the common security headers.
- **Impact:** Weaker XSS mitigation and clickjacking risk on auth/teacher UI.
- **Remediation:** Add CSP (`default-src 'self'`; tight `script-src`), `frame-ancestors 'none'`, `nosniff`, HSTS at nginx and/or a Spring filter.
- **Backlog priority:** P1

---

### M-03 — Classroom invites have no expiry, max uses, or revoke API

- **Severity:** Medium  
- **OWASP / CWE:** A01 / CWE-613  
- **Location:**
  - `ClassroomAdminService` invite issue calls with `null` maxUses/expiresAt
  - `InviteLinkService` supports expiry/maxUses/revocation in the model
  - `ClassroomController` exposes create/redeem only
- **Evidence:** Codes are 128-bit and stored hashed (good), but issued unbounded and never revocable via API.
- **Impact:** Leaked invite codes remain valid indefinitely for enrollment.
- **Remediation:** Default TTL + optional maxUses; teacher revoke endpoint; rotate links.
- **Backlog priority:** P1

---

### M-04 — Client-supplied character chat history / reading position (prompt injection)

- **Severity:** Medium  
- **OWASP / CWE:** A03 Injection / LLM01 Prompt Injection  
- **Location:** `CharacterController.java` chat/call-session (~288–405); `CharacterChatService`  
- **Evidence:** Request body supplies `conversationHistory`, `readerChapterIndex`, `readerParagraphIndex` into persona prompts. Reading Buddy intentionally ignores client history; character chat does not.
- **Impact:** Spoiler bypass, persona jailbreaks, poisoned history influencing model output / cost.
- **Remediation:** Prefer server-side history (as Reading Buddy); clamp position to known progress; harden system prompt; ensure chat routes stay rate-limited after C-01 fix.
- **Backlog priority:** P1

---

### M-05 — Costly generation/TTS paths depend entirely on deployment mode + cache flags

- **Severity:** Medium (High while C-01 remains open)  
- **OWASP / CWE:** A01 / CWE-770  
- **Location:** `TtsController`, `IllustrationController`, `PreGenerationController`, `SensitiveApiRequestMatcher` (TTS GET speak path not classified); prod profile sets `tts.cache-only=true` / `generation.cache-only=true` but live auth mode is still local.
- **Evidence:** Live unauthenticated cover request returned `202`; TTS POST reached business logic.
- **Impact:** API spend / DoS against OpenAI/ComfyUI/xAI if cache-only is flipped or bypassed.
- **Remediation:** Never deploy `local` publicly; rate-limit TTS GET; keep cache-only in prod; add auth even for cache-miss paths.
- **Backlog priority:** P1

---

### M-06 — Public collaborator sessions store raw tokens in memory

- **Severity:** Medium  
- **OWASP / CWE:** A02 / CWE-312  
- **Location:** `PublicSessionAuthService.java` — `ConcurrentHashMap<String, Long> sessions` keyed by raw token  
- **Evidence:** Account sessions correctly store SHA-256 hashes (`AccountAuthService` / `UserSessionEntity`); collaborator sessions do not.
- **Impact:** Memory dump / debug exposure yields usable tokens; multi-instance invalidation is inconsistent.
- **Remediation:** Store only token hashes; consider durable/shared store for multi-instance.
- **Backlog priority:** P2

---

### M-07 — Anonymous reader-profile claim is first-claimer-wins on known cookie

- **Severity:** Medium  
- **OWASP / CWE:** A01 / CWE-639  
- **Location:** `ReaderProfileService` (accepts cookie value); `AccountClaimSyncService` claim move; DB unique is on `(user, reader)` not `reader_id` alone  
- **Evidence:** Knowing/setting `pdr_reader_profile` lets another account claim unclaimed anonymous progress. UUID entropy makes guessing hard; theft/shared-device is the realistic path.
- **Impact:** Theft of another browser’s unclaimed annotations/quiz/buddy history after cookie disclosure.
- **Remediation:** Treat reader cookie as a secret (Secure + binding); optional one-time claim nonce; refuse invalid formats.
- **Backlog priority:** P2

---

### L-01 — No CSRF tokens; cookie APIs rely on SameSite=Lax only

- **Severity:** Low  
- **OWASP / CWE:** A01 / CWE-352  
- **Location:** Account/classroom cookie-authenticated mutating APIs; no Spring Security CSRF  
- **Evidence:** Cookies use `SameSite=Lax`, which blocks most cross-site POSTs in modern browsers. No double-submit/CSRF header.
- **Impact:** Residual CSRF for older clients or future SameSite changes.
- **Remediation:** Require custom header (e.g. `X-Requested-With` / CSRF token) on cookie-authenticated mutating APIs.
- **Backlog priority:** P2

---

### L-02 — Long-lived account sessions (~30 days) without rotation / global logout

- **Severity:** Low  
- **OWASP / CWE:** A07 / CWE-613  
- **Location:** `AccountAuthService` — TTL default 43200 minutes; no rotate-on-login / delete-all-sessions  
- **Impact:** Stolen cookie remains valid up to ~30 days; no user “sign out everywhere”.
- **Remediation:** Sliding expiry + absolute cap; rotate on login/privilege change; invalidate on password change; “sign out everywhere”.
- **Backlog priority:** P2

---

### L-03 — Public S3/Spaces read policy covers entire bucket

- **Severity:** Low (Informational if assets-only by design)  
- **OWASP / CWE:** A01 / CWE-552  
- **Location:** `public-read-policy.json`  
- **Evidence:** `s3:GetObject` for `arn:aws:s3:::classic-chat-reader/*` to `Principal: *`.
- **Impact:** Any object in that bucket is world-readable if this policy is applied.
- **Remediation:** Scope to `assets/` prefix; keep secrets/backups out of the bucket.
- **Backlog priority:** P2

---

### L-04 — `illustration.allow-prompt-editing` defaults to true

- **Severity:** Low  
- **OWASP / CWE:** A05 Misconfiguration  
- **Location:** `application.properties` (~106); `IllustrationController` prompt get/regenerate  
- **Impact:** Prompt injection into image pipeline / GPU abuse when auth is misconfigured.
- **Remediation:** Default `false` in shared/prod configs.
- **Backlog priority:** P2

---

### L-05 — Upstream LLM error response bodies logged

- **Severity:** Low  
- **OWASP / CWE:** A09 Logging Failures / CWE-532  
- **Location:** `XaiRealtimeSessionService.java` (~111–114)  
- **Impact:** Upstream error payloads may land in logs.
- **Remediation:** Log status + truncated/redacted body.
- **Backlog priority:** P3

---

### I-01 — Spring Boot 3.4.1 is behind patched 3.4.x; most 2026 CVEs do not apply here

- **Severity:** Informational (upgrade still recommended)  
- **OWASP / CWE:** A06 Vulnerable Components  
- **Location:** `pom.xml` parent `spring-boot-starter-parent` `3.4.1`  
- **Applicability notes:**
  - **CVE-2026-40976** (auth bypass via Actuator/Security defaults): **not applicable** — no `spring-boot-starter-security` / Actuator on classpath.
  - **CVE-2026-40972** (DevTools remote secret timing): **not applicable** — DevTools not depended on.
  - **CVE-2026-40975** (`${random.value}` weak PRNG): **not applicable** — property not used.
  - **CVE-2026-40977** (PID file symlink): **not applicable** — `ApplicationPidFileWriter` not configured.
  - **CVE-2026-40973** (ApplicationTemp): requires local attacker + typically `server.servlet.session.persistent=true`; app uses custom cookie sessions — **low practical exposure**, still upgrade.
- **Remediation:** Upgrade Spring Boot to latest 3.4.x / supported line (e.g. ≥ 3.4.16 when available in your channel) as routine hygiene.
- **Backlog priority:** P2

---

### I-02 — jsoup 1.18.3 CVE-2026-71497 does not apply

- **Severity:** Informational  
- **Details:** CVE-2026-71497 affects custom `Safelist` + `Cleaner` with raw-text elements. This codebase uses Jsoup for parse/`.text()` extraction and does not call `Jsoup.clean` / custom Safelists. Upgrade opportunistically to ≥ 1.23.1.
- **Backlog priority:** P3

---

### I-03 — No committed secrets found

- **Severity:** Informational  
- **Evidence:** `.gitignore` ignores `.env` / `.env.*` (allows `*.example`); examples use placeholders; no hardcoded production API keys found in source. npm audit on Playwright e2e deps reported 0 vulnerabilities.
- **Remediation:** Keep secret scanning in CI; ensure `./data/xai-oauth-refresh-token` never ships in artifacts.

---

## Suggested backlog order

| Priority | Items | Goal |
|----------|-------|------|
| **P0** | C-01, H-01, H-02, H-03, H-04, H-05, H-06, H-07 | Stop unauthenticated production access; close authz/XSS/SSRF holes |
| **P1** | M-01 … M-05 | Reduce recon, headers, invite abuse, LLM/cost exposure |
| **P2** | M-06, M-07, L-01 … L-04, I-01 | Session hardening, CSRF defense-in-depth, dependency bump |
| **P3** | L-05, I-02 | Logging hygiene, opportunistic jsoup bump |

---

## Out of scope / not confirmed

| Lead | Result |
|------|--------|
| Classroom term/assignment IDOR | **Not confirmed** — `ClassroomAuthorizationService` deny-by-default; teacher ops use `requireTeacher` / ownership checks. |
| Student → teacher via invite | **Not confirmed** — redeem creates `STUDENT` only; staff hits `ALREADY_STAFF`. |
| Create-class privilege escalation | **Not confirmed** — requires durable `CREATE_CLASSROOM` capability. |
| Account chat session IDOR | **Not confirmed** — owner-scoped queries (`findByIdAndUserId`). |
| Admin delete via collaborator session (when public mode on) | **Not confirmed as designed** — `ADMIN` requires API key only; residual risk is C-01 (local mode). |
| Password storage | **OK** — BCrypt strength clamped; account login lockout present. |
| OAuth redirect / state / PKCE / nonce | **OK** — state compare, PKCE S256, nonce, issuer/audience/expiry; `returnTo` sanitized to relative paths. |
| Invite code storage | **OK** — SHA-256 hashed, 128-bit SecureRandom. |
| Cookie HttpOnly / SameSite | **OK** — HttpOnly + SameSite=Lax on auth/profile cookies (Secure is the gap: H-07). |
| SQL / command / template injection | **Not confirmed** — parameterized Spring Data / static SQL; no user-influenced `Runtime.exec`; no server HTML templates. |
| Path traversal on asset reads | **Not confirmed** — `ComfyUIService.safeResolve` / `AssetKeyService` normalization. |
| Open redirect (OAuth / CDN) | **Not confirmed** — `sanitizeReturnTo`; CDN URLs from configured base + sanitized keys. |
| CORS wildcard | **Not confirmed** — no permissive `CorsConfiguration`; live OPTIONS from foreign origin did not grant ACAO. |
| Spring Actuator / H2 console | **Not present / disabled** (`spring.h2.console.enabled=false`). |
| Hardcoded production secrets in repo | **Not found**. |
| jsoup CVE-2026-71497 exploitability | **Not applicable** (see I-02). |
| Spring Boot CVE-2026-40976 / DevTools CVEs | **Not applicable** (see I-01). |
| Full library wipe via live `DELETE /api/library` | **Not confirmed completed** — unauthenticated DELETE is reachable in local mode by code; a probe received nginx `504` and subsequent `GET /api/library` still returned books. Treat as **reachable/dangerous**, not as proven data loss. |

---

## Live verification notes (non-destructive)

Performed against https://classicchatreader.com on 2026-08-11:

| Probe | Result |
|-------|--------|
| `GET /api/auth/status` | `publicMode=false`, no API key / collaborator auth configured |
| `GET /health` | `{"status":"ok"}` |
| `GET /health/details` | Full metrics/provider/queue/account rollout JSON |
| `POST /api/import/gutenberg/1` | Unauthenticated success path (“already imported”) |
| `POST /api/library/{id}/cover/request` | `202` without auth |
| `POST /api/tts/speak` | Reached controller without auth |
| Response headers | No CSP / HSTS / X-Frame-Options / nosniff |

**Ops note:** After enabling public mode, re-test the same probes and expect `401`/`503` on sensitive routes until keys are configured.

---

## Appendix — key control map

| Control | Mechanism | Gap |
|---------|-----------|-----|
| Public API auth | `PublicApiGuardInterceptor` + `SensitiveApiRequestMatcher` | Opt-in via `deployment.mode=public`; allowlist incomplete |
| Account auth | Cookie sessions (hashed), BCrypt, Google OAuth | OAuth auto-link; Secure default; long TTL |
| Collaborator auth | Shared password → in-memory session | No rate limit; raw token storage |
| Rate limits | In-memory / DB fixed windows | Trust spoofable XFF |
| Classroom authz | Capability + membership checks | Invite lifetime |
| XSS | Ad-hoc `escapeHtml` in many UI paths | Reader surface / character cards / chapter list |
| Secrets | Env placeholders + gitignore | Prod fail-open defaults |
