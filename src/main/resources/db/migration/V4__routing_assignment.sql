-- ============================================================================
-- Nagorik Seba — V4 routing & assignment (Blueprint §3.3, Phase 4)
-- PostgreSQL 16 + PostGIS 3.4
-- Assignment history for authority workflows + routing support columns.
-- Idempotent on fresh DB and existing dev DB (IF EXISTS guards).
-- ============================================================================

-- ============================================================================
-- complaint_assignments — who had it, when (enables reassignment analytics)
-- ============================================================================
CREATE TABLE IF NOT EXISTS complaint_assignments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id    BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    department_id   BIGINT NOT NULL REFERENCES departments(id),
    officer_id      BIGINT REFERENCES users(id),
    assigned_by     BIGINT REFERENCES users(id),
    strategy_used   VARCHAR(30),
    strategy_explanation TEXT,
    unassigned_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index on active assignments (the queue/workload access path, §4)
CREATE INDEX IF NOT EXISTS idx_assignment_officer_active
    ON complaint_assignments (officer_id) WHERE unassigned_at IS NULL;

-- Current-assignment lookup per complaint (reassignment close + StartWork guard)
CREATE INDEX IF NOT EXISTS idx_assignment_complaint_active
    ON complaint_assignments (complaint_id) WHERE unassigned_at IS NULL;

-- ============================================================================
-- departments — routing support columns (present from V1, guarded for safety)
-- ============================================================================
ALTER TABLE IF EXISTS departments
    ADD COLUMN IF NOT EXISTS handles_categories TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS office_location geography(Point, 4326);
