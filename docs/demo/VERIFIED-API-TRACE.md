# Verified API trace — Nagorik Seba demo

Every command below was executed against a running instance on
`http://localhost:8080` and the status codes are the observed ones. Use this as
a fallback if the UI misbehaves on camera, and as evidence that the lifecycle is
enforced server-side rather than only in the browser.

Reference complaint: `NS-2026-000061`, filed as `citizen1@demo` with category
`ELECTRICITY`, auto-assigned to `officer3@demo`.

## 1. Authentication

```bash
curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"identifier":"citizen1@demo","password":"demo1234"}'
# -> accessToken (JWT), refreshToken, user{role: CITIZEN}
```

All of these return `200`:

| Identifier | Password | Role |
|---|---|---|
| `citizen1@demo` … `citizen3@demo` | `demo1234` | CITIZEN |
| `officer1@demo` … `officer4@demo` | `demo1234` | DEPT_OFFICER |
| `councilor17@demo` | `demo1234` | WARD_COUNCILOR |
| `admin@demo` | `demo1234` | ADMIN |

Legacy accounts (documented in the README) also work: `admin@example.com` /
`admin123`, `councilor17@example.com` / `councilor123`.

## 2. Submit

```bash
curl -s -X POST localhost:8080/api/complaints \
  -H "Authorization: Bearer $CITIZEN" \
  -H 'Idempotency-Key: demo-video-001' \
  -F title='Demo: broken streetlight' -F description='Video demo complaint' \
  -F category=ELECTRICITY -F latitude=23.7925 -F longitude=90.4035 \
  -F photos=@/tmp/pothole.png
# -> 201, referenceCode NS-2026-000061
```

`photos` is `@NotEmpty` and capped at 5; latitude must be 20–27 and longitude
88–93 (Bangladesh bounds).

## 3. Officer transitions

All against `officer3@demo` (the ELECTRICITY officer):

| Step | Call | Result |
|---|---|---|
| Verify | `POST /api/authority/complaints/$REF/verify` | `200` → VERIFIED |
| Auto-assign | `POST /api/authority/complaints/$REF/assign/auto` | `200` → ASSIGNED |
| Start | `POST /api/authority/complaints/$REF/start` | `200` → IN_PROGRESS |
| Resolve (JSON) | `POST …/resolve` with `-d '{"note":"fixed"}'` | **`415`** — multipart only |
| Resolve (multipart) | `POST …/resolve -F note=… -F photos=@/tmp/pothole.png` | `200` → RESOLVED |

`POST …/start` as `officer1@demo` (ROADS) returns **`403`** — only the assigned
officer may act. This guard is intentional.

## 4. Citizen closes the loop

| Step | Call | Result |
|---|---|---|
| Rate | `POST /api/complaints/$REF/rate?rating=5&feedback=Great+work` | `200` → CLOSED |
| Reopen after rating | `POST /api/complaints/$REF/reopen?reason=Still+dark` | **`422`** — CLOSED is terminal |

To demonstrate reopen, either reopen **before** rating, or file a second
complaint and drive that one to RESOLVED first.

## 5. Public transparency

```bash
curl -s 'localhost:8080/api/public/heatmap?municipality=dhaka-north&minLng=90.36&minLat=23.72&maxLng=90.44&maxLat=23.88'
# -> {"clustered":false,"points":[…35 points…]}
#    each point: {referenceCode, category, status, lng, lat} — no name, no phone,
#    coordinates snapped to 0.001° (~100 m)

curl -s 'localhost:8080/api/public/wards/scoreboard?municipality=dhaka-north'
# -> per-ward totalComplaints / resolvedComplaints / resolutionRate /
#    averageResolutionHours / slaBreachCount
```

Both are anonymous (`permitAll`) and capped at 60 requests/minute/IP.

## 6. Pages

| Path | Anonymous | Notes |
|---|---|---|
| `/` `/wards` `/login` `/login/authority` `/citizen/dashboard` | `200` | Shells; data comes from the API via JWT |
| `/admin` | **`403`** | `hasRole("ADMIN")`; `200` once the `nagorikSebaToken` cookie is present |
| `/actuator/health` | `200` | Also DOWN if the oldest pending outbox row exceeds 10 min |

## 7. Notifications

`app.scheduling.enabled` defaults to **true** in the default profile, so the
outbox relay polls every 5 s. After each transition the terminal logs:

```
DEV SMS outbox_id=78 payload={...}
```

That line is the transactional outbox doing its job: the transition commits
first, then a separate worker delivers, with retries and a dead-letter state.