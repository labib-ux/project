# Phase 5 Handoff — SLA, Notifications, Outbox & Public Transparency

**Date:** 2026-09-06
**Status:** ✅ **Complete. `DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw clean verify` → `Tests run: 79, Failures: 0, Errors: 0, BUILD SUCCESS`** (65 pre-existing + 14 new: 4 SLA + 5 outbox + 5 heatmap).
**Scope:** Blueprint §3.4–§3.6, §5 (R4/R5/R7), §7.3, §7.4, §9, §6 (RESOLVE/CLOSE/REOPEN edges).

> Names, paths, SQL identifiers and JSON keys below are copied from the code, not the blueprint.

---

## (a) Files created / modified (one line each)

### Migration

| File | Purpose |
|---|---|
| `src/main/resources/db/migration/V5__sla_outbox_notification.sql` | `sla_policies` (unique per municipality/category/priority), `sla_instances` (unique per complaint), `sla_breaches` (partial-unique active breach), notification-column superset, widened outbox CHECK + poll index, `resolution_attempts`, `ward_monthly_performance`, guarded backfill of ROADS/NORMAL=72h, WATERLOGGING/CRITICAL=12h, MOSQUITO_BREEDING/NORMAL=48h |

### SLA (`sla/`)

| File | Purpose |
|---|---|
| `sla/SlaPolicy.java` | Municipality policy entity (max + L1/L2 escalation hours) |
| `sla/SlaInstance.java` | Materialized per-complaint deadline snapshot (nullable policy for fallback path) |
| `sla/SlaBreach.java` | Breach detection row with active/cleared states |
| `sla/SlaPolicyRepository.java` | Active-policy lookup per municipality/category/priority |
| `sla/SlaInstanceRepository.java` | Instance lookup per complaint |
| `sla/SlaBreachRepository.java` | Active-breach lookup, history, active count |
| `sla/SlaService.java` | Deadline engine (bean `slaEngine`): policy → legacy-rule → 120h-default precedence, `ensureInstance`, reopen-half and priority recalculation — always `Clock` + `submittedAt` |
| `sla/SlaBreachScanner.java` | Unconditional scan logic: SKIP LOCKED overdue claim, breach-once insert, L1-councilor/L2-admin escalation with outbox + in-app rows, `clearBreachOnResolve` |
| `sla/SlaBreachScheduler.java` | Conditional hourly trigger (`app.sla.scan-cron`, default top of hour) |

### Lifecycle / resolution

| File | Purpose |
|---|---|
| `complaint/domain/ResolutionAttempt.java` | Per-cycle row with `Outcome` (PENDING_CITIZEN/CLOSED/REOPENED) and rating/reopen fields |
| `complaint/repo/ResolutionAttemptRepository.java` | Attempt history, latest-attempt and count queries |
| `complaint/lifecycle/ResolveHandler.java` | IN_PROGRESS → RESOLVED: assigned-officer (or ADMIN) guard, evidence validation, attempt row, breach clear, `COMPLAINT_RESOLVED` outbox + status event |
| `complaint/lifecycle/CloseHandler.java` | RESOLVED → CLOSED: citizen-only, rating 1–5 required, attempt CLOSED with rating, `COMPLAINT_CLOSED` outbox + event |
| `complaint/lifecycle/ReopenHandler.java` | RESOLVED → REOPENED: citizen-only, reason required, 5-reopen budget (6th → 422), priority HIGH, half-hours SLA recalc, `COMPLAINT_REOPENED` + councilor `SLA_ESCALATION` outbox rows + event |
| `complaint/lifecycle/TransitionCommand.java` | Added nullable `rating`/`feedback` + `ofRated()` overload (existing factories unchanged) — deviation, see (b) |
| `complaint/api/CitizenComplaintController.java` | Added `POST /{ref}/rate?rating=&feedback=` and `POST /{ref}/reopen?reason=` |
| `complaint/api/AuthorityComplaintController.java` | Added multipart `POST /complaints/{ref}/resolve` (stages proof photos, then RESOLVE with evidence ids) — deviation, see (b) |
| `complaint/service/ComplaintMapper.java` | Added `toPublicResponse` (snapped coords, no PII) + `snap()` — real path, not the scoped `api/` path — deviation, see (b) |

### Outbox + notifications

| File | Purpose |
|---|---|
| `shared/outbox/OutboxRepository.java` | Added single-statement `claimBatch(size, now)` (`UPDATE … RETURNING *` with SKIP LOCKED) |
| `shared/config/SchedulingConfig.java` | Conditional `@EnableScheduling` switch for `app.scheduling.enabled` |
| `shared/config/AsyncConfig.java` | `outboxExecutor` pool (2–4 threads) for parallel relay dispatch |
| `notification/ComplaintStatusChangedEvent.java` | IDs-only status-change record published inside the lifecycle transaction |
| `notification/NotificationTemplateService.java` | bn/en template rendering with `{referenceCode}/{status}/{note}` placeholders |
| `notification/NotificationMessage.java` | In-app row entity on `notifications` (explicit JPA name `AppNotification`; `outbox_id` for redelivery dedupe) |
| `notification/NotificationMessageRepository.java` | Inbox reads, unread count, outbox-id existence check |
| `notification/ChannelSender.java` | Delivery port: `channel()` + `send()` (throw = retry) |
| `notification/LoggingSmsSender.java` / `LoggingEmailSender.java` | Dev (`!prod`) no-op loggers |
| `notification/TwilioSmsSender.java` / `SmtpEmailSender.java` | `prod` wiring points (same channel names, log until SDK/credentials land) |
| `notification/NotificationDispatcher.java` | Event-type router (deliberately non-transactional — see (b)): SMS/EMAIL → senders, COMPLAINT_*/SLA_ESCALATION → deduped in-app rows |
| `notification/NotificationListener.java` | AFTER_COMMIT (REQUIRES_NEW) fan-out: durable SMS_SEND/EMAIL_SEND outbox rows for recipients with phone/email |
| `notification/OutboxWorker.java` | Unconditional relay logic: `claim` + per-row `processOne` (SENT / backoff / FAILED after 5) on the executor |
| `notification/OutboxRelayScheduler.java` | Conditional 10 s trigger for the worker |

### Transparency (`transparency/`)

| File | Purpose |
|---|---|
| `transparency/WardMonthlyPerformance.java` | Monthly snapshot entity with ward/period uniqueness |
| `transparency/WardMonthlyPerformanceRepository.java` | Snapshot reads (ward+period, municipality ranking) |
| `transparency/HeatmapService.java` | Public bbox query: 500-point threshold, `ST_SnapToGrid(0.001)` clustering, mapper public projection for detail |
| `transparency/ScoreboardService.java` | Monthly ward rankings (resolution rate, Clock-based period default) |
| `transparency/PerformanceSnapshotJob.java` | Conditional monthly snapshot with `pg_try_advisory_lock` + per-ward upserts |
| `transparency/api/PublicTransparencyController.java` | `GET /api/public/heatmap` + `GET /api/public/wards/scoreboard` (wards list intentionally not duplicated) |
| `transparency/api/PublicRateLimitFilter.java` | 60/min/IP sliding window on `/api/public/**` → 429 + `Retry-After` |

### UI / seed / tests

| File | Purpose |
|---|---|
| `src/main/resources/templates/citizen/complaint-detail.html` | Timeline, photo gallery, rate/reopen forms (fetch + token) |
| `src/main/resources/static/js/heatmap.js` | Leaflet + heat layer, debounced moveend fetch, category legend |
| `src/main/resources/static/js/timeline.js` | Detail render + 30 s complaint-endpoint polling |
| `src/main/resources/templates/index.html` | Added live-map section (heatmap div, municipality select, legend) + scoreboard table |
| `src/main/resources/messages_en.properties` / `messages_bn.properties` | 7 template codes in English + Bangla |
| `config/DataSeeder.java` | Full municipality SLA-policy matrix (all categories × priorities; LOW 72/NORMAL 48/HIGH 24/CRITICAL 12) — real path, not the scoped `bootstrap/` path — deviation, see (b) |
| `src/test/resources/application-test.yml` | `app.scheduling.enabled: false` for the test profile |
| `src/test/java/com/nagorikseba/SlaScannerIntegrationTests.java` | D13: breach-once, L1 + outbox row, concurrent-scan convergence, breach cleared on resolve |
| `src/test/java/com/nagorikseba/OutboxDeliveryIntegrationTests.java` | D14: delivery + in-app row, fail-twice-then-SENT, 5-failures → FAILED, redelivery convergence, disjoint concurrent claims |
| `src/test/java/com/nagorikseba/HeatmapPrivacyIntegrationTests.java` | D15: exhaustive PII-absence, ≤150 m obfuscation, exclusion rules, >500 clustering, scoreboard allowlist |

---

## (b) Key decisions and why (+ deviations)

1. **Trigger/logic split for schedulers.** `SlaBreachScanner` and `OutboxWorker` are unconditional (handlers and tests inject them everywhere); only `SlaBreachScheduler` / `OutboxRelayScheduler` / `PerformanceSnapshotJob` obey `app.scheduling.enabled`. Conditioning the logic beans broke every non-scheduler context at startup (missing-bean cascade into `ResolveHandler`).
2. **Dispatcher is non-transactional.** An inner boundary marked the worker's transaction rollback-only when delivery threw, making catch-and-backoff uncommittable. One boundary per row (the worker's) is the whole retry design.
3. **Notifications table extended, not reshaped.** V5 only adds nullable columns; the V1 entity still validates. The new `NotificationMessage` entity carries explicit JPA name `AppNotification` (duplicate default names fail bootstrap) and its own repository (duplicate `notificationRepository` bean names likewise).
4. **Evidence validated, not linked.** `Attachment.transition` has no mutator and its file is out of scope, so `ResolveHandler` enforces "each evidence id exists, belongs to this complaint, is not deleted" and documents the linking gap instead of pretending.
5. **Outbox statuses widened** (`PROCESSING`, `SENT` added to the CHECK); the V3 time-ordered index is kept and the blueprint `(status, next_attempt_at)` poll index added alongside.
6. **Deviations (additive, documented):** `TransitionCommand` + `rating`/`feedback` + `ofRated()` (rating cannot ride on `note`); `POST …/resolve` on the authority controller (citizen chain 403s officers); `ComplaintMapper` edited at its real `service/` path; `DataSeeder` edited at its real `config/` path (both scoped paths do not exist); `test/application-test.yml` created per the scheduling constraint.

---

## (c) Notification template codes and placeholder schema

Codes (identical keys in `messages_en` + `messages_bn`): `COMPLAINT_SUBMITTED`, `COMPLAINT_VERIFIED`, `COMPLAINT_ASSIGNED`, `COMPLAINT_RESOLVED`, `COMPLAINT_CLOSED`, `COMPLAINT_REOPENED`, `SLA_ESCALATION`. Every template takes exactly `{referenceCode}`, `{status}`, `{note}` (empty string when absent; whitespace collapsed). Unknown codes fall back to generic English, never throw. Default locale `bn`.

---

## (d) Outbox payload schema (exact JSON fields per event type)

Common: `complaintId (number)`, `referenceCode (string)`, `occurredAt (ISO string)`. Per type:

| Event | Extra fields |
|---|---|
| `COMPLAINT_STATUS_CHANGED` | `action`, `from`, `to`, `actorId`, `note\|null` |
| `COMPLAINT_ASSIGNED` | `action:"ASSIGN"`, `departmentId`, `departmentCode`, `officerId\|null`, `strategy`, `explanation`, `actorId` |
| `COMPLAINT_WORK_STARTED` | `action:"START"`, `actorId`, `note\|null` |
| `COMPLAINT_RESOLVED` | `action:"RESOLVE"`, `attemptNumber`, `evidenceCount`, `actorId`, `note\|null` |
| `COMPLAINT_CLOSED` | `action:"CLOSE"`, `rating`, `actorId` |
| `COMPLAINT_REOPENED` | `action:"REOPEN"`, `reopenCount`, `actorId`, `note` |
| `SLA_ESCALATION` | `escalationLevel`, `hoursOverdue`, `deadline`, `explanation`, `escalatedToUserId\|null` (+ `note` on reopen variant) |
| `SMS_SEND` / `EMAIL_SEND` | `to`, `locale`, `text`, `referenceCode`, `complaintId` |

`aggregateType` is always `COMPLAINT`. Relay: success → `SENT`; failure → `retryCount+1`, `nextAttemptAt = now + 2^retries` minutes, terminal `FAILED` at 5.

---

## (e) Scheduler toggle mechanism (for Phase 6 tests)

- Beans: `SlaBreachScheduler`, `OutboxRelayScheduler`, `PerformanceSnapshotJob` carry `@ConditionalOnProperty(name="app.scheduling.enabled", havingValue="true", matchIfMissing=false)`; logic beans (`SlaBreachScanner`, `OutboxWorker`, services) are always present.
- `src/test/resources/application-test.yml` sets `app.scheduling.enabled: false` for every `@ActiveProfiles("test")` suite.
- Opt-in per suite: `@SpringBootTest(properties = "app.scheduling.enabled=true")` (used by all three Phase-5 suites). Background triggers then exist but tests stay deterministic by (1) calling `scanOnce()` / `processOne(id)` / `claim(size, instant)` directly, (2) future-dating rows the background poller (real clock) must not see, and (3) oversizing concurrent claims where sibling suites share the context database.
- Production currently ships with scheduling **off** (no key in `application.yml` + `matchIfMissing=false`); Phase 6's `application-prod.yml` is the place to turn it on.

---

## (f) Public API field allowlist (Phase 6 contract)

- Heatmap detail points: exactly `{referenceCode, category, status, lng, lat}` (snapped). Clustered cells: exactly `{lng, lat, category, count}`. Envelope: `{clustered, points}`.
- Scoreboard entries: exactly `{wardId, wardNumber, areaName, totalComplaints, resolvedComplaints, resolutionRate, averageResolutionHours, slaBreachCount}`.
- Forbidden everywhere public: `citizenName`, `citizenPhone`, email/phone strings, `addressText`, exact coordinates, `anonymousContactPhone`. Enforced by `HeatmapPrivacyIntegrationTests` (exhaustive key + substring assertions).

---

## (g) Contracts Phase 6 depends on

- **Resolution lifecycle:** attempt number = per-complaint count + 1; latest attempt resolves CLOSE/REOPEN; `Outcome` enum has exactly PENDING_CITIZEN/CLOSED/REOPENED; reopen budget constant `ReopenHandler.MAX_REOPENS = 5`.
- **SLA engine:** `slaEngine.ensureInstance` (idempotent), `recalculateForReopen` (now + 50%), `recalculateForPriority` (from `submittedAt`); policy precedence policy → legacy `sla_rules` → `app.sla.default-hours` (120); `SlaBreachScanner.clearBreachOnResolve(complaintId, now)` for future resolve paths.
- **Outbox relay:** `OutboxWorker.claim(size, now)` / `processOne(id)` / `processBatch(size)`; `OutboxRepository.claimBatch` single-statement SKIP LOCKED claim; `NotificationDispatcher.channels()`; `ChannelSender` SPI (`channel()` unique) with `!prod` logging and `prod` wiring-point senders.
- **Notifications:** `NotificationTemplateService.render(code, locale, vars)`; listener writes only SMS/EMAIL outbox rows (in-app rows come from the worker, deduped by `(outbox_id, user_id)`); `NotificationMessageRepository.findByUserIdOrderByIdDesc` feeds badges/lists.
- **Transparency:** `HeatmapService.heatmap(slug, bbox)` (threshold constant `CLUSTER_THRESHOLD = 500`, `GRID_SIZE = 0.001`); `ScoreboardService.scoreboard(municipalityId, yyyy-MM|null)`; `PerformanceSnapshotJob.snapshotMonth(first-of-month)` + advisory lock name `ward-snapshot-job`; `PublicRateLimitFilter.LIMIT_PER_MINUTE = 60`.
- **Seeded policy data:** full category × priority matrix per municipality from `DataSeeder`; migration backfill values (ROADS/NORMAL 72, WATERLOGGING/CRITICAL 12, MOSQUITO_BREEDING/NORMAL 48) for pre-existing databases.

---

## (h) Full-lifecycle smoke test (curl)

Base `http://localhost:8080`. Photo files must be real JPEG/PNG/WebP.

```bash
# 1. Register citizen → 201 (save $CITIZEN); login officer1 + admin
curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"fullName":"Lifecycle Five","email":"phase5-smoke@example.com","phone":"01712345888","password":"a-secure-password"}'
export CITIZEN='<accessToken>'
curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"officer1@demo","password":"demo1234"}'
export OFFICER='<accessToken>'

# 2. Submit → 201 (save $REF; save first attachment id $PROOF)
curl -s -X POST http://localhost:8080/api/complaints \
  -H "Authorization: Bearer $CITIZEN" \
  -F 'title=Broken streetlight' -F 'description=Light out for three nights.' \
  -F 'category=ELECTRICITY' -F 'latitude=23.7890' -F 'longitude=90.4000' \
  -F 'photos=@/tmp/light.jpg'
export REF='<referenceCode>' PROOF='<attachments[0].id>'

# 3. Verify (councilor17/councilor123) → 200 VERIFIED
export COUNCILOR=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"identifier":"councilor17@example.com","password":"councilor123"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/verify?note=smoke" \
  -H "Authorization: Bearer $COUNCILOR"

# 4. Auto-assign (officer) → 200 ASSIGNED; start → 200 IN_PROGRESS
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/assign/auto" \
  -H "Authorization: Bearer $OFFICER"
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/start" \
  -H "Authorization: Bearer $OFFICER"

# 5. Resolve with work-proof photo → 200 RESOLVED
curl -s -X POST "http://localhost:8080/api/authority/complaints/$REF/resolve?note=bulb-replaced" \
  -H "Authorization: Bearer $OFFICER" -F 'photos=@/tmp/fixed.jpg'

# 6. Citizen rates 5★ → 200 CLOSED
curl -s -X POST "http://localhost:8080/api/complaints/$REF/rate?rating=5&feedback=Fixed+fast%21" \
  -H "Authorization: Bearer $CITIZEN"

# 7. Reopen path on a SECOND complaint ($REF2 via steps 2–5): → 200 REOPENED, priority HIGH
curl -s -X POST "http://localhost:8080/api/complaints/$REF2/reopen?reason=Light+is+out+again" \
  -H "Authorization: Bearer $CITIZEN"

# 8. Force an SLA breach → scanner (hourly cron) → breach row exists.
# Backdate the deadline, then wait for the top-of-hour run (or restart once with
# --app.sla.scan-cron='*/30 * * * * *' for a 30 s cadence while smoking):
psql "$DB_URL" -c "UPDATE sla_instances SET deadline_at = now() - interval '26 hours'
  WHERE complaint_id = (SELECT id FROM complaints WHERE reference_code = '$REF2');"
# ... after the next scan ...
psql "$DB_URL" -c "SELECT complaint_id, escalation_level, hours_overdue FROM sla_breaches WHERE resolved_at IS NULL;"
psql "$DB_URL" -c "SELECT event_type FROM outbox_messages WHERE event_type = 'SLA_ESCALATION' ORDER BY id DESC LIMIT 3;"
```

Expected: submit 201 → verify/assign/start/resolve 200s → rate 200 CLOSED → reopen 200 REOPENED (priority HIGH) → exactly one active `sla_breaches` row for `$REF2` plus an `SLA_ESCALATION` outbox row. (Manual resolve may alternatively pass the submit-time attachment id as evidence; the endpoint accepts fresh proof files as shown.)
