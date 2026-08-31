-- ============================================================================
-- Nagorik Seba — V1 platform baseline (Blueprint §3.1)
-- PostgreSQL 16 + PostGIS 3.4
-- Tenancy foundation (municipalities, wards, departments) + tables for the
-- existing domain entities so that spring.jpa.hibernate.ddl-auto=validate
-- passes. Identity hardening (V2) and SLA/outbox (V5) come in later phases.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;

-- ============================================================================
-- Municipalities (tenancy root)
-- ============================================================================
CREATE TABLE municipalities (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug            VARCHAR(60)  NOT NULL,                    -- URL key: 'dhaka-north'
    name            VARCHAR(120) NOT NULL,                    -- 'Dhaka North City Corporation'
    name_bn         VARCHAR(120),
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_municipality_slug UNIQUE (slug)
);

-- ============================================================================
-- Wards (PostGIS boundaries, generated centroid)
-- ============================================================================
CREATE TABLE wards (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    municipality_id     BIGINT NOT NULL REFERENCES municipalities(id),
    ward_number         INT    NOT NULL,
    area_name           VARCHAR(120) NOT NULL,
    area_name_bn        VARCHAR(120),
    boundary            geometry(MultiPolygon, 4326) NOT NULL,
    centroid            geography(Point, 4326) GENERATED ALWAYS AS (ST_Centroid(boundary)::geography) STORED,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ward_municipality_number UNIQUE (municipality_id, ward_number),
    CONSTRAINT ck_ward_number CHECK (ward_number > 0)
);
CREATE INDEX idx_ward_boundary_gist ON wards USING GIST (boundary);

-- ============================================================================
-- Departments (per-municipality, category-scoped)
-- ============================================================================
CREATE TABLE departments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    municipality_id     BIGINT NOT NULL REFERENCES municipalities(id),
    code                VARCHAR(30) NOT NULL,                 -- ROADS, WATER_SUPPLY, ...
    name                VARCHAR(120) NOT NULL,
    office_location     geography(Point, 4326),               -- for distance routing
    handles_categories  TEXT[] NOT NULL DEFAULT '{}',         -- categories this dept accepts
    is_active           BOOLEAN NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_department_municipality_code UNIQUE (municipality_id, code)
);

-- ============================================================================
-- Users (existing entity; identity module hardening lands in Phase 2 / V2)
-- ============================================================================
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         VARCHAR(100) NOT NULL,
    phone         VARCHAR(20),
    password      VARCHAR(255) NOT NULL,
    full_name     VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    ward_id       BIGINT REFERENCES wards(id),
    department_id BIGINT REFERENCES departments(id),
    is_active     BOOLEAN NOT NULL DEFAULT true,
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT uq_user_phone UNIQUE (phone)
);
CREATE INDEX idx_user_ward ON users (ward_id);
CREATE INDEX idx_user_department ON users (department_id);

-- ============================================================================
-- SLA rules (existing entity; SLA policies per municipality land in Phase 5)
-- ============================================================================
CREATE TABLE sla_rules (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    category         VARCHAR(50) NOT NULL,
    priority         VARCHAR(20) NOT NULL,
    max_hours        INT NOT NULL,
    escalation_level INT NOT NULL DEFAULT 1
);

-- ============================================================================
-- Complaints (existing entity; complaint module redesign lands in Phase 3)
-- ============================================================================
CREATE TABLE complaints (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title                  VARCHAR(200) NOT NULL,
    description            TEXT NOT NULL,
    category               VARCHAR(50) NOT NULL,
    status                 VARCHAR(30) NOT NULL,
    priority               VARCHAR(20) NOT NULL,
    latitude               NUMERIC(10,8),
    longitude              NUMERIC(11,8),
    ward_id                BIGINT REFERENCES wards(id),
    citizen_id             BIGINT NOT NULL REFERENCES users(id),
    assigned_department_id BIGINT REFERENCES departments(id),
    assigned_officer_id    BIGINT REFERENCES users(id),
    submitted_at           TIMESTAMP NOT NULL DEFAULT now(),
    resolved_at            TIMESTAMP,
    deadline_at            TIMESTAMP,
    rating                 INT,
    rating_feedback        TEXT,
    reopen_reason          TEXT,
    reopen_count           INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_complaint_ward ON complaints (ward_id);
CREATE INDEX idx_complaint_citizen ON complaints (citizen_id);
CREATE INDEX idx_complaint_status ON complaints (status);

-- ============================================================================
-- Attachments (existing entity)
-- ============================================================================
CREATE TABLE attachments (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id  BIGINT NOT NULL REFERENCES complaints(id),
    file_url      VARCHAR(500) NOT NULL,
    file_type     VARCHAR(20),
    uploaded_by   BIGINT NOT NULL REFERENCES users(id),
    is_work_proof BOOLEAN NOT NULL DEFAULT false,
    uploaded_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- ============================================================================
-- Notifications (existing entity)
-- ============================================================================
CREATE TABLE notifications (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   BIGINT NOT NULL REFERENCES users(id),
    title     VARCHAR(200),
    message   TEXT NOT NULL,
    channel   VARCHAR(20) NOT NULL,
    is_read   BOOLEAN NOT NULL DEFAULT false,
    sent_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- ============================================================================
-- Status updates / complaint timeline (existing entity)
-- ============================================================================
CREATE TABLE status_updates (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id BIGINT NOT NULL REFERENCES complaints(id),
    updated_by   BIGINT REFERENCES users(id),
    from_status  VARCHAR(30),
    to_status    VARCHAR(30) NOT NULL,
    note         TEXT,
    created_at   TIMESTAMP NOT NULL DEFAULT now()
);

-- ============================================================================
-- Ward performance snapshots (existing entity)
-- ============================================================================
CREATE TABLE ward_performance (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ward_id              BIGINT NOT NULL REFERENCES wards(id),
    performance_month    INT NOT NULL,
    performance_year     INT NOT NULL,
    total_complaints     INT NOT NULL DEFAULT 0,
    resolved_complaints  INT NOT NULL DEFAULT 0,
    avg_resolution_hours NUMERIC(10,2),
    avg_rating           NUMERIC(3,2),
    sla_breach_count     INT NOT NULL DEFAULT 0
);