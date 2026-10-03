# Nagorik Seba — Live Demo Runbook & Script

**Target length:** 7–8 minutes · 2:00–2:30 opening explanation · 5:00–5:30 live walkthrough
**Audience assumption:** technical reviewers (interview panel / faculty). Adjust depth if non-technical.

---

## PART 0 — START THE STACK (do this 10 minutes BEFORE you record)

```bash
# 1. Database
docker start nagorik-postgis
sleep 8

# 2. App
cd /Users/nafizimtiazlabib/project/project
DOCKER_HOST=unix:///Users/nafizimtiazlabib/.docker/run/docker.sock ./mvnw spring-boot:run
```

**Wait for:** `Started NagorikSebaApplication` → then open `http://localhost:8080`

### Pre-flight checklist (tick before recording)
- [ ] `http://localhost:8080` loads, hero numbers visible
- [ ] `/wards` shows the heatmap with coloured dots
- [ ] Login as `citizen1@demo` / `demo1234` works
- [ ] Login as `officer1@demo` / `demo1234` works
- [ ] Login as `admin@demo` / `demo1234` works
- [ ] Browser zoom at 100%, window ~1440px wide (not fullscreen — avoid the dock covering the UI)
- [ ] Terminal visible in a second window (optional, for the audit-trail moment)
- [ ] Notifications tab closed. Do NOT have the deck open — this is the live demo, not slides.

### Demo credentials
| Role | Email | Password |
|---|---|---|
| Citizen | `citizen1@demo` | `demo1234` |
| Officer (ROADS) | `officer1@demo` | `demo1234` |
| Officer (WATER_SUPPLY) | `officer2@demo` | `demo1234` |
| Admin | `admin@demo` | `demo1234` |

⚠️ **Use `officer1@demo` for the officer flow.** Officers 1–4 are posted to ROADS, WATER_SUPPLY,
ELECTRICITY, SANITATION respectively. **No officer is posted to WATERLOGGING** — if you submit a
waterlogging complaint and hit "Auto-assign", it lands on the department with *no officer*, and
Start Work then 403s. That is correct behaviour but it will derail the demo. **Submit a ROADS
complaint and auto-assign will find `officer1@demo`.**

### Curated reference codes (stable seed data)
| Code | Status | Use for |
|---|---|---|
| `NS-2026-000007` | SUBMITTED · ROADS | "what's in the queue" |
| `NS-2026-000011` | ASSIGNED · ROADS | "an officer is on it" |
| `NS-2026-000053` | IN_PROGRESS · ROADS | "work underway" |
| `NS-2026-000062` | REOPENED · ROADS | "citizen sent it back" |
| `NS-2026-000001` | CLOSED · ROADS | "resolved and rated" |

---

## PART 1 — THE MAP (where to click, in order)

```
[0:00] LANDING  http://localhost:8080
       → hero, problem framing, live ward numbers
              ↓
[0:45] PUBLIC TRANSPARENCY  /wards          ★ NO LOGIN
       → heatmap dots, ward scoreboard table
              ↓
[1:30] "TRACKING IS TIED TO AN ACCOUNT"  → /login
       → login citizen1@demo
              ↓
[2:15] CITIZEN DASHBOARD  /citizen/dashboard
       → their 23 complaints, status chips, timeline

## PART 2 — THE SCRIPT

> Read the **quoted** lines aloud. Lines starting `→` are actions/notes to yourself — never spoken.
> Timings are cumulative. If you drift, cut the blocks marked ✂ OPTIONAL.

---

### ▶ [0:00–0:45] STOP 1 — LANDING PAGE
*Go to: `http://localhost:8080`*

> "This is **Nagorik Seba** — a civic grievance redress system for Dhaka's city corporations.
>
> The problem it solves is this: when a citizen reports a broken streetlight or a pothole, today
> that report goes into a **paper register**. There's no tracking number. If the officer who was
> supposed to visit the ward loses the file, the citizen has no way to know and no way to prove
> it. The ward's performance is invisible to the public, and to the corporation itself.
>
> Nagorik Seba replaces that register with a system where **every complaint gets a reference
> code**, **every state change is validated and audited on the server**, and **the public can see
> how each ward is performing without logging in at all**."
>
> → *(point at the hero numbers)* "Those aren't hardcoded — that's live data from the database."

---

### ▶ [0:45–1:30] STOP 2 — PUBLIC TRANSPARENCY `/wards` ★ no login
*Go to: `/wards` (or click the nav link)*

> "This is the transparency layer, and **notice I did not log in to see it** — this page is
> completely public.
>
> → *(move the mouse over the map)* "Every dot is a real complaint, matched to a real ward polygon
> using a point-in-polygon test in PostGIS. The colours are the status.
>
> → *(scroll to the scoreboard)* "And here's the part that creates actual pressure: a monthly
> ranking of wards by **resolution rate and average turnaround**. If one ward is at 0% and another
> is at 28%, that's a fact anybody can see — the corporation's own performance, published.
>
> One deliberate design decision: **the heatmap snaps coordinates to a roughly 100-metre grid**.
> If it plotted exact complaint locations, you could reverse-engineer someone's house from a
> public page. We didn't want to build a public accountability tool that also became a doxxing
> tool, so the public layer sacrifices precision for privacy."

---

### ▶ [1:30–2:15] STOP 3 — WHY LOGIN (the honest limitation)
*Go to: `/login`*

> "Now the honest part, because an earlier draft of this deck **overstated** what this does.
>
> I originally wrote that you get a reference code and can follow up **without logging in**.
> That is not true, and I corrected it rather than demonstrating it.
>
> → *(click the complaint-detail link, or just say it)* "Tracking is tied to the **signed-in
> citizen account**. The API returns **401** if you try to read a complaint with no token, and
> **404** if you're logged in as a *different* citizen — not 403, so the system doesn't even
> confirm the complaint exists to someone with no business knowing.
>
> Reference codes are visible to you at submission, but they are **not** a bearer token. That was
> the right call for privacy, and it's a real cost in convenience — and I'd rather show you the
> limitation than fake it."

*→ Log in: `citizen1@demo` / `demo1234`*

---

### ▶ [2:15–3:00] STOP 4 — CITIZEN DASHBOARD
*Go to: `/citizen/dashboard`*

> "I'm logged in as a demo citizen. This is their dossier — every complaint they've filed, with
> live status.
>
> → *(point at a status chip)* "Every one of these is a real state in a guarded state machine.
> There's a status update feed, so the citizen isn't left guessing what happened to their report.
>
> You can see a spread of statuses here — submitted, in progress, closed — which is what makes
> the next part demoable."

---

### ▶ [3:00–3:45] STOP 5 — FILE A COMPLAINT ★ the handoff moment
*Go to: `/citizen/complaints/new`*


### ▶ [3:45–5:00] STOP 6 — OFFICER QUEUE & WORKFLOW
*Log out. Go to `/login/authority`. Login `officer1@demo` / `demo1234`. Go to `/authority/queue`*

> **Before you touch the queue — show the bell.** Look at the top-right of the nav. There is a
> notification badge with a count on it. Say: *"Before I open the queue — that badge is the
> complaint I just filed. I didn't refresh. It got here on its own."*
>
> Click the bell. The complaint is at the top, named by its reference code. Click it and it
> opens the complaint and clears the badge.

> "Now the other side. I'm an officer posted to the **ROADS** department in Dhaka North.
>
> → *(Load Queue)*
>
> This is the department work queue, and **it is scoped to me** — I see ROADS complaints, not
> sanitation or electricity. That's enforced in the query, not hidden in the UI.
>
> → *(open the complaint we just filed)* **VERIFY.** I've confirmed it's real. Now
> → **AUTO-ASSIGN** — the routing resolver picked the department by category and recorded *why*
> it chose, on the assignment row. → **START WORK.**
>
> "Every one of those three actions went through **one guarded service**. There isn't a button
> handler that sets a status field — there's a single lifecycle service that checks the
> transition is legal, writes an **audit row**, and recomputes the **SLA clock**."

---

### ▶ [5:00–5:30] STOP 7 — THE GUARD ★ highest-value 15 seconds
*Option A (simplest): stay in the officer1 session and just narrate the guard.*
*Option B (the real proof): sign in as `officer2@demo` in a private window, open a ROADS
complaint, press Start Work.*

> "Here's the check I care most about. I'm signed in as the **water supply** officer. I'm opening
> a **ROADS** complaint — one that's assigned to a roads officer — and I'm pressing Start Work.
>
> → *(press it — the request returns 403)*
>
> **403.** The server refused it. And notice the order of the checks: on a complaint that's
> already in progress I get a **422 invalid-state-transition** instead — so the state machine and
> the permission model are genuinely separate layers, both enforced server-side.
>
> This is the distinction that matters: **a hidden button is a UX feature; a 403 is a security
> control.** Any of these actions called directly with curl — no browser, no UI — get the same
> answer. The UI is not the enforcement."

*→ If you did Option B, sign back in as `officer1@demo` to continue.*

---

### ▶ [5:30–6:00] STOP 8 — RESOLVE
*Still officer1, on the complaint*

> "I can't just mark it done. **Resolve requires a work-proof photo** — it's multipart, and the
> handler validates at least one attachment inside the same transaction. → *(upload)* → **RESOLVE**.
>
> → *(if you have 20 seconds)* In the terminal you'd see a `DEV SMS outbox_id=…` line.
> Notifications aren't sent inline — the row is written to a **transactional outbox** in the same
> transaction as the status change, and a relay delivers after commit. An SMS gateway failing can
> therefore never roll back a citizen's complaint."

---

### ▶ [6:00–6:30] STOP 9 — CITIZEN RATES
*Back to `citizen1@demo`. Open the complaint.*

> "Back as the citizen. The complaint is **RESOLVED**, waiting on me. → **RATE 4** → **CLOSED**.
>
> → *(if you have 15 seconds)* And if the work had been poor, **Reopen** sends it back — that
> escalates priority to HIGH, recalculates the SLA at half the remaining hours, and the attempt
> count is capped at five, after which it's refused with a 422. The citizen genuinely has teeth."

---

### ▶ [6:30–7:00] STOP 10 — ADMIN / SLA MATRIX
*Log out, login `admin@demo` / `demo1234`. Go to `/admin`, then `/admin/sla-policies`*


> **Show the bell FIRST here too.** The admin badge also carries a count, because admins are
> cross-tenant — every new report in every ward reaches them, not just their own desk. Say:
> *"The admin does not pick a complaint; they are notified of all of them."*
>
> "Finally, the admin console. → **Statutory SLA policies** — 64 rows: every category crossed
> with every priority level, each with a resolution deadline and **two escalation tiers**. ROADS at
> low priority is 72 hours; critical escalates far sooner.

---

## NOTIFICATION FLOW — the answer if they ask "how does the officer actually find out?"

One breath: *the complaint and the notification are written by the same transaction, so one
cannot exist without the other.*

```
citizen files  ->  ComplaintLifecycleService.recordSubmission()
                    |- writes the complaint          (same transaction)
                    |- writes the SUBMIT audit row   (same transaction)
                    `- writes an OUTBOX row          (same transaction)
                                        |
                    OutboxRelayScheduler polls every 5s
                                        |
                    OutboxWorker claims the row (SKIP LOCKED)
                                        |
                    NotificationDispatcher
                      |- citizen     -> "your complaint was received"
                      `- SUBMIT only -> every officer posted to that municipality
                                        + every admin (cross-tenant)
                                        |
                    notifications table -> bell badge in the nav
```

**"Why an outbox table instead of just sending an email?"** A notification sent in the middle
of the complaint transaction can half-succeed: the SMS goes out, the transaction then rolls
back, and the citizen holds an SMS for a complaint that does not exist. Writing the row inside
the same transaction means it is either committed or it never happened. The relay then retries
on its own — 5 attempts with exponential backoff, then parked `FAILED`.

**"What if the app crashes mid-send?"** The row is already `PENDING` in the database, so the
next relay picks it up. Delivery is idempotent: `uq_notification_outbox_user` makes a
redelivered row collide on the unique constraint instead of duplicating, and the collision is
treated as success.

**"Who exactly gets told?"** Officers are resolved through `user_municipality_memberships`,
**not** the user's `department_id`, because §3.2 models transfers as membership *history* — a
stale FK would page the wrong desk after an officer transfers. Admins are cross-tenant.

**"Can I read someone else's notifications?"** No. Every query is scoped to the caller's own id
from the JWT; there is no user id in the request to tamper with. Asking for another user's
notification returns **404, not 403** — a 403 would confirm the id is real.

**"Won't officers be spammed on every status change?"** Correct, and that is why the fan-out is
gated on `action == SUBMIT`. Officers get one alert per new report; later transitions reach the
assigned officer only, and the citizen.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/notifications?limit=20&unreadOnly=false` | the caller's feed, newest first |
| `GET` | `/api/notifications/unread-count` | badge count (one indexed COUNT) |
| `POST` | `/api/notifications/{id}/read` | mark one read (404 if not yours) |
| `POST` | `/api/notifications/read-all` | clear the badge |

**Tests protecting this:** `NotificationFanoutIntegrationTests` — 8 tests: officer + admin both
alerted, citizen still notified in their own words, later transitions not re-paging everyone,
tenancy isolation, ownership (404), auth (401).


## PART 3 — REHEARSAL NOTES & TROUBLESHOOTING

**Things I verified work, so you don't waste time worrying about them:**

| Check | Result |
|---|---|
| All 7 demo logins | ✅ 200 |
| Officer1 (ROADS) queue | ✅ 4 complaints, ROADS only |
| Officer2 (WATER_SUPPLY) queue | ✅ 2 complaints, WATER_SUPPLY only — isolation proven |
| Officer2 Start Work on a ROADS complaint | ✅ **403 Forbidden** |
| Anonymous read of a complaint | ✅ **401** |
| Citizen2 reading citizen1's complaint | ✅ **404** (existence not disclosed) |
| Auto-assign of a ROADS complaint | ✅ finds officer1@demo |
| Full lifecycle SUBMIT→…→CLOSED | ✅ all 200, full audit trail written |
| Idempotency-Key replay | ✅ returns the same code, no duplicate |
| Ward auto-detect from map pin | ✅ Gulshan |
| `/wards`, `/`, `/login`, `/authority/queue` | ✅ 200, no error pages |
| Admin pages (with admin token) | ✅ 200 with data tables |
| Non-admin hitting `/admin/*` | ✅ 403 |
| Backend test suite | ✅ 96 pass, 0 fail, 0 error |

**Things that will bite you:**

1. **`WATERLOGGING` has no posted officer.** Auto-assign → department only → Start Work = 403.
   Use **ROADS** for the live flow. (Verified — this is real, not a guess.)
2. **The login form has no server-side POST.** It's JS → `/api/auth/login` → token in a cookie →
   `WebJwtAuthenticationFilter`. If you test with curl the field is `identifier`, **not** `email`,
   and a 403 on a page just means "not logged in", not "broken".
3. **A 422 before a 403** on Start Work means the complaint is already IN_PROGRESS — check the
   state before concluding the permission model is broken.
4. **Photos are required** to submit, and required again to resolve. Have 2 images ready —
   any real photo of a pothole works.
5. **Map tiles need internet.** The heatmap background is OpenStreetMap tiles. If your connection
   drops mid-demo the map goes grey — the data layer still works, so keep talking.
6. **Rate limits are OFF in the default profile** (`RATE_LIMIT_ENABLED=false`). You cannot lock
   yourself out of the demo with repeated logins. Good.
7. **Lockout after 5 failed attempts for 15 minutes.** If you fat-finger `demo1234` five times
   you're locked out. Slow down on that field.

**Reset between takes:** you do *not* need to reseed — the lifecycle is additive, just file a new
complaint each take. Seeding only runs when the ward table is empty, so restarting the app will
**not** wipe your demo data (and will not duplicate it either).

---

## PART 4 — YOUR QUESTION: DO IT YOURSELF, DON'T CLONE YOUR VOICE

**Short answer: present it yourself, live, no voice cloning.**

Reasons, in order of how much they matter:

1. **The demo *is* the evidence.** The strongest thing you can show a technical reviewer is that
   you understand the system well enough to navigate it, react to it, and answer what happens next.
   A cloned voice narrating a screen recording proves neither. The 403 moment only lands if you
   *react* to it — "that's the server refusing, not the UI hiding a button" is a line that works
   because you know it's true.

2. **It's a credibility risk, not just a taste question.** Voice-cloned narration is now
   detectable, and in an assessed context it's at best distracting and at worst looks like
   misrepresenting your own work. It also removes your ability to adapt — if the demo breaks, a
   pre-recorded voiceover keeps narrating things that are no longer on screen.

3. **Live demos are expected to be live.** Panels know demos fail. The recovery is part of the
   assessment. "The 403 is real, here's the curl that proves it" beats any recording.

**The genuinely useful version of the agent idea:** have me *drive* the browser as a rehearsal
partner — click through all 10 stops, catch whatever breaks, fix it — then you record yourself
narrating the same path. That's the "rehearsal" you already asked for, and it costs you nothing
in credibility.

**If you still want a recording as a backup**, record it yourself but narrate live over the
screen capture. Natural voice, no synthesis, and it's edit-able.

---

## PART 5 — TIMING SUMMARY

| Time | Stop | Cumulative |
|---|---|---|
| 0:00 | Landing — problem framing | 0:45 |
| 0:45 | `/wards` — public transparency | 1:30 |
| 1:30 | `/login` — the honest limitation | 2:15 |
| 2:15 | Citizen dashboard | 3:00 |
| 3:00 | **File a complaint → get a reference code** | 3:45 |
| 3:45 | Officer queue → verify → assign → start | 5:00 |
| 5:00 | **The 403 guard** ★ | 5:30 |
| 5:30 | Resolve with proof photo | 6:00 |
| 6:00 | Citizen rates → CLOSED | 6:30 |
| 6:30 | Admin SLA matrix ✂ optional | 7:00 |
| 7:00 | Close on `/wards` | 7:30 |

If you're over time: cut **Stop 10 (admin)** → 6:30. Then cut **Stop 4 (dashboard)** → 6:00.
Never cut **Stops 5, 6, or 7** — they carry the reference code, the lifecycle, and the guard,
which are the three things the rest of the deck claims.

>
> This is the configuration that makes the SLA promises real. Change a deadline here and it
> propagates to the SLA engine that recomputes due dates on every state change. There's also
> **multi-tenancy** — Dhaka North and Dhaka South are separate municipalities, and the membership
> table scopes which officers and councilors serve which ward in which city. The same binary
> serves both corporations with a hard data boundary between them."

*✂ OPTIONAL — cut this stop first if you're running over 7:30.*

---

### ▶ [7:00–7:30] CLOSE
*Go back to `/wards`*

> "So: a citizen can file and track with a real reference code; an officer works a queue scoped
> to their department, under a state machine the server enforces; and the public can see
> ward-level performance without an account, at a privacy-preserving resolution.
>
> It's **one Spring Boot deployable with six bounded modules** — identity, complaint, municipality,
> SLA, notification, transparency — and a deliberately small shared kernel. The module boundaries
> mean this can be split into services later without rewriting its neighbours.
>
> Happy to take questions — and I'd rather you ask me about the **403 guard** and the
> **transactional outbox** than the CRUD."

---

> "Let me file a new one live. → **ROADS**, drop a pin, add a photo, and submit.
>
> → *(submit, wait for the confirmation)*
>
> **'NS-2026-000065'.** That code now exists. It's generated from a sequence in the same code
> path as the live ones, so demo and real reference codes can never collide.
>
> Two things worth noticing while you watched that. First, **the ward was auto-detected from the
> pin** — I never selected a ward; PostGIS worked out I was in Gulshan. Second, submission
> accepts an **Idempotency-Key** header. If my network drops and I hit submit twice, it returns
> *the same complaint* instead of filing a duplicate report of the same pothole."

---

              ↓
[3:00] FILE A COMPLAINT  /citizen/complaints/new
       → wizard: category → map pin → photo → submit
       → SHOW the returned reference code  ★ THE HANDOFF MOMENT
              ↓
[3:45] OFFICER QUEUE  /authority/queue      (logout, login officer1@demo)
       → LOAD QUEUE, municipality 1
       → open the complaint just filed → VERIFY → AUTO-ASSIGN → START WORK
              ↓
[5:00] THE GUARD  ★ HIGHEST-VALUE 15 SECONDS
       → stay in officer1 session, or switch to officer2@demo
       → press Start Work on a ROADS complaint → 403
       → "that is the server refusing, not the UI hiding a button"
              ↓
[5:30] RESOLVE  → upload proof photo → RESOLVE
              ↓
[6:00] CITIZEN RATES  (back to citizen1) → RATE 4 → CLOSED
              ↓
[6:30] ADMIN  /admin  (login admin@demo)
       → SLA policy matrix: 64 rows, ROADS LOW=72h, escalation tiers
              ↓
[7:00] CLOSE  → back to /wards → the dot moved / scoreboard moved
```

**Total: 8 stops, 7:00–7:30.** If running long, cut the Admin section first (slide 22 covers it).

---
