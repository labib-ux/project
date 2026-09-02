-- ============================================================================
-- Nagorik Seba — V2 identity hardening (Blueprint §3.2, §8.1)
-- PostgreSQL 16 + citext
--
-- 1. users: CITEXT email, nullable contact columns, password_hash rename,
--    lockout counters, optimistic-lock version, timestamptz timestamps.
-- 2. refresh_tokens: opaque refresh tokens, SHA-256 hashed at rest, rotation
--    chain via replaced_by_token_id (family revocation on reuse — R9).
-- 3. user_municipality_memberships: officer/councilor tenancy as membership
--    history instead of a mutable FK on users.
--
-- Forward-only. ward_id / department_id on users are intentionally retained:
-- the Phase 1 authority/complaint code still reads them. Memberships become
-- the authoritative tenancy source from Phase 3 onwards.
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS citext;

-- ============================================================================
-- users — hardening
-- ============================================================================

-- Case-insensitive email. The existing uq_user_email index is rebuilt with
-- citext comparison semantics, so 'Amina@x.com' and 'amina@x.com' collide.
ALTER TABLE users ALTER COLUMN email TYPE CITEXT;

-- §3.2: either email or phone is enough to identify an account, so both are
-- nullable and guarded by ck_user_contact below.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- password is a hash, and anonymous-complaint citizens may have none at all.
ALTER TABLE users RENAME COLUMN password TO password_hash;
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

ALTER TABLE users ALTER COLUMN full_name TYPE VARCHAR(120);

-- Lockout counters (§8.1: 5 failures → 15 min lock).
ALTER TABLE users ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN locked_until       TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN last_login_at      TIMESTAMPTZ;

-- Optimistic locking (R8).
ALTER TABLE users ADD COLUMN version INT NOT NULL DEFAULT 0;

-- UTC storage for all identity timestamps. Existing rows were written by
-- Hibernate with hibernate.jdbc.time_zone=Asia/Dhaka, so that is the zone the
-- naked wall-clock values must be interpreted in.
ALTER TABLE users
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'Asia/Dhaka';
ALTER TABLE users ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE users
    ADD CONSTRAINT ck_user_contact CHECK (email IS NOT NULL OR phone IS NOT NULL);
ALTER TABLE users
    ADD CONSTRAINT ck_user_password CHECK (password_hash IS NOT NULL OR role = 'CITIZEN');

CREATE INDEX idx_user_role_active ON users (role) WHERE is_active;

-- ============================================================================
-- user_municipality_memberships — tenancy history
-- ============================================================================
CREATE TABLE user_municipality_memberships (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id),
    municipality_id BIGINT NOT NULL REFERENCES municipalities(id),
    ward_id         BIGINT REFERENCES wards(id),
    department_id   BIGINT REFERENCES departments(id),
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_membership_current UNIQUE (user_id, municipality_id, valid_until),
    CONSTRAINT ck_membership_range CHECK (valid_until IS NULL OR valid_until > valid_from)
);
CREATE INDEX idx_membership_user_current
    ON user_municipality_memberships (user_id) WHERE valid_until IS NULL;
CREATE INDEX idx_membership_dept
    ON user_municipality_memberships (department_id) WHERE valid_until IS NULL;

-- ============================================================================
-- refresh_tokens — rotation with reuse detection
-- ============================================================================
CREATE TABLE refresh_tokens (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id              BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash           VARCHAR(255) NOT NULL,   -- SHA-256 hex of the opaque token
    expires_at           TIMESTAMPTZ NOT NULL,
    revoked_at           TIMESTAMPTZ,
    replaced_by_token_id BIGINT REFERENCES refresh_tokens(id),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    ip_address           INET,
    user_agent           VARCHAR(255),
    CONSTRAINT uq_refresh_token_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_user_active
    ON refresh_tokens (user_id, expires_at) WHERE revoked_at IS NULL;
