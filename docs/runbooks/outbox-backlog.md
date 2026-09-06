# Runbook: outbox backlog

## Symptoms

- `OutboxLagHealthIndicator` DOWN (`/actuator/health` shows `outboxLag` with
  `lagSeconds > 600`), or `outbox.lag.seconds` climbing.
- Notifications arrive late or not at all; `notification.delivery{result=failed}` rising.

## Inspect

```sql
-- Backlog by status:
SELECT status, count(*), min(next_attempt_at), max(next_attempt_at)
FROM outbox_messages GROUP BY status;

-- Oldest claimable rows (what the relay should be working on):
SELECT id, event_type, status, retry_count, next_attempt_at, left(last_error, 200)
FROM outbox_messages
WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now()
ORDER BY id LIMIT 20;

-- Terminal failures needing a decision:
SELECT id, event_type, retry_count, left(last_error, 200), last_error
FROM outbox_messages WHERE status = 'FAILED' ORDER BY id DESC LIMIT 20;
```

Checklist order: worker running (`app.scheduling.enabled=true`)? Downstream
credentials valid (Twilio/SMTP)? A single poison row (invalid JSON payload)
blocks nothing — rows are independent — but a down provider fails everything.

## Replay procedure

Re-queue terminal rows after fixing the cause (credentials, provider outage).
Only `FAILED` rows are ever re-queued; `SENT` rows are never touched:

```sql
-- Re-queue all terminal rows with immediate backoff reset:
UPDATE outbox_messages
SET status = 'PENDING', retry_count = 0, next_attempt_at = now(), last_error = NULL
WHERE status = 'FAILED';

-- Or a single row by id:
UPDATE outbox_messages
SET status = 'PENDING', retry_count = 0, next_attempt_at = now(), last_error = NULL
WHERE id = :id AND status = 'FAILED';
```

The relay claims them on its next 10 s poll. Redelivery is safe: channel sends
carry the outbox id in provider metadata, and in-app writes converge on the
`(outbox_id, user_id)` unique constraint (R5).

## Twilio / SMTP credential rotation

1. Set the new `TWILIO_SID` / `TWILIO_TOKEN` / `TWILIO_FROM` (or `SMTP_HOST`,
   `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`) in the deploy environment.
2. Rolling restart (senders read env at boot; see `TwilioSmsSender` /
   `SmtpEmailSender` wiring points).
3. Re-queue `FAILED` rows per above; watch `notification.delivery` recover.
4. Old credentials remain valid until the provider revokes them — rotate first,
   revoke second.
