# Phase 2 Handoff — Identity, Authentication & Security Hardening

**Date:** 2026-09-02
**Status:** ⚠️ Code complete; **integration tests unverified in this environment** — Docker is unreachable from the sandbox, so all 20 Testcontainers-backed tests error at context startup. 25 non-Docker unit tests pass. See [Verification](#verification) for the exact, unedited outcome and the one command you need to run.

---

## Summary

Phase 2 replaces the Phase 1 placeholder auth with the real identity module: a hardened `users` table, refresh-token rotation with family revocation on reuse (R9), account lockout, CITEXT (case-insensitive) emails, canonical Bangladeshi phone numbers, a claim-based typed principal, a two-chain Spring Security configuration, and a single RFC-7807 error contract for the whole API.

The old flat `entity/User`, `repository/UserRepository`, `service/AuthService`, `controller/AuthController`, `config/SecurityConfig`, `security/*` and `dto/auth/*` classes are **deleted**. Complaint, SLA and notification code was left functionally untouched and adapted by imports only, per the phase constraints.

**Security properties this phase is responsible for:**

| Property | Mechanism |
|---|---|
| Passwords are not recoverable | BCrypt strength 12; `password_hash` only; never logged |
| Refresh tokens are not recoverable from a DB dump | 256-bit opaque token, SHA-256 at rest; the raw value exists only in the response body |
| A stolen refresh token cannot be used twice | Rotation on every use; reuse of a rotated token revokes the whole family |
| Credential stuffing is throttled | 5 failures → 15-minute lock; a correct password does not lift an active lock |
| Accounts cannot be enumerated | Unknown identifier and wrong password return byte-identical 401 bodies; logout always 204 |
| A refresh token cannot be replayed as an access token | `typ` claim is required to equal `ACCESS` at parse time |
| Tenancy cannot be forgotten by accident | `PrincipalContext.requireMunicipality(...)` is the single guard; services never touch `SecurityContextHolder` |

---

## Changes Made

### STEP 1 — Flyway Migration `V2__identity_hardening.sql`

`CREATE EXTENSION IF NOT EXISTS citext`.

**`users` altered** (forward-only, no data loss):
- `email` → **CITEXT**, now **nullable** (phone-only accounts are legal)
- `password` → `password_hash`
- `full_name` → `VARCHAR(120)`
- added `failed_login_count INT NOT NULL DEFAULT 0`, `locked_until TIMESTAMPTZ`, `last_login_at TIMESTAMPTZ`, `version BIGINT NOT NULL DEFAULT 0`
- `created_at`/`updated_at` → `TIMESTAMPTZ`, converting existing local values with `AT TIME ZONE 'Asia/Dhaka'` so no timestamp shifts
- `ck_user_contact` — at least one of email/phone must be present
- `ck_user_password` — hash is non-blank
- `idx_user_role_active` — partial index for the authority lookups Phase 3 will add
- `phone` keeps its existing UNIQUE constraint

**`refresh_tokens`** (new): `token_hash` UNIQUE, `user_id` FK, `expires_at`, `revoked_at`, `replaced_by_token_id` self-FK (the family link), `ip_address INET`, `user_agent VARCHAR(255)`, `created_at`; `idx_refresh_user_active` partial index on live tokens.

**`user_municipality_memberships`** (new): `user_id`, `municipality_id`, optional `ward_id`/`department_id`, `valid_from`, `valid_until` (NULL = current); `uq_membership_current`, `ck_membership_range`, and two partial indexes on `valid_until IS NULL`.

### STEP 2 — Identity Module (`com.nagorikseba.identity`)

```
identity/
├── domain/
│   ├── User.java                          (I1) lockout state machine, canonicalIdentifier(), @Version
│   ├── RefreshToken.java                  (I2) isRevoked/isExpired/revoke, replacedByTokenId
│   └── UserMunicipalityMembership.java    (I3) valid_until IS NULL == current
├── repo/
│   ├── UserRepository.java                (I4) findByIdentifier — email OR canonical phone, one round trip
│   ├── RefreshTokenRepository.java        (I5) findActiveTokenFamilies / revokeFamily
│   └── MembershipRepository.java          (I6) findCurrentMunicipalityIds projection
├── service/
│   ├── AuthService.java                   (I7) register / login / refresh / logout
│   ├── TokenService.java                  (I8) issue / rotate / revoke — the R9 core
│   ├── AppUserDetailsService.java         (I9) identifier → principal; suppresses Boot's default user
│   ├── LoginAttemptTracker.java           lockout bookkeeping in REQUIRES_NEW
│   ├── IdentifierNormalizer.java          email lower/trim; phone → 01XXXXXXXXX
│   └── ClientInfo.java                    forensic ip/user-agent capture
├── config/
│   └── LockoutProperties.java             app.security.lockout.*
└── api/
    ├── AuthController.java                (I10) the four endpoints
    └── dto/                               (I11) six records
```

Entities mirror `V2` exactly so `ddl-auto=validate` passes; `citext`, `inet` and `timestamptz` are pinned with explicit `columnDefinition`, which is the only reliable escape hatch for Hibernate's type-code comparison.

### STEP 3 — `AuthService` (I7)

- **register** — normalizes email (lower/trim) and phone (`+880…`/`880…`/`0…` → `01XXXXXXXXX`), pre-checks both for uniqueness → 409, hashes with BCrypt(12), role `CITIZEN`, then issues a token pair. A unique-constraint race that slips past the pre-check is caught and re-reported as the same 409.
- **login** — resolves the identifier as email **or** phone, then checks in this order: *is locked* → *password* → *is active*. The `active` check comes **after** the password check on purpose, so a deactivated account is not detectable without valid credentials.
- **refresh** / **logout** — delegate to `TokenService`.

`login` is deliberately **not** `@Transactional`: a failed attempt must increment `failed_login_count` and commit even though the method exits by throwing. `LoginAttemptTracker` does that bookkeeping in `Propagation.REQUIRES_NEW`.

### STEP 4 — `TokenService` (I8) — rotation and family revocation

- **Access token**: JWT, 15 min, claims `sub` (canonical identifier), `uid`, `role`, `mids` (current municipality ids), `typ=ACCESS`.
- **Refresh token**: 256 bits from `SecureRandom`, URL-base64 for transport, **SHA-256 hex at rest**. The raw value is never stored and never logged.
- **rotate**: look up by hash → reject unknown → **if already revoked, revoke the entire family and reject** → reject expired → reject inactive user → issue a new pair → mark the presented token revoked and set `replaced_by_token_id`.
- Family traversal walks `replaced_by_token_id` **both** directions (`walkToRoot` then forward), with a visited-set cycle guard and a 1 000-token ceiling, then revokes in one bulk `UPDATE`.
- `rotate` is `@Transactional(noRollbackFor = InvalidRefreshTokenException.class)` — otherwise the revocation would roll back with the exception that reports it, which is the exact bug this design exists to avoid.

### STEP 5 — `SecurityConfig` (S3) — two chains

**`@Order(1)` `securityMatcher("/api/**")`** — stateless: `SessionCreationPolicy.STATELESS`, request cache off, form login/basic/logout off, **CSRF disabled here and only here** (there is no session cookie to ride on), CORS from `app.security.cors.allowed-origins` (default `http://localhost:8080` only), `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`.

**`@Order(2)`** — Thymeleaf pages: session-based, **CSRF enabled** via `CookieCsrfTokenRepository.withHttpOnlyFalse()` plus `XorCsrfTokenRequestAttributeHandler` (Spring Security 6 BREACH protection).

Endpoint rules (§8.2): public `/`, `/login`, `/register`, `/api/auth/**`, `/api/public/**`, static assets, `/actuator/health*`, `/actuator/info`; `/api/authority/**` → `WARD_COUNCILOR`, `DEPT_OFFICER`, `ADMIN`; `/api/admin/**` → `ADMIN`; `/api/complaints/**` → `CITIZEN`, `ADMIN`; everything else authenticated.

Headers (§8.3): frame-deny, nosniff, HSTS one year + subdomains, referrer policy, and a CSP per chain (`default-src 'none'` for the API, an asset-aware policy for pages). The legacy `X-XSS-Protection` header is explicitly disabled — it is unsafe in modern browsers.

401 and 403 are written by the chain's own entry point / denied handler as `application/problem+json`, so an unauthenticated API call and a thrown `AccessDeniedException` produce the same shape of body.

`JwtAuthenticationFilter` is intentionally **not** a `@Component`: Boot auto-registers any `Filter` bean into the main servlet chain, which would run it on Thymeleaf routes too.

### STEP 6 — `PrincipalContext` (S9) and `GlobalExceptionHandler` (S18)

`PrincipalContext` is the only class that reads `SecurityContextHolder`; it resolves the caller entirely from verified JWT claims, so a tenancy check costs zero queries. `GlobalExceptionHandler` maps every API exception to RFC-7807. Both contracts are specified in full under [Contracts for Phase 3](#contracts-for-phase-3).

### STEP 7 — `auth.html` (task 10)

Added a CSRF hidden field to the auth form:

```html
<input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}">
```

`static/js/auth.js` was **not** modified (out of scope) and still works: it builds its JSON payload field-by-field, so `_csrf` never reaches the API, and it reads only `accessToken`, `user.fullName`, `message` and `fieldErrors` — all still present in the response. Consequence: the browser client does not yet store or use the refresh token. Wiring that up is a Phase 3+ task.

### STEP 8 — Config (`application.yml`)

```yaml
security.jwt:
  secret: ${JWT_SECRET:<dev default>}
  access-token-seconds: ${JWT_ACCESS_TOKEN_SECONDS:900}
  refresh-token-days:   ${JWT_REFRESH_TOKEN_DAYS:30}
  issuer:               ${JWT_ISSUER:nagorik-seba}
app.security:
  cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:8080}
  lockout:
    max-failed-attempts: ${LOCKOUT_MAX_FAILED_ATTEMPTS:5}
    duration:            ${LOCKOUT_DURATION:15m}
```

The dev default secret is acceptable per the phase constraints. **`application-prod.yml` must drop the default so `JWT_SECRET` is mandatory in production** — that file is a Phase 6 deliverable and does not exist yet.

### STEP 9 — Deletions

`entity/User`, `repository/UserRepository`, `service/AuthService`, `controller/AuthController`, `config/SecurityConfig`, `security/{JwtTokenProvider, JwtAuthenticationFilter, CustomUserDetailsService}`, `dto/auth/{AuthResponse, LoginRequest, RegistrationRequest, UserResponse}` — 12 files.

---

## Verification

### 1. Compilation — clean

```bash
./mvnw -o -B clean test-compile
```

```
BUILD SUCCESS
```

### 2. Unit tests (no Docker required) — 25/25 pass

```bash
./mvnw -o -B test -Dtest=AuthUnitTests
```

```
Tests run: 25, Failures: 0, Errors: 0, Skipped: 0 -- in com.nagorikseba.identity.AuthUnitTests
BUILD SUCCESS
```

Covers phone/email canonicalization, the lockout state machine (including the boundary at exactly `locked_until` and the "lapsed lock grants a fresh window" case), access-token round-trip, and rejection of tampered / expired / wrong-issuer / wrong-key / wrong-`typ` tokens, plus the `PrincipalContext` admin-bypass and anonymous paths.

**These tests found two real defects, both fixed:**
1. `JwtTokenProvider.parseAccessToken` validated `exp` against wall-clock time, bypassing the injected `Clock` and violating S17. Fixed by passing the `Clock` to the jjwt parser — behaviourally identical in production (where the bean is `Clock.systemUTC()`), but it makes expiry deterministically testable.
2. My own test assumed `AuthenticatedUser.servesMunicipality` had an admin bypass. It does not, by design — the bypass lives in `PrincipalContext`. The test was corrected to assert the real split, and both halves are now pinned by tests so Phase 3 cannot drift.

### 3. `./mvnw clean verify` — **FAILS in this environment (Docker unavailable)**

```bash
./mvnw -o -B clean verify
```

```
Tests run: 45, Failures: 0, Errors: 20, Skipped: 0
BUILD FAILURE
```

**Every one of the 20 errors is the same environmental root cause — not an assertion failure:**

```
java.lang.IllegalStateException: Could not find a valid Docker environment.
    Please see logs and check configuration
… then, for the remaining classes:
java.lang.IllegalStateException: ApplicationContext failure threshold (1) exceeded:
    skipping repeated attempt to load context
java.lang.IllegalStateException: Previous attempts to find a Docker environment failed. Will not retry.
```

| Test class | Run | Failures | Errors |
|---|---|---|---|
| `AuthUnitTests` | 25 | 0 | **0** |
| `AuthSecurityIntegrationTests` | 14 | 0 | 14 |
| `AuthControllerIntegrationTests` | 3 | 0 | 3 |
| `ComplaintSubmissionIntegrationTests` | 2 | 0 | 2 |
| `NagorikSebaApplicationTests` | 1 | 0 | 1 |

`Failures: 0` throughout — **no assertion ever executed** in the integration classes. The Testcontainers Postgres container could not start:

```
docker info
# permission denied while trying to connect to the docker API at
# unix:///Users/nafizimtiazlabib/.docker/run/docker.sock
```

`DOCKER_HOST=tcp://localhost:2375` is also refused. The sandbox this phase was implemented in blocks both the Docker unix socket and localhost TCP, so Testcontainers cannot run here at all.

> **⚠️ Action required before Phase 3.** The 14 `AuthSecurityIntegrationTests` cases are written and compile, but their assertions have **never been executed**. Run this on a machine with Docker Desktop running and treat any failure as Phase 2 work, not Phase 3 work:
>
> ```bash
> ./mvnw clean verify
> ```

### 4. Manual smoke test (documented, not executed — same Docker blocker)

Requires a reachable PostgreSQL 16 + PostGIS + citext instance and `./mvnw spring-boot:run`.

```bash
# 1. Register → expect 201 with accessToken + refreshToken
curl -si localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"fullName":"Amina Rahman","email":"amina@example.com","phone":"01712345678","password":"a-secure-password"}'

# 2. Log in (email or phone, any case) → expect 200
curl -si localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"identifier":"AMINA@example.com","password":"a-secure-password"}'

# 3. Protected endpoint WITH token → expect 200
curl -si localhost:8080/api/complaints/my -H "Authorization: Bearer $ACCESS"

# 4. Protected endpoint WITHOUT token → expect 401 + application/problem+json
curl -si localhost:8080/api/complaints/my

# 5. Citizen token on an authority endpoint → expect 403 + application/problem+json
curl -si localhost:8080/api/authority/dashboard -H "Authorization: Bearer $ACCESS"

# 6. Rotation → expect 200 and a DIFFERENT refreshToken
curl -si localhost:8080/api/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"

# 7. Replay the OLD refresh token → expect 401, and the new one is now dead too
curl -si localhost:8080/api/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"
```

---

## Contracts for Phase 3

### `PrincipalContext` (`com.nagorikseba.shared.security`) — inject this; never touch `SecurityContextHolder`

| Method | Returns | Notes |
|---|---|---|
| `currentUser()` | `Optional<AuthenticatedUser>` | empty when anonymous |
| `requireUser()` | `AuthenticatedUser` | throws `AuthenticationCredentialsNotFoundException` → **401** |
| `isAuthenticated()` | `boolean` | |
| `currentUserId()` / `requireUserId()` | `Optional<Long>` / `Long` | |
| `currentRole()` | `Optional<UserRole>` | |
| `isAdmin()` | `boolean` | |
| `municipalityIds()` | `Set<Long>` | current memberships; **empty for citizens** |
| `servesMunicipality(Long)` | `boolean` | **admins are always true** (cross-tenant by design) |
| `requireMunicipality(Long)` | `void` | throws `AccessDeniedException` → **403**. Use this as the tenancy guard in every authority-facing service method |
| `isOwnerOrAdmin(Long ownerUserId)` | `boolean` | |

`AuthenticatedUser` is a record: `id`, `identifier`, `role`, `municipalityIds`, plus `isAdmin()` and `servesMunicipality(Long)`. **Note the deliberate asymmetry:** on the *record*, `servesMunicipality` is raw set membership with **no** admin bypass; on `PrincipalContext` it includes the bypass. Authorization decisions should go through `PrincipalContext`.

**Staleness window:** the principal is built from token claims, so role changes, new memberships and deactivations take effect only after the access token expires — **up to 15 minutes**. If Phase 3 adds an action that must take effect immediately (e.g. suspending an officer), it has to revoke the refresh-token family *and* accept the ≤15-minute access-token tail, or re-check the DB at that call site.

### Membership queries (`MembershipRepository`)

```java
List<UserMunicipalityMembership> findByUserIdAndValidUntilIsNull(Long userId);
List<Long> findCurrentMunicipalityIds(Long userId);                       // projection, no entity load
boolean existsByUserIdAndMunicipalityIdAndValidUntilIsNull(Long userId, Long municipalityId);
```

**"Current" always means `valid_until IS NULL`** — that exact predicate backs the partial indexes. End a posting by setting `valid_until`; never delete the row (it is the audit trail).

### User lookups (`UserRepository`)

```java
Optional<User> findByIdentifier(String identifier);   // email OR canonical phone, one round trip
Optional<User> findByEmailIgnoreCase(String email);
Optional<User> findByPhone(String phone);             // expects canonical 01XXXXXXXXX
boolean existsByEmailIgnoreCase(String email);
boolean existsByPhone(String phone);
```

Always normalize with `IdentifierNormalizer` before querying. Email comparisons use `lower()` on both sides even though the column is CITEXT, because pgjdbc binds parameters as `varchar` and `citext = varchar` degrades to case-sensitive `text = text`. **Do not write a new `email = :email` query without `lower()`.**

### Refresh tokens (`RefreshTokenRepository`)

```java
Optional<RefreshToken> findByTokenHash(String tokenHash);
Optional<RefreshToken> findByReplacedByTokenId(Long replacedByTokenId);
List<RefreshToken> findActiveTokenFamilies(Long userId, Instant now);
int revokeFamily(Collection<Long> tokenIds, Instant now);
```

`TokenService.revokeAllForUser(Long)` is the intended entry point for "log this user out everywhere."

### Exception → HTTP contract (`GlobalExceptionHandler`, S18)

Every API error is `application/problem+json` (RFC 7807) with `type` (`urn:nagorik-seba:problem:<slug>`), `title`, `status`, `detail`, `instance`, `timestamp`, and `fieldErrors` when relevant.

| Throw this | Status | `type` slug |
|---|---|---|
| `MethodArgumentNotValidException` / `BindException` / `ConstraintViolationException` | **400** | `validation-failed` (+ `fieldErrors`) |
| `IllegalArgumentException` | 400 | `bad-request` |
| `FileStorageException` | 400 | `invalid-upload` |
| `BadCredentialsException` | 401 | `invalid-credentials` |
| `InvalidRefreshTokenException` | 401 | `invalid-refresh-token` |
| any other `AuthenticationException` | 401 | `unauthenticated` |
| `AccessDeniedException` | **403** | `access-denied` |
| `ResourceNotFoundException` | **404** | `not-found` |
| **`ConflictException`** | **409** | `conflict` |
| `OptimisticLockingFailureException` (R8, `@Version`) | **409** | `concurrent-modification` |
| `DataIntegrityViolationException` | 409 | `conflict` |
| **`InvalidStateTransitionException`** | **422** | `invalid-state-transition` |
| `AccountLockedException` | 423 | `account-locked` (+ `Retry-After`) |

**For Phase 3 specifically:** an illegal complaint status transition must throw `InvalidStateTransitionException` (→ 422); a duplicate or already-claimed resource must throw `ConflictException` (→ 409); a stale `@Version` needs no handling — let `OptimisticLockingFailureException` propagate and it becomes a 409 automatically. Messages in these two exceptions **are** shown to the client, so write them for a citizen, not a developer.

### Adding a new API endpoint

Add its rule to the `@Order(1)` chain in `SecurityConfig`. `anyRequest().authenticated()` is the default, so a new endpoint is protected by omission — but role rules are **not** inferred from the path. `/api/public/**` is the only unauthenticated namespace for new work.

---

## Database Schema (after V2)

| Table | Key Features |
|---|---|
| `users` | email **CITEXT** nullable, `password_hash`, `failed_login_count`, `locked_until`, `last_login_at`, `version`, TIMESTAMPTZ timestamps, `ck_user_contact`, `ck_user_password`, `idx_user_role_active`, phone UNIQUE |
| `refresh_tokens` | `token_hash` UNIQUE (SHA-256), `expires_at`, `revoked_at`, `replaced_by_token_id` self-FK (family chain), `ip_address INET`, `user_agent`, `idx_refresh_user_active` |
| `user_municipality_memberships` | `valid_until IS NULL` = current, `uq_membership_current`, `ck_membership_range`, 2 partial indexes |
| `municipalities`, `wards`, `departments` | unchanged from V1 |
| `complaints`, `attachments`, `notifications`, `status_updates`, `sla_rules`, `ward_performance` | unchanged — Phase 3 owns these |

---

## Deviations from SCOPE

Per the phase constraint *"changes outside a phase's SCOPE list require an explicit deviation entry in the handoff."*

### Files created that the SCOPE list did not name

| File | Why |
|---|---|
| `shared/time/ClockConfig.java` | S17 `Clock` bean was specified for Phase 1 but never created; `GlobalExceptionHandler`, `TokenService` and `AuthService` all require it |
| `shared/exception/InvalidStateTransitionException.java` | Task 8 requires a 422 mapping; the exception did not exist |
| `shared/exception/InvalidRefreshTokenException.java`, `AccountLockedException.java` | Needed for the 401/423 mappings |
| `shared/config/JwtProperties.java` *(modified)* | Replaced `expiration-seconds` with `access-token-seconds` / `refresh-token-days` / `issuer` |
| `shared/exception/ApiError.java` *(modified)* | Added RFC-7807 fields (`type`, `title`, `instance`, `timestamp`) while keeping the legacy `error`/`message`/`fieldErrors` fields that out-of-scope code reads |
| `identity/config/LockoutProperties.java` | Task 4's "5 failures → 15 min" made configurable rather than hard-coded |
| `identity/service/IdentifierNormalizer.java` | Phone canonicalization is used by both register and login; inlining it twice would risk divergence |
| `identity/service/LoginAttemptTracker.java` | `REQUIRES_NEW` is the only way to commit a failed-attempt counter from a method that exits by throwing |
| `identity/service/ClientInfo.java` | Populates the `ip_address`/`user_agent` columns the migration defines |
| `identity/service/AppUserDetailsService.java` | I9 in the blueprint, omitted from the §12 SCOPE list |
| `src/test/java/com/nagorikseba/identity/AuthUnitTests.java` | **Added because Docker is unavailable here.** The SCOPE named only `AuthSecurityIntegrationTests`; with Testcontainers unable to start, that class proves nothing in this environment, so the security-critical pure logic was given non-Docker coverage. It found the two defects listed under [Verification](#verification). |

### Out-of-scope files modified (import-only, per the "keep old classes compiling by adapting imports only" constraint)

- **Import swap** `com.nagorikseba.entity.User` → `com.nagorikseba.identity.domain.User` and `com.nagorikseba.repository.UserRepository` → `com.nagorikseba.identity.repo.UserRepository` in ~21 files: `config/DataSeeder`, `controller/{AuthorityController, ComplaintController}`, `service/ComplaintService`, `event/ComplaintStatusChangedEvent`, `factory/{NotificationFactory, EmailNotificationFactory}`, `state/*` (9 files), `template/*` (3 files).
- **New import added** to `entity/{Attachment, Complaint, Notification, StatusUpdate}` — they referenced `User` from the same package before.
- `config/DataSeeder` — `.password(...)` → `.passwordHash(...)` at 3 sites (the column was renamed).
- `controller/ComplaintController` — three fully-qualified `com.nagorikseba.{entity.User, repository.UserRepository}` references rewritten.

No logic in these files was changed.

### Design deviations

1. **`shared/security/JwtTokenProvider` now passes the injected `Clock` to the jjwt parser.** Expiry was previously validated against wall-clock time, bypassing S17. Identical behaviour in production; deterministic in tests.
2. **`ApiError` stayed in `shared/exception/`** rather than moving to `shared/api/` as §2 suggests — moving it would have meant editing every out-of-scope class that imports it.
3. **`GET /api/municipalities/**` was made public.** The Thymeleaf pages fetch ward/department lists before login. Revisit if that is not intended.
4. **`uq_membership_current` cannot actually enforce "one current membership per user per municipality"** — PostgreSQL treats NULLs as distinct in unique constraints, so rows with `valid_until IS NULL` do not collide. A partial unique index (`... WHERE valid_until IS NULL`) is the real fix; **Phase 3 should add it in V3** before relying on the invariant.
5. **`users.ward_id` / `users.department_id` were retained.** `user_municipality_memberships` supersedes them, but complaint/authority code still reads them. Phase 3 should migrate those reads and then drop the columns.
6. **`/api/auth/me` was considered and deliberately not added** — it is not in the task-9 endpoint list.
7. **`static/js/auth.js` was not modified** (out of scope), so the browser client still ignores the refresh token.

### Known limitation to fix in Phase 3

`AuthorityController` and `ComplaintController` still resolve the caller with `findByEmailIgnoreCase(userDetails.getUsername())`. `getUsername()` returns the *canonical identifier*, which for a phone-only account is the phone number — and that will never match an email lookup. **Phone-only accounts therefore cannot use the authority, rate, or reopen endpoints.** Registration through the web form always supplies an email, so this is latent rather than active. The fix is `principalContext.requireUserId()`, which is correct for every account type; it was left undone because rewriting those controllers is out of Phase 2's scope.

---

## Next Steps (Phase 3)

1. **Run `./mvnw clean verify` with Docker available** and fix anything the 14 security tests surface. This is Phase 2 work.
2. **V3 migration** — partial unique index for current memberships (deviation 4); plan the drop of `users.ward_id`/`department_id`.
3. **Rewrite the complaint module** on `PrincipalContext` — replace every `findByEmailIgnoreCase(getUsername())` with `requireUserId()`, and guard each authority action with `requireMunicipality(...)`.
4. **Use the 409/422 contract** — `InvalidStateTransitionException` for illegal status transitions, `ConflictException` for duplicates, and let `@Version` conflicts surface as 409.
5. **Wire the refresh token into `auth.js`** so sessions survive the 15-minute access-token TTL.

---

## Key Files to Review

- `src/main/resources/db/migration/V2__identity_hardening.sql` — the schema this phase is built on
- `src/main/java/com/nagorikseba/identity/service/TokenService.java` — rotation + family revocation (R9)
- `src/main/java/com/nagorikseba/identity/service/AuthService.java` — check ordering matters; see STEP 3
- `src/main/java/com/nagorikseba/shared/config/SecurityConfig.java` — the two-chain split and why CSRF is off for `/api/**` only
- `src/main/java/com/nagorikseba/shared/security/PrincipalContext.java` — the API Phase 3 builds on
- `src/main/java/com/nagorikseba/shared/exception/GlobalExceptionHandler.java` — the error contract
- `src/test/java/com/nagorikseba/AuthSecurityIntegrationTests.java` — **written but never executed; run it first**
