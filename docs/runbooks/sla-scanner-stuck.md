# Runbook: SLA scanner stuck

## Symptoms

- Overdue complaints never escalate: no new `sla_breaches` rows, no `SLA_ESCALATION` outbox rows.
- `sla.breach.detected` counter flat while overdue instances exist.
- Logs show `SLA scanner run failed` from `SlaBreachScheduler`.

## Check state

```sql
-- Overdue, unbreached, still-active instances the next run should claim:
SELECT i.id, i.complaint_id, i.deadline_at, c.status
FROM sla_instances i JOIN complaints c ON c.id = i.complaint_id
WHERE i.deadline_at < now() AND i.breach_at IS NULL
  AND c.status IN ('SUBMITTED','VERIFIED','ASSIGNED','IN_PROGRESS','REOPENED')
ORDER BY i.id;

-- Active (uncleared) breaches:
SELECT id, complaint_id, escalation_level, hours_overdue, detected_at
FROM sla_breaches WHERE resolved_at IS NULL ORDER BY detected_at DESC;

-- Is another instance holding the work? (Scanner uses per-row SKIP LOCKED,
-- so overlap is impossible — but a second scheduler with a broken clock can starve.)
SELECT * FROM pg_locks WHERE NOT granted;
```

Also confirm scheduling is on: `app.scheduling.enabled=true` (prod profile sets
it; default is off) and the cron `app.sla.scan-cron` (default top of hour).

## Manually trigger a scan

There is no HTTP trigger by design (scanners are not web-callable). Against a
running instance, either wait for the next cron fire or restart once with an
accelerated cadence:

```bash
SPRING_PROFILES_ACTIVE=prod ./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="" \
  --app.sla.scan-cron='*/30 * * * * *'
```

In tests, call `SlaBreachScanner.scanOnce()` directly (see
`SlaScannerIntegrationTests`).

## Restart procedure

1. Confirm no deploy is mid-migration (`flyway_schema_history` has no failed rows).
2. Rolling restart: new instance boots, Flyway validates, scheduler resumes on cron.
3. Verify: `sla_breaches` grows on the next run and `SLA_ESCALATION` rows appear
   in `outbox_messages`.
