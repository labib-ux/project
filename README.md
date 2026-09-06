# Nagorik Seba

Nagorik Seba is a ward-level civic complaint platform for Bangladesh. Residents report local infrastructure problems with photos, track every status change, and rate the resolution. Authorities verify, assign, resolve and monitor complaints transparently.

Architecture: modular monolith — Spring Boot 3.5 / Java 21 / PostgreSQL 16 + PostGIS / Flyway / Thymeleaf. Design intent lives in `docs/ENTERPRISE_BLUEPRINT.md`; per-phase build records live in `docs/implementation/PHASE-N-HANDOFF.md`.

## Quickstart

Java 21 is required. Maven ships via the wrapper; Docker provides PostgreSQL + PostGIS.

```bash
docker run -d --name nagorik-postgis -e POSTGRES_USER=nagorik \
  -e POSTGRES_PASSWORD=nagorik -e POSTGRES_DB=nagorik_seba \
  -p 5432:5432 postgis/postgis:16-3.4
DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw spring-boot:run
```

Open `http://localhost:8080`. Flyway migrates on boot; the seeder loads demo municipalities, wards, officers and complaints on an empty database. Verify with:

```bash
DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw clean verify
```

## Demo credentials

All demo passwords are `demo1234`, except the two legacy authority accounts noted below.

| Role | Email | Password |
|---|---|---|
| Citizen | `citizen1@demo` | `demo1234` |
| Citizen | `citizen2@demo`, `citizen3@demo` | `demo1234` |
| Dept officers | `officer1@demo` … `officer4@demo` (ROADS/WATER_SUPPLY/ELECTRICITY/SANITATION, Dhaka North) | `demo1234` |
| Ward councilor | `councilor17@example.com` (Ward 17, Dhaka North) | `councilor123` (legacy) |
| Roads officer | `roads.north@example.com` | `officer123` (legacy) |
| Admin | `admin@example.com` | `admin123` (legacy) |

## API summary

All API errors are RFC-7807 `application/problem+json`. Send JWTs as `Authorization: Bearer <accessToken>`.

| Group | Example |
|---|---|
| Auth — `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/refresh`, `POST /api/auth/logout` | `curl -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' -d '{"identifier":"citizen1@demo","password":"demo1234"}'` |
| Citizen complaints — `POST /api/complaints` (multipart + photos), `GET /api/complaints/my`, `GET /api/complaints/{ref}`, `POST …/{ref}/cancel?reason=`, `POST …/{ref}/rate?rating=&feedback=`, `POST …/{ref}/reopen?reason=` | `curl -X POST localhost:8080/api/complaints -H "Authorization: Bearer $CITIZEN" -F title=Pothole -F description=… -F category=ROADS -F latitude=23.79 -F longitude=90.41 -F photos=@pothole.jpg` |
| Authority — `GET /api/authority/dashboard`, `GET /api/authority/queue`, `POST …/complaints/{ref}/verify`, `…/reject`, `…/assign`, `…/assign/auto`, `…/start`, `…/resolve` | `curl -X POST localhost:8080/api/authority/complaints/$REF/assign/auto -H "Authorization: Bearer $OFFICER"` |
| Municipalities — `GET /api/municipalities`, `/{slug}`, `/{slug}/wards`, `/{slug}/departments`, `/api/municipalities/{slug}/wards/containing?lat=&lng=` | `curl 'localhost:8080/api/municipalities/dhaka-north/wards'` |
| Public — `GET /api/public/heatmap?municipality=&minLng=&minLat=&maxLng=&maxLat=`, `GET /api/public/wards/scoreboard?municipality=&period=` (60/min/IP, snapped coords, no PII) | `curl 'localhost:8080/api/public/heatmap?municipality=dhaka-north&minLng=90.36&minLat=23.72&maxLng=90.44&maxLat=23.88'` |
| Admin (ADMIN only) — `/api/admin/municipalities`, `/api/admin/wards/geojson`, `/api/admin/departments`, `/api/admin/users`, `/api/admin/sla-policies` + pages at `/admin/municipalities`, `/admin/users`, `/admin/sla-policies` | `curl localhost:8080/api/admin/users -H "Authorization: Bearer $ADMIN"` |

Full lifecycle: submit → verify → assign/auto → start → resolve (proof photo) → rate (CLOSED) or reopen (REOPENED, priority HIGH, half-hours SLA). See `docs/implementation/PHASE-5-HANDOFF.md` §(h) for the complete curl chain.

## Deployment (Render / Railway + Neon)

| Env var | Purpose | Example |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | Must be `prod` | `prod` |
| `SPRING_DATASOURCE_URL` | Neon/Postgres JDBC URL (with `?sslmode=require` on Neon) | `jdbc:postgresql://host:5432/db?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | DB credentials | — |
| `JWT_SECRET` | **Required.** Base64 access-token key (≥32 bytes); boot fails without it | `openssl rand -base64 48` |
| `APP_CORS_ALLOWED_ORIGINS` | **Required.** Browser origins, comma-separated | `https://nagorik.example.com` |
| `APP_STORAGE_PATH` | Upload dir (persistent disk) | `/var/lib/nagorik-seba/uploads` |
| `APP_SLA_DEFAULT_HOURS` | SLA fallback hours | `120` |
| `TWILIO_SID` / `TWILIO_TOKEN` / `TWILIO_FROM`, `SMTP_*`, `SMS_ENABLED` | Notification providers (log-only until wired) | — |
| `CORS_ALLOWED_ORIGINS` is read as `APP_CORS_ALLOWED_ORIGINS` in prod; dev default is `http://localhost:8080` | — | — |

Checklist: set env vars → deploy (`./mvnw -Pprod package` or the Dockerfile build) → Flyway migrates automatically on boot (`ddl-auto=validate`, `clean-disabled`) → health check path `/actuator/health` (plus `outboxLag` DOWN if the oldest pending outbox row exceeds 10 min) → log in as each demo role and run the demo script in `docs/implementation/PHASE-6-HANDOFF.md` §(c). See that handoff for the full checklist, known limitations and future work.
