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

Additional one-click demo logins (all `demo1234`), matching the quick-fill buttons on `/login`:
`councilor17@demo` (WARD_COUNCILOR) and `admin@demo` (ADMIN).

### Which officer handles which category

`assign/auto` routes by category, and only the **assigned** officer may start or
resolve. Sign in as the officer whose department matches the category you pick in
the complaint form:

| Category in the form | Officer to sign in as |
|---|---|
| ROADS | `officer1@demo` |
| WATER_SUPPLY | `officer2@demo` |
| ELECTRICITY | `officer3@demo` |
| SANITATION | `officer4@demo` |

Signing in as the wrong officer and pressing *Start Field Work* correctly returns
403 — that guard is the point, but it is not what you want on camera.

The legacy `roads.north@example.com` / `roads.south@example.com` accounts still
exist so those old credentials resolve, but they are seeded **inactive** and are
therefore skipped by auto-assignment. Without that, their lower user ids won the
"least-loaded officer, lowest id" tie-break and every auto-assigned ROADS
complaint landed on an account you never sign in with.

## Running the app locally

```bash
docker start nagorik-postgis          # or the docker run line in Quickstart
./mvnw spring-boot:run
```

Then open `http://localhost:8080`.

Background workers are **on** in the default profile: the SLA scanner, AUTO_CLOSE
job, retention purge and notification outbox relay all run, so a status change
produces a logged `DEV SMS outbox_id=…` line in the terminal within a few
seconds. Override with `SCHEDULING_ENABLED=false` to silence them. The public
scoreboard is backfilled with six trailing months of ward snapshots on first
boot, so the landing hero numbers and ward table are populated immediately.

**The app depends on three CDNs** (Tailwind, Leaflet, Google Fonts) plus
OpenStreetMap tiles for the map. Confirm they load before recording:

```bash
curl -o /dev/null -w '%{http_code}\n' https://cdn.tailwindcss.com
curl -o /dev/null -w '%{http_code}\n' https://unpkg.com/leaflet@1.9.4/dist/leaflet.js
```

Without them the pages still load but render unstyled and the map is blank.

## Demo runbook

Everything below was executed against the running app, so the status codes are
the real ones, not aspirations.

### 0. Before you record (5 minutes)

```bash
docker start nagorik-postgis
./mvnw spring-boot:run          # wait for "Started NagorikSebaApplication"
```

Smoke-test the three roles you will show:

```bash
for u in citizen1@demo officer1@demo admin@demo; do
  printf '%s -> ' "$u"
  curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"identifier\":\"$u\",\"password\":\"demo1234\"}"
done
```

All three must print `200`. If they do not, the database was never seeded —
stop the app, `docker exec -it nagorik-postgis psql -U nagorik -d nagorik_seba
-c 'drop schema public cascade; create schema public;'`, restart, and wait for
`DataSeeder` to log "Seeding initial data...".

Keep the terminal visible in a second window. Every status change writes a
`DEV SMS outbox_id=…` line there within ~5 seconds, and that is your proof the
transactional-outbox pattern is real rather than decorative.

### 1. Landing page — the problem statement

Open `http://localhost:8080`. The hero counters and ward table are populated
from `ward_monthly_performance` (six trailing months backfilled on first boot).
Scroll to the live map: 35 points, coordinates snapped to a 100 m grid. Say
explicitly: *"coordinates are snapped server-side, so this map cannot be used to
find someone's door."*

### 2. Citizen files a complaint

1. `/login` → click the **Citizen 1** quick-fill button → Sign In. You are
   redirected to the complaint form.
2. Title, description, category **ROADS**, click the map to drop a pin inside
   Dhaka, attach one photo, Submit.
3. You land on the dossier with a reference like `NS-2026-000062`.

### 3. Officer works the queue

1. Open a **second browser profile** (or a private window) → `/login/authority`
   → sign in as `officer1@demo`. *This must be officer1 because you filed the
   complaint as ROADS.*
2. Authority Hub → Queue → open your new reference.
3. Press **Verify** → **Auto-Assign** → **Start Field Work**.
4. Press **Complete & Upload Proof**, attach a photo, submit. Status → RESOLVED.

### 4. Citizen rates it

Back in the first window, reload the dossier. The star panel appears, enter 5
and a comment, press **Ratify Resolution**. Status → CLOSED and the rating block
now shows ★★★★★ 5/5 with your comment.

To also show **reopen**, do it *before* rating on a second complaint: after the
officer resolves it, press **Reopen Docket** with a reason. Status goes back to
REOPENED, `reopen_count` becomes 1, priority is escalated to HIGH and a fresh SLA
clock starts. Reopening *after* rating returns 422 — CLOSED is terminal, which is
the correct behaviour but a bad thing to hit live.

### 5. Transparency

`/wards` → the ward scoreboard shows resolution rate and average resolution
time per ward. This is the "does the authority actually perform" view.

The board defaults to the **last completed month**, not the current one. A month
that is still accumulating would show near-zero totals and a 0% rate, which is
misleading; pass `?period=yyyy-MM` to pin an explicit month.

### 6. Admin

Sign in as `admin@demo` in a third window → `/admin`. Navigate to Users, then
SLA Policies. Note that `/admin` is `hasRole("ADMIN")` at the filter chain and
reads the JWT from the `nagorikSebaToken` cookie that `auth.js` writes on login.

### Verified request/response trace

Kept in `docs/demo/VERIFIED-API-TRACE.md` so you can reproduce any step from the
terminal if the UI misbehaves on camera.

### Gotchas that will bite you if you forget them

| Symptom | Cause | Do this |
|---|---|---|
| Officer sees no Start/Resolve buttons | You signed in as an officer whose department does not match the complaint category | Use the category→officer table in the README |
| *Complete & Upload Proof* fails | `resolve` is multipart-only; it rejects a JSON body with **415** | Always attach a photo |
| *Reopen Docket* returns **422** | You already rated, so the complaint is CLOSED and CLOSED is terminal | Reopen **before** rating, or file a second complaint |
| Landing hero stats show `0` | Performance table was empty when the app first booted | Restart once; the trailing-window seed fills it |
| Ward table says "No ward data for this period yet" | Same cause | Restart once |
| Ward rates all show 0% | You are looking at the current, still-accumulating month | The default view is the last completed month; check the seeded snapshot months |
| Map is blank / page unstyled | CDN or tile server unreachable | Run the two `curl` checks in step 0 |
| Everything 403s after signing in | Access token is 15 minutes old | Sign in again |

### Resetting to a clean demo state

If you want to re-record from scratch:

```bash
# stop the app first (Ctrl-C), then:
docker exec -it nagorik-postgis psql -U nagorik -d nagorik_seba \
  -c 'drop schema public cascade; create schema public;'
rm -rf uploads/complaints
./mvnw spring-boot:run
```

Flyway recreates the schema, `DataSeeder` reloads demo data and
`SnapshotSeedRunner` backfills the six trailing months of ward snapshots.

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
