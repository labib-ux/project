-- ============================================================================
-- Nagorik Seba — V3 complaint core (Blueprint §3.3)
-- PostgreSQL 16 + PostGIS 3.4
-- Core complaint aggregate: complaints, complaint_transitions, attachments
-- Idempotent on fresh DB and existing dev DB (IF EXISTS guards)
-- ============================================================================

-- ============================================================================
-- Sequence for reference_code generation: NS-yyyy-######
-- ============================================================================
CREATE SEQUENCE IF NOT EXISTS complaint_ref_seq;

-- ============================================================================
-- complaints — hardened aggregate root
-- ============================================================================
-- Drop legacy columns that existed in V1 if present
ALTER TABLE IF EXISTS complaints
    DROP COLUMN IF EXISTS latitude,
    DROP COLUMN IF EXISTS longitude,
    DROP COLUMN IF EXISTS deadline_at,
    DROP COLUMN IF EXISTS rating,
    DROP COLUMN IF EXISTS rating_feedback,
    DROP COLUMN IF EXISTS reopen_reason;

-- Add new columns per blueprint
ALTER TABLE IF EXISTS complaints
    ADD COLUMN IF NOT EXISTS reference_code          VARCHAR(20) NOT NULL,
    ADD COLUMN IF NOT EXISTS municipality_id         BIGINT NOT NULL REFERENCES municipalities(id),
    ADD COLUMN IF NOT EXISTS ward_id                 BIGINT REFERENCES wards(id),
    ADD COLUMN IF NOT EXISTS citizen_id              BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS anonymous_contact_phone VARCHAR(20),
    ADD COLUMN IF NOT EXISTS title                   VARCHAR(200) NOT NULL,
    ADD COLUMN IF NOT EXISTS description             TEXT NOT NULL,
    ADD COLUMN IF NOT EXISTS category                VARCHAR(50) NOT NULL,
    ADD COLUMN IF NOT EXISTS status                  VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    ADD COLUMN IF NOT EXISTS priority                VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    ADD COLUMN IF NOT EXISTS location                geography(Point, 4326) NOT NULL,
    ADD COLUMN IF NOT EXISTS location_source         VARCHAR(20) NOT NULL DEFAULT 'DEVICE',
    ADD COLUMN IF NOT EXISTS address_text            VARCHAR(300),
    ADD COLUMN IF NOT EXISTS assigned_department_id  BIGINT REFERENCES departments(id),
    ADD COLUMN IF NOT EXISTS assigned_officer_id     BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS submitted_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS first_verified_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS first_assigned_at       TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_at             TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS closed_at               TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_transition_at      TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reopen_count            INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rejection_reason        TEXT,
    ADD COLUMN IF NOT EXISTS cancellation_reason     TEXT,
    ADD COLUMN IF NOT EXISTS is_public_visible       BOOLEAN NOT NULL DEFAULT true,
    ADD COLUMN IF NOT EXISTS moderation_status       VARCHAR(20) NOT NULL DEFAULT 'APPROVED',
    ADD COLUMN IF NOT EXISTS version                 INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at              TIMESTAMPTZ NOT NULL DEFAULT now();

-- Blueprint §3.3: citizen_id is nullable (NULL = anonymous).
-- V1 created it NOT NULL; ADD COLUMN IF NOT EXISTS above is a no-op when the
-- column already exists, so drop the legacy NOT NULL explicitly.
ALTER TABLE IF EXISTS complaints ALTER COLUMN citizen_id DROP NOT NULL;

-- Convert existing timestamp columns to timestamptz if they exist
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'complaints' AND column_name = 'submitted_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE complaints ALTER COLUMN submitted_at TYPE TIMESTAMPTZ USING submitted_at AT TIME ZONE 'Asia/Dhaka';
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'complaints' AND column_name = 'resolved_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE complaints ALTER COLUMN resolved_at TYPE TIMESTAMPTZ USING resolved_at AT TIME ZONE 'Asia/Dhaka';
    END IF;
END $$;

-- Unique constraint on reference_code (idempotent)
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_complaint_reference') THEN
        ALTER TABLE complaints ADD CONSTRAINT uq_complaint_reference UNIQUE (reference_code);
    END IF;
END $$;

-- Check constraints
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_complaint_reopen_count') THEN
        ALTER TABLE complaints ADD CONSTRAINT ck_complaint_reopen_count CHECK (reopen_count >= 0 AND reopen_count <= 5);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_complaint_anonymous') THEN
        ALTER TABLE complaints ADD CONSTRAINT ck_complaint_anonymous CHECK (
            (citizen_id IS NOT NULL) OR (anonymous_contact_phone IS NOT NULL)
        );
    END IF;
END $$;

-- Indexes per blueprint §4
CREATE INDEX IF NOT EXISTS idx_complaint_tenant_status ON complaints (municipality_id, status);
CREATE INDEX IF NOT EXISTS idx_complaint_ward_status ON complaints (ward_id, status) WHERE status NOT IN ('CLOSED','REJECTED','CANCELLED');
CREATE INDEX IF NOT EXISTS idx_complaint_citizen ON complaints (citizen_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS idx_complaint_officer_active ON complaints (assigned_officer_id) WHERE status IN ('ASSIGNED','IN_PROGRESS','REOPENED');
CREATE INDEX IF NOT EXISTS idx_complaint_public_heatmap ON complaints (municipality_id, category, status)
    WHERE is_public_visible AND moderation_status = 'APPROVED' AND status NOT IN ('REJECTED','CANCELLED');
CREATE INDEX IF NOT EXISTS idx_complaint_location_gist ON complaints USING GIST (location);

-- ============================================================================
-- complaint_transitions — append-only lifecycle log
-- ============================================================================
CREATE TABLE IF NOT EXISTS complaint_transitions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id    BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    from_status     VARCHAR(30),
    to_status       VARCHAR(30) NOT NULL,
    action          VARCHAR(30) NOT NULL,
    actor_user_id   BIGINT REFERENCES users(id),
    actor_role      VARCHAR(20),
    note            TEXT,
    metadata        JSONB,
    idempotency_key VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transition_idempotency UNIQUE (complaint_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_transition_complaint_time ON complaint_transitions (complaint_id, created_at);

-- ============================================================================
-- attachments — hardened with checksum, scan_status, soft delete
-- ============================================================================
-- Drop legacy columns if present
ALTER TABLE IF EXISTS attachments
    DROP COLUMN IF EXISTS file_url,
    DROP COLUMN IF EXISTS file_type,
    DROP COLUMN IF EXISTS uploaded_at;

-- Add new columns per blueprint
ALTER TABLE IF EXISTS attachments
    ADD COLUMN IF NOT EXISTS storage_key         VARCHAR(300) NOT NULL,
    ADD COLUMN IF NOT EXISTS storage_provider    VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN IF NOT EXISTS original_filename   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS content_type        VARCHAR(100) NOT NULL,
    ADD COLUMN IF NOT EXISTS byte_size           BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 10485760),
    ADD COLUMN IF NOT EXISTS checksum_sha256     VARCHAR(64) NOT NULL,
    ADD COLUMN IF NOT EXISTS is_work_proof       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS scan_status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS deleted_at          TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS uploaded_by         BIGINT REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS transition_id       BIGINT REFERENCES complaint_transitions(id),
    ADD COLUMN IF NOT EXISTS created_at          TIMESTAMPTZ NOT NULL DEFAULT now();

-- Convert existing timestamp if needed
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name = 'attachments' AND column_name = 'uploaded_at' AND data_type = 'timestamp without time zone') THEN
        ALTER TABLE attachments ALTER COLUMN uploaded_at TYPE TIMESTAMPTZ USING uploaded_at AT TIME ZONE 'Asia/Dhaka';
    END IF;
END $$;

-- Unique constraint on storage_key
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_attachment_storage_key') THEN
        ALTER TABLE attachments ADD CONSTRAINT uq_attachment_storage_key UNIQUE (storage_key);
    END IF;
END $$;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_attachment_complaint ON attachments (complaint_id) WHERE deleted_at IS NULL;

-- ============================================================================
-- outbox_messages — transactional outbox (§3.5, §7.3)
-- ============================================================================
-- Rows accumulate from Phase 3 onward; the relay worker lands in Phase 5.
-- The PENDING/FAILED partial index is the claim query's access path.
CREATE TABLE IF NOT EXISTS outbox_messages (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  VARCHAR(30) NOT NULL,
    aggregate_id    BIGINT NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count     INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED', 'DEAD'))
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox_messages (next_attempt_at, id)
    WHERE status IN ('PENDING', 'FAILED');

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate ON outbox_messages (aggregate_type, aggregate_id);

-- ============================================================================
-- Submission idempotency (R3)
-- ============================================================================
-- A replayed Idempotency-Key must return the original complaint, not create a
-- second one. The partial unique index is the race guard: two concurrent
-- submissions with the same key mean one INSERT wins and the loser retries the
-- lookup. Lifecycle-action idempotency is separate — see uq_transition_idempotency.
ALTER TABLE IF EXISTS complaints
    ADD COLUMN IF NOT EXISTS submission_idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_complaint_submission_idempotency
    ON complaints (submission_idempotency_key)
    WHERE submission_idempotency_key IS NOT NULL;