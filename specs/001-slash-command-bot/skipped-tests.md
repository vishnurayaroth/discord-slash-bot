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
