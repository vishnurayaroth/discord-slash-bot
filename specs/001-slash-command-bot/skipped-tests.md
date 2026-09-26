# Skipped tests and deferred tasks

Working rule from the maintainer: implement all code first; do not write or run tests that need the
maintainer's accounts or a live deployment (Render, Neon, Discord, UptimeRobot); tests that can run
locally are still written and run. This file is updated at the end of every phase.

Legend: **Skipped test** = an automated or manual check that was not run and why.
**Deferred task** = a task left unchecked in `tasks.md` because only the maintainer can do it.

## Phase 1: Setup (skeleton and measurement)

Automated tests defined for this phase: none.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T007 create the Render web service | Deferred task | Needs the maintainer's Render account | Service exists with `PORT=8080` and health path `/health` |
| T008 deploy skeleton, verify public `/health`, log view, cold-start time | Deferred task | Needs the Render deployment | Public `/health` answers GET and HEAD; INFO line seen in Render's log view |
| T009 UptimeRobot monitor | Deferred task | Needs the maintainer's UptimeRobot account | Monitor shows Up at 5-minute intervals |
| T010 spike-branch database probe | Deferred task | Only useful with the live measurement; prepared on a separate local branch at the end, never merged | Probe deployed and later removed |
| T011 set Neon variables on Render, confirm Neon goes idle | Deferred task | Needs Render and Neon accounts | Neon console shows Idle while the app is quiet |
| T012 run the R1 latency protocol | Deferred task | Needs the live database and about 2.5 hours of waiting | p50/p95/max recorded |
| T013 record R1 results and the final database deadline | Deferred task | Depends on T012 | `research.md` R1 "Results" filled in. Until then `Timing.RECORD_DEADLINE` stays at the provisional 2.5 s |
| T014 remove the probe and redeploy | Deferred task | Depends on T010 to T013 | `/probe/db` returns 404 |

Verified locally in this phase (T006): the Docker image builds; `GET` and `HEAD /health` return 200;
the start-up INFO line appears in `docker logs`; Tomcat's thread limit and the JVM flags are applied.
Not yet verified: the same on Render's free instance (T008).

## Phase 2: Foundational

Automated tests written and run: 40, all passing (AppConfigTest, DatabaseConfigTest, SignatureVerifierTest,
InteractionParseTest, ResponsesTest, DiscordClientTest with a JDK HttpServer stub, SmokeDatabaseTest and
SchemaTest on a real Postgres through Testcontainers).

Skipped tests: none. Every Phase 2 test runs locally without the maintainer's accounts.

Deferred tasks: none in this phase. One dependency note: T016 (`Timing`) and T022 (`Database`) depend on T013
(the recorded cold-database measurement). Both are done with the provisional 2.5 s record deadline; the value
in `Timing.RECORD_DEADLINE` is revisited when T013 is completed.

Findings worth knowing:
- Testcontainers 2.0.5 works with Docker 28.4 on this machine (was an unverified risk in research R14).
- `DatabaseConfigTest` caught that HikariCP 7's default `keepaliveTime` is 2 minutes, not 0. It is now set to 0
  explicitly and research R4 and R8 were corrected.

## Phase 3: User Story 2 (authentic, handled once, answered in time)

Automated tests written and run: 35, all passing (RecordGateTest 10, InteractionHandlerTest 12,
InteractionStoreTest 6 on a real Postgres, SignatureVerifierTest 7). They cover forged, tampered and stale
requests, PING/PONG, duplicate delivery (five times leaves one record), a slow or failing store (refused and
never recorded later), a blocked downstream (acknowledgement still returns), the unconfirmed-commit path
(callback exactly once, never on failure), and a 150-iteration race proving a refused command is never committed.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T038 set Discord/mirror/admin variables on Render, deploy, save the Interactions Endpoint URL, send an unsigned and a wrongly signed request with curl | Deferred task / skipped live test | Needs the maintainer's Render and Discord accounts and a public deployment | Discord accepts `https://<app>/interactions` (US2 scenario 3) and forged requests get 401 with nothing in the log (US2 scenario 1, SC-002) |
| Live check that Discord shows the private "thinking" state and no "did not respond" | Skipped live test | Needs a real Discord server | Covered by quickstart rows and T098 |
| Cold-database behavior of the 2.5 s record deadline | Skipped live test | Needs Neon and the R1 measurement (T012/T013) | `Timing.RECORD_DEADLINE` confirmed or changed |

## Phase 4: User Story 1 (commands end to end)

Automated tests written and run: 23, all passing (CommandRulesTest 12, StoresTest 5 on a real Postgres,
ActionRunnerTest 6 against a real Postgres and a JDK HttpServer stub standing in for Discord and the mirror
webhook). They cover every outcome (handled, not_configured, wrong_server, unsupported, disabled), the
"urgent" rule in any letter case, the 500 ms first-reply delay, post and mirror going out at once, a failing
mirror never delaying the reply, sanitized stored errors, and `updated_at` set on every action update.

Implementation note: the "start the actions after the acknowledgement" behavior (T047) was already built in
Phase 3 (the handler returns an after-response callback and gives the gate a late-commit callback); this phase
added the real `ActionDispatcher`, `ActionRunner`, `CommandRules` and the wiring in `AppLifecycle`.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T050 live run of `/status`, `/report`, and `/report ... urgent` in a real Discord server, including the early-edit race and `allowed_mentions` behavior | Deferred task / skipped live test | Needs Discord, Render and Neon accounts | Quickstart US1 rows pass; results recorded in research R5 and R13 |
| Interim setup (register commands with curl, insert the connection row) | Manual steps | Needs the bot token and database access; documented in `quickstart.md` | Commands visible in Discord; a `server_connection` row exists |
