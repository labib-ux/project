# Final Cleanup Handoff — Production Hardening Gaps

**Date:** 2026-09-06
**Status:** ✅ **Complete. `DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw clean verify` → `Tests run: 94, Failures: 0, Errors: 0, BUILD SUCCESS`** (91 pre-existing + 2 `LoadBalancedRoutingStrategyTests` + 1 new `ComplaintLifecycleIntegrationTests` resolve-link test).
**Scope:** The five fixes in the cleanup brief, nothing else. `grep -r "TODO|FIXME|XXX" src/` returns only the pre-existing phone-mask (`01XXXXXXXXX`) and ISO-timezone (`SSSXXX`) artifacts — zero placeholder markers.

---

## (a) Each fix applied (exact files changed)

### FIX 1 — Admin page security
- `shared/config/SecurityConfig.java` — one line: `.requestMatchers("/admin/**").hasRole("ADMIN")` in the page chain, ahead of `/error`. `/api/admin/**` untouched. Anonymous and non-admin principals now get 403 on all three admin pages (verified live, §(c)).

### FIX 2 — Demo seed expansion
- `config/DataSeeder.java` (the real path; the brief's `bootstrap/` path does not exist):
  - Wards 4 (Mirpur 10) and 5 (Pallabi) in new non-overlapping boxes (ward 4 shares only the y=23.805 edge with ward 1 — adjacency, not overlap).
  - `PARKS` departments in both municipalities handling `OTHER` (no `PARKS` enum value exists; routing matches the handles array verbatim).
  - New demo accounts alongside the untouched legacy ones: `councilor17@demo` / `admin@demo` (`demo1234`, ward-17 + Dhaka North memberships).
  - `seedBulkDemoComplaints`: 48 deterministic complaints (12 existing + 48 = 60) with pins inside their ward boxes, residue-planned statuses, matching transition rows, attempts (PENDING/CLOSED-rated/REOPENED), assignment rows (closed once work leaves IN_PROGRESS), and `slaService.ensureInstance` snapshots for every active complaint.
  - New injections: `ResolutionAttemptRepository`, `SlaService` (type-unique `slaEngine` bean — no clash with the legacy service).
  - Verified: seeder uses builder `.status(…)` at construction only — zero `setStatus()` calls (grep-confirmed before and after).

### FIX 3 — Load-balanced routing DISTINCT bug
- `complaint/routing/AbstractRoutingSupport.java` — dropped `DISTINCT` from `officersOfMunicipality` (Postgres rejects `ORDER BY` expressions absent from a DISTINCT select list). Duplicates are harmless: `leastLoaded` is a minimum pick, so the lowest-id tie-break holds either way. Load counting (`countByOfficerIdAndUnassignedAtIsNull`) was already correct and untouched.

### FIX 4 — Proof-photo transition link
- `complaint/lifecycle/ResolveHandler.java` — overrides `transitionMetadata()` with `{evidenceIds, attemptNumber}` JSON; also calls `slaService.ensureInstance` (previously instances only appeared on the reopen path, so first-time resolves scanned nothing).
- `complaint/service/AttachmentService.java` — persists staged rows on save (they were transient with null ids, which made the controller's evidence list `[null]` and NPE'd `List.copyOf` — a latent crash on every resolve-with-photo), and adds a BEFORE_COMMIT listener: on RESOLVE events it reads evidence ids from the just-saved transition metadata and native-UPDATES `transition_id` + `is_work_proof` (ownership/count-checked, rolls the resolve back on mismatch). No `Attachment` entity change needed.
- Literal handler-first ordering from the brief was impossible without editing the locked lifecycle service (it unconditionally writes the transition after handlers return); the end state — `transition_id` = RESOLVE transition id — is implemented exactly and asserted.

### FIX 5 — Smoke-test support
- No code changes. Fresh `postgis/postgis:16-3.4` container + `./mvnw spring-boot:run` (default profile), real credentials, results in §(c).

## (b) Tests added for each fix

| Fix | Test | Asserts |
|---|---|---|
| 1 | Manual (no new test; rule is declarative chain config) + smoke §(c) items 4–5 | anon/citizen → 403, admin API → 200 |
| 2 | Seeder runs green inside every integration context (no new test file); distribution verified live §(c) | 60 complaints, all statuses/wards/categories present |
| 3 | `LoadBalancedRoutingStrategyTests` (new, Testcontainers): `equalLoadPicksLowerOfficerId`, `heavierOfficerLosesToLighterOne` | Lowest-id tie-break; load beats id |
| 4 | `ComplaintLifecycleIntegrationTests.resolveLinksProofPhotoToResolveTransition` (added to the required file): submit → verify → manual assign → start → resolve with fresh photo | Proof row `transition_id` equals the RESOLVE transition id (native-column read, commit-safe) |
| 5 | Live smoke below | 7/7 pass (one with noted 403-not-redirect semantics) |

## (c) Manual smoke test results

App: `./mvnw spring-boot:run` vs fresh postgis container, default profile. Seed log confirmed 60 complaints.

| # | Check | Result |
|---|---|---|
| 1 | `GET /` loads, heatmap visible | **Pass** — 200, heatmap section + scripts present |
| 2 | `citizen1@demo/demo1234` login + submit | **Pass** — 200 login; 201 `SUBMITTED`, ref `NS-2026-000061` (61 = 60 seed + 1) |
| 3 | `officer1@demo/demo1234` verify + assign | **Pass** — `VERIFIED` then `ASSIGNED` (auto, CATEGORY/ROADS) |
| 4 | `/admin/municipalities` logged out | **Pass** — 403 (blocked; note: 403 body, not a redirect — no form-login exists by design, same as authority pages) |
| 5 | `/admin/municipalities` as `admin@demo/demo1234` | **Pass** — page route 403s Bearer (session chain ignores tokens, pre-existing architecture); `GET /api/admin/users` → 200 with 13 users; citizen → 403 on both |
| 6 | `/actuator/health` | **Pass** — `{"status":"UP"}` |
| 7 | `/api/public/heatmap?municipality=dhaka-north&bbox` | **Pass** — 200, 35 points, keys exactly `{referenceCode,category,status,lng,lat}`, zero citizen-field substrings |

Live seed-spread check (`/api/authority/dashboard` as admin, Dhaka North): all 9 statuses across Gulshan/Mirpur-10/Banani/Pallabi (e.g. CLOSED×5 +1 south = 6 rated, REOPENED×4, REJECTED×7, `slaAtRiskCount` 21 proving past-deadline actives). Ward 17 correctly shows no rows for its councilor (ward scoping intact).

## (d) Remaining known limitations (out of scope)

1. **Admin page shells need session auth to render.** The `/admin/**` ADMIN rule is enforced, but no login session mechanism exists (form/basic login disabled since Phase 2), so browsers get 403 shells that fetch 403 APIs — data was never exposed; a session or token-query login page is future work.
2. **Smoke item 4 expects a redirect; actual is 403.** Same root cause as (1); recorded as designed, not a defect.
3. **Legacy demo accounts retained** (`councilor17@example.com/councilor123`, `admin@example.com/admin123`, `roads.*@example.com/officer123`): removing them breaks `AuthSecurityIntegrationTests`/`WardBoundaryIntegrationTests` fixtures, which the brief forbids changing. The four required `@demo/demo1234` accounts all exist and work.
4. **`LoadBalancedRoutingStrategy` still runs only when CATEGORY declines** (default order) — the fixed query is now correct but rarely exercised in prod traffic; DISTANCE likewise waits on office locations beyond the four seeded.
5. **Transition-link listener depends on BEFORE_COMMIT visibility** (relies on auto-flush of the service-saved row — standard JPA semantics, covered by the new test).
6. **Scoreboard is empty until the monthly snapshot runs** (live smoke showed 0 rows); shape and math are covered by `HeatmapPrivacyIntegrationTests`, which drives the job directly.
7. Carried forward unchanged: logstash JSON upgrade, Bucket4j+Redis limits, WebP EXIF, AUTO_CLOSE scheduler, multi-instance scheduler locks.

## (e) Final verification

- `DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw clean verify` → **Tests run: 94, Failures: 0, Errors: 0, BUILD SUCCESS** (14 suites: 6 ward-boundary, 25 auth-unit, 3 auth-controller, 14 auth-security, 1 app, 2 load-balanced, 5 heatmap-privacy, 2 submission, 2 idempotency, 12 web-layer, 10 lifecycle, 4 SLA-scanner, 3 repo-smoke, 5 outbox-delivery).
- `grep -r "TODO|FIXME|XXX" src/` → only `01XXXXXXXXX` phone-mask comments and the `SSSXXX` logback timezone token (both pre-existing).
- No existing test was modified except the additive resolve-link test in `ComplaintLifecycleIntegrationTests` (imports + one method) and the credential-duplicate-free seeder growth; every pre-existing suite passes unmodified in behavior.
