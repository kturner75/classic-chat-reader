# BL-021 Auth Architecture and Security ADR

Last updated: 2026-03-23
Status: Approved

## Context
- Current reader identity is anonymous-by-default and cookie-backed via `pdr_reader_profile`.
- Public-mode collaborator access uses `/api/auth` with an in-memory session store and should remain intact for operational access.
- Reader state is split across local browser storage and server-side data keyed by `reader_id` (not account-backed).
- BL-025 classroom workflows need durable account identity, but current BL-021 work must not break existing anonymous reader flows.

## Decisions

### 1. Keep collaborator auth and reader account auth separate
- `/api/auth` remains collaborator/admin-focused for public-mode sensitive endpoint access.
- New reader account endpoints will live under `/api/account/*` and use separate cookies/session storage.
- This avoids mixing operational auth concerns with end-user reader identity.

### 2. Launch account auth with local credentials first, then add Google OAuth on the same session model
- Initial v1 supports:
- `POST /api/account/register`
- `POST /api/account/login`
- `POST /api/account/logout`
- `GET /api/account/status`
- Follow-on support adds:
- `POST /api/account/google/start`
- `GET /api/account/google/callback`
- Google sign-in is additive, not a separate account system: provider identities resolve to the same app-level user/session model used by email/password sign-in.
- Magic-link remains deferred until account migration and reliability needs justify it.
- Anonymous reading remains supported.

### 3. Use durable DB-backed sessions for accounts
- Add `users` and `user_sessions` tables.
- Add provider/credential tables (`user_local_credentials`, `user_auth_identities`) so core user identity is not coupled to password storage.
- Session cookies are `HttpOnly`, `SameSite=Lax`, and `Secure` in non-local/public deployments.
- Store only hashed session tokens in DB (never raw token values).
- Session TTL: 30 days rolling inactivity window; logout invalidates session immediately.

### 4. Enforce baseline account security controls
- Password storage: BCrypt (`spring-security-crypto`) with work factor 12.
- Password policy for v1: minimum 10 characters.
- Login/register rate limits:
- Per-IP and per-email attempt limits on auth endpoints.
- Temporary lockout/backoff after repeated failures.
- Add structured auth audit logs with non-PII identifiers (hashed user or email fingerprints).

### 5. Introduce a shared identity resolution contract
- Reader-scoped APIs resolve principal as:
1. Authenticated account `userId` when account session exists.
2. Fallback anonymous `readerId` from `pdr_reader_profile` cookie when not authenticated.
- This allows staged migration without breaking current anonymous UX.

### 6. Define account data ownership and retention policy (v1)
- Reader-owned data: profile, progress, favorites, preferences, annotations/bookmarks, quiz attempts, and trophies.
- Retention while account is active: indefinite unless user deletes account.
- Account deletion behavior:
- Remove active sessions immediately.
- Hard-delete account-linked reader data from primary DB within 24 hours.
- Backups may retain deleted records for up to 30 days before normal backup expiry.
- Anonymous local-only state remains on device unless claimed into an account.

### 7. Roll out behind feature flags
- Stage 1: internal-only enablement and migration validation.
- Stage 2: optional production sign-up/sign-in.
- Stage 3: required account paths only where needed (for example classroom enrollment and assignment workflows).

## Migration Rules (v1)
- On first successful account sign-in/register, run one-time claim/sync:
- Favorites: union local + server, preserve deterministic order.
- Progress: keep maximum progress depth and latest activity timestamps.
- Preferences: most recently updated settings win.
- Annotations/bookmarks: merge by `(book, chapter, paragraph)` with latest `updated_at` winning.
- Quiz/trophy ownership: move to per-user scope; no cross-user sharing.
- Migration operation must be idempotent so retries do not duplicate or corrupt state.

## Non-Goals (v1)
- Additional social login providers beyond Google (for example Apple).
- Multi-tenant organization admin features.
- Full account management UI (password reset email flows) beyond minimal operator-supported recovery.

## Implementation Notes for Next Slice
- Add Flyway migration for:
- `users`
- `user_sessions`
- user ownership columns/index updates on reader-scoped tables.
- Add backend tests for:
- register/login/logout/status
- session expiry and invalidation
- rate-limit behavior
- anonymous-to-account claim behavior.
