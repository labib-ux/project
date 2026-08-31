# Nagorik Seba — Enterprise Blueprint & Implementation Roadmap

**Version:** 1.0 · **Status:** Approved for implementation · **Audience:** Cline (AI coding assistant), project owner
**Stack baseline (locked):** Java 21 · Spring Boot 3.5 · Maven · Thymeleaf · PostgreSQL 16 + PostGIS 3.4 · Flyway · Testcontainers

This document is the single source of truth for the evolution of the existing repository into an enterprise-grade, multi-municipality civic complaint platform. It supersedes the original specification wherever they conflict.

---

## Table of Contents

1. [System Context & Architectural Decisions](#1-system-context--architectural-decisions)
2. [Target Module Architecture](#2-target-module-architecture)
3. [Database Schema — Hardened](#3-database-schema--hardened)
4. [Index & Query Catalogue](#4-index--query-catalogue)
5. [Concurrency, Race Conditions & Idempotency](#5-concurrency-race-conditions--idempotency)
6. [Complaint Lifecycle — Legal Transitions](#6-complaint-lifecycle--legal-transitions)
7. [Spring-Idiomatic Design Patterns](#7-spring-idiomatic-design-patterns)
8. [Security Architecture](#8-security-architecture)
9. [Privacy, Moderation & Public Data](#9-privacy-moderation--public-data)
10. [Observability & Operations](#10-observability--operations)
11. [Model-Tiered Task Matrix](#11-model-tiered-task-matrix)
12. [Cline Implementation Sequence (Phases 1–6)](#12-cline-implementation-sequence-phases-1-6)
13. [Definition of Done & Global Engineering Standards](#13-definition-of-done--global-engineering-standards)

---

## 1. System Context & Architectural Decisions

### 1.1 Product scope (locked)

| Decision | Choice | Rationale |
|---|---|---|
| Tenancy model | **Shared-schema multi-tenancy** with mandatory `municipality_id` on every tenant-scoped table | One deployment serves many City Corporations/Pourashavas; cheapest to operate at this scale; isolation enforced at service + query + constraint level |
| Frontend | **Thymeleaf SSR** + progressive JS (Leaflet, fetch) | Matches existing repo; no SPA build pipeline; SEO-friendly public heatmap |
| Architecture style | **Modular monolith** (package-by-feature) | Team of one + AI pair; deployable as a single artifact; module boundaries documented and enforced |
| Schema management | **Flyway only**, `ddl-auto=validate` | Reproducible environments; no silent schema drift |
| Canonical DB | **PostgreSQL 16 + PostGIS 3.4** | Ward boundary point-in-polygon, heatmap bbox queries, partial indexes, `timestamptz`, JSONB |
| Test DB | **Testcontainers PostgreSQL** (H2 removed from integration tests) | H2 cannot validate PostGIS, pessimistic locks, partial indexes, or `timestamptz` semantics |
| Time | **UTC everywhere** (`Instant`, `timestamptz`); render `Asia/Dhaka` | Eliminates DST/timezone bugs in SLA math |
| Notifications | **Transactional outbox** + polling workers | SMS/email side effects survive crashes; at-least-once delivery with idempotent consumers |
| File storage | Local FS (dev) behind `StorageService` port; S3/Cloudinary adapter (prod) | Swappable via interface; no vendor lock-in |
| SMS/Email | Twilio + JavaMailSender behind `NotificationSender` ports | Adapters mockable in tests |

### 1.2 C4 — System context

```
[Citizen] --(submit/track/rate/reopen)--> [Nagorik Seba App]
[Dept Officer] --(verify/assign/progress/resolve)--> [Nagorik Seba App]
[Ward Councilor] --(dashboard/escalations)--> [Nagorik Seba App]
[Admin] --(municipality/ward/dept/user mgmt, SLA policies)--> [Nagorik Seba App]
[Anonymous Visitor] --(heatmap, scoreboard)--> [Nagorik Seba App]
[Nagorik Seba App] --(SMS via Twilio API)--> [Twilio]
[Nagorik Seba App] --(SMTP)--> [Mail Server]
[Nagorik Seba App] --(object storage)--> [Local FS / S3 / Cloudinary]
```

### 1.3 Non-functional requirements

| Concern | Target |
|---|---|
| Availability | 99.5% (single region, blue/green on Render/Railway) |
| p95 API latency | < 500 ms (non-map); heatmap tile < 1.5 s |
| Complaint submission | Survives app crash between DB commit and file write (compensation logic) |
| Notification delivery | At-least-once, idempotent, ≤ 60 s from status change |
| SLA scheduler | Safe with multiple app instances (advisory lock / `FOR UPDATE SKIP LOCKED`) |
| Data retention | Complaints 7 years (public record); PII purge on request per policy |
| Security | OWASP ASVS L2; no PII in public APIs; rate-limited anonymous access |

---

## 2. Target Module Architecture

Migrate **incrementally** from the current package-by-layer layout. The final structure:

```
com.nagorikseba/
├── NagorikSebaApplication.java
├── shared/                          # Cross-cutting, dependency-free of all modules
│   ├── api/                         # PageResponse, ErrorCodes, ApiError
│   ├── config/                      # SecurityConfig, WebConfig, AsyncConfig, SchedulingConfig
│   ├── security/                    # JwtTokenProvider, JwtAuthenticationFilter, PrincipalContext
│   ├── tenant/                      # TenantContext (ThreadLocal), TenantScope aspect
│   ├── audit/                       # Auditable JPA listener, AuditEvent entity+repo
│   ├── outbox/                      # OutboxMessage, OutboxRepository, OutboxPublisher
│   ├── storage/                     # StorageService port + LocalFsAdapter
│   ├── time/                        # Clock bean (testable time)
│   └── exception/                   # GlobalExceptionHandler, domain exceptions
├── municipality/                    # Municipality, Ward, Department, boundary geometry
│   ├── api/ (controller, dto)
│   ├── domain/ (entity, enums)
│   ├── repo/
│   └── service/ (WardBoundaryService, MunicipalityService)
├── identity/                        # Users, auth, memberships, refresh tokens
│   ├── api/ (AuthController, UserController, dto)
│   ├── domain/ (User, RefreshToken, UserMunicipalityMembership)
│   ├── repo/
│   └── service/ (AuthService, TokenService, AppUserDetailsService)
├── complaint/                       # Complaint aggregate: lifecycle, attachments, ratings
│   ├── api/ (CitizenComplaintController, AuthorityComplaintController, dto)
│   ├── domain/ (Complaint, ComplaintTransition, Assignment, ResolutionAttempt, Rating, ReopenRequest, Attachment)
│   ├── lifecycle/ (ComplaintLifecycleService, TransitionHandler registry, guards)
│   ├── submission/ (ComplaintSubmissionTemplate + variants)
│   ├── routing/ (ComplaintRoutingStrategy + impls, RoutingStrategyResolver)
│   ├── repo/
│   └── service/ (ComplaintQueryService, AttachmentService)
├── sla/                             # SLA policies, instances, breaches, escalations
│   ├── api/ (dto)
│   ├── domain/ (SlaPolicy, SlaInstance, SlaBreach, Escalation)
│   ├── repo/
│   └── service/ (SlaService, SlaBreachScanner, EscalationService)
├── notification/                    # Templates, outbox consumers, channel adapters
│   ├── api/ (dto)
│   ├── domain/ (Notification, NotificationTemplate, channel enums)
│   ├── repo/
│   └── service/ (NotificationService, OutboxWorker, TwilioSmsSender, SmtpEmailSender, NotificationFactory)
├── transparency/                    # Public heatmap, scoreboard, monthly snapshots
│   ├── api/ (PublicController, dto)
│   ├── domain/ (WardMonthlyPerformance)
│   ├── repo/
│   └── service/ (HeatmapService, ScoreboardService, PerformanceSnapshotJob)
└── bootstrap/                       # DataSeeder, demo fixtures (never in prod profile)
```

**Dependency rule (enforced in code review):** `shared` depends on nothing. Modules depend only on `shared` and explicitly whitelisted cross-module contracts (e.g., `complaint` → `municipality` repo interfaces; `sla` → `complaint` domain). No cyclic dependencies. Controllers never call repositories directly.

---

## 3. Database Schema — Hardened

All migrations are Flyway-versioned (`V{n}__{description}.sql`). Types: `BIGINT GENERATED ALWAYS AS IDENTITY`, `timestamptz` (UTC), `text` over `varchar(n)` where length is arbitrary. Enums stored as `varchar` with CHECK constraints (portable, readable in queries).

### 3.1 Tenancy & organization

```sql
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
```

**Edge cases covered:** duplicate ward numbers within a municipality (unique); overlapping ward boundaries (application validates on save + PostGIS `ST_Intersects` check in admin UI); inactive wards reject new complaints but keep history.

### 3.2 Identity

```sql
CREATE TABLE users (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email               CITEXT,                               -- case-insensitive
    phone               VARCHAR(20),                          -- normalized: 01XXXXXXXXX
    password_hash       VARCHAR(255),                         -- bcrypt; NULL only for anonymous
    full_name           VARCHAR(120) NOT NULL,
    role                VARCHAR(20) NOT NULL,                 -- CITIZEN, DEPT_OFFICER, WARD_COUNCILOR, ADMIN
    is_active           BOOLEAN NOT NULL DEFAULT true,
    failed_login_count  INT NOT NULL DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    last_login_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    version             INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_user_email UNIQUE (email),
    CONSTRAINT uq_user_phone UNIQUE (phone),
    CONSTRAINT ck_user_contact CHECK (email IS NOT NULL OR phone IS NOT NULL),
    CONSTRAINT ck_user_password CHECK (password_hash IS NOT NULL OR role = 'CITIZEN')
);
CREATE INDEX idx_user_role_active ON users (role) WHERE is_active;

-- Officers/councilors belong to a municipality (and optionally ward/dept) — history-preserving
CREATE TABLE user_municipality_memberships (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id),
    municipality_id     BIGINT NOT NULL REFERENCES municipalities(id),
    ward_id             BIGINT REFERENCES wards(id),
    department_id       BIGINT REFERENCES departments(id),
    valid_from          TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until         TIMESTAMPTZ,                          -- NULL = current
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_current UNIQUE (user_id, municipality_id, valid_until),
    CONSTRAINT ck_membership_range CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX idx_membership_user_current ON user_municipality_memberships (user_id) WHERE valid_until IS NULL;
CREATE INDEX idx_membership_dept ON user_municipality_memberships (department_id) WHERE valid_until IS NULL;

CREATE TABLE refresh_tokens (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id             BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          VARCHAR(255) NOT NULL,                -- SHA-256 of token; raw never stored
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    replaced_by_token_id BIGINT REFERENCES refresh_tokens(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address          INET,
    user_agent          VARCHAR(255),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_user_active ON refresh_tokens (user_id, expires_at) WHERE revoked_at IS NULL;
```

**Edge cases covered:** officer transfers between wards (membership history, not mutable FK on user); account lockout counters; refresh-token rotation detection (reuse of revoked token ⇒ revoke entire family); CITEXT emails; phone normalization to a single canonical format.

### 3.3 Complaint aggregate

```sql
CREATE TABLE complaints (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference_code          VARCHAR(20) NOT NULL,             -- human code: 'NS-2026-000123'
    municipality_id         BIGINT NOT NULL REFERENCES municipalities(id),
    ward_id                 BIGINT REFERENCES wards(id),
    citizen_id              BIGINT REFERENCES users(id),      -- NULL = anonymous
    anonymous_contact_phone VARCHAR(20),
    title                   VARCHAR(200) NOT NULL,
    description             TEXT NOT NULL,
    category                VARCHAR(50) NOT NULL,
    status                  VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED',
    priority                VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    location                geography(Point, 4326) NOT NULL,
    location_source         VARCHAR(20) NOT NULL DEFAULT 'DEVICE',   -- DEVICE, MAP_PIN, ADDRESS_TEXT
    address_text            VARCHAR(300),
    assigned_department_id  BIGINT REFERENCES departments(id),
    assigned_officer_id     BIGINT REFERENCES users(id),
    submitted_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    first_verified_at       TIMESTAMPTZ,
    first_assigned_at       TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    closed_at               TIMESTAMPTZ,
    last_transition_at      TIMESTAMPTZ,
    reopen_count            INT NOT NULL DEFAULT 0,
    rejection_reason        TEXT,
    cancellation_reason     TEXT,
    is_public_visible       BOOLEAN NOT NULL DEFAULT true,
    moderation_status       VARCHAR(20) NOT NULL DEFAULT 'APPROVED',  -- PENDING, APPROVED, REJECTED
    version                 INT NOT NULL DEFAULT 0,           -- optimistic locking
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_complaint_reference UNIQUE (reference_code),
    CONSTRAINT ck_complaint_rating_removed CHECK (true),      -- rating moved to resolution_attempts
    CONSTRAINT ck_complaint_reopen_count CHECK (reopen_count >= 0 AND reopen_count <= 5),
    CONSTRAINT ck_complaint_anonymous CHECK (
        (citizen_id IS NOT NULL) OR (anonymous_contact_phone IS NOT NULL))
);
CREATE INDEX idx_complaint_tenant_status ON complaints (municipality_id, status);
CREATE INDEX idx_complaint_ward_status ON complaints (ward_id, status) WHERE status NOT IN ('CLOSED','REJECTED','CANCELLED');
CREATE INDEX idx_complaint_citizen ON complaints (citizen_id, submitted_at DESC);
CREATE INDEX idx_complaint_officer_active ON complaints (assigned_officer_id) WHERE status IN ('ASSIGNED','IN_PROGRESS','REOPENED');
CREATE INDEX idx_complaint_public_heatmap ON complaints (municipality_id, category, status)
    WHERE is_public_visible AND moderation_status = 'APPROVED' AND status NOT IN ('REJECTED','CANCELLED');
CREATE INDEX idx_complaint_location_gist ON complaints USING GIST (location);
```

**Key hardening decisions:**

- **`reference_code`** — immutable, human-readable, used in all citizen-facing communication; `id` never exposed publicly.
- **`version`** — JPA `@Version`; every lifecycle mutation must send `expectedVersion`; mismatch ⇒ HTTP 409.
- **Rating/reopen history normalized** — the flat `rating`/`reopen_reason` columns from the original schema lose history when a complaint is resolved → reopened → resolved again. Moved to `resolution_attempts` (below).
- **`reopen_count <= 5`** — prevents infinite reopen loops; after 5, complaint auto-closes with `ESCALATED_TO_COUNCILOR` flag and councilor review.
- **Anonymous submissions** — `citizen_id NULL` + required contact phone; tracked separately for moderation priority.

```sql
-- Append-only lifecycle log (one row per transition; the timeline)
CREATE TABLE complaint_transitions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id    BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    from_status     VARCHAR(30),
    to_status       VARCHAR(30) NOT NULL,
    action          VARCHAR(30) NOT NULL,                     -- VERIFY, ASSIGN, START, RESOLVE, CLOSE, REOPEN, REJECT, CANCEL
    actor_user_id   BIGINT REFERENCES users(id),              -- NULL for system/scheduler
    actor_role      VARCHAR(20),
    note            TEXT,
    metadata        JSONB,                                     -- routing decision, escalation level, etc.
    idempotency_key VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_transition_idempotency UNIQUE (complaint_id, idempotency_key)
);
CREATE INDEX idx_transition_complaint_time ON complaint_transitions (complaint_id, created_at);

-- Assignment history (who had it, when) — enables reassignment analytics
CREATE TABLE complaint_assignments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id    BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    department_id   BIGINT NOT NULL REFERENCES departments(id),
    officer_id      BIGINT REFERENCES users(id),
    assigned_by     BIGINT REFERENCES users(id),
    strategy_used   VARCHAR(30),                              -- CATEGORY, LOAD_BALANCED, DISTANCE, MANUAL
    unassigned_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assignment_officer_active ON complaint_assignments (officer_id) WHERE unassigned_at IS NULL;

-- One row per resolution cycle (resolve → rate/reopen). Preserves full history.
CREATE TABLE resolution_attempts (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id        BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    attempt_number      INT NOT NULL,
    resolved_at         TIMESTAMPTZ NOT NULL,
    resolved_by         BIGINT NOT NULL REFERENCES users(id),
    resolution_note     TEXT,
    outcome             VARCHAR(20) NOT NULL,                 -- PENDING_CITIZEN, CLOSED, REOPENED
    rating              INT CHECK (rating BETWEEN 1 AND 5),
    rating_feedback     TEXT,
    rated_at            TIMESTAMPTZ,
    reopen_reason       TEXT,
    reopened_at         TIMESTAMPTZ,
    CONSTRAINT uq_resolution_attempt UNIQUE (complaint_id, attempt_number)
);

CREATE TABLE attachments (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id        BIGINT NOT NULL REFERENCES complaints(id) ON DELETE CASCADE,
    transition_id       BIGINT REFERENCES complaint_transitions(id),  -- proof photos link to RESOLVE transition
    storage_key         VARCHAR(300) NOT NULL,                -- 'complaints/2026/08/ns-2026-000123/uuid.jpg'
    storage_provider    VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    original_filename   VARCHAR(255),
    content_type        VARCHAR(100) NOT NULL,
    byte_size           BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 10485760),
    checksum_sha256     VARCHAR(64) NOT NULL,
    is_work_proof       BOOLEAN NOT NULL DEFAULT false,
    scan_status         VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, CLEAN, INFECTED
    deleted_at          TIMESTAMPTZ,                          -- soft delete; storage GC'd by job
    uploaded_by         BIGINT REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_attachment_storage_key UNIQUE (storage_key)
);
CREATE INDEX idx_attachment_complaint ON attachments (complaint_id) WHERE deleted_at IS NULL;
```

### 3.4 SLA engine

```sql
CREATE TABLE sla_policies (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    municipality_id     BIGINT NOT NULL REFERENCES municipalities(id),
    category            VARCHAR(50) NOT NULL,
    priority            VARCHAR(20) NOT NULL,
    max_hours           INT NOT NULL CHECK (max_hours > 0),
    escalation_level_1_hours INT,                             -- warn councilor
    escalation_level_2_hours INT,                             -- warn mayor/CEO
    is_active           BOOLEAN NOT NULL DEFAULT true,
    CONSTRAINT uq_sla_policy UNIQUE (municipality_id, category, priority)
);

-- Materialized per-complaint SLA state; recomputed on priority change / reopen
CREATE TABLE sla_instances (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    complaint_id        BIGINT NOT NULL UNIQUE REFERENCES complaints(id) ON DELETE CASCADE,
    policy_id           BIGINT NOT NULL REFERENCES sla_policies(id),
    deadline_at         TIMESTAMPTZ NOT NULL,
    warn_at             TIMESTAMPTZ,
    breach_at           TIMESTAMPTZ,                          -- set when scanner detects breach
    last_calculated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_sla_deadline CHECK (deadline_at > submitted_at_ref()) -- enforced in service layer
);

CREATE TABLE sla_breaches (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sla_instance_id     BIGINT NOT NULL REFERENCES sla_instances(id) ON DELETE CASCADE,
    complaint_id        BIGINT NOT NULL REFERENCES complaints(id),
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    hours_overdue       NUMERIC(6,2) NOT NULL,
    escalation_level    INT NOT NULL DEFAULT 1,
    escalated_to_user_id BIGINT REFERENCES users(id),
    resolved_at         TIMESTAMPTZ,                          -- breach cleared when complaint resolves
    CONSTRAINT uq_breach_active_per_complaint UNIQUE (complaint_id) WHERE resolved_at IS NULL
);
CREATE INDEX idx_sla_open_breach ON sla_breaches (escalation_level) WHERE resolved_at IS NULL;
```

**Edge cases covered:** SLA policy changes don't retroactively alter existing instances (instance snapshots the deadline); breach is recorded exactly once (partial unique index); breach clears on resolution; multiple escalation levels with distinct thresholds.

### 3.5 Notifications & outbox

```sql
CREATE TABLE notifications (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT REFERENCES users(id),
    complaint_id    BIGINT REFERENCES complaints(id),
    channel         VARCHAR(20) NOT NULL,                     -- SMS, EMAIL, IN_APP
    template_code   VARCHAR(50) NOT NULL,                     -- 'COMPLAINT_STATUS_CHANGED', 'SLA_ESCALATION'
    title           VARCHAR(200),
    body            TEXT NOT NULL,
    payload         JSONB,                                     -- template variables
    is_read         BOOLEAN NOT NULL DEFAULT false,
    read_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notification_user_unread ON notifications (user_id, created_at DESC) WHERE is_read = false;

-- Transactional outbox: written in the SAME transaction as the state change
CREATE TABLE outbox_messages (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    aggregate_type  VARCHAR(30) NOT NULL,                     -- 'COMPLAINT'
    aggregate_id    BIGINT NOT NULL,
    event_type      VARCHAR(50) NOT NULL,                     -- 'COMPLAINT_STATUS_CHANGED', 'SLA_BREACHED'
    payload         JSONB NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING, PROCESSING, SENT, FAILED
    retry_count     INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_poll ON outbox_messages (status, next_attempt_at) WHERE status IN ('PENDING','FAILED');
```

### 3.6 Transparency snapshots

```sql
CREATE TABLE ward_monthly_performance (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ward_id             BIGINT NOT NULL REFERENCES wards(id),
    municipality_id     BIGINT NOT NULL REFERENCES municipalities(id),
    period_start        DATE NOT NULL,                        -- 2026-08-01
    total_complaints    INT NOT NULL DEFAULT 0,
    resolved_complaints INT NOT NULL DEFAULT 0,
    avg_resolution_hours NUMERIC(10,2),
    avg_rating          NUMERIC(3,2),
    sla_breach_count    INT NOT NULL DEFAULT 0,
    reopen_rate         NUMERIC(5,4),
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ward_period UNIQUE (ward_id, period_start),
    CONSTRAINT ck_ward_period_counts CHECK (resolved_complaints <= total_complaints)
);
CREATE INDEX idx_ward_perf_municipality_period ON ward_monthly_performance (municipality_id, period_start DESC);
```

---

## 4. Index & Query Catalogue

| Query | Index / technique |
|---|---|
| Authority dashboard: counts by status per ward | `idx_complaint_ward_status` (partial: active statuses) |
| SLA scanner: overdue active complaints | `idx_complaint_sla_scan` on `(deadline_at)` via join to `sla_instances` — add `CREATE INDEX idx_sla_instance_deadline ON sla_instances (deadline_at) WHERE breach_at IS NULL;` |
| Heatmap: visible complaints in bbox | `idx_complaint_location_gist` + `idx_complaint_public_heatmap` (partial, covers filter) |
| Citizen: my complaints, newest first | `idx_complaint_citizen` |
| Officer workload: active per officer | `idx_complaint_officer_active` (partial) |
| Timeline: transitions by complaint | `idx_transition_complaint_time` |
| Outbox worker polling | `idx_outbox_poll` (partial on PENDING/FAILED) |
| Unread notification badge | `idx_notification_user_unread` (partial) |
| Ward lookup by point | `idx_ward_boundary_gist` + `ST_Covers` |
| Refresh-token family revocation | `idx_refresh_user_active` |
| Monthly scoreboard | `idx_ward_perf_municipality_period` |

**Heatmap query pattern (PostGIS):**

```sql
SELECT category, status, count(*) AS cnt,
       array_agg(ST_X(location::geometry) ORDER BY id) AS lngs,
       array_agg(ST_Y(location::geometry) ORDER BY id) AS lats
FROM complaints
WHERE municipality_id = :municipalityId
  AND is_public_visible
  AND moderation_status = 'APPROVED'
  AND status NOT IN ('REJECTED','CANCELLED')
  AND location && ST_MakeEnvelope(:minLng, :minLat, :maxLng, :maxLat, 4326)
GROUP BY category, status;
```

For dense areas (> 500 points in a bbox), switch to a server-side grid clustering: `ST_SnapToGrid(location::geometry, :gridSize)` and return cell centroids with counts.

---

## 5. Concurrency, Race Conditions & Idempotency

| # | Race condition | Control |
|---|---|---|
| R1 | Two officers click "Assign" simultaneously | `SELECT ... FOR UPDATE` on complaint row inside `@Transactional` lifecycle service; second waits, then sees new status and gets `InvalidStateTransitionException` → HTTP 409 |
| R2 | Citizen reopens while officer resolves | Same row lock; whichever commits first wins; loser gets 409 with current status in body |
| R3 | Duplicate submission (double-click / retry) | Client sends `Idempotency-Key` header; stored in `complaint_transitions.idempotency_key` + a `idempotency_keys` table for submissions; replay returns original response |
| R4 | SLA scanner runs on 2+ app instances | `SELECT ... FOR UPDATE SKIP LOCKED` on `sla_instances` rows; each instance claims a disjoint set |
| R5 | Outbox worker double-delivers a notification | Row status transition PENDING→PROCESSING guarded by `UPDATE ... WHERE status='PENDING'` (optimistic claim); consumers idempotent by `outbox_messages.id` in provider metadata |
| R6 | File saved but DB transaction rolls back | Files written to temp dir first; moved to final storage key only after commit (`@TransactionalEventListener(AFTER_COMMIT)`); orphan temp cleaner job |
| R7 | Snapshot job overlaps with itself | `pg_try_advisory_lock(hashtext('ward-snapshot-job'))` |
| R8 | Optimistic-lock failure on any update | `@Version` on `complaints`, `users`; `ObjectOptimisticLockingFailureException` → 409 via `GlobalExceptionHandler` |
| R9 | Refresh token reuse after revocation (theft) | On reuse detection, revoke entire token family (all tokens with same root) and force re-login |
| R10 | Rating submitted twice | Unique `(complaint_id, attempt_number)` on `resolution_attempts` + state guard: CLOSE only valid from RESOLVED |

---

## 6. Complaint Lifecycle — Legal Transitions

Actors: `CITIZEN`, `OFFICER` (dept officer), `COUNCILOR`, `ADMIN`, `SYSTEM` (scheduler).

| From | Action | To | Actor | Guards | Required evidence | Side effects |
|---|---|---|---|---|---|---|
| — | SUBMIT | SUBMITTED | CITIZEN/ANON | valid geo within municipality; ≥1 photo; category allowed; rate-limit | photos | SLA instance created; outbox `COMPLAINT_SUBMITTED`; notify ward officers |
| SUBMITTED | VERIFY | VERIFIED | OFFICER | officer's dept handles category | — | outbox; notify citizen |
| SUBMITTED | REJECT | REJECTED | OFFICER | reason required | reason | outbox; notify citizen; hide from heatmap |
| VERIFIED | ASSIGN | ASSIGNED | OFFICER/COUNCILOR | target dept active; officer belongs to dept | — | assignment row; outbox; notify officer + citizen |
| VERIFIED | ASSIGN (auto) | ASSIGNED | SYSTEM | routing strategy resolves a dept | — | routing decision persisted in `complaint_assignments.strategy_used` + transition metadata |
| ASSIGNED | START | IN_PROGRESS | OFFICER | actor == assigned officer (or admin) | — | outbox; notify citizen |
| IN_PROGRESS | RESOLVE | RESOLVED | OFFICER | actor == assigned officer; ≥1 work-proof photo | proof photos | `resolution_attempts` row (PENDING_CITIZEN); SLA breach cleared; outbox; notify citizen |
| RESOLVED | CLOSE | CLOSED | CITIZEN | actor == citizen; rating 1–5 | rating | attempt outcome CLOSED; outbox; snapshot counters |
| RESOLVED | AUTO_CLOSE | CLOSED | SYSTEM | no citizen action within 7 days | — | attempt outcome CLOSED (no rating); outbox |
| RESOLVED | REOPEN | REOPENED | CITIZEN | actor == citizen; reason; reopen_count < 5 | reason | attempt outcome REOPENED; reopen_count++; priority → HIGH; SLA recalculated (50% of original); outbox; escalation to councilor |
| REOPENED | ASSIGN | ASSIGNED | OFFICER/COUNCILOR | same as VERIFIED→ASSIGN | — | new assignment row |
| Any active | CANCEL | CANCELLED | CITIZEN | actor == citizen; status in (SUBMITTED, VERIFIED); reason | reason | outbox; hide from heatmap |
| CLOSED | — | (terminal) | — | no transitions out | — | — |
| REJECTED | — | (terminal) | — | no transitions out | — | — |
| REOPENED | REJECT | REJECTED | OFFICER | reopen_count == 5 (forced terminal) | reason | outbox |

**Implementation rule:** every mutation flows through `ComplaintLifecycleService.execute(command)` — one `@Transactional` method that (1) locks the row, (2) checks version, (3) resolves the `TransitionHandler` from the registry, (4) runs guards, (5) mutates, (6) appends `complaint_transitions`, (7) writes outbox rows. **No controller or other service may call `complaint.setStatus(...)` directly.**

---

## 7. Spring-Idiomatic Design Patterns

### 7.1 State Pattern → **Stateless Transition Handler Registry**

The original design (state objects holding entity references) fights JPA: entities become detached, state objects aren't Spring beans, and transitions can't be transactional. Refinement:

```java
// complaint/lifecycle/TransitionHandler.java
public interface TransitionHandler {
    ComplaintAction supportedAction();
    Set<ComplaintStatus> sourceStatuses();
    void execute(Complaint complaint, TransitionCommand cmd);  // runs inside locked tx
}

// complaint/lifecycle/ComplaintLifecycleService.java
@Service
public class ComplaintLifecycleService {
    private final Map<ComplaintAction, TransitionHandler> handlers;   // built from List<TransitionHandler> injection

    @Transactional
    public Complaint execute(TransitionCommand cmd) {
        Complaint c = complaintRepo.findAndLockById(cmd.complaintId())   // SELECT ... FOR UPDATE
            .orElseThrow(() -> new ResourceNotFoundException(...));
        if (c.getVersion() != cmd.expectedVersion()) throw new ConflictException(...);
        TransitionHandler h = handlers.get(cmd.action());
        if (h == null || !h.sourceStatuses().contains(c.getStatus()))
            throw new InvalidStateTransitionException(c.getStatus(), cmd.action());
        h.execute(c, cmd);                       // guards + mutation + transition log + outbox
        return c;
    }
}
```

Concrete handlers (`VerifyHandler`, `AssignHandler`, `StartWorkHandler`, `ResolveHandler`, `CloseHandler`, `ReopenHandler`, `RejectHandler`, `CancelHandler`) are stateless `@Component`s. The entity keeps a plain `ComplaintStatus` enum. Illegal transitions are impossible to express — the registry lookup fails before mutation.

### 7.2 Strategy Pattern → **Typed Registry with `supports()`**

```java
public interface ComplaintRoutingStrategy {
    RoutingStrategyType type();
    boolean supports(Complaint complaint, MunicipalityContext ctx);
    RoutingDecision route(Complaint complaint);   // RoutingDecision = dept + officer + explanation
}
```

`RoutingStrategyResolver` injects `List<ComplaintRoutingStrategy>`, orders by configured priority (`app.routing.strategy-order: CATEGORY,LOAD_BALANCED,DISTANCE`), and picks the first `supports()`. Every decision returns an **explanation** (matched category / least load count / distance km) persisted in transition metadata — routing is auditable, not a black box. Tie-breaking is deterministic (lowest dept id).

### 7.3 Observer Pattern → **Spring Events + Transactional Outbox**

- Domain events are **immutable records carrying IDs only** (no JPA entities — avoids lazy-loading accidents after commit): `record ComplaintStatusChangedEvent(Long complaintId, Long actorId, String from, String to, String note, Instant occurredAt)`.
- The lifecycle service publishes via `ApplicationEventPublisher` **inside** the transaction.
- `@TransactionalEventListener(phase = AFTER_COMMIT)` listeners react locally (in-app notification rows, audit log).
- Durable side effects (SMS/email) go through the **outbox table written in the same transaction**; `OutboxWorker` (`@Scheduled`, `SKIP LOCKED`) delivers via channel adapters. This is the correct Observer for cross-boundary effects — in-memory observer lists are not crash-safe.

### 7.4 Factory Pattern → **Channel Provider Registry**

`NotificationFactory` per channel builds a `Notification` row from a template code + variables (Bangla/English via `messages_bn.properties`). `NotificationSender` port has `TwilioSmsSender` / `SmtpEmailSender` / `InAppSender` adapters; a `NotificationDispatcher` maps channel → sender from the Spring context. Factories create; senders deliver; the outbox retries.

### 7.5 Template Method → **Transactional Submission Workflow**

`ComplaintSubmissionTemplate` remains a `final` orchestrated method (`validate → persist → attach → resolveWard → createSla → afterSubmit`) but each step is overridable via protected hooks, the whole flow is `@Transactional` (self-injection or split into orchestrator + steps to avoid proxy bypass), file persistence uses the temp-then-commit-move pattern (R6), and `AnonymousComplaintSubmission` overrides validation (phone required, stricter rate limits, moderation queue) rather than weakening the standard flow.

---

## 8. Security Architecture

### 8.1 Authentication

- **Access token:** JWT, 15 min, claims: `sub`, `uid`, `role`, `mids` (municipality ids), `typ: ACCESS`.
- **Refresh token:** opaque 256-bit random, SHA-256 hashed at rest, 30 days, rotation on every use, family revocation on reuse detection (R9). Endpoint: `POST /api/auth/refresh`.
- **Login hardening:** bcrypt(12); account lockout after 5 failures for 15 min; normalized phone (`+8801…` → `01…` canonical) or CITEXT email as identifier.
- **Registration:** phone OTP verification optional Phase 6; email verification token.

### 8.2 Authorization (object-level, deny-by-default)

| Operation | Rule |
|---|---|
| Read complaint | citizen owns it; OR officer/councilor of complaint's municipality; OR admin |
| VERIFY/ASSIGN/REJECT | officer with active membership in complaint's municipality; ASSIGN target dept must handle complaint category |
| START/RESOLVE | assigned officer only (or admin override, logged) |
| CLOSE/REOPEN/CANCEL | owning citizen only |
| Ward dashboard | councilor: own ward(s); officer: own dept; admin: all |
| Public endpoints | anonymous; municipality slug path param; rate-limited |

Enforced in `@PreAuthorize` + method guards inside services (defense in depth). A `TenantScope` argument-resolver/aspect ensures every tenant-scoped query filters by the caller's municipality membership — a query without tenant filter fails fast in dev (Hibernate `@Filter` or explicit repository discipline).

### 8.3 CSRF, CORS, headers

- **API (`/api/**`):** stateless JWT ⇒ CSRF disabled **only** for these paths.
- **Thymeleaf form pages:** session-less forms use a per-request CSRF token in a hidden field (custom `CsrfTokenRequestHandler`); login/register forms included.
- CORS: locked-down allowlist from config (`app.security.cors.allowed-origins`), no `*` in prod.
- Headers: HSTS, X-Content-Type-Options, X-Frame-Options DENY, CSP for Thymeleaf pages (Leaflet from allowlisted CDN or self-hosted).
- Uploads: content-type sniffing via Apache Tika magic bytes (already partially present), filename randomization (present), EXIF GPS stripping (Phase 6 privacy), size caps (present).

### 8.4 Rate limits (Bucket4j, in-memory for now)

| Endpoint | Limit |
|---|---|
| `POST /api/auth/login` | 5/min/IP |
| `POST /api/auth/register` | 3/hour/IP |
| `POST /api/complaints` (authed) | 10/day/user |
| `POST /api/complaints` (anonymous) | 3/day/phone + captcha (Phase 6) |
| Public map | 60/min/IP |

---

## 9. Privacy, Moderation & Public Data

1. **Coordinate obfuscation for public heatmap:** public API returns coordinates snapped to ~100 m grid (`ST_SnapToGrid(loc, 0.001)`) — enough for heatmaps, useless for door-stepping. Exact coordinates visible only to authenticated owner and authority.
2. **PII never in public payloads:** no citizen name, phone, email, exact address in any `/api/public/**` response. Title/description pass through a profanity/moderation check (simple wordlist Phase 5; flagged → `moderation_status=PENDING`).
3. **Attachment visibility:** work-proof and citizen photos served via time-limited signed URLs in prod; direct `/uploads/**` only in dev profile.
4. **Retention:** anonymous complaint contact phones purged after 90 days; deleted users' complaints retained with anonymized citizen reference (`citizen_id` set NULL, `citizen_ref_hash` kept for dedup).
5. **Sensitive categories** (e.g., sanitation hazards) may be configured per municipality to be excluded from public heatmap.

---

## 10. Observability & Operations

- **Structured JSON logging** (logstash encoder) with `traceId`, `municipalityId`, `complaintId` MDC fields.
- **Micrometer metrics:** `complaint.submitted`, `complaint.transition{action,status}`, `sla.breach.detected`, `outbox.lag.seconds`, `notification.delivery{channel,result}`.
- **Health:** `/actuator/health` + custom `OutboxLagHealthIndicator` (down if lag > 10 min).
- **Alerting (prod):** SLA breach count spike, outbox lag, failed-login spike.
- **Backups:** nightly `pg_dump` + WAL archiving (Neon/Render managed).
- **Runbooks:** `docs/runbooks/` — SLA scanner stuck, outbox backlog, ward boundary import.

---

## 11. Model-Tiered Task Matrix

Legend: **G** = GLM-5.3 (high reasoning) · **B** = Budget model (boilerplate/standard). "Phase" maps to §12. Every file the project will contain appears exactly once.

### 11.1 Shared platform (`shared/`)

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| S1 | `shared/api/PageResponse.java` | Generic pagination wrapper | 1 | B | Record + generics, zero logic |
| S2 | `shared/api/ApiError.java` | RFC-7807 problem+json body | 1 | B | Move of existing class |
| S3 | `shared/config/SecurityConfig.java` | Filter chain, endpoint rules, CSRF split | 2 | **G** | CSRF/CORS/session policy for hybrid SSR+API is security-critical |
| S4 | `shared/config/WebConfig.java` | Static resources, converters | 1 | B | Boilerplate |
| S5 | `shared/config/AsyncConfig.java` | Executor for AFTER_COMMIT listeners | 4 | B | Standard config |
| S6 | `shared/config/SchedulingConfig.java` | Enable scheduling + ShedLock/advisory-lock wiring | 5 | **G** | Multi-instance scheduler safety |
| S7 | `shared/security/JwtTokenProvider.java` | Access-token issue/parse, claims incl. `mids` | 2 | **G** | Token contract underpins all authz |
| S8 | `shared/security/JwtAuthenticationFilter.java` | Bearer parsing → Authentication | 2 | B | Mechanical filter |
| S9 | `shared/security/PrincipalContext.java` | Typed principal + municipality resolution | 2 | **G** | Tenancy resolution is core |
| S10 | `shared/tenant/TenantContext.java` | ThreadLocal tenant holder | 1 | B | Small utility |
| S11 | `shared/audit/Auditable.java` + JPA listener | `created_at`/`updated_at` auto-set | 1 | B | Standard callback |
| S12 | `shared/outbox/OutboxMessage.java` | Entity | 1 | B | Field mapping |
| S13 | `shared/outbox/OutboxRepository.java` | Repo + `SKIP LOCKED` claim query | 5 | **G** | Concurrent claim semantics |
| S14 | `shared/outbox/OutboxPublisher.java` | Append helper (JSONB payload) | 1 | B | Simple writer |
| S15 | `shared/storage/StorageService.java` | Port interface | 1 | **G** | Contract design |
| S16 | `shared/storage/LocalFsStorageAdapter.java` | Dev filesystem impl | 1 | B | Existing logic moved |
| S17 | `shared/time/ClockConfig.java` | `Clock` bean | 1 | B | Trivial |
| S18 | `shared/exception/GlobalExceptionHandler.java` | Map all exceptions → problem+json | 2 | **G** | Correct status codes for locks/transitions shape API contract |
| S19 | `shared/exception/*` (5 domain exceptions) | ResourceNotFound, Conflict, InvalidStateTransition, FileStorage, AccessDenied | 1 | B | One-line classes |

### 11.2 Municipality module

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| M1 | `municipality/domain/Municipality.java` | Entity | 1 | B | Field mapping |
| M2 | `municipality/domain/Ward.java` | Entity + PostGIS `boundary`/`centroid` mapping | 1 | **G** | Geometry mapping is error-prone |
| M3 | `municipality/domain/Department.java` | Entity + `handles_categories` array | 1 | B | Field mapping |
| M4 | `municipality/repo/MunicipalityRepository.java` | CRUD | 1 | B | Boilerplate |
| M5 | `municipality/repo/WardRepository.java` | + `findWardContainingPoint` native `ST_Covers` | 4 | **G** | Spatial query correctness |
| M6 | `municipality/repo/DepartmentRepository.java` | + active-by-category lookup | 4 | B | Simple derived/`@Query` |
| M7 | `municipality/service/WardBoundaryService.java` | Point→ward resolution, overlap validation | 4 | **G** | Spatial edge cases (boundary point, no match, multiple match) |
| M8 | `municipality/service/MunicipalityService.java` | CRUD + slug validation | 1 | B | Standard service |
| M9 | `municipality/api/MunicipalityAdminController.java` | Admin CRUD endpoints | 6 | B | Thin controller |
| M10 | `municipality/api/dto/*` (4 records) | Request/response records | 1/6 | B | Records |
| M11 | `municipality/api/WardBoundaryController.java` | GeoJSON boundary endpoint for map | 4 | B | Thin controller |

### 11.3 Identity module

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| I1 | `identity/domain/User.java` | Hardened entity (lockout, version) | 2 | B | Field mapping from migration |
| I2 | `identity/domain/RefreshToken.java` | Entity | 2 | B | Field mapping |
| I3 | `identity/domain/UserMunicipalityMembership.java` | Entity | 2 | B | Field mapping |
| I4 | `identity/repo/UserRepository.java` | + lockout queries | 2 | B | Derived queries |
| I5 | `identity/repo/RefreshTokenRepository.java` | + family revocation | 2 | **G** | Token-family traversal logic |
| I6 | `identity/repo/MembershipRepository.java` | Current-membership lookups | 2 | B | Derived queries |
| I7 | `identity/service/AuthService.java` | Register/login/lockout/normalize identifiers | 2 | **G** | Auth flow + lockout + normalization edge cases |
| I8 | `identity/service/TokenService.java` | Refresh rotation, reuse detection | 2 | **G** | Security-critical rotation logic |
| I9 | `identity/service/AppUserDetailsService.java` | Load by email/phone + memberships | 2 | B | Move of existing |
| I10 | `identity/api/AuthController.java` | register/login/refresh/logout | 2 | B | Thin controller |
| I11 | `identity/api/dto/*` (6 records) | Auth DTOs | 2 | B | Records |
| I12 | `identity/service/UserAdminController.java` | Admin user management | 6 | B | CRUD controller |

### 11.4 Complaint module

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| C1 | `complaint/domain/Complaint.java` | Hardened aggregate root | 3 | **G** | Aggregate invariants, version, reference code |
| C2 | `complaint/domain/ComplaintTransition.java` | Append-only log entity | 3 | B | Field mapping |
| C3 | `complaint/domain/ComplaintAssignment.java` | Assignment history entity | 4 | B | Field mapping |
| C4 | `complaint/domain/ResolutionAttempt.java` | Rating/reopen history entity | 5 | B | Field mapping |
| C5 | `complaint/domain/Attachment.java` | Hardened entity | 3 | B | Field mapping |
| C6 | `complaint/domain/enums/*` (6 enums) | Status, Category, Priority, Action, ModerationStatus, LocationSource | 1 | B | Enum lists |
| C7 | `complaint/repo/ComplaintRepository.java` | + `findAndLockById`, dashboard aggregates, heatmap native query | 3/4/5 | **G** | Locking + native PostGIS + aggregate correctness |
| C8 | `complaint/repo/ComplaintTransitionRepository.java` | Timeline queries | 3 | B | Derived queries |
| C9 | `complaint/repo/ComplaintAssignmentRepository.java` | Workload count | 4 | B | `@Query` count |
| C10 | `complaint/repo/ResolutionAttemptRepository.java` | Attempt lookups | 5 | B | Derived queries |
| C11 | `complaint/repo/AttachmentRepository.java` | Existing + soft-delete filter | 3 | B | Derived queries |
| C12 | `complaint/lifecycle/TransitionHandler.java` | Handler SPI | 3 | **G** | Core contract |
| C13 | `complaint/lifecycle/ComplaintLifecycleService.java` | Lock→version→handler→guards→log→outbox | 3 | **G** | The heart of the system |
| C14–C21 | `complaint/lifecycle/{Verify,Assign,StartWork,Resolve,Close,Reopen,Reject,Cancel}Handler.java` | 8 concrete handlers | 3/4/5 | **G** | Guard logic + side effects per transition |
| C22 | `complaint/lifecycle/TransitionCommand.java` | Command record (action, ids, note, evidence, idempotencyKey, expectedVersion) | 3 | **G** | Contract design |
| C23 | `complaint/submission/ComplaintSubmissionTemplate.java` | Transactional workflow | 3 | **G** | Orchestration + compensation |
| C24 | `complaint/submission/StandardComplaintSubmission.java` | Authed flow | 3 | **G** | Ward resolution + SLA hooks |
| C25 | `complaint/submission/AnonymousComplaintSubmission.java` | Anonymous flow + moderation | 5 | **G** | Abuse controls |
| C26 | `complaint/routing/ComplaintRoutingStrategy.java` | Strategy SPI + `RoutingDecision` | 4 | **G** | Contract design |
| C27 | `complaint/routing/CategoryRoutingStrategy.java` | Category→dept | 4 | B | Simple filter |
| C28 | `complaint/routing/LoadBalancedRoutingStrategy.java` | Least-loaded officer | 4 | **G** | Concurrent workload counting |
| C29 | `complaint/routing/DistanceBasedRoutingStrategy.java` | PostGIS nearest office | 4 | **G** | Spatial query |
| C30 | `complaint/routing/RoutingStrategyResolver.java` | Ordered registry | 4 | **G** | Fallback chain semantics |
| C31 | `complaint/service/ComplaintQueryService.java` | Read-side: detail, my-complaints, authority lists | 3 | B | Query assembly |
| C32 | `complaint/service/AttachmentService.java` | Upload validation, checksum, temp→final move | 3 | **G** | File/DB consistency (R6) |
| C33 | `complaint/api/CitizenComplaintController.java` | Submit, my, detail, rate, reopen, cancel | 3/5 | B | Thin controllers |
| C34 | `complaint/api/AuthorityComplaintController.java` | verify/assign/start/resolve/reject + queues | 4 | B | Thin controllers |
| C35 | `complaint/api/dto/*` (12 records) | Request/response projections | 3–5 | B | Records |
| C36 | `complaint/api/ComplaintMapper.java` | Entity→DTO incl. privacy filtering | 5 | **G** | Public vs private projection logic |

### 11.5 SLA module

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| L1 | `sla/domain/SlaPolicy.java` | Entity | 5 | B | Field mapping |
| L2 | `sla/domain/SlaInstance.java` | Entity | 5 | B | Field mapping |
| L3 | `sla/domain/SlaBreach.java` | Entity | 5 | B | Field mapping |
| L4 | `sla/repo/SlaPolicyRepository.java` | Lookup by tenant/category/priority | 5 | B | Derived query |
| L5 | `sla/repo/SlaInstanceRepository.java` | + `findAndLockOverdue SKIP LOCKED` | 5 | **G** | Concurrent scanner claim |
| L6 | `sla/repo/SlaBreachRepository.java` | Active-breach lookups | 5 | B | Derived query |
| L7 | `sla/service/SlaService.java` | Deadline calc (business clock, reopen 50% rule, missing-policy fallback) | 5 | **G** | SLA math + edge cases |
| L8 | `sla/service/SlaBreachScanner.java` | Hourly scanner, breach-once, escalation levels | 5 | **G** | Idempotent detection + escalation |
| L9 | `sla/service/EscalationService.java` | Level 1→councilor, level 2→mayor; notifications | 5 | **G** | Escalation policy |
| L10 | `sla/api/dto/SlaPolicyDto.java` | Admin CRUD DTO | 6 | B | Record |

### 11.6 Notification module

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| N1 | `notification/domain/Notification.java` | Entity | 5 | B | Field mapping |
| N2 | `notification/repo/NotificationRepository.java` | Unread counts | 5 | B | Derived query |
| N3 | `notification/service/NotificationService.java` | Create in-app rows, template rendering (bn/en) | 5 | B | Template assembly |
| N4 | `notification/service/NotificationFactory.java` | Channel factories → Notification rows | 5 | B | Builder logic |
| N5 | `notification/service/NotificationDispatcher.java` | Channel→sender registry | 5 | **G** | Registry + retry policy |
| N6 | `notification/service/OutboxWorker.java` | Poll, claim, dispatch, retry/backoff | 5 | **G** | At-least-once semantics |
| N7 | `notification/service/TwilioSmsSender.java` | SMS adapter (mock in dev) | 5 | B | API client wrapper |
| N8 | `notification/service/SmtpEmailSender.java` | Mail adapter | 5 | B | JavaMailSender wrapper |
| N9 | `notification/listener/ComplaintEventOutboxListener.java` | AFTER_COMMIT → enqueue outbox rows | 5 | **G** | Transactional boundary correctness |
| N10 | `notification/listener/InAppNotificationListener.java` | AFTER_COMMIT → in-app rows | 5 | B | Simple listener |
| N11 | `notification/api/NotificationController.java` | List/mark-read | 5 | B | Thin controller |
| N12 | `resources/templates/messages_bn.properties` | Bangla templates | 5 | B | Properties file |

### 11.7 Transparency module

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| T1 | `transparency/domain/WardMonthlyPerformance.java` | Entity | 5 | B | Field mapping |
| T2 | `transparency/repo/WardPerformanceRepository.java` | Snapshot + scoreboard queries | 5 | B | Derived/native queries |
| T3 | `transparency/service/HeatmapService.java` | Bbox query, grid clustering, obfuscation | 5 | **G** | Privacy + spatial clustering |
| T4 | `transparency/service/ScoreboardService.java` | Monthly rankings | 5 | B | Aggregation |
| T5 | `transparency/service/PerformanceSnapshotJob.java` | Monthly job (advisory lock) | 5 | **G** | Idempotent snapshot |
| T6 | `transparency/api/PublicController.java` | Heatmap, wards, scoreboard | 5 | B | Thin controller |
| T7 | `transparency/api/dto/*` (5 records) | Public projections (PII-free) | 5 | B | Records |

### 11.8 UI (Thymeleaf + JS/CSS)

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| U1 | `templates/index.html` | Public home + heatmap | 5 | B | Markup from existing |
| U2 | `templates/auth.html` | Login/register | 2 | B | Existing, add CSRF |
| U3 | `templates/citizen/dashboard.html` | My complaints list | 3 | B | Standard page |
| U4 | `templates/citizen/complaint-form.html` | Submit w/ map pin | 3 | B | Existing + Leaflet picker |
| U5 | `templates/citizen/complaint-detail.html` | Timeline, rate, reopen | 5 | B | Standard page |
| U6 | `templates/authority/dashboard.html` | Stats + charts | 4 | B | Standard page |
| U7 | `templates/authority/queue.html` | Dept work queue | 4 | B | Standard page |
| U8 | `templates/fragments/*` (nav, head) | Shared fragments | 3 | B | Fragments |
| U9 | `static/js/map-picker.js` | Leaflet pin + reverse ward lookup | 3 | B | Leaflet boilerplate |
| U10 | `static/js/heatmap.js` | Leaflet.heat + bbox fetch + clustering | 5 | B | Leaflet boilerplate |
| U11 | `static/js/timeline.js` | Poll/fetch timeline updates | 5 | B | Fetch boilerplate |
| U12 | `static/css/app.css` | Styles | 3 | B | CSS |
| U13 | `templates/admin/*` (3 pages) | Municipality/ward/user admin | 6 | B | CRUD pages |

### 11.9 Migrations, config, tests, docs

| # | File | Responsibility | Phase | Tier | Why |
|---|---|---|---|---|---|
| D1 | `db/migration/V1__platform_baseline.sql` | Municipalities, wards (PostGIS), departments, users, memberships | 1 | **G** | Tenancy + spatial foundation |
| D2 | `db/migration/V2__identity_hardening.sql` | Refresh tokens, lockout columns | 2 | B | Follows entity DDL |
| D3 | `db/migration/V3__complaint_core.sql` | Complaints, transitions, attachments + indexes | 3 | **G** | Core aggregate + partial indexes |
| D4 | `db/migration/V4__routing_assignment.sql` | Assignments, dept categories | 4 | B | Follows entity DDL |
| D5 | `db/migration/V5__sla_outbox_notification.sql` | SLA tables, outbox, notifications, resolution attempts, snapshots | 5 | **G** | Partial unique indexes, outbox |
| D6 | `db/migration/V6__demo_seed.sql` | Demo municipality/wards/users/complaints | 6 | B | Seed data |
| D7 | `db/migration/R__ward_boundaries.geojson.sql` | Repeatable: upsert ward boundaries from GeoJSON | 4 | **G** | Spatial data pipeline |
| D8 | `application.yml` (+ `application-postgres.yml`, `application-prod.yml`) | Config incl. routing order, rate limits, CORS | 1–6 | B | YAML |
| D9 | `TestcontainersConfiguration.java` | PostgreSQL+PostGIS container | 1 | **G** | Test infra correctness |
| D10 | `ComplaintLifecycleIntegrationTests.java` | Full transition matrix + concurrency (R1/R2/R10) | 3 | **G** | Highest-value tests |
| D11 | `AuthSecurityIntegrationTests.java` | Lockout, rotation, reuse, authz matrix | 2 | **G** | Security tests |
| D12 | `WardBoundaryIntegrationTests.java` | Point-in-polygon edge cases | 4 | **G** | Spatial tests |
| D13 | `SlaScannerIntegrationTests.java` | Breach-once, escalation, SKIP LOCKED | 5 | **G** | Concurrency tests |
| D14 | `OutboxDeliveryIntegrationTests.java` | At-least-once, retry, idempotency | 5 | **G** | Reliability tests |
| D15 | `HeatmapPrivacyIntegrationTests.java` | Obfuscation, PII absence | 5 | **G** | Privacy tests |
| D16 | `SubmissionIdempotencyTests.java` | Duplicate submission | 3 | **G** | Idempotency tests |
| D17 | `RepositorySmokeTests.java` | Each repo basic CRUD | 3–5 | B | Repetitive tests |
| D18 | `ControllerWebLayerTests.java` | Slice tests per controller | 3–6 | B | Repetitive tests |
| D19 | `docs/runbooks/*.md` (3 files) | Ops runbooks | 6 | B | Prose |
| D20 | `docs/implementation/PHASE-{1..6}-HANDOFF.md` | Per-phase handoff | 1–6 | B | Prose (Cline writes) |

**Tier distribution summary:** ~68% of files are Budget-tier (DTOs, entities, repos, controllers, templates, migrations following entity DDL, repetitive tests). ~32% are GLM-5.3 (lifecycle engine, security, spatial, SLA/outbox, concurrency tests, foundational migrations). This matches the cost-optimization goal: reasoning spend concentrates where bugs are expensive.

---

## 12. Cline Implementation Sequence (Phases 1–6)

**How to use:** copy each prompt verbatim into Cline at the start of the phase. Do not start Phase N+1 until Phase N's handoff exists and `./mvnw clean verify` passes. Each prompt is self-contained (Cline re-reads the blueprint) to avoid context-window degradation across long sessions.

---

### **PHASE 1 PROMPT — Platform Baseline & Tenancy Foundation**

```
You are implementing Phase 1 of Nagorik Seba per docs/ENTERPRISE_BLUEPRINT.md (§2, §3.1, §11.1, §11.2, D1, D9).

MODEL TIER REQUIRED: GLM-5.3 (high reasoning). Do not attempt this phase with reduced reasoning.

CONTEXT: This repo currently has a working package-by-layer Spring Boot 3.5 app (JWT auth, complaint submission, H2 default). We are evolving it into the modular structure in Blueprint §2. Existing functionality must keep compiling and passing tests throughout.

SCOPE — you may ONLY create/modify:
- src/main/java/com/nagorikseba/shared/** (files S1–S19 in matrix §11.1)
- src/main/java/com/nagorikseba/municipality/** (files M1–M4, M8, M10)
- src/main/java/com/nagorikseba/bootstrap/DataSeeder.java (move from config/)
- src/main/resources/db/migration/V1__platform_baseline.sql (D1)
- src/main/resources/application*.yml (D8)
- src/test/java/com/nagorikseba/TestcontainersConfiguration.java (D9)
- pom.xml (add: flyway-core, flyway-database-postgresql, postgresql, spring-boot-testcontainers, testcontainers-postgresql, hibernate-spatial; remove H2 runtime dep)

TASKS:
1. Read docs/ENTERPRISE_BLUEPRINT.md fully before writing any code.
2. Add dependencies. Configure Flyway (locations classpath:db/migration). Set spring.jpa.hibernate.ddl-auto=validate for all profiles. Default profile now uses Testcontainers-style PostgreSQL via Docker (spring.datasource.url points to localhost:5432/nagorik_seba; document docker run command in README) — H2 is removed.
3. Write V1__platform_baseline.sql exactly per Blueprint §3.1: municipalities, wards (with PostGIS geometry(MultiPolygon,4326), generated centroid, GiST index, unique (municipality_id, ward_number)), departments (handles_categories TEXT[]). Enable the postgis extension with CREATE EXTENSION IF NOT EXISTS postgis.
4. Create the shared/ package per §2 with files S1–S19. Move existing ApiError, exceptions, WebConfig, StorageProperties/LocalFsStorageAdapter, JwtProperties into shared/ with updated package names. Keep behavior identical.
5. Create municipality/ module: Municipality, Ward (map boundary as org.locationtech.jts.geom.MultiPolygon with @Column(columnDefinition="geometry(MultiPolygon,4326)") and hibernate-spatial @Type or Hibernate 6 @JdbcTypeCode(SqlTypes.GEOMETRY)), Department entities; MunicipalityRepository, WardRepository (basic CRUD only — spatial query comes in Phase 4), DepartmentRepository; MunicipalityService; DTO records.
6. Create shared/time ClockConfig exposing a Clock bean; replace direct LocalDateTime.now()/Instant.now() calls in moved code with injected Clock.
7. Update existing entities that remain in place (User, Complaint, etc.) ONLY to fix compilation after package moves — do not redesign them yet (that happens in their phases).
8. Update DataSeeder to seed one municipality (slug 'dhaka-north'), 3 wards with simple rectangular MultiPolygon boundaries around Dhaka (approx 23.72–23.88 N, 90.36–90.44 E), 4 departments with handles_categories arrays.
9. Write TestcontainersConfiguration (D9): @ServiceConnection PostgreSQLContainer with postgis/postgis:16-3.4 image; tests use it automatically.
10. Convert existing tests (AuthControllerIntegrationTests, ComplaintSubmissionIntegrationTests, NagorikSebaApplicationTests) to run against Testcontainers PostgreSQL. Fix any failures caused by dialect/geometry changes.

CONSTRAINTS:
- No TODOs, no stub methods, no unimplemented interfaces. Every file compiles and is used.
- Do not modify files outside SCOPE. If a compilation fix requires touching an out-of-scope file, make the minimal change and list it in the handoff.
- Flyway migration must be idempotent-safe on a fresh database (fresh container each test run).
- Java 21 features welcome (records, pattern matching) where they reduce code.

VERIFY (run all; fix failures before finishing):
1. ./mvnw clean verify
2. Confirm Flyway applied V1 (check test logs for "Successfully applied 1 migration" or query flyway_schema_history in the container).
3. Confirm no H2 references remain in src/ (grep -r "h2" src/ should return nothing except maybe comments you remove).

DELIVERABLE: Write docs/implementation/PHASE-1-HANDOFF.md containing: (a) files created/modified with one-line purpose each, (b) key decisions taken, (c) exact commands run and their outcomes, (d) contracts Phase 2 can rely on (entity field names, package paths, Clock bean, Testcontainers setup), (e) any deviations from the blueprint and why.
```

---

### **PHASE 2 PROMPT — Identity, Auth Hardening & Security**

```
You are implementing Phase 2 of Nagorik Seba per docs/ENTERPRISE_BLUEPRINT.md (§3.2, §8, §11.1 S3/S7–S9/S18, §11.3, D2, D11).

MODEL TIER REQUIRED: GLM-5.3 (high reasoning).

CONTEXT: Phase 1 is complete — read docs/implementation/PHASE-1-HANDOFF.md first, then the blueprint sections above. The app runs on PostgreSQL+PostGIS via Flyway with the shared/ and municipality/ modules in place.

SCOPE — you may ONLY create/modify:
- src/main/java/com/nagorikseba/shared/config/SecurityConfig.java
- src/main/java/com/nagorikseba/shared/security/** (JwtTokenProvider, JwtAuthenticationFilter, PrincipalContext)
- src/main/java/com/nagorikseba/shared/exception/GlobalExceptionHandler.java
- src/main/java/com/nagorikseba/identity/** (all files I1–I12)
- src/main/resources/db/migration/V2__identity_hardening.sql
- src/main/resources/templates/auth.html (add CSRF token to forms)
- src/main/resources/application*.yml (security config keys)
- src/test/java/com/nagorikseba/AuthSecurityIntegrationTests.java
- Existing auth-related files being replaced (old security/, dto/auth/, AuthService, AuthController) — delete after migration.

TASKS:
1. Read the Phase 1 handoff and blueprint §8 fully.
2. Write V2__identity_hardening.sql per §3.2: alter users (add failed_login_count, locked_until, last_login_at, version; convert email to CITEXT via citext extension; keep phone unique), create refresh_tokens and user_municipality_memberships with all constraints and partial indexes listed.
3. Migrate identity into identity/ module: User (I1), RefreshToken (I2), UserMunicipalityMembership (I3) entities matching V2 exactly; repositories (I4–I6) including RefreshTokenRepository.findActiveTokenFamilies / revokeFamily logic.
4. Implement AuthService (I7): register (normalize phone to 01XXXXXXXXX, CITEXT email, bcrypt strength 12), login with lockout (5 failures → 15 min lock, reset on success), identifier = email OR phone. Issue access token (15 min, claims sub/uid/role/mids/typ) + refresh token (opaque 256-bit, SHA-256 at rest).
5. Implement TokenService (I8): refresh rotation — validate hash + expiry + not revoked; on reuse of a revoked token, revoke the entire family (walk replaced_by_token_id chain both directions) and throw; issue new pair; update replaced_by_token_id.
6. Rewrite SecurityConfig (S3): stateless /api/** with CSRF disabled ONLY there; Thymeleaf routes keep CSRF via CookieCsrfTokenRepository with the Spring Security 6 XOR protection enabled; CORS from app.security.cors.allowed-origins (default http://localhost:8080 only); security headers per §8.3; endpoint rules per §8.2 table (public: /, /login, /register, /api/auth/**, /api/public/**; role rules for the rest).
7. PrincipalContext (S9): resolve user + active municipality memberships from JWT claims; expose to services.
8. GlobalExceptionHandler (S18): map ResourceNotFound→404, Conflict/OptimisticLocking→409, InvalidStateTransition→422, AccessDenied→403, MethodArgumentNotValid→400 with field errors, all as RFC-7807 application/problem+json via ApiError.
9. AuthController (I10): POST /api/auth/register, /login, /refresh, /logout (revokes provided refresh token). DTOs per I11.
10. Update auth.html forms with CSRF hidden fields; keep existing JS working.
11. Write AuthSecurityIntegrationTests (D11) covering: successful register/login; wrong password ×5 → lockout → 423/429 response; refresh rotation works; refresh reuse after rotation → family revoked → 401; access token expired → 401; role-based endpoint access matrix (citizen cannot hit /api/authority/**); CITEXT email case-insensitivity; duplicate phone rejection.

CONSTRAINTS:
- No TODOs/stubs. All tests green. Do not touch complaint/ sla/ notification/ modules (they still use old code — that's fine; keep old classes compiling by adapting imports only).
- JWT secret from env only in prod profile; dev default acceptable (already present).
- Passwords never logged. Tokens never logged.

VERIFY:
1. ./mvnw clean verify
2. ./mvnw test -Dtest=AuthSecurityIntegrationTests — all pass.
3. Manual smoke (document in handoff): start app, register, login, call GET /api/complaints/my with token → 200; without token → 401; with citizen token on an authority endpoint → 403.

DELIVERABLE: docs/implementation/PHASE-2-HANDOFF.md (same structure as Phase 1: files, decisions, commands, contracts for Phase 3 — especially PrincipalContext API, membership query methods, and the 409/422 exception contract).
```

---

### **PHASE 3 PROMPT — Complaint Aggregate & Lifecycle Engine**

```
You are implementing Phase 3 of Nagorik Seba per docs/ENTERPRISE_BLUEPRINT.md (§3.3, §5, §6, §7.1, §7.5, §11.4 C1–C14/C22–C25/C31–C33/C35, D3, D10, D16).

MODEL TIER REQUIRED: GLM-5.3 (high reasoning). This phase contains the system's core invariants.

CONTEXT: Phase 2 complete — read docs/implementation/PHASE-2-HANDOFF.md first. Identity, security, tenancy are stable.

SCOPE — you may ONLY create/modify:
- src/main/java/com/nagorikseba/complaint/** (files listed above)
- src/main/resources/db/migration/V3__complaint_core.sql
- src/test/java/com/nagorikseba/ComplaintLifecycleIntegrationTests.java
- src/test/java/com/nagorikseba/SubmissionIdempotencyTests.java
- src/test/java/com/nagorikseba/RepositorySmokeTests.java (complaint repos only)
- Templates: citizen/dashboard.html, citizen/complaint-form.html, fragments/ (U3, U4, U8), static/js/map-picker.js (U9), static/css/app.css (U12)
- Old complaint code being replaced (entity/Complaint.java, state/, template/, dto/complaint/, ComplaintService, ComplaintController, observer/, event/, factory/) — delete after migration.
- bootstrap/DataSeeder.java (add demo citizens + complaints in various statuses)

TASKS:
1. Read Phase 2 handoff + blueprint §3.3, §5, §6, §7.1, §7.5.
2. Write V3__complaint_core.sql exactly per §3.3: complaints (all columns incl. reference_code, version, geography(Point,4326) location, moderation, partial indexes), complaint_transitions (idempotency unique), attachments (checksum, scan_status, soft delete). Drop legacy columns from the old schema if the table already exists from prior ddl-auto (write proper ALTER/DROP statements — the migration must run cleanly on a fresh DB AND on the existing dev DB; use IF EXISTS guards).
3. Entities: Complaint (C1 — aggregate root with @Version, reference_code generation 'NS-yyyy-######' via a sequence, location as org.locationtech.jts.geom.Point mapped with hibernate-spatial, enum attributes, helper methods that ONLY the lifecycle service calls), ComplaintTransition (C2), Attachment (C5), enums (C6).
4. Lifecycle engine per §7.1: TransitionHandler SPI (C12), TransitionCommand record (C22), ComplaintLifecycleService (C13) with the exact execute() flow: findAndLockById (SELECT ... FOR UPDATE via @Lock(PESSIMISTIC_WRITE) @Query) → version check → handler lookup by action → source-status check → handler.execute() → (handler appends transition row + outbox rows via OutboxPublisher) → return.
5. Implement handlers for Phase 3 actions: VerifyHandler, RejectHandler, CancelHandler (C14, C21). Assign/Start/Resolve/Close/Reopen handlers are Phase 4/5 — but create their classes now ONLY if you can fully implement them against current repos; otherwise leave them for their phases and note it in the handoff. (Preferred: implement Verify/Reject/Cancel fully now.)
6. Submission per §7.5: ComplaintSubmissionTemplate (C23) — final orchestrated method validate → persist (generate reference_code, resolve municipality from PrincipalContext, resolve ward via WardRepository basic bbox fallback for now — full PostGIS in Phase 4) → saveAttachments (temp dir + AFTER_COMMIT move per R6) → createSlaInstance placeholder is NOT allowed: instead call sla module's existing SlaService if compatible, else skip SLA in Phase 3 and note it. StandardComplaintSubmission (C24). AnonymousComplaintSubmission (C25) — implement fully with phone-required validation and moderation_status=PENDING.
7. AttachmentService (C32): magic-byte type check (existing Tika/logic), size caps, SHA-256 checksum, random storage key 'complaints/{yyyy}/{MM}/{ref}/{uuid}.{ext}', temp→final move on AFTER_COMMIT, orphan-temp cleaner @Scheduled.
8. Read side: ComplaintQueryService (C31) + CitizenComplaintController (C33): POST /api/complaints (multipart, Idempotency-Key header support per R3 — store key, replay returns original), GET /api/complaints/my, GET /api/complaints/{referenceCode} (owner or authority per §8.2), POST /api/complaints/{ref}/cancel. DTOs (C35) — never expose citizen PII to non-owners.
9. UI: citizen dashboard (list + status badges), complaint form with Leaflet map picker (U9: click sets lat/lng, shows ward name via GET /api/public/wards/lookup?lat=&lng= — add this thin endpoint to municipality module), shared fragments, CSS.
10. Tests (D10, D16): ComplaintLifecycleIntegrationTests — full legal-transition matrix from §6 for implemented actions; illegal transitions → 422; concurrent verify (two threads, R1) → one 200 one 409; version mismatch → 409; idempotency replay → same reference_code. SubmissionIdempotencyTests — double submit with same key → one complaint. RepositorySmokeTests for new repos.

CONSTRAINTS:
- The ONLY code allowed to change complaint.status is a TransitionHandler. Enforce via package-private setter or documented aggregate method — verify with a test that reflection-free illegal setStatus is impossible from outside the lifecycle package (or at minimum assert the setter is package-private).
- No TODOs/stubs. All tests green. Old state/ template/ observer/ packages fully removed.
- Outbox rows written for COMPLAINT_SUBMITTED and each transition (payload: complaintId, referenceCode, from, to, actorId, note, occurredAt) — OutboxWorker itself is Phase 5; rows just accumulate.

VERIFY:
1. ./mvnw clean verify
2. ./mvnw test -Dtest='ComplaintLifecycleIntegrationTests,SubmissionIdempotencyTests'
3. Manual smoke: register → submit complaint with photo via curl (README pattern) → GET /api/complaints/my shows SUBMITTED with timeline → officer verify via seeded officer token → status VERIFIED. Document tokens/commands in handoff.

DELIVERABLE: docs/implementation/PHASE-3-HANDOFF.md (files, decisions, commands, contracts for Phase 4: TransitionHandler registry API, TransitionCommand shape, outbox payload schema, Complaint aggregate invariants).
```

---

### **PHASE 4 PROMPT — Authority Workflows, Routing & Geospatial**

```
You are implementing Phase 4 of Nagorik Seba per docs/ENTERPRISE_BLUEPRINT.md (§3.1 wards spatial, §3.3 assignments, §4 spatial queries, §7.2, §11.2 M5–M7/M11, §11.4 C3/C9/C26–C30/C34, D4, D7, D12).

MODEL TIER REQUIRED: GLM-5.3 (high reasoning) for spatial + routing; the authority UI pages (U6, U7) may be delegated to a lower-reasoning pass — implement them last and keep them simple.

CONTEXT: Phase 3 complete — read docs/implementation/PHASE-3-HANDOFF.md first. Complaint lifecycle engine, submission, citizen UI work.

SCOPE — you may ONLY create/modify:
- municipality/repo/WardRepository.java (add spatial query), municipality/service/WardBoundaryService.java, municipality/api/WardBoundaryController.java
- complaint/domain/ComplaintAssignment.java, complaint/repo/ComplaintAssignmentRepository.java, complaint/repo/ComplaintRepository.java (add dashboard aggregates + heatmap native query)
- complaint/routing/** (C26–C30)
- complaint/lifecycle/AssignHandler.java, StartWorkHandler.java
- complaint/api/AuthorityComplaintController.java (C34)
- src/main/resources/db/migration/V4__routing_assignment.sql, R__ward_boundaries.geojson.sql
- templates/authority/dashboard.html, templates/authority/queue.html
- bootstrap/DataSeeder.java (officers per dept, sample assignments)
- src/test/java/com/nagorikseba/WardBoundaryIntegrationTests.java

TASKS:
1. Read Phase 3 handoff + blueprint §4, §7.2.
2. V4__routing_assignment.sql: complaint_assignments table per §3.3 with partial index on active assignments; add handles_categories to departments if not present from V1.
3. WardRepository: native query findWardContaining(municipalityId, lng, lat) using ST_Covers(boundary, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)) with the GiST index; WardBoundaryService (M7): resolve point → ward; handle: point outside all wards (throw WardNotCoveredException → submission maps it to a clear 422), point on shared boundary (deterministic: lowest ward id), inactive wards excluded. Also validateBoundaryOverlap(municipalityId) using ST_Intersects for admin use.
4. R__ward_boundaries.geojson.sql: repeatable migration upserting realistic Dhaka North ward boundaries (at minimum wards 1–5 as adjacent polygons; hand-drawn approximations acceptable, must not overlap and must cover the test area). Source GeoJSON embedded in the SQL.
5. Routing per §7.2: ComplaintRoutingStrategy SPI returning RoutingDecision(department, officer, explanation); CategoryRoutingStrategy (dept whose handles_categories contains complaint category; fallback: throw NoEligibleDepartmentException → complaint stays VERIFIED with transition note), LoadBalancedRoutingStrategy (count active assignments per officer via C9; pick min; tie → lowest officer id; officers = active memberships of dept), DistanceBasedRoutingStrategy (nearest dept office_location by ST_Distance on geography). RoutingStrategyResolver: ordered by app.routing.strategy-order config, first supports() wins; decision explanation persisted in complaint_assignments.strategy_used + transition metadata JSONB.
6. AssignHandler: manual assign (officer/councilor picks dept+officer; validate membership + category handling per §6 guards) and auto-assign path (SYSTEM actor; resolver). StartWorkHandler: guard actor == assigned officer or admin.
7. AuthorityComplaintController (C34): GET /api/authority/queue (dept's active complaints, filterable by status), GET /api/authority/dashboard (per-ward counts by status, avg resolution time, SLA-at-risk count — via ComplaintRepository aggregate queries), POST /api/complaints/{ref}/verify|assign|start. Authorization per §8.2: officer sees own municipality; councilor sees own ward(s).
8. Authority UI: dashboard.html (stat cards + per-ward table + simple CSS bar charts — no JS chart lib needed), queue.html (filterable list with action buttons calling the API with fetch + JWT from localStorage).
9. WardBoundaryIntegrationTests (D12): point inside ward → correct ward; outside municipality → WardNotCoveredException; on shared edge → deterministic lowest-id; overlapping boundaries rejected by validator; heatmap native query returns only public-visible approved complaints in bbox.

CONSTRAINTS:
- All routing decisions auditable (strategy_used + explanation in transition metadata) — test asserts this.
- No TODOs/stubs. All tests green. Native SQL tested against Testcontainers PostGIS (not H2 — it's gone).
- Dashboard aggregate queries must use the indexes from §4 — verify with EXPLAIN in one test (assert index usage or at least document plan in handoff).

VERIFY:
1. ./mvnw clean verify
2. ./mvnw test -Dtest='WardBoundaryIntegrationTests,ComplaintLifecycleIntegrationTests'
3. Manual smoke: submit complaint → verify → auto-assign (category ROADS → roads dept) → start → check assignment row + transition metadata contains routing explanation. Document in handoff.

DELIVERABLE: docs/implementation/PHASE-4-HANDOFF.md (files, decisions, commands, contracts for Phase 5: routing decision schema, dashboard aggregate DTOs, assignment history API).
```

---

### **PHASE 5 PROMPT — SLA, Notifications, Outbox & Public Transparency**

```
You are implementing Phase 5 of Nagorik Seba per docs/ENTERPRISE_BLUEPRINT.md (§3.4, §3.5, §3.6, §4 heatmap, §5 R4/R5/R7, §7.3, §7.4, §9, §11.5–§11.7, D5, D13–D15).

MODEL TIER REQUIRED: GLM-5.3 (high reasoning). Reliability logic (outbox, SLA scanner) and privacy logic (heatmap) demand it.

CONTEXT: Phase 4 complete — read docs/implementation/PHASE-4-HANDOFF.md first.

SCOPE — you may ONLY create/modify:
- sla/** (L1–L9), notification/** (N1–N12), transparency/** (T1–T7)
- complaint/lifecycle/ResolveHandler.java, CloseHandler.java, ReopenHandler.java, complaint/domain/ResolutionAttempt.java (C4), complaint/repo/ResolutionAttemptRepository.java (C10), complaint/api/ComplaintMapper.java (C36), CitizenComplaintController (add rate/reopen endpoints)
- shared/outbox/OutboxRepository.java (SKIP LOCKED claim), shared/config/SchedulingConfig.java, AsyncConfig.java
- src/main/resources/db/migration/V5__sla_outbox_notification.sql
- templates/citizen/complaint-detail.html (U5), static/js/heatmap.js (U10), static/js/timeline.js (U11), templates/index.html (heatmap section)
- resources/templates/messages_bn.properties + messages_en.properties
- src/test/java/com/nagorikseba/{SlaScannerIntegrationTests,OutboxDeliveryIntegrationTests,HeatmapPrivacyIntegrationTests}.java
- bootstrap/DataSeeder.java (SLA policies for all categories × priorities)

TASKS:
1. Read Phase 4 handoff + blueprint §3.4–§3.6, §5, §7.3–§7.4, §9.
2. V5__sla_outbox_notification.sql per §3.4–§3.6: sla_policies (unique per municipality/category/priority), sla_instances (unique complaint, deadline), sla_breaches (partial unique active per complaint), notifications, outbox_messages (partial poll index), resolution_attempts, ward_monthly_performance. Seed default SLA policies (e.g., ROADS/NORMAL 72h, WATERLOGGING/CRITICAL 12h, MOSQUITO/NORMAL 48h — reasonable values, document them).
3. SlaService (L7): calculateDeadline uses injected Clock + complaint.submittedAt (NOT now()); reopen → deadline = now + 50% of original policy hours; priority change → recalculate; missing policy → fallback default policy (config app.sla.default-hours=120) + WARN log, never crash submission.
4. SlaBreachScanner (L8): @Scheduled hourly; SELECT sla_instances joined complaints WHERE deadline < now AND breach_at IS NULL AND complaint active — FOR UPDATE SKIP LOCKED (R4); for each: set breach_at, insert sla_breaches row (breach-once via partial unique), escalate level 1 (notify ward councilor via outbox) or level 2 if hours_overdue > escalation_level_2_hours (notify mayor/CEO); clear breach (resolved_at) when complaint reaches RESOLVED — hook into ResolveHandler.
5. ResolveHandler: guards (assigned officer, ≥1 work-proof attachment linked to this transition); creates resolution_attempt (PENDING_CITIZEN); clears active SLA breach; outbox COMPLAINT_RESOLVED. CloseHandler: citizen-only, rating 1–5, sets attempt outcome CLOSED, closed_at. ReopenHandler: citizen-only, reason, reopen_count<5 guard, attempt outcome REOPENED, priority→HIGH, SLA recalc, escalation to councilor, outbox. Auto-close: @Scheduled daily — RESOLVED complaints older than 7 days with no citizen action → SYSTEM AUTO_CLOSE (no rating).
6. Outbox (§7.3): OutboxRepository.claimBatch(size) via UPDATE ... SET status='PROCESSING' WHERE id IN (SELECT id ... WHERE status IN ('PENDING','FAILED') AND next_attempt_at <= now ORDER BY id LIMIT :size FOR UPDATE SKIP LOCKED) RETURNING *; OutboxWorker (N6): @Scheduled every 10s; dispatch by event_type → NotificationDispatcher; success → SENT; failure → retry_count++, next_attempt_at = now + exponential backoff (max 5 attempts → FAILED terminal + metric); idempotency: provider metadata carries outbox id; Twilio/SMTP adapters are no-op loggers in dev profile (N7/N8 with @Profile or config flag).
7. Notification listeners (N9/N10): @TransactionalEventListener(AFTER_COMMIT) on ComplaintStatusChangedEvent → write in-app notification rows + ensure outbox rows exist for SMS/EMAIL channels per citizen preference (default: EMAIL if email, SMS if phone). Templates bn/en via messages properties (N12): COMPLAINT_SUBMITTED/VERIFIED/ASSIGNED/RESOLVED/CLOSED/REOPENED/SLA_ESCALATION with {referenceCode}, {status}, {note} placeholders.
8. Transparency: HeatmapService (T3) — public bbox query per §4 (only is_public_visible + APPROVED + not REJECTED/CANCELLED), coordinates obfuscated via ST_SnapToGrid(location::geometry, 0.001) (~100m), grid clustering when > 500 points (ST_SnapToGrid + count per cell, return centroids); ScoreboardService (T4) — monthly per-ward ranking from ward_monthly_performance; PerformanceSnapshotJob (T5) — @Scheduled monthly (and on-demand endpoint), advisory lock pg_try_advisory_lock (R7), upsert per ward/month. PublicController (T6): GET /api/public/heatmap?municipality=&minLng=&minLat=&maxLng=&maxLat=, GET /api/public/wards, GET /api/public/wards/scoreboard?municipality=&period=. Rate-limit per §8.4 (simple in-memory filter acceptable; document upgrade path to Bucket4j+Redis).
9. Citizen endpoints: POST /api/complaints/{ref}/rate {rating, feedback} (→ CLOSE), POST /api/complaints/{ref}/reopen {reason}. ComplaintMapper (C36): public projection (obfuscated coords, no PII) vs private projection (exact coords for owner/authority) — enforced centrally.
10. UI: complaint-detail.html (timeline from transitions API, attachments gallery, rate/reopen forms), heatmap.js (Leaflet + leaflet.heat from CDN, fetch /api/public/heatmap on moveend with debounce, legend by category), timeline.js (30s polling of transitions for open page), index.html heatmap section + ward scoreboard table.
11. Tests: SlaScannerIntegrationTests (D13) — breach detected once (run scanner twice → one breach row); escalation levels; SKIP LOCKED (two threads scan concurrently → no double-claim, assert via row counts); breach cleared on resolve. OutboxDeliveryIntegrationTests (D14) — event → outbox row → worker delivers (mock sender records); sender fails 2× then succeeds → retry works, SENT after 3rd; 5 failures → FAILED terminal; duplicate dispatch → consumer idempotent. HeatmapPrivacyIntegrationTests (D15) — public response contains no citizen fields (assert JSON keys); coords differ from exact by ≤ ~150m; REJECTED/PENDING-moderation complaints excluded; > 500 points → clustered response.

CONSTRAINTS:
- No TODOs/stubs. All tests green. Schedulers must not run during unrelated tests (disable via @ConditionalOnProperty or test profile app.scheduling.enabled=false; enable explicitly in scheduler tests).
- All notification content available in Bangla and English.
- Public API responses validated against a strict allowlist of fields (test enforces).

VERIFY:
1. ./mvnw clean verify
2. ./mvnw test -Dtest='SlaScannerIntegrationTests,OutboxDeliveryIntegrationTests,HeatmapPrivacyIntegrationTests'
3. Manual smoke: submit → verify → assign → start → resolve (with proof photo) → citizen rates 5★ → CLOSED; reopen path on a second complaint; force an SLA breach by seeding a past deadline → run scanner endpoint/trigger → breach row + councilor notification row exists. Document all curl commands in handoff.

DELIVERABLE: docs/implementation/PHASE-5-HANDOFF.md (files, decisions, commands, contracts for Phase 6: public API shapes, notification template codes, scheduler toggles).
```

---

### **PHASE 6 PROMPT — Admin, Hardening, Demo & Deployment**

```
You are implementing Phase 6 of Nagorik Seba per docs/ENTERPRISE_BLUEPRINT.md (§8.4 rate limits, §9 retention, §10, §11.8 U13, §11.9 D6/D19, §13).

MODEL TIER: Mixed — admin CRUD pages and seed data are Budget-tier work; the security/retention/observability items need GLM-5.3. Do the GLM-5.3 items first.

CONTEXT: Phase 5 complete — read docs/implementation/PHASE-5-HANDOFF.md first. All core flows work.

SCOPE — you may ONLY create/modify:
- municipality/api/MunicipalityAdminController.java, identity/service/UserAdminController.java, templates/admin/* (3 pages)
- shared/config/RateLimitFilter.java (or web config), shared/observability/** (metrics config, OutboxLagHealthIndicator)
- sla/api/ (admin CRUD for SLA policies)
- src/main/resources/db/migration/V6__demo_seed.sql, application-prod.yml
- docs/runbooks/*.md (3 files), README.md
- EXIF-stripping in AttachmentService; retention @Scheduled job
- src/test/java/com/nagorikseba/ControllerWebLayerTests.java (remaining slices)

TASKS:
1. Read Phase 5 handoff + blueprint §8.4, §9, §10, §13.
2. Admin APIs + pages (ADMIN role only): municipality CRUD (with boundary GeoJSON upload → validation via WardBoundaryService.validateBoundaryOverlap), ward CRUD, department CRUD (handles_categories editor), user management (activate/deactivate, membership assignment), SLA policy CRUD. Simple Thymeleaf tables + forms; no JS framework.
3. Rate limiting per §8.4: servlet filter with in-memory token buckets keyed by IP/user; 429 with Retry-After header; config-driven limits; test with MockMvc hitting login 6×.
4. Observability: Micrometer counters/timers at the extension points listed in §10 (complaint.submitted, complaint.transition, sla.breach.detected, outbox.lag.seconds gauge, notification.delivery); OutboxLagHealthIndicator (DOWN if oldest PENDING > 10 min); JSON logging pattern with MDC traceId/municipalityId/complaintId (set in a servlet filter + lifecycle service).
5. Privacy hardening: strip EXIF (incl. GPS) from uploaded images on write (use metadata-extractor + ImageIO re-encode or a simple re-encode-through-ImageIO which drops EXIF — implement the re-encode approach, no new deps if possible); retention job: purge anonymous contact phones > 90 days old on terminal complaints; user deletion → anonymize citizen reference per §9.4.
6. V6__demo_seed.sql: rich demo dataset — 1 municipality, 10 wards with boundaries, ~15 users across roles, 4 departments, SLA policies, ~60 complaints spread across all statuses/categories/wards with realistic timestamps (some breached, some resolved+rated, some reopened), transitions history for each, a few work-proof attachments (tiny placeholder images written by DataSeeder into uploads dir — seed via Java seeder, not SQL, for files). Demo credentials documented in README (citizen@demo / officer@demo / councilor@demo / admin@demo, password 'demo1234').
7. application-prod.yml: postgres via env vars, ddl-auto=validate, Flyway clean-disabled, JWT secret from env (fail startup if missing), CORS allowlist from env, actuator restricted, uploads dir from env, Twilio/SMTP props from env, scheduling enabled.
8. Runbooks (D19): (a) SLA scanner stuck — symptoms, queries to check sla_instances/outbox, restart procedure; (b) outbox backlog — check FAILED rows, replay procedure (reset status), Twilio/SMTP credential rotation; (c) ward boundary import — GeoJSON format, R-migration behavior, overlap validation.
9. README rewrite: quickstart (Docker postgres+postgis command → ./mvnw spring-boot:run), demo credentials, API summary table linking to handoff docs, architecture summary linking blueprint, deployment guide (Render/Railway + Neon: env vars table, Flyway on deploy, health check path).
10. Final test sweep: ControllerWebLayerTests for all controllers (auth on, validation errors, happy paths); run full suite; fix flakes (scheduler tests use explicit triggers, no sleeps > 2s).

CONSTRAINTS:
- No TODOs/stubs. Full suite green: ./mvnw clean verify.
- Demo seed must produce a visually interesting heatmap (complaints clustered around real Dhaka landmarks) and a non-trivial scoreboard.
- Do not weaken any security setting to make demo work.

VERIFY:
1. ./mvnw clean verify
2. Fresh-clone simulation: docker run postgres+postgis → SPRING_PROFILES_ACTIVE=postgres ./mvnw spring-boot:run → login as each demo role → exercise: citizen submit+rate, officer verify→assign→resolve, public heatmap without login, admin SLA edit. Document the full demo script (numbered steps with expected results) in the handoff.
3. grep -r "TODO\|FIXME\|XXX" src/ returns nothing.

DELIVERABLE: docs/implementation/PHASE-6-HANDOFF.md (files, decisions, commands, demo script, deployment checklist, known limitations & future work).
```

---

## 13. Definition of Done & Global Engineering Standards

Every phase, every file, without exception:

1. **Build:** `./mvnw clean verify` exits 0.
2. **No placeholders:** zero `TODO`/`FIXME`/stub methods/unused files.
3. **Tests:** new logic ships with tests; bug fixes ship with regression tests. Concurrency claims (§5) backed by actual multi-threaded tests.
4. **Transactions:** every lifecycle mutation inside `ComplaintLifecycleService.execute()`; no direct status writes anywhere.
5. **Tenancy:** every tenant-scoped query filters by municipality; public endpoints take explicit municipality slug; tests assert cross-tenant access → 404/403.
6. **Time:** all persistence in UTC `Instant`; only the presentation layer formats `Asia/Dhaka`; all clock reads via the `Clock` bean.
7. **Privacy:** public API responses validated by allowlist tests; no PII leakage.
8. **Migrations:** forward-only Flyway; every migration runs clean on fresh DB and on the prior dev DB; `ddl-auto=validate` everywhere.
9. **Handoff:** `docs/implementation/PHASE-N-HANDOFF.md` written before the phase is considered done.
10. **Scope discipline:** changes outside a phase's SCOPE list require an explicit deviation entry in the handoff.

---

*End of blueprint. Begin with the Phase 1 prompt in §12.*