# Improvements Handoff — Post-Cleanup Hardening (6 items)

**Date:** 2026-10-01
**Status:** ✅ **Complete. `./mvnw verify` → `Tests run: 96, Failures: 0, Errors: 0, BUILD SUCCESS`**
(94 pre-existing + 2 new `AutoCloseIntegrationTests`; 2 stale copy assertions in
`AuthControllerIntegrationTests` updated to the revamped templates — see §(e)).
**Scope:** The six improvements from the project review. No new dependencies.
`grep -r "TODO|FIXME|XXX" src/` returns only the pre-existing phone-mask and
logback-timezone artifacts.

---

## (a) Each improvement applied

### 1. AUTO_CLOSE daily job (blueprint §6 gap)
- `complaint/domain/enums/ComplaintAction.java` — new `AUTO_CLOSE` action.
- `complaint/lifecycle/AutoCloseHandler.java` (new) — RESOLVED → CLOSED with no
  rating; works for anonymous complaints (no owner to rate); refuses resolutions
  younger than 7 days even if the scheduler misfires; flips the latest attempt
  to CLOSED (missing attempt = corrupt data → loud failure, since `resolved_by`
  is NOT NULL and inventing a resolver would lie).
- `complaint/lifecycle/ComplaintLifecycleService.java` — new `executeAsSystem()`
  sharing the same funnel (row lock → replay check → version check → handler →
  audit row → outbox); NULL actor, `actorRole` SYSTEM; non-AUTO_CLOSE actions
  refuse a null actor. `publishStatusChanged` now null-safe on `actorId`.
- `complaint/repo/ComplaintRepository.java` — `findByStatusAndResolvedAtBefore`.
- `complaint/lifecycle/AutoCloseScheduler.java` (new) — daily
  `app.autoclose.cron` (default 02:30), advisory-locked, stable idempotency key
  `auto-close-<id>`, per-row failures logged and skipped.
- `src/test/java/com/nagorikseba/AutoCloseIntegrationTests.java` (new, 2 tests):
  stale RESOLVED closes exactly once (SYSTEM actor, unrated CLOSED attempt,
  second pass converges); fresh RESOLVED left alone.

### 2. Admin pages actually load for admins
- `shared/security/WebJwtAuthenticationFilter.java` (new, page-chain only, not a
  bean) — accepts the access token from `Authorization` header, `nagorikSebaToken`
  cookie, or `?accessToken=` param (fresh-tab fallback).
- `shared/config/SecurityConfig.java` — filter added to the page chain; the
  `/admin/** → ADMIN` rule now authenticates cookie holders instead of 403ing them.
- `static/js/auth.js`, `authority-login.js` — write the cookie on login;
  `nav.js` sign-out clears it. API callers unchanged (header path).

### 3. Real SMS/Email senders (no new deps)
- `notification/SmtpEmailSender.java` (prod) — actually sends via `JavaMailSender`
  when `app.notifications.email-enabled=true`; otherwise logs and returns (no
  FAILED pile-up on unconfigured deploys). Failures throw → worker retries.
- `notification/TwilioSmsSender.java` (prod) — real `api.twilio.com` POST via
  `java.net.http` (no SDK dependency/CVEs) when `sms-enabled` + SID/token/from
  are set; otherwise logs. Non-2xx throws → retry. Payload contract (`to`,
  `text`) already supplied by `NotificationListener`.
- `application-prod.yml` — added `EMAIL_ENABLED`, `APP_MAIL_FROM`;
  `application.yml` — dev `notifications` block (both off by default).

### 4. Multi-instance scheduler locks (no new deps)
- `shared/config/SchedulerLock.java` (new) — `pg_try_advisory_lock` wrapper;
  fail-open with a warning so a lock-query outage never silently stops SLA scans.
- Applied to `SlaBreachScheduler`, `OutboxRelayScheduler`, `DataRetentionScheduler`.
- `transparency/PerformanceSnapshotJob.java` — inline lock replaced with the helper;
  `@ConditionalOnProperty` removed (redundant: `@Scheduled` only fires when
  `SchedulingConfig` enables scheduling), so the boot seeder can inject it in dev.

### 5. Scoreboard non-empty on fresh boot
- `transparency/SnapshotSeedRunner.java` (new, order 20 after `DataSeeder` order 10)
  — snapshots the current month when the performance table is empty; never
  overwrites. On only with `app.snapshot.seed-on-boot` (dev `true`, tests `false`,
  prod unset → monthly job owns it).
- `config/DataSeeder.java` — explicit `@Order(10)` to fix runner ordering.
- `application-test.yml` — `seed-on-boot: false` (pristine DB for suites).

### 6. Privacy/logging/heatmap tuning
- `complaint/service/AttachmentService.java` — pure-Java WebP RIFF metadata strip
  (drops EXIF/XMP chunks, rewrites RIFF size, validates structure; malformed →
  original bytes, never fails an upload closed). JPEG/PNG path unchanged.
- `transparency/HeatmapService.java` — viewport-adaptive cluster grid
  (`span/100` clamped 0.001–0.02°): street bbox keeps ~100 m cells, city-wide
  returns districts not noise. Detail points still snap 0.001 (mapper unchanged);
  response adds `gridSize` in clustered mode only (privacy tests assert exact
  cell keys — unaffected).
- `logback-spring.xml` — rolling file appender (`logs/`, 50 MB × 14 days, 1 GB
  cap) with the same MDC pattern; logstash JSON stays the documented upgrade.
- `.gitignore` — `logs/`.

---

## (b) Test-only fix (pre-existing failure, not a regression)
`AuthControllerIntegrationTests` asserted landing copy (`"Report the problem"`,
`"Report a local issue"`) removed by the recent template revamp — the suite was
red before these improvements. Assertions updated to current copy
(`"Report Local Issues"`, `"Lodge Civic Grievance"`). No template changed.

---

## (c) Verification
- `./mvnw -q compile` → clean.
- `./mvnw verify` → **96 tests, 0 failures** (16 suites incl. new
  `AutoCloseIntegrationTests`; Docker Desktop required for Testcontainers).
- `grep -r "TODO|FIXME|XXX" src/` → only pre-existing artifacts.

## (d) Deliberately left for later (need infra/deps)
Bucket4j+Redis distributed rate limits, ShedLock, logstash JSON encoder,
Twilio delivery-receipt webhooks, session-based admin login (cookie JWT is the
pragmatic bridge, not the end state).
