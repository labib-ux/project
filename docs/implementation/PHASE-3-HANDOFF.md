# Phase 3 Handoff — Complaint Aggregate & Lifecycle Engine

**Date:** 2026-09-05
**Status:** ✅ **Complete. `./mvnw test` → `Tests run: 59, Failures: 0, Errors: 0, BUILD SUCCESS`** (with `DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock` on hosts without `/var/run/docker.sock`).
**Scope:** Blueprint §3.3, §5 (R1–R3, R6, R8), §6 (SUBMIT/VERIFY/REJECT/CANCEL only), §7.1, §7.5.

> Everything below is verified against the current tree. Class/method/field names, endpoint paths, SQL constraint names and JSON keys are copied from the code, not from the blueprint.

---

## (a) Files created or modified (Phase-3-relevant, verified present)

### Migration

| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V3__complaint_core.sql` | Complaint core schema: new `complaints` columns, `complaint_transitions`, hardened `attachments`, `outbox_messages`, submission-idempotency index, plus `ALTER COLUMN citizen_id DROP NOT NULL` for the V1 legacy column |

### Domain (`complaint/domain/`)

| File | Purpose |
|---|---|
| `domain/Complaint.java` | Complaint aggregate root with package-private lifecycle setters and `@Version` optimistic locking |
| `domain/ComplaintTransition.java` | Append-only lifecycle audit row (from/to/action/actor/note/idempotencyKey/createdAt) |
| `domain/Attachment.java` | Photo evidence row with checksum, sniffed content type, soft delete, `@CreationTimestamp createdAt` |
| `domain/ComplaintMutator.java` | Capability base class re-exposing package-private setters as `protected final` to subclasses only |
| `domain/enums/ComplaintStatus.java` | Statuses: SUBMITTED, VERIFIED, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED, REOPENED, REJECTED, CANCELLED |
| `domain/enums/ComplaintAction.java` | Actions: SUBMIT, VERIFY, REJECT, ASSIGN, START, RESOLVE, CLOSE, REOPEN, CANCEL |
| `domain/enums/Category.java` | Complaint categories (ROADS, WATER_SUPPLY, ELECTRICITY, SANITATION, WATERLOGGING, MOSQUITO_BREEDING, WASTE_MANAGEMENT, OTHER) |
| `domain/enums/Priority.java` | LOW, NORMAL, HIGH, CRITICAL |
| `domain/enums/ModerationStatus.java` | PENDING, APPROVED, REJECTED |
| `domain/enums/LocationSource.java` | DEVICE, MAP_PIN, ADDRESS_TEXT |

### Repositories (`complaint/repo/`)

| File | Purpose |
|---|---|
| `repo/ComplaintRepository.java` | Finders plus `findAndLockById` (`SELECT … FOR UPDATE`) and `findBySubmissionIdempotencyKey` (R3 replay) |
| `repo/ComplaintTransitionRepository.java` | Timeline ordered query plus `findByComplaintIdAndIdempotencyKey` / `exists…` replay checks |
| `repo/AttachmentRepository.java` | Non-deleted and full attachment listings per complaint |

### Lifecycle (`complaint/lifecycle/`)

| File | Purpose |
|---|---|
| `lifecycle/ComplaintLifecycleService.java` | Single funnel: lock → replay check → version check → handler lookup → guards → audit row → outbox; plus `recordSubmission` for the SUBMIT edge |
| `lifecycle/TransitionHandler.java` | Handler SPI: `supportedAction()`, `sourceStatuses()`, `execute(complaint, command, occurredAt)` |
| `lifecycle/TransitionCommand.java` | Request record (action, complaintId, actorId, note, evidenceAttachmentIds, idempotencyKey, expectedVersion) with `of()` factory |
| `lifecycle/VerifyHandler.java` | SUBMITTED → VERIFIED, stamps `firstVerifiedAt` once, moderation → APPROVED |
| `lifecycle/RejectHandler.java` | SUBMITTED → REJECTED, mandatory reason, hides from public, moderation → REJECTED |
| `lifecycle/CancelHandler.java` | SUBMITTED\|VERIFIED → CANCELLED, mandatory reason, owner-only, refuses anonymous |

### Submission (`complaint/submission/`)

| File | Purpose |
|---|---|
| `submission/ComplaintSubmissionTemplate.java` | Non-final `@Transactional submit()` skeleton: replay lookup → validate → persist → attachments → ward resolve → `recordSubmission` |
| `submission/StandardComplaintSubmission.java` | Authenticated-citizen variant, moderation default APPROVED |
| `submission/AnonymousComplaintSubmission.java` | Phone-required variant, moderation PENDING, priority LOW |

### Services (`complaint/service/`)

| File | Purpose |
|---|---|
| `service/AttachmentService.java` | Tika-sniffed JPEG/PNG/WebP validation, SHA-256 fingerprint, temp-stage + AFTER_COMMIT promotion (R6) |
| `service/AttachmentsStoredEvent.java` | Immutable record of staged storage keys for the after-commit promoter |
| `service/ComplaintMapper.java` | Entity → `ComplaintResponse` with owner/authority PII gating via `PrincipalContext` |
| `service/ComplaintQueryService.java` | Read model: `describe` (re-fetches detached complaints), `findMyComplaints`, `findByReferenceCode`, authority paging |

### API (`complaint/api/`)

| File | Purpose |
|---|---|
| `api/CitizenComplaintController.java` | `POST /api/complaints` (multipart submit), `GET /api/complaints/my`, `GET /api/complaints/{referenceCode}`, `POST /api/complaints/{referenceCode}/cancel?reason=` |
| `api/AuthorityComplaintController.java` | `GET /api/authority/dashboard` (claim-based + `@Transactional`), `POST /api/authority/complaints/{referenceCode}/verify?note=`, `POST …/reject?reason=` |
| `api/dto/ComplaintSubmissionRequest.java` | Validated multipart binding: title, description, category, latitude/longitude (BD ranges), photos (1–5), phone, addressText, locationSource, idempotencyKey |
| `api/dto/ComplaintResponse.java` | Citizen/authority response: id, referenceCode, title/description/category/status/priority, lat/lng, ward/municipality, gated citizenName/Phone, timestamps, reopenCount, reasons, visibility, moderation, attachments, timeline |
| `api/dto/AttachmentResponse.java` | Attachment view: id, storageKey, originalFilename, contentType, byteSize, workProof, scanStatus, createdAt |
| `api/dto/TransitionResponse.java` | Timeline view: id, from/toStatus, action, actorName, actorRole, note, createdAt |

### Shared outbox

| File | Purpose |
|---|---|
| `shared/outbox/OutboxMessage.java` | Durable side-effect row (aggregateType/aggregateId/eventType/payload/status/retryCount/nextAttemptAt); Phase 3 only accumulates PENDING rows |
| `shared/outbox/OutboxPublisher.java` | `MANDATORY`-propagation writer joining the caller's transaction (no dual writes) |
| `shared/outbox/OutboxRepository.java` | Outbox persistence port |

### Cross-module fixes in scope

| File | Purpose |
|---|---|
| `identity/repo/MembershipRepository.java` | `findByUserIdAndValidUntilIsNull` now `@EntityGraph({municipality, ward, department})` for the dashboard |
| `complaint/domain/Attachment.java` | Added `@CreationTimestamp` on `createdAt` so direct builder inserts also satisfy NOT NULL |
| `config/DataSeeder.java` | Seeds municipalities, wards (incl. ward 17), councilor/admin/officer memberships, SLA rules, 10 demo complaints (1 anonymous with phone) |
| `src/main/resources/templates/citizen/complaint-form.html` | Header aligned with test contract (`Report a local issue`) |

### Tests

| File | Purpose |
|---|---|
| `src/test/java/com/nagorikseba/ComplaintLifecycleIntegrationTests.java` | §7.1 matrix: legal VERIFY/REJECT/CANCEL, illegal → 422, concurrent VERIFY → 1×200 + 1×409/422, stale version → 409, idempotent replay, owner-only CANCEL |
| `src/test/java/com/nagorikseba/SubmissionIdempotencyTests.java` | R3: same key → one complaint; no key → two complaints |
| `src/test/java/com/nagorikseba/ComplaintSubmissionIntegrationTests.java` | Multipart submit with photo → 201 + `GET /uploads/{key}` serves bytes; non-image → 400 |
| `src/test/java/com/nagorikseba/RepositorySmokeTests.java` | CRUD for complaints, transitions (incl. idempotency lookup), attachments (incl. soft delete) |

---

## (b) Key decisions

### 1. Why `citizen_id` is nullable

Blueprint §3.3 declares `citizen_id BIGINT REFERENCES users(id), -- NULL = anonymous` with `ck_complaint_anonymous CHECK ((citizen_id IS NOT NULL) OR (anonymous_contact_phone IS NOT NULL))`. The entity mirrors it: `Complaint.citizen` is `@ManyToOne @JoinColumn(name = "citizen_id")` with no `nullable = false`, plus `isAnonymous()` = `citizen == null`. `AnonymousComplaintSubmission` requires `phone`, stores it in `anonymousContactPhone`, sets moderation PENDING; `StandardComplaintSubmission` stores the citizen and leaves the phone null. `CancelHandler` refuses anonymous cancels (`AccessDeniedException`), authority path for those is REJECT. V1 had created the column `NOT NULL`; V3 keeps the nullable `ADD COLUMN IF NOT EXISTS` definition **and** adds `ALTER TABLE IF EXISTS complaints ALTER COLUMN citizen_id DROP NOT NULL` because the `IF NOT EXISTS` add is a no-op on databases where V1 already created the column.

### 2. How the package-private status setter is enforced

`Complaint.setStatus` (and the other lifecycle setters) are package-private (`void setStatus(...)`, `Complaint.java`). `ComplaintMutator` lives in `complaint.domain` and re-exposes them as `protected final` methods (`changeStatus`, `markFirstVerifiedAt`, `recordRejection`, …). The only extenders are `TransitionHandler` implementations and `ComplaintLifecycleService` itself (for `markLastTransitionAt`). A controller, seeder or service calling `complaint.setStatus(...)` does not compile. All runtime changes therefore flow through `ComplaintLifecycleService.execute()` (lock → version → handler → audit → outbox).

### 3. How idempotency keys are stored

Two separate mechanisms, both in the schema (V3), no standalone `idempotency_keys` table exists in the code:

- **Submissions (R3):** `complaints.submission_idempotency_key VARCHAR(64)` (nullable) with partial unique index `uq_complaint_submission_idempotency … WHERE submission_idempotency_key IS NOT NULL`. `ComplaintSubmissionTemplate.findReplay()` checks `findBySubmissionIdempotencyKey(key)` **before** any write; the controller copies the `Idempotency-Key` header onto the request. `persist()` uses `saveAndFlush` so a concurrent duplicate surfaces as a constraint violation at insert time.
- **Lifecycle actions:** `complaint_transitions.idempotency_key VARCHAR(64)` with `uq_transition_idempotency UNIQUE (complaint_id, idempotency_key)`. `ComplaintLifecycleService.execute()` checks `findByComplaintIdAndIdempotencyKey` **before** the version check, so a retry with a stale version still returns the original outcome instead of 409.

---

## (c) Deferred handlers

Verified by searching `src/main/java`: **none of the following classes exist in the current tree.** The `ComplaintAction` enum already contains their actions, and `ComplaintLifecycleService` answers any of them with `InvalidStateTransitionException` → 422 until the handler lands. `AuthorityComplaintController` javadoc states only VERIFY and REJECT are exposed.

| Expected class | Expected location | Status |
|---|---|---|
| `AssignHandler` (VERIFIED/REOPENED → ASSIGNED) | `src/main/java/com/nagorikseba/complaint/lifecycle/AssignHandler.java` | **Absent — Phase 4** |
| `StartWorkHandler` (ASSIGNED → IN_PROGRESS) | `src/main/java/com/nagorikseba/complaint/lifecycle/StartWorkHandler.java` | **Absent — Phase 4** |
| `ResolveHandler` (IN_PROGRESS → RESOLVED) | `src/main/java/com/nagorikseba/complaint/lifecycle/ResolveHandler.java` | **Absent — Phase 5** (needs work-proof photos + `resolution_attempts`) |
| `CloseHandler` (RESOLVED → CLOSED) | `src/main/java/com/nagorikseba/complaint/lifecycle/CloseHandler.java` | **Absent — Phase 5** (needs rating on `resolution_attempts`) |
| `ReopenHandler` (RESOLVED → REOPENED) | `src/main/java/com/nagorikseba/complaint/lifecycle/ReopenHandler.java` | **Absent — Phase 5** (needs reopen_count ≤ 5 + SLA recalculation) |

Existing handlers for reference: `VerifyHandler`, `RejectHandler`, `CancelHandler` in the same package.

---

## (d) Contracts Phase 4 depends on

### 1. TransitionHandler registry API

File: `complaint/lifecycle/TransitionHandler.java`. To register a new handler:

1. Create `@Component public class XHandler extends ComplaintMutator implements TransitionHandler`.
2. Implement `ComplaintAction supportedAction()` (must be unique — duplicates fail startup in `buildRegistry`), `Set<ComplaintStatus> sourceStatuses()`, and `void execute(Complaint complaint, TransitionCommand command, Instant occurredAt)`.
3. No other registration step: `ComplaintLifecycleService` builds `Map<ComplaintAction, TransitionHandler>` from the injected `List<TransitionHandler>` at construction. Handlers must be stateless, must not read the wall clock (use `occurredAt`), and must not lock/check versions/write audit or outbox — the service does that around them. `supportedActions()` exposes the current key set.

### 2. TransitionCommand record

File: `complaint/lifecycle/TransitionCommand.java`:

```java
public record TransitionCommand(
    ComplaintAction action,       // what to do
    Long complaintId,             // aggregate id (not referenceCode)
    Long actorId,                 // authenticated user; CANCEL checks ownership against it
    String note,                  // mandatory for REJECT/CANCEL, optional elsewhere
    List<Long> evidenceAttachmentIds, // reserved; unused until RESOLVE (Phase 5)
    String idempotencyKey,        // nullable; replay returns original outcome
    int expectedVersion           // stale value → ConflictException → 409
)
```

Factory `TransitionCommand.of(action, complaintId, actorId, note, idempotencyKey, expectedVersion)` fills `evidenceAttachmentIds` with `List.of()`. `hasIdempotencyKey()` is null/blank-safe. Error mapping: unknown action or wrong source status → `InvalidStateTransitionException` → 422; stale `expectedVersion` → `ConflictException` → 409 (`GlobalExceptionHandler`).

### 3. Outbox payload schema

Constants: `ComplaintLifecycleService.AGGREGATE_TYPE = "COMPLAINT"`, `EVENT_STATUS_CHANGED = "COMPLAINT_STATUS_CHANGED"`. Written via `OutboxPublisher.publish(...)` with `Propagation.MANDATORY` (must join the caller's tx). Phase 3 only accumulates PENDING rows; no relay worker drains them yet.

- `recordSubmission` payload: `complaintId (number)`, `referenceCode (string)`, `occurredAt (ISO string)`, `action: "SUBMIT"`, `from: null`, `to: "SUBMITTED"`, `actorId (number | null)`, `note ("Complaint submitted" | "Anonymous complaint submitted")`.
- `execute` payload (`publishStatusChanged`): same `complaintId`, `referenceCode`, `occurredAt`, plus `action (<ACTION>)`, `from (<STATUS>)`, `to (<STATUS>)`, `actorId (number)`, `note (string | null)`.

### 4. Complaint aggregate invariants

- Identity: `referenceCode` is `nullable = false, unique = true`; database id is never exposed publicly (controllers address by referenceCode).
- Locking: `@Version int version`; `execute()` pessimistically locks via `findAndLockById` then checks `expectedVersion`; mismatch → 409. Never bump version by hand (Hibernate owns it).
- Status: only handlers mutate; legal Phase-3 edges are SUBMITTED → VERIFIED (VERIFY), SUBMITTED → REJECTED (REJECT, reason required), SUBMITTED|VERIFIED → CANCELLED (CANCEL, reason + owner required, anonymous refused). Everything else → 422.
- Ownership/PII: `ComplaintMapper` reveals `citizenName`/`citizenPhone` only to the owner or a principal serving the complaint's municipality; anonymous shows `"Anonymous"` + contact phone to authorized readers.
- `reopenCount` defaults 0 (`@Builder.Default`); `publicVisible` defaults true; moderation defaults APPROVED (standard) / PENDING (anonymous); `ward_id` nullable (outside-boundary points stay null); attachments/transitions are `CascadeType.ALL, orphanRemoval = true` with unmodifiable getters.

### 5. Reference code format

`NS-yyyy-######`: `"NS-%d-%06d".formatted(year, sequence)` where `year` is `Year.from(clock.instant().atZone(ZoneOffset.UTC))` and `sequence` is `nextval('complaint_ref_seq')` via the injected `EntityManager` (`ComplaintSubmissionTemplate.nextReferenceCode()`). `DataSeeder` uses the identical sequence query so demo and live codes never collide. Uniqueness enforced by `uq_complaint_reference`.

### 6. Ward lookup contract

- Repository (`WardRepository`): `findByPointWithinBoundary(Object point)` (any active ward) and `findByPointWithinBoundary(Long municipalityId, Object point)` (scoped), both `ST_Contains(w.boundary, :point) = true`; plus `findByMunicipalityIdAndIsActiveTrueOrderByWardNumberAsc`, `findByMunicipalityIdAndWardNumber`.
- REST (`MunicipalityController`, base `/api/municipalities`, GETs are public): `GET /api/municipalities`, `GET /api/municipalities/{slug}`, `GET /api/municipalities/{slug}/wards`, `GET /api/municipalities/{slug}/wards/{wardNumber}`, `GET /api/municipalities/{slug}/departments[?category=]`, and `GET /api/municipalities/public/wards/lookup?lat={double}&lng={double}` → `WardResponse` (id, municipalityId, wardNumber, areaName, areaNameBn, isActive, createdAt, updatedAt) or 404 when no ward contains the point.
- Submission behavior: `resolveMunicipality` prefers the ward containing the point, else falls back to `findFirstByIsActiveTrue` (throws `IllegalStateException` if none); `resolveWard` assigns the scoped ward when found and leaves it null otherwise — a complaint outside every boundary is still accepted.

---

## (e) Deviations from the blueprint and why

| # | Blueprint says | Code does | Why |
|---|---|---|---|
| 1 | §7.5 `submit()` is `final` | `ComplaintSubmissionTemplate.submit()` is non-final `@Transactional` (with comment) | CGLIB cannot proxy a final method: calls ran on the unconstructed proxy with null repositories (NPE on every idempotent replay). Sequence stays fixed by convention |
| 2 | §5 R3 stores submission keys in an `idempotency_keys` table | Keys live on `complaints.submission_idempotency_key` (partial unique index); no `idempotency_keys` table exists | One-column partial index gives the same replay guarantee with one fewer table; transition replay stays on `uq_transition_idempotency` |
| 3 | §3.3 defines `resolution_attempts` and `complaint_assignments` | V3 creates neither; only `complaint_transitions`, hardened `attachments`, `outbox_messages` | Deferred to Phase 4 (assignments) / Phase 5 (resolutions, ratings); flat V1 rating columns were dropped instead of migrated |
| 4 | — (implied attached entity in reads) | `ComplaintQueryService.describe()` re-fetches by id inside its read-only tx | The submitted complaint is detached after its write tx commits; mapping lazy `municipality/ward/citizen` through it threw `LazyInitializationException` |
| 5 | — | Dashboard uses `@EntityGraph({municipality, ward, department})` + `@Transactional(readOnly = true)` | Same `LazyInitializationException` hazard Phase 2 fixed on `UserRepository`; fixed here without touching callers |
| 6 | — | `Attachment.createdAt` has `@CreationTimestamp` (service still sets it from `Clock`) | Direct builder inserts (e.g. smoke tests) otherwise violate NOT NULL; explicit values are preserved |
| 7 | §3.3 `citizen_id` nullable | V3 keeps the nullable definition **and** adds `ALTER COLUMN citizen_id DROP NOT NULL` | V1 created the column `NOT NULL`, making the V3 `ADD COLUMN IF NOT EXISTS` a no-op on migrated databases |
| 8 | — | `ComplaintLifecycleIntegrationTests` / `SubmissionIdempotencyTests` apply `springSecurity()` to `MockMvcBuilders` | Without the filter chain, Bearer tokens were ignored and every submit fell into the anonymous path (400); test-only fix |
| 9 | — | `citizen/complaint-form.html` header is `Report a local issue` | Aligns the page with the `AuthControllerIntegrationTests` contract |
| 10 | §7.3 outbox + workers | Rows accumulate only; no relay worker, no `SKIP LOCKED` claim query yet | Relay/delivery is Phase 5; `OutboxMessage` javadoc states this explicitly |

---

## (f) Citizen smoke test (exact curl)

Base `http://localhost:8080`. Seeded authority for VERIFY: `councilor17@example.com / councilor123` (serves `dhaka-north`; use a Gulshan pin so tenancy passes). Photo must be a real JPEG/PNG/WebP file.

```bash
# 1. Register a citizen → 201 with accessToken (save it as $CITIZEN)
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Amina Rahman","email":"amina-smoke@example.com","phone":"01712345678","password":"a-secure-password"}'

export CITIZEN='<accessToken-from-register>'

# 2. Submit a complaint with a photo → 201 with referenceCode (save it as $REF)
curl -s -X POST http://localhost:8080/api/complaints \
  -H "Authorization: Bearer $CITIZEN" \
  -F 'title=Large pothole on Lake Road' \
  -F 'description=The pothole is dangerous for motorcycles, especially after rain.' \
  -F 'category=ROADS' \
  -F 'latitude=23.7925' \
  -F 'longitude=90.4120' \
  -F 'photos=@/tmp/pothole.jpg'

export REF='<referenceCode-from-submit>'

# 3. Log in as the seeded ward councilor → 200 (save as $AUTH)
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"councilor17@example.com","password":"councilor123"}'

export AUTH='<accessToken-from-councilor-login>'

# 4. Verify the complaint → 200, status VERIFIED
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/verify?note=Verified-on-smoke-test" \
  -H "Authorization: Bearer $AUTH"

# 5. Check status as the citizen → 200, $.status == "VERIFIED"
curl -s "http://localhost:8080/api/complaints/$REF" \
  -H "Authorization: Bearer $CITIZEN"

# 6. Cancel as the owner (allowed from SUBMITTED or VERIFIED) → 200, status CANCELLED
curl -s -X POST "http://localhost:8080/api/complaints/$REF/cancel?reason=No-longer-needed" \
  -H "Authorization: Bearer $CITIZEN"
```

Expected status codes: register 201, submit 201, councilor login 200, verify 200, status check 200, cancel 200. Reject (alternative to verify) is `POST /api/authority/complaints/$REF/reject?reason=<required>` with `$AUTH`.
