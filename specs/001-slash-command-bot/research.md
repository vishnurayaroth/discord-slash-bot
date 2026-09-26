# Research: Discord Slash-Command Bot with Admin Dashboard

**Feature**: `001-slash-command-bot` | **Date**: 2026-09-27 | **Plan**: [plan.md](plan.md)

Every fact below was read from a fetched page by a research task on 2026-09-27, unless it is
tagged **(unverified)**. Free-tier terms change, so re-check them before relying on them late.
Values that need evidence from your own deployment are tagged **(provisional)** and are settled
by R1.

Stack is fixed by the constitution (Java 17, Servlets/JSP on Tomcat 10.1, Maven WAR, Neon
Postgres over JDBC with a pool, Docker on Render free). Nothing here reopens it.

---

## Verified facts the decisions rest on

### Neon (free plan)

| Fact | Source |
|---|---|
| 100 CU-hours per project per month. A 0.25 CU compute is therefore about 400 hours of uptime. When exhausted, compute suspends until the next billing period. | neon.com/pricing, neon.com/docs/introduction/plans |
| Compute suspends after **5 minutes idle**. On the Free plan this setting is fixed and cannot be changed or disabled. | neon.com/docs/introduction/scale-to-zero |
| Waking from idle "typically takes a few hundred milliseconds"; another page says "within seconds". Neon recommends a connect timeout and retries with backoff. | neon.com/docs/connect/connection-latency, .../connection-errors |
| At 0.25 CU (1 GB), `max_connections` is 104; 97 usable. | neon.com/docs/connect/connection-pooling |
| The pooled endpoint (`-pooler` host) uses PgBouncer in transaction mode. It rejects session `SET` calls and SQL-level PREPARE. Use the direct endpoint. | neon.com/docs/connect/choose-connection |
| No credit card required; the Free plan is permanent. Storage 0.5 GB per project. | neon.com/pricing |
| Regions include ap-southeast-1 (Singapore). No Mumbai. | neon.com/docs/introduction/regions |
| JDBC option is spelled `channelBinding` (not `channel_binding`). Needs pgjdbc 42.7.4+; versions before 42.7.7 (and a later silent-downgrade bug fixed in 42.7.12) did not enforce `require`. Use the latest 42.7.x. | neon.com/docs/guides/java, GHSA-hq9p-pm7w-8p54, pgjdbc CHANGELOG |

### Render (free web service) and UptimeRobot (free)

| Fact | Source |
|---|---|
| Spins down after **15 minutes** without inbound traffic; spin-up takes "about one minute". | render.com/docs/free |
| 512 MB RAM and **0.1 CPU**. | render.com/docs/compute-plans |
| 750 free instance hours per **workspace** per month. One always-on service is 744 h in a 31-day month (arithmetic), so it fits, but a second free service in the same workspace would not. | render.com/docs/free |
| `PORT` defaults to 10000, must bind 0.0.0.0, and can be overridden by setting the env var (so `PORT=8080` works). | render.com/docs/web-services |
| Health check: a GET every few seconds; success is 2xx/3xx within 5 s. Not stated whether health checks stop spin-down. | render.com/docs/health-checks |
| Filesystem is ephemeral. Regions include Singapore (free-plan availability **unverified**). | render.com/docs/free, /regions |
| UptimeRobot free: 50 monitors, **5-minute** minimum interval. HTTP monitors send **HEAD** by default; choosing GET is a paid feature. A keyword monitor uses GET. | uptimerobot.com/pricing, blog and FAQ |
| UptimeRobot terms say free use includes commercial use; the pricing page says "hobby and non-profit". Conflict noted; irrelevant for this exercise. | uptimerobot.com/terms |

### Discord

| Fact | Source |
|---|---|
| Initial response due within **3 seconds**, else the token is invalidated. Token valid **15 minutes** for follow-ups. | docs.discord.com/developers/interactions/receiving-and-responding |
| PONG is `{"type":1}`. Private message: `{"type":4,"data":{"content":...,"flags":64}}`. Deferred: `{"type":5,"data":{"flags":64}}`. | same |
| A deferred **ephemeral** response stays ephemeral; the flag cannot be changed later. So private must be set on the first acknowledgement. | same, Create Followup section |
| Edit the deferred reply with `PATCH /webhooks/{application.id}/{interaction.token}/messages/@original`. The token in the URL is the credential. Content up to 2000 characters. | docs.discord.com/developers/resources/webhook |
| Signing: headers `X-Signature-Ed25519` (hex) and `X-Signature-Timestamp`; verify over timestamp + raw body with the hex-decoded application public key; invalid must return 401. | docs.discord.com/developers/interactions/overview |
| Discord runs routine checks "purposefully sending you invalid signatures"; failing them removes the URL. | same |
| **Nothing documented** on redelivery, duplicate interactions, or a timestamp replay window. | (absence checked) |
| Command payload: `id`, `application_id`, `type` (2 = command), `token`, `guild_id`, `channel_id`, `member.user.{id,username}`, `data.name`, `data.options[{name,type,value}]`. String option type = 3. | docs.discord.com/developers/interactions/application-commands |
| Register with `PUT /applications/{app}/guilds/{guild}/commands` (bulk overwrite), header `Authorization: Bot <token>`, base `https://discord.com/api/v10`. Guild commands update instantly. Names 1-32 lowercase; a required option is `{"name":"text","description":"...","type":3,"required":true}`. String options may set `max_length` 1-6000. | same |
| Post to a channel: `POST /channels/{id}/messages` (needs Send Messages, content up to 2000). Guild list: `GET /users/@me/guilds`. Channels: `GET /guilds/{id}/channels` (text channel type 0). From 2026-11-16 that list omits channels the bot cannot view. | docs.discord.com/developers/resources/message, /guild |
| Webhook execute: `POST /webhooks/{id}/{token}`, no auth header, body `{"content":...}`, 204 without `?wait=true`. | docs.discord.com/developers/resources/webhook |
| Rate limit: HTTP 429 with JSON `retry_after` (seconds) and a `Retry-After` header. | docs.discord.com/developers/topics/rate-limits |
| Invite link: `https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot%20applications.commands&permissions=3072` (3072 = View Channel 1024 + Send Messages 2048). | docs.discord.com/developers/topics/oauth2 |

### Java and Maven (all run on Java 17)

| Item | Version | Note |
|---|---|---|
| `jakarta.servlet-api` | 6.0.0, `provided` | 6.1 targets Tomcat 11. |
| `jakarta.servlet.jsp.jstl-api` | 3.0.2 | Java 11 |
| `org.glassfish.web:jakarta.servlet.jsp.jstl` | 3.0.1 | Fat jar; put API and impl in the WAR. |
| `com.fasterxml.jackson.core:jackson-databind` | 2.22.3 | Jackson 3 is a different group; not used. |
| `org.postgresql:postgresql` | 42.7.13 | Satisfies every channel-binding fix. |
| `com.zaxxer:HikariCP` | 7.1.0 | Java 11+ |
| `at.favre.lib:bcrypt` | 0.10.2 | `BCrypt.withDefaults().hashToString(cost, pw)`; `BCrypt.verifyer().verify(pw, hash).verified` |
| JUnit Jupiter | 6.1.3 | Needs Java 17 at runtime. |
| Testcontainers | 2.0.5 (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) | Docker 28.x compatibility **unverified** (2.0.2+ fixes Docker 29). |
| Plugins | surefire 3.6.0, war 3.5.1, compiler 3.16.0 | Current pom pins compiler 3.13.0 and war 3.4.0. |
| Image | `tomcat:10.1-jdk17-temurin` exists | Tomcat 10.1: Servlet 6.0, JSP 3.1, Java 11+. |

Technical facts confirmed: JDK 17 verifies Ed25519 using only `java.security` (raw 32-byte key wrapped
with the prefix `302a300506032b6570032100`, tested on OpenJDK 17.0.15). HikariCP's
`connectionTimeout` has a 250 ms floor and bounds the wait for a connection; per-statement limits
use `Statement.setQueryTimeout(seconds)` (whole seconds) or the pgjdbc `queryTimeout` parameter.
`System.Logger` uses `java.util.logging` on JDK 17; Tomcat routes that through its own log manager
with an INFO default.

---

## R1. Measure cold-database latency before fixing the timeout (your requirement 1)

**Decision.** The database timeout is **provisional at 2.5 s** until measured on the real free
Render instance against Neon. The measurement is the first task after the skeleton deploys, before
any feature depends on the number.

**Why.** Neon says a wake-up is "a few hundred milliseconds" and elsewhere "within seconds". On a
0.1-CPU host the TLS handshake and the driver's first use are also slow. Neither is measured for
this deployment, so the number is a guess until then.

**Protocol.**
1. Put Render and Neon in the same or nearest region (Singapore for both if the free plan offers it).
2. On a **spike branch only**, add a temporary probe that opens a connection and runs a trivial
   query, returning the elapsed milliseconds split into connect and query. It exposes nothing
   sensitive. Delete it after measuring; it is never merged.
3. **Cold samples (20):** wait more than 6 minutes with no traffic (confirm the Neon console shows
   the compute idle), call the probe once, record. Spread over about two hours.
4. **Warm samples (100):** call it back to back.
5. **App-cold sample:** the first call after the app itself restarts, to include pool creation.
6. Report p50, p95 and max for cold and warm, and how many exceeded 1.5 s and 2.5 s.

**Decision rule** (suggested thresholds for the maintainer, not spec requirements):

| Cold result | Action |
|---|---|
| max ≤ 1.5 s | Keep 2.5 s. Comfortable margin. |
| p95 ≤ 2.0 s and max ≤ 2.5 s | Keep 2.5 s. Expect an occasional refusal after a long idle. |
| p95 > 2.0 s | 2.5 s cannot be raised (Discord's window). The first command after 5+ idle minutes would be refused too often. Take the data to the maintainer: accept and document, pre-warm before evaluation, or reopen the Principle II amendment. |

**Results:** *not measured yet.* Fill in here after the spike.

**Alternatives considered.** Skip measuring and trust Neon's "few hundred ms": rejected, because the
0.1-CPU host is the unknown. Measure on a laptop: rejected, it does not reproduce the free host.

---

## R2. Database time budget and the commit gate (your requirement 2)

**Decision.**
- The whole record step (get a connection, read settings, insert the interaction and its actions,
  commit) has one deadline of **2.5 s from the moment the request arrives** (provisional, R1).
- Enforced with: pool `connectionTimeout` **1.5 s**, per-statement `queryTimeout` **1 s** (applied to every statement by one shared helper in `Database`), and a
  **commit gate** described next. The record step is one short transaction of at most four
  statements (settings and connection read together, insert the interaction with duplicate
  detection, insert the actions, commit).
- Total on-the-wire budget: 2.5 s plus at most a **0.4 s grace** (below) stays under Discord's
  3 s, but with little spare for network time. This is why R1 matters.

**The commit gate (why it exists).** A timed-out write may still commit on the server a moment
later. If the member was told "try again" and the row lands anyway, it would be processed, and the
member's retry would be a second command: a duplicate report. To make "do not accept or process"
true, the request thread and the database thread share one three-state flag:

| State | Set by | Meaning |
|---|---|---|
| `PENDING` | start | Nothing decided. |
| `COMMITTING` | database thread, just before it commits | Point of no return. |
| `ABANDONED` | request thread at the deadline | The database thread must roll back instead of committing. |

Both moves are atomic compare-and-set from `PENDING`, so exactly one wins:
- Deadline hits first: the request thread wins, replies with the fallback (R3), and the database
  thread rolls back. The command was never accepted.
- Commit starts first: the request thread cannot abandon. It waits up to **0.4 s** for the commit.
  If it finishes, acknowledge normally. If not, acknowledge anyway (the row will most likely
  appear, and a completion callback from the gate then starts its actions, so no restart or
  polling is needed; if it never lands, nothing is recorded and nothing happens); a hung
  "thinking" indicator is the lesser harm
  compared with a duplicate command. This residual case is documented, not hidden.

**Alternatives considered.** Rely on the query timeout alone: rejected, it can cancel a statement
but not a commit already in flight. Give the record step longer than 2.5 s: rejected, Discord's
window is fixed. Amend Principle II so the acknowledgement comes first: rejected by you (Option 1).

---

## R3. Truthful fallback when the record cannot be written in time (your requirement 3)

**Decision.** When the deadline or the gate says the command was not accepted, or the database
errors, respond immediately (not deferred) with a private message:
`{"type":4,"data":{"content":"Temporarily unavailable, please try again.","flags":64}}`.
Nothing is recorded, no actions exist, nothing is posted or mirrored. The member knows and can
retry. An application log line notes the refusal (interaction id and reason only, no secrets).

**Why.** It keeps the invariant "acknowledged means recorded", and the member is never left
guessing. It is not a silent loss because the member is told.

**Known gap, stated openly.** A refused command is not visible in the admin dashboard, because
there was nowhere to record it. It appears only in application logs. The same is true of any
command that arrives while the database is completely down.

**Duplicates.** A repeated delivery that collides on the interaction id gets the same private
deferred acknowledgement and creates no new actions (FR-012).

---

## R4. Keep-warm strategy, and whether it touches the database (your requirement 4)

**Decision.**
- **App keep-warm:** UptimeRobot pings `/health` every 5 minutes, inside Render's 15-minute idle
  limit. `/health` answers `GET` and `HEAD` with 200 and does **not** touch the database.
  Because UptimeRobot's free HTTP monitor sends `HEAD`, the endpoint must handle `HEAD` correctly;
  the fallback is a keyword monitor (uses `GET`).
- **Database keep-warm: no.** Do not ping the database on a schedule.

**Evidence.** The Neon free allowance is 100 CU-hours a month. A compute that never suspends uses
about 0.25 CU × 730 h ≈ 182 CU-hours, which exhausts the allowance around day 17 and then **suspends
the database until the next billing period**, an outage far worse than an occasional slow first
query. Neon's 5-minute suspend is fixed on the free plan, so it cannot be tuned.

**Things that would silently keep the database awake, and must be avoided:**
1. A connection pool that refills itself. HikariCP's housekeeper keeps the pool at `minimumIdle`
   (default: the maximum size), reopening connections after Neon drops them, which wakes the
   database again. Fix: `minimumIdle = 0`, short `idleTimeout` (60 s), and `keepaliveTime` explicitly 0 (HikariCP 7 defaults to 2 minutes; DatabaseConfigTest guards it).
   *(Behavior from HikariCP's documented defaults; confirm in the spike by watching the Neon console
   go idle. Unverified here.)*
2. A retry worker that polls the database on a timer. Fix: retries are scheduled in memory (R5),
   with one recovery scan at start-up.
3. A dashboard tab left open, polling forever. Fix: pause polling when the tab is hidden and after
   10 minutes without input (R9).

**Residual risk.** If commands arrive at least every 5 minutes all month, the database never
suspends and the allowance runs out anyway. Not a concern for this exercise; noted in the README.
**Render hours:** one always-on service is 744 of 750 workspace hours; do not add a second free
service to the same workspace.

**Alternatives considered.** Ping the database every 4 minutes: rejected (allowance). Ping only
during evaluation: a manual operator choice, not built in (YAGNI).

---

## R5. Retry policy (decided here as the spec required)

**Decision.**

| | `mirror` and `post` actions | `reply` action |
|---|---|---|
| First attempt | Immediately after the acknowledgement | **500 ms after** the acknowledgement is sent |
| Backoff after failure n | `min(10 s × 2^(n−1), 300 s)`, ±20% jitter | `min(2 s × 2^(n−1), 120 s)`, ±20% jitter |
| Limit | 20 attempts (about 75 minutes in total) | Until `token_expires_at − 30 s` (the 15-minute token lifetime) |
| Retry when | Network error, timeout, HTTP 5xx, HTTP 429 (wait at least `retry_after`) | Same |
| Fail permanently at once | HTTP 4xx other than 429 (bad address, deleted channel, missing access) | Same, after the first-attempt rule below |
| Terminal reason shown | `last_error`: status plus Discord error code | `follow-up window expired` when the token runs out |

**First-reply race (expected, unverified).** The service starts work as soon as it returns the
acknowledgement, and Discord may not have processed that response yet. Editing the original reply
too early is expected to fail with an "unknown webhook/interaction" 404. So the first reply attempt
waits 500 ms, and a 404 on the first three attempts within 10 s counts as retryable. Confirm in
the first live test.

**Why.** SC-004 needs every missed notification delivered within 10 minutes of the channel coming
back. With a 5-minute cap plus jitter, the next attempt is always at most about 6 minutes after
restoration. Outages longer than about 75 minutes end as visible permanent failures, which the spec
permits (FR-015). A manual "retry" button is out of scope.

**In-memory scheduling.** Each retry is a timer in the running process. On start-up, one query
loads every `pending` action and reschedules it (FR-016). No periodic polling (R4).

**Alternatives considered.** A fixed 5-minute interval: slower first retry, no benefit. Unlimited
retries: hides permanent misconfiguration. A polling worker: rejected (R4).

---

## R6. Replay freshness window (decided here as the spec required)

**Decision.** Accept a request only if its `X-Signature-Timestamp` is **at most 15 seconds old and
at most 5 seconds in the future**. Otherwise return 401 with no side effects.

**Why.** Discord documents no replay window (verified absence), so this is our choice. Discord
invalidates an interaction that gets no response within 3 seconds, so any request older than that
is already dead: processing it would record a command the member saw fail. A short window also
shrinks what a captured request is worth. The unique interaction id (R2) remains the main replay
defense; this is defense in depth. 5 seconds of future allowance covers clock drift.

**Alternatives considered.** 5 minutes: the common convention for webhook signatures, but it accepts
hopeless late requests. No window: leaves only the unique id, weaker.

---

## R7. Outbound call timeouts (decided here as the spec required)

**Decision.** Every outbound HTTP call (Discord API, the second-channel webhook, follow-ups, channel
and server lookups) uses **connect 3 s, whole request 5 s**. One pair of constants. Database is
covered separately by R2.

**Why.** Constitution Principle III requires an explicit timeout on every outbound call. All calls
except the dashboard lookups run in the background, so 5 s is safe; a final reply still lands well
inside SC-001's 10 seconds on the first attempt.

---

## R8. Connection pool settings

**Decision.** HikariCP, maximum 4 connections, `minimumIdle = 0`, `idleTimeout` 60 s, `maxLifetime`
10 minutes, `connectionTimeout` 1.5 s on the request path, `keepaliveTime` explicitly 0 (HikariCP 7 defaults to 2 minutes), `initializationFailTimeout` -1 so start-up never touches a sleeping database. Direct Neon endpoint
(not pooled), `sslmode=require`, and `channelBinding=require` with pgjdbc 42.7.13. Credentials are
separate variables, not embedded in the URL.

**Why.** A small pool fits 512 MB and 0.1 CPU. The direct endpoint avoids PgBouncer restrictions.
`minimumIdle = 0` prevents the self-refilling behavior in R4.

---

## R9. Live log updates

**Decision.** The page shell is a JSP. A small script polls `GET /api/log` every **3 seconds**
and redraws the newest **50** entries (each with its actions) using `textContent`. It pauses while
the tab is hidden and after 10 minutes without input, showing "paused, click to resume".

**Why.** Actions change status after their row appears, so returning the latest 50 each time is
simpler than tracking changes. SC-005 needs 5 seconds; 3 s polling meets it. With small volume the
response is small. Untrusted text is never inserted as markup (FR-021).

**Alternatives considered.** Server-sent events: long-lived connections cost threads on a
0.1-CPU, 512 MB host and may be cut by the proxy. WebSockets: more machinery for no gain.

---

## R10. Connecting a server, and registering commands

**Decision.** The admin page shows an "Add the bot" link (the invite URL in Verified facts) and a
"Refresh" action. Refresh lists the servers the bot is in (`GET /users/@me/guilds`). The admin picks
one, then a text channel (`GET /guilds/{id}/channels`, type 0). On save the service (1) sends a
short "Bot connected" test message to that channel to prove it can post, showing the error and
saving nothing if it cannot, (2) stores the connection, and (3) registers `/status` and `/report`
for that server with the bulk-overwrite call (idempotent, so re-saving is safe).

**Why.** The channel list only hides channels the bot cannot *view*, not ones it cannot *post* to,
so a test post is the reliable check for User Story 5. Guild commands appear instantly.

`/report` defines one required string option `text` with `max_length` **1000**, keeping composed
messages under Discord's 2000-character limit. Composed text is still truncated defensively.

---

## R11. Admin sign-in, sessions, CSRF

**Decision.** One admin, from `ADMIN_USERNAME` and `ADMIN_PASSWORD_HASH` (BCrypt via
`at.favre.lib:bcrypt`, cost 10; tune down if a login takes over about a second on the free host).
Wrong username and wrong password give the same generic message. On success, change the session id.
Session cookie: `HttpOnly`, `Secure`, `SameSite=Lax`; 30 minutes of inactivity. Every state-changing
form carries a per-session CSRF token checked in constant time. A filter guards `/dashboard/*` and
`/api/*`: pages redirect to sign-in, API calls get 401.

**Why.** Meets FR-018, FR-019, FR-024. Sessions live in memory, so a restart signs the admin out,
acceptable for one operator. Sign-in itself carries no CSRF token (low risk, YAGNI).
Constitution wording requires BCrypt, so the JDK's PBKDF2 alternative is not used.

---

## R12. Logging

**Decision.** `System.Logger` (in the JDK), levels INFO, WARNING, ERROR only. Log the interaction id,
command, outcome, timings, and reason codes. Never log tokens, the webhook address, request bodies,
signatures, credentials, or interaction tokens. Record-step timings are logged on every interaction
(this also feeds R1).

**Why.** Principle VI prefers the JDK over a new dependency. Tomcat's log manager defaults to INFO,
so DEBUG would be dropped; the design does not depend on it. *(That INFO lines reach the container
log is expected but unverified; confirm in the skeleton deploy.)*

**Alternatives considered.** Logback: rejected, no need it can meet that the JDK cannot.

---

## R13. Outbound message safety

**Decision.** Every message the service sends carries `allowed_mentions` with an empty `parse` list,
so a member cannot make the channel post or the second-channel notification ping `@everyone` or a
role by putting it in report text. Content is truncated to 2000 characters. *(The `allowed_mentions`
field is known from Discord's API but was not part of the verified research; confirm when building
the client.)*

---

## R14. Dependencies (each with a stated need, Principle VI)

| Dependency | Need |
|---|---|
| `jakarta.servlet-api` 6.0.0 (provided) | Write servlets and filters; Tomcat supplies it. |
| JSTL API 3.0.2 and implementation 3.0.1 | `<c:out>` escaping in JSPs (FR-021). |
| `jackson-databind` 2.22.3 | Parse Discord's JSON and build responses; the JDK has no JSON parser. |
| `postgresql` 42.7.13 | Database driver. |
| `HikariCP` 7.1.0 | Connection pool, required by the constitution. |
| `at.favre.lib:bcrypt` 0.10.2 | The constitution requires a BCrypt hash; the JDK has none. |
| JUnit Jupiter 6.1.3 (test) | Unit tests. |
| `testcontainers-postgresql` and `-junit-jupiter` 2.0.5 (test) | Check duplicate detection, unique constraints, and the retry-recovery query against a real Postgres. |

Not added: an Ed25519 library, an HTTP client, a logging framework, a migration tool, a mocking
library. The JDK covers them (`java.security`, `java.net.http`, `System.Logger`), and the schema is
applied by one idempotent script at start-up. Tests use a stub built on the JDK's
`com.sun.net.httpserver.HttpServer` for Discord.

**Risks to check in the first setup task (unverified):** Testcontainers 2.0.5 with your Docker
Engine 28.4 (fallback: tests read a `TEST_DATABASE_URL` pointing at a local `docker run postgres`);
JUnit 6.1 with surefire 3.6.0; the existing pom pins older compiler and WAR plugins to update.

---

## R15. Testing approach

**Decision.** Logic lives in plain classes; servlets only adapt bytes and headers, so nearly
everything is testable without a servlet container. Required tests (Principle V), each runnable with
`mvn -Dtest=<Class> test`:
- **Signature verifier:** valid, tampered body, wrong key, malformed hex, missing header, stale
  timestamp, future timestamp.
- **Interaction handler:** PING/PONG, invalid signature has no side effects, duplicate delivery
  leaves one record and no second actions, disabled/not-configured/wrong-server/unsupported
  outcomes, priority rule on mixed case.
- **Record gate:** deadline before commit rolls back and returns the fallback; commit in flight
  waits the grace; nothing is accepted when refused.
- **Retry policy:** delays, jitter bounds, limits, permanent vs retryable classification, the
  reply expiry rule.
- **Discord client:** against a JDK `HttpServer` stub for 2xx, 429 with `retry_after`, 5xx, 404,
  timeout.
- **Persistence (Testcontainers):** unique interaction id, one action per kind, restart recovery
  query, empty reply text rejected.
- **Dashboard:** auth filter redirects and 401s, CSRF rejection, escaping of markup in member text.

Live checks against a real Discord test server are in `quickstart.md`.

---

## R16. Deployment shape

**Decision.** Multi-stage Dockerfile: build the WAR with Maven on a Java 17 image, then copy it as
`ROOT.war` into `tomcat:10.1-jdk17-temurin`. `PORT=8080`. Cap the JVM heap around 256 MB and reduce
Tomcat's thread limit to fit 512 MB; both are starting points to tune in the skeleton deploy.
Render health check path `/health`. All secrets are Render environment variables; `.env.example`
lists names only. Render free filesystem is ephemeral, so nothing is stored on disk.

**Risk.** With 0.1 CPU, JVM warm-up after a restart is slow; the keep-warm ping (R4) makes restarts
rare, and R1 measures the first-request cost.

---

## Remaining unknowns (none block planning)

- Cold-database latency on the real host (R1).
- Whether Render's free plan offers Singapore, and whether Render health checks stop spin-down (we
  do not rely on either).
- Discord's exact behavior for an early edit of the original reply (R5) and `allowed_mentions` (R13).
- Testcontainers with Docker 28.4, JUnit 6 with surefire, INFO logging in the container (R14, R12).
