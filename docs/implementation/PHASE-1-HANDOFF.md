# Phase 1 Handoff — Nagorik Seba Platform Baseline

**Date:** 2026-09-01  
**Status:** ✅ Complete — `./mvnw clean verify` passes

---

## Summary

Phase 1 establishes the PostgreSQL + PostGIS foundation with Flyway migrations, Testcontainers integration, shared kernel packages, and the municipality/ward/department domain module.

---

## Changes Made

### STEP 1 — `pom.xml` Dependencies
- **Added:** `flyway-core`, `flyway-database-postgresql`, `postgresql` driver, `spring-boot-testcontainers`, `testcontainers-postgresql`, `hibernate-spatial`
- **Removed:** H2 runtime dependency
- **Java:** 21, Spring Boot 3.5.16

### STEP 2 — Flyway Migration `V1__platform_baseline.sql`
- `CREATE EXTENSION IF NOT EXISTS postgis`
- **municipalities** table (id, slug, name, name_bn, is_active, timestamps)
- **wards** table with `geometry(MultiPolygon,4326)` boundary, generated `geography(Point,4326)` centroid, GiST index, `UNIQUE(municipality_id, ward_number)`
- **departments** table with `handles_categories TEXT[]` array
- Existing domain tables (users, complaints, attachments, etc.) kept for ddl-auto=validate compatibility

### STEP 3 — `application.yml`
- `spring.jpa.hibernate.ddl-auto=validate`
- Flyway location: `classpath:db/migration`
- Removed H2 datasource config
- PostgreSQL datasource via env vars

### STEP 4 — Testcontainers Configuration
- `TestcontainersConfiguration.java` with `@ServiceConnection`
- Uses `postgis/postgis:16-3.4` image (PostgreSQL 16 + PostGIS 3.4)
- Tests opt-in via `@Import(TestcontainersConfiguration.class)`

### STEP 5 — Shared Kernel Package (`com.nagorikseba.shared`)
Moved and updated package references:
```
shared/
├── config/
│   ├── JwtProperties.java
│   ├── StorageProperties.java
│   └── WebConfig.java
├── exception/
│   ├── ApiError.java
│   ├── ConflictException.java
│   ├── FileStorageException.java
│   ├── GlobalExceptionHandler.java
│   └── ResourceNotFoundException.java
└── service/
    └── FileStorageService.java
```
Updated imports in 8 affected files (ComplaintService, ComplaintController, JwtTokenProvider, AuthService, StandardComplaintSubmission, AnonymousComplaintSubmission, AuthorityController, AbstractComplaintState).

### STEP 6 — Municipality Module (`com.nagorikseba.municipality`)
```
municipality/
├── entity/
│   ├── Municipality.java
│   ├── Ward.java          (PostGIS Geometry boundary, generated centroid)
│   └── Department.java    (code, name, Point office_location, String[] handles_categories)
├── repository/
│   ├── MunicipalityRepository.java
│   ├── WardRepository.java      (findByPointWithinBoundary with ST_Contains)
│   └── DepartmentRepository.java (findByMunicipalityIdAndHandlesCategory via native query)
├── service/
│   └── MunicipalityService.java
├── dto/
│   ├── MunicipalityResponse.java
│   ├── WardResponse.java
│   └── DepartmentResponse.java
└── controller/
    └── MunicipalityController.java  (REST endpoints at /api/municipalities)
```

### STEP 7 — Compilation Fixes
- Removed duplicate old `WardRepository` and `DepartmentRepository` from `com.nagorikseba.repository`
- Updated `User` entity to reference new `municipality.entity.Ward` and `Department`
- Fixed `DataSeeder` to use new municipality entities with PostGIS polygons
- Fixed `PublicController` Map type inference
- Fixed JPQL array queries (used native query for `handles_categories TEXT[]`)
- Added `@CreationTimestamp`/`@UpdateTimestamp` to municipality entities
- Fixed type mismatches (BigDecimal → double for JTS Coordinate)

---

## Verification

```bash
./mvnw clean verify
# BUILD SUCCESS
# Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
```

All 6 integration tests pass:
- `NagorikSebaApplicationTests.contextLoads`
- `AuthControllerIntegrationTests` (3 tests)
- `ComplaintSubmissionIntegrationTests` (2 tests)

---

## Database Schema (PostgreSQL + PostGIS)

| Table | Key Features |
|-------|--------------|
| municipalities | slug UNIQUE, is_active, timestamps |
| wards | boundary (MultiPolygon,4326), centroid (generated), GiST index, UNIQUE(municipality_id, ward_number) |
| departments | code, handles_categories TEXT[], office_location (Point), timestamps |
| users | ward_id, department_id FKs (to new tables) |
| complaints | ward_id FK, lat/long, status, priority |
| sla_rules | category, priority, max_hours |
| attachments, notifications, status_updates, ward_performance | unchanged |

---

## Next Steps (Phase 2+)

1. **Identity Hardening (V2 migration)** — Add password_hash, phone_verified, email_verified, last_login_at to users; add refresh_token table
2. **Complaint Module Redesign (Phase 3)** — SLA deadlines, assignment workflow, reopen logic
3. **SLA Engine & Outbox (Phase 5)** — Async escalation, outbox pattern for notifications
4. **Geo Query Optimization** — Add R-tree indexes, bounding box filters

---

## Key Files to Review

- `src/main/resources/db/migration/V1__platform_baseline.sql` — Canonical schema
- `src/main/java/com/nagorikseba/municipality/` — New domain module
- `src/main/java/com/nagorikseba/shared/` — Shared kernel
- `src/test/java/com/nagorikseba/TestcontainersConfiguration.java` — Test infra
- `src/main/resources/application.yml` — Config (ddl-auto=validate)