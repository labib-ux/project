-- ============================================================================
-- Nagorik Seba — V5 SLA, notifications, outbox relay & transparency (§3.4–§3.6)
-- PostgreSQL 16 + PostGIS 3.4
-- Idempotent on fresh DB and existing dev DB (IF EXISTS / IF NOT EXISTS guards,
-- guarded seeds, ON CONFLICT DO NOTHING).
-- ============================================================================

-- ============================================================================
-- sla_policies — one active policy per municipality/category/priority
-- ============================================================================
CREATE TABLE IF NOT EXISTS sla_policies (
    id                          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    municipality_id             BIGINT NOT NULL REFERENCES municipalities(id),
    category                    VARCHAR(50) NOT NULL,
    priority                    VARCHAR(20) NOT NULL,
    max_hours                   INT NOT NULL CHECK (max_hours > 0),
    escalation_level_1_hours    INT,
    escalation_level_2_hours    INT,
    is_active                   BOOLEAN NOT NULL DEFAULT true,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_sla_policy') THEN
        ALTER TABLE sla_policies
            ADD CONSTRAINT uq_sla_policy UNIQUE (municipality_id, category, priority);
    END IF;
END $$;

-- ============================================================================
-- sla_instances — materialized per-complaint SLA state.
-- policy_id is nullable: when no policy matches, the service falls back to the
-- configured default hours (app.sla.default-hours) instead of crashing the flow.
-- ============================================================================
CREATE TABLE IF NOT EXISTS sla_instances (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id            BIGINT NOT NULL UNIQUE REFERENCES complaints(id) ON DELETE CASCADE,
    policy_id               BIGINT REFERENCES sla_policies(id),
    deadline_at             TIMESTAMPTZ NOT NULL,
    warn_at                 TIMESTAMPTZ,
    breach_at               TIMESTAMPTZ,
    last_calculated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sla_instance_deadline ON sla_instances (deadline_at)
    WHERE breach_at IS NULL;

-- ============================================================================
-- sla_breaches — breach-once per complaint (partial unique on active breach)
-- ============================================================================
CREATE TABLE IF NOT EXISTS sla_breaches (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sla_instance_id         BIGINT NOT NULL REFERENCES sla_instances(id) ON DELETE CASCADE,
    complaint_id            BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    detected_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    hours_overdue           NUMERIC(6,2) NOT NULL,
    escalation_level        INT NOT NULL DEFAULT 1,
    escalated_to_user_id    BIGINT REFERENCES users(id),
    resolved_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial unique indexes (Postgres has no partial UNIQUE table constraint).
CREATE UNIQUE INDEX IF NOT EXISTS uq_breach_active_per_complaint
    ON sla_breaches (complaint_id) WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sla_open_breach ON sla_breaches (escalation_level)
    WHERE resolved_at IS NULL;

-- ============================================================================
-- notifications — extend the V1 table (superset: old columns untouched so the
-- V1 entity still validates; new nullable columns serve template rendering)
-- ============================================================================
ALTER TABLE IF EXISTS notifications
    ADD COLUMN IF NOT EXISTS complaint_id   BIGINT REFERENCES complaints(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS template_code  VARCHAR(60),
    ADD COLUMN IF NOT EXISTS locale         VARCHAR(10),
    ADD COLUMN IF NOT EXISTS payload        JSONB,
    ADD COLUMN IF NOT EXISTS read_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS outbox_id      BIGINT REFERENCES outbox_messages(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_outbox_user
    ON notifications (outbox_id, user_id) WHERE outbox_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notification_user_unread ON notifications (user_id, id DESC)
    WHERE is_read = false;

-- ============================================================================
-- outbox_messages — widen statuses for the relay (PROCESSING claim, SENT done)
-- and add the blueprint poll index (the V3 time-ordered index is kept too)
-- ============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outbox_status') THEN
        ALTER TABLE outbox_messages DROP CONSTRAINT ck_outbox_status;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_outbox_status') THEN
        ALTER TABLE outbox_messages
            ADD CONSTRAINT ck_outbox_status
            CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'PUBLISHED', 'FAILED', 'DEAD'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_outbox_poll ON outbox_messages (status, next_attempt_at)
    WHERE status IN ('PENDING', 'FAILED');

-- ============================================================================
-- resolution_attempts — one row per resolve → rate/reopen cycle (§3.3)
-- ============================================================================
CREATE TABLE IF NOT EXISTS resolution_attempts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id        BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    attempt_number      INT NOT NULL,
    resolved_at         TIMESTAMPTZ NOT NULL,
    resolved_by         BIGINT NOT NULL REFERENCES users(id),
    resolution_note     TEXT,
    outcome             VARCHAR(20) NOT NULL,
    rating              INT CHECK (rating BETWEEN 1 AND 5),
    rating_feedback     TEXT,
    rated_at            TIMESTAMPTZ,
    reopen_reason       TEXT,
    reopened_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_resolution_attempt') THEN
        ALTER TABLE resolution_attempts
            ADD CONSTRAINT uq_resolution_attempt UNIQUE (complaint_id, attempt_number);
    END IF;
END $$;

-- ============================================================================
-- ward_monthly_performance — transparency snapshots (§3.6)
-- ============================================================================
CREATE TABLE IF NOT EXISTS ward_monthly_performance (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ward_id                 BIGINT NOT NULL REFERENCES wards(id) ON DELETE CASCADE,
    municipality_id         BIGINT NOT NULL REFERENCES municipalities(id) ON DELETE CASCADE,
    period_start            DATE NOT NULL,
    total_complaints        INT NOT NULL DEFAULT 0,
    resolved_complaints     INT NOT NULL DEFAULT 0,
    avg_resolution_hours    NUMERIC(10,2),
    avg_rating              NUMERIC(3,2),
    sla_breach_count        INT NOT NULL DEFAULT 0,
    reopen_rate             NUMERIC(5,4),
    computed_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ward_period UNIQUE (ward_id, period_start),
    CONSTRAINT ck_ward_period_counts CHECK (resolved_complaints <= total_complaints)
);

CREATE INDEX IF NOT EXISTS idx_ward_perf_municipality_period
    ON ward_monthly_performance (municipality_id, period_start DESC);

-- ============================================================================
-- Default SLA policy backfill (documented values).
-- Guarded on municipalities existing: on a fresh database this migration runs
-- before the seeder creates municipalities and is a no-op; the seeder's full
-- category × priority matrix is canonical there. On pre-existing databases
-- this backfills the three reference policies without touching present rows.
-- ============================================================================
INSERT INTO sla_policies
    (municipality_id, category, priority, max_hours,
     escalation_level_1_hours, escalation_level_2_hours, is_active)
SELECT m.id, v.category, v.priority, v.max_hours, v.l1_hours, v.l2_hours, true
FROM municipalities m
CROSS JOIN (VALUES
    ('ROADS', 'NORMAL', 72, 48, 72),
    ('WATERLOGGING', 'CRITICAL', 12, 6, 12),
    ('MOSQUITO_BREEDING', 'NORMAL', 48, 24, 48)
) AS v(category, priority, max_hours, l1_hours, l2_hours)
ON CONFLICT (municipality_id, category, priority) DO NOTHING;
