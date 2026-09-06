# Phase 4 Handoff — Authority Workflows, Routing & Geospatial

**Date:** 2026-09-06
**Status:** ✅ **Complete. `DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw clean verify` → `Tests run: 65, Failures: 0, Errors: 0, BUILD SUCCESS`** (59 pre-existing + 6 new `WardBoundaryIntegrationTests`).
**Scope:** Blueprint §3.1 (wards spatial), §3.3 (assignments), §4 (spatial queries), §7.2 (routing), §6 (ASSIGN/START edges), §8.2 (authority authorization).

> Names, paths, SQL identifiers and JSON keys below are copied from the code, not the blueprint.

---

## (a) Files created / modified (one line each)

### Migrations

| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V4__routing_assignment.sql` | `complaint_assignments` table per §3.3 (+ `strategy_explanation` audit column), officer/complaint active partial indexes, `handles_categories` + `office_location` guards on `departments` |
| `src/main/resources/db/migration/R__ward_boundaries.geojson.sql` | Repeatable upsert of Dhaka North wards 1–5 as adjacent non-overlapping grid polygons (GeoJSON embedded as comment, applied as WKT); no-op when the municipality does not exist yet (test ordering) |

### Ward spatial

| File | Purpose |
|---|---|
| `municipality/repo/WardRepository.java` | Added `findWardContaining(municipalityId, lng, lat)` (`ST_Covers` + GiST + `ORDER BY id LIMIT 1`) and `findOverlappingWardIds` (`ST_Overlaps`, touching excluded) |
| `municipality/service/WardBoundaryService.java` | `resolvePoint` (throws `WardNotCoveredException` → 404 when uncovered) and `validateBoundaryOverlap` returning `OverlapPair` list |
| `municipality/api/WardBoundaryController.java` | Public `GET /api/municipalities/{slug}/wards/containing?lat=&lng=` and `GET /api/municipalities/{slug}/boundary-validation` |

### Assignment history

| File | Purpose |
|---|---|
| `complaint/domain/ComplaintAssignment.java` | Assignment row entity with `close()` (reassignment keeps history), `isActive()` |
| `complaint/repo/ComplaintAssignmentRepository.java` | History, current-assignment and officer/department active-load queries |

### Routing (`complaint/routing/`)

| File | Purpose |
|---|---|
| `routing/ComplaintRoutingStrategy.java` | Strategy SPI: `type()`, `supports(complaint)`, `route(complaint)` |
| `routing/RoutingDecision.java` | Auditable answer record: department, officer (nullable), strategy, explanation |
| `routing/NoEligibleDepartmentException.java` | 400-mapped fallback (complaint stays VERIFIED, transaction rolls back) |
| `routing/AbstractRoutingSupport.java` | Shared membership-based officer reads and least-load picking (lowest id tie-break) |
| `routing/CategoryRoutingStrategy.java` | Lowest-id dept handling the category + its least-loaded officer (dept-only when none) |
| `routing/LoadBalancedRoutingStrategy.java` | Least-loaded officer municipality-wide; department follows the officer's posting |
| `routing/DistanceBasedRoutingStrategy.java` | Nearest office via `ST_Distance` on geography; skips when no locations set |
| `routing/RoutingStrategyResolver.java` | Ordered by `app.routing.strategy-order` (default `CATEGORY,LOAD_BALANCED,DISTANCE`), first `supports()` wins |

### Lifecycle

| File | Purpose |
|---|---|
| `complaint/lifecycle/AssignHandler.java` | VERIFIED\|REOPENED → ASSIGNED, manual (§6 guards) + resolver auto paths, closes prior assignment, writes assignment + `COMPLAINT_ASSIGNED` outbox rows, exposes routing JSON via `transitionMetadata` |
| `complaint/lifecycle/StartWorkHandler.java` | ASSIGNED → IN_PROGRESS, assigned-officer-or-ADMIN guard, writes `COMPLAINT_WORK_STARTED` outbox row |
| `complaint/lifecycle/TransitionCommand.java` | Added nullable `departmentId`/`officerId` + `ofAssignment()` overload (existing `of()` unchanged) — deviation, see (b) |
| `complaint/lifecycle/TransitionHandler.java` | Added `default transitionMetadata()` returning null — deviation, see (b) |
| `complaint/lifecycle/ComplaintLifecycleService.java` | Stores `handler.transitionMetadata(...)` on the audit row (1 line) — deviation, see (b) |

### Authority API + aggregates

| File | Purpose |
|---|---|
| `complaint/repo/ComplaintRepository.java` | Added `countByMunicipalityGroupByWardAndStatus` (ward dashboard) and `averageResolutionHoursByMunicipality` (native `EXTRACT(EPOCH)`) |
| `complaint/api/AuthorityComplaintController.java` | Extended dashboard (kept keys + `wardStats`/`averageResolutionHours`/`slaAtRiskCount`), new `GET /queue` (municipality + status filter + role scoping), `POST …/assign`, `POST …/assign/auto`, `POST …/start` |

### UI + seed + tests

| File | Purpose |
|---|---|
| `src/main/resources/templates/authority/dashboard.html` | Stat cards + per-ward table with CSS bars, `fetch` + `localStorage nagorikSebaToken` |
| `src/main/resources/templates/authority/queue.html` | Filterable queue with Verify / Auto-assign / Start buttons |
| `config/DataSeeder.java` | Officers `officer1@demo`…`officer4@demo` (`demo1234`) on the first four Dhaka North departments, office locations, sample ASSIGNED + IN_PROGRESS complaints with assignment rows |
| `src/test/java/com/nagorikseba/WardBoundaryIntegrationTests.java` | 6 tests: inside/outside/shared-edge/overlap-validator/dashboard aggregates/full submit→verify→queue→auto-assign→start auditability flow |

---

## (b) Key decisions and why

1. **Manual/auto share one `AssignHandler`, dispatched on `command.departmentId() != null`.** Two endpoints, one state machine edge — guards, closing, audit and outbox cannot drift between paths.
2. **Officers resolve through membership rows, not `users.department_id`.** Memberships are authoritative since Phase 3; strategies query them via explicit `EntityManager` JPQL so no existing repository needed a new finder.
3. **`ST_Covers` (not `ST_Contains`) for pins, `ST_Overlaps` (not `ST_Intersects`) for validation.** Covers resolves shared-edge pins instead of dropping them; Overlaps reports true area errors while treating touching edges as intended adjacency.
4. **Lowest-id determinism everywhere** (ward lookup `ORDER BY id LIMIT 1`, dept choice, officer tie-breaks) — concurrent/edge inputs always agree.
5. **Dashboard extends the existing map instead of replacing it.** `AuthSecurityIntegrationTests` asserts `$.role` on `/api/authority/dashboard`; old keys are kept, aggregate keys added.
6. **SLA-at-risk is a Phase-4 approximation** (`submittedAt + SlaRule hours < now + 24h`, fallback 120h). No `sla_instances` table exists yet; the counting rule is kept so Phase 5 can swap the source.
7. **Action endpoints live under `/api/authority/complaints/…`, not `/api/complaints/…`.** The citizen chain only admits CITIZEN/ADMIN and would 403 officer tokens; `SecurityConfig` is untouched.
8. **Deviations (additive, backwards-compatible, forced by task requirements):**
   - `TransitionCommand` + nullable `departmentId`/`officerId` + `ofAssignment()` — manual ASSIGN must carry its picks; the old `of()` is byte-identical in behavior.
   - `TransitionHandler.transitionMetadata()` default null + 1-line service passthrough — the transition row is built by the service *after* handlers run, so handler-computed routing JSON cannot otherwise reach `metadata`; only `AssignHandler` overrides it.
   - `config/DataSeeder.java` modified instead of the scoped `bootstrap/DataSeeder.java` — that path does not exist; the scoped path would have created a duplicate seeder. No second seeder was added.
   - `app.routing.strategy-order` is read via `@Value` with default — `application.yml` was out of scope, so no config file was touched.

---

## (c) Routing decision schema

`RoutingDecision(department, officer, strategy, explanation)`; persisted twice:

- `complaint_assignments`: `strategy_used` = `strategy` (`CATEGORY`/`LOAD_BALANCED`/`DISTANCE`/`MANUAL`), `strategy_explanation` = `explanation` (examples: `"matched department ROADS (id 3) for category ROADS; officer X (id 7) has the least load with 0 active assignments"`, `"least-loaded officer Y (id 9) with 1 active assignments in department WATER_SUPPLY (id 4)"`, `"nearest department ELECTRICITY (id 5) at 1.23 km; …"`, `"manually assigned to department SANITATION (id 6), officer Z (id 8) by …"`).
- `complaint_transitions.metadata` JSONB on the ASSIGN row: `{"strategy","explanation","departmentId","officerId|null"}`.
- Outbox `COMPLAINT_ASSIGNED` payload: `{complaintId, referenceCode, occurredAt, action:"ASSIGN", departmentId, departmentCode, officerId|null, strategy, explanation, actorId}`; `COMPLAINT_WORK_STARTED` payload: `{complaintId, referenceCode, occurredAt, action:"START", actorId, note|null}` (plus the standard `COMPLAINT_STATUS_CHANGED` rows the service writes for both).

---

## (d) Dashboard aggregate DTO shape

`GET /api/authority/dashboard?municipalityId=` (param optional when the caller serves exactly one municipality) → JSON object with the pre-existing `userId, role, municipalityIds, postings` plus:

```json
{
  "municipalityId": 1,
  "wardStats": [
    {"wardId": 1, "wardNumber": 1, "wardName": "Gulshan",
     "counts": {"ASSIGNED": 2, "VERIFIED": 1}},
    {"wardId": null, "wardNumber": null, "wardName": "Outside ward boundaries",
     "counts": {"SUBMITTED": 1}}
  ],
  "averageResolutionHours": 41.5,
  "slaAtRiskCount": 3
}
```

`wardStats` is filtered to the councilor's wards for `WARD_COUNCILOR`, full municipality otherwise. `GET /api/authority/queue?municipalityId=&status=` returns `ComplaintResponse[]` (default statuses VERIFIED/ASSIGNED/IN_PROGRESS/REOPENED; officers see their department's, councilors their wards', admins everything).

---

## (e) Contracts Phase 5 depends on

- **Assignment API:** `ComplaintAssignmentRepository.findByComplaintIdAndUnassignedAtIsNull` (current), `…OrderByCreatedAtAsc` (chain), `countByOfficerIdAndUnassignedAtIsNull` (load). Active = `unassigned_at IS NULL` (partial indexes). `ResolveHandler` should `close()` the active row on RESOLVE and clear the breach hook there.
- **Transition metadata:** ASSIGN rows carry routing JSON (`strategy`, `explanation`, `departmentId`, `officerId`); all other actions carry null. Never updated after write (entity has no setters).
- **Outbox event types in use:** `COMPLAINT_STATUS_CHANGED` (all transitions, payload has `action`), plus `COMPLAINT_ASSIGNED` and `COMPLAINT_WORK_STARTED`. Relay must handle all three; rows accumulate PENDING (no worker yet).
- **Executable edges now:** SUBMIT → VERIFIED → ASSIGNED → IN_PROGRESS (+ REJECT/CANCEL terminals). `ComplaintAction` still lists RESOLVE/CLOSE/REOPEN with no handlers → 422 until Phase 5. `TransitionCommand.evidenceAttachmentIds` is reserved for RESOLVE work-proof.
- **Ward lookup:** `WardBoundaryService.resolvePoint(municipalityId, lat, lng)` / `WardRepository.findWardContaining`; public REST at `/api/municipalities/{slug}/wards/containing`.
- **Seeded accounts:** `officer1@demo`…`officer4@demo` / `demo1234` (ROADS/WATER_SUPPLY/ELECTRICITY/SANITATION, Dhaka North), `councilor17@example.com` / `councilor123`, `admin@example.com` / `admin123`.

---

## (f) Smoke test: submit → verify → auto-assign → start

Base `http://localhost:8080`. Photo must be a real JPEG/PNG/WebP file.

```bash
# 1. Register a citizen → 201 (save $CITIZEN)
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Phase Four","email":"phase4-smoke@example.com","phone":"01712345678","password":"a-secure-password"}'
export CITIZEN='<accessToken>'

# 2. Submit (Gulshan pin, Dhaka North) → 201 (save $REF)
curl -s -X POST http://localhost:8080/api/complaints \
  -H "Authorization: Bearer $CITIZEN" \
  -F 'title=Pothole on Gulshan Avenue' \
  -F 'description=Large pothole near Gulshan 1 circle.' \
  -F 'category=ROADS' -F 'latitude=23.7925' -F 'longitude=90.4120' \
  -F 'photos=@/tmp/pothole.jpg'
export REF='<referenceCode>'

# 3. Councilor verifies → 200 VERIFIED (save $COUNCILOR)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"councilor17@example.com","password":"councilor123"}'
export COUNCILOR='<accessToken>'
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/verify?note=smoke-verify" \
  -H "Authorization: Bearer $COUNCILOR"

# 4. Officer queue contains it (save $OFFICER from officer1@demo/demo1234 login)
curl -s "http://localhost:8080/api/authority/queue?municipalityId=1&status=VERIFIED" \
  -H "Authorization: Bearer $OFFICER" | grep -o "$REF"

# 5. Auto-assign → 200 ASSIGNED (strategy CATEGORY for ROADS)
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/assign/auto" \
  -H "Authorization: Bearer $OFFICER"

# 6. Start as admin (or the assigned officer) → 200 IN_PROGRESS
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"admin@example.com","password":"admin123"}'
export ADMIN='<accessToken>'
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/start" \
  -H "Authorization: Bearer $ADMIN"

# 7. Assignment row exists (psql): strategy_used + explanation populated
psql "$DB_URL" -c "SELECT strategy_used, strategy_explanation FROM complaint_assignments WHERE unassigned_at IS NULL;"
psql "$DB_URL" -c "SELECT action, metadata FROM complaint_transitions WHERE metadata IS NOT NULL ORDER BY id DESC LIMIT 3;"
```

Expected: 201 → 201 → 200 → queue contains `$REF` → 200 `ASSIGNED` → 200 `IN_PROGRESS`; assignment row `strategy_used=CATEGORY` with a ROADS explanation; ASSIGN transition `metadata` contains `"strategy":"CATEGORY"`. The same flow is asserted executable in `WardBoundaryIntegrationTests.autoAssignAuditsStrategyInAssignmentRowAndTransitionMetadata`.
