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

## Phase 5: User Story 3 (nothing lost when a downstream channel or the service hiccups)

Automated tests written and run: 23, all passing (RetryPolicyTest 10, RetrySchedulerTest 4, ActionRecoveryTest 3,
ActionRunnerTest 6 re-run with the new wiring). They cover the exact delay sequences (10 s doubling to 300 s;
2 s doubling to 120 s), the plus or minus 20% jitter bound (worst case 360 s, inside the 10-minute recovery
target), the 20-attempt limit, `retry_after` handling, permanent failures, the early-404 rule for replies, the
30-second margin before a token expires, a mirror that fails twice then recovers (attempts recorded), exhaustion
ending as a failed action with a fixed reason, a brand-new scheduler finishing pending work after a "restart",
and an idle scheduler making zero database queries.

Implementation note: the pending-actions recovery query (`ActionStore.pending()`) and the `updated_at` handling
were already built in Phase 4; this phase added `RetryPolicy`, `RetryScheduler` and the wiring in `AppLifecycle`.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T058 live check: make the second channel unreachable (temporarily change the address), run a command, restore it; restart the service right after a command | Deferred task / skipped live test | Needs the live deployment and Discord | Reply still arrives, mirror shows pending with attempts, then is delivered; a pending notification survives a restart (quickstart US3 rows) |

## Phase 6: User Story 4 (sign-in and live log)

Automated tests written and run: 26, all passing (AdminAuthTest 5, AdminAuthFilterTest 8, LogViewTest 5,
LiveLogScriptTest 4, LatestLogTest 2 on a real Postgres, DatabaseConfigTest 2). They cover generic sign-in
failure, signed-out redirects and 401s, CSRF refusal on every POST, sessions, the exact JSON contract, no
tokens or addresses in the log JSON, and a guard that the script never uses innerHTML.

Extra local verification (not part of the task list): the real Docker image was run against a local Postgres
with a locally generated Discord key pair and fake credentials, with outbound DNS disabled so nothing left the
machine. Checked and passing: signed PING gets PONG; forged, stale, tampered and unsigned requests get 401;
GET /interactions gets 405; `not_configured` and `handled` outcomes; the urgent flag; five re-deliveries leave
one record; retries show as pending with rising attempts and `network error`; JSPs compile and render;
sign-in, cookie flags (`Secure; HttpOnly; SameSite=Lax`), CSRF refusal (403), and logout ending the session;
zero occurrences of any secret in the API output or the container log.

Bugs found by that local run and fixed in this phase:
- `No suitable driver`: under Tomcat's class loader the PostgreSQL driver must be named explicitly
  (`Database.hikariConfig` now sets it; `DatabaseConfigTest` asserts it).
- The Docker build failed intermittently with a transient TLS error while downloading from Maven Central;
  the Dockerfile now downloads single-threaded and retries.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T077 generate the real admin hash, set the real `ADMIN_*` values on Render, run the US4 quickstart rows on the deployment (new command visible within 5 s, markup shown as text, sign-out) | Deferred task / skipped live test | Needs the maintainer's credentials and the live deployment | SC-005 and SC-006 confirmed live |
| JSP output escaping (`<c:out>`) on the live pages | Skipped live test | Only the script is covered by an automated guard; the JSPs were checked by rendering them locally | Quickstart markup row passes |

## Phase 7: User Story 5 (connect a Discord server)

Automated tests written and run: 32 including the earlier client tests re-run (CommandDefinitionsTest 5,
DiscordClientConnectTest 5, ConnectServiceTest 8, ServerConnectionStoreTest 2, DiscordClientTest 12). They
cover the command definitions against Discord's naming rules, listing servers and text channels only,
bulk command registration, the fixed order (test message, then save, then register), nothing saved when the
test message fails, unlisted servers and channels rejected, registration failure keeping the connection with a
"save again" message, the channel change moving later posts, and the invite link (scopes and permission 3072).

Extra local verification: the Connect page was rendered on the real Docker image. Invite link correct; the
page never contains the bot token; the Discord-unreachable paths show an escaped error and save nothing; a
POST without the CSRF token gets 403.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T088 connect a real test server through the page, run `/report` and see the post in the chosen channel, change the channel, time a first-time connect | Deferred task / skipped live test | Needs the maintainer's Discord server, bot and deployment | Quickstart US5 rows pass; SC-008 (under 5 minutes) confirmed |
| Real Discord responses to the list, channel-list, test-post and command-registration calls | Skipped live test | Needs Discord; the calls follow the documented shapes and are tested only against a stub | Confirmed during T088 |

## Phase 8: User Story 6 (configure command behavior)

Automated tests written and run: 17 (ConfigServiceTest 8 on a real Postgres, MaskedAddressTest 5, AppConfigTest 4
re-run). They cover empty, blank and null reply text refused with the old value kept, unknown commands and
invalid enabled values refused, the last saved edit winning, text trimmed but otherwise verbatim, and
SC-009: disabling a command or changing its reply text changes the very next command's outcome and stored
reply with no restart. The masked address never reveals the webhook id or token.

Extra local verification: the Commands page was exercised on the real Docker image. It shows only the masked
address; an empty reply is refused; a POST without the CSRF token gets 403; a saved change redirects with a
notice; the very next signed `/report` was recorded as `disabled` with "This command is currently
unavailable."; markup in the reply text is shown escaped.

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T095 run the US6 quickstart rows on the deployment: disable `/report` and see "currently unavailable" in Discord, change the reply text, save an empty text, POST without a token | Deferred task / skipped live test | Needs the live deployment and a Discord server | Quickstart US6 rows pass (US6 scenarios 1 to 5) |

## Phase 9: Polish and cross-cutting

Done: README.md (T096) with the "Testing it" section and known limits; a full-suite run as completion evidence
(165 tests, 0 failures, 0 errors); the local part of the secrets search (T099); the local part of the research
update (T100); the spike probe for T010 prepared on the local branch `spike/db-probe` (never merged).

| Item | Kind | Reason | Done when |
|---|---|---|---|
| T097 `AI_NOTES.md` | Draft ready, maintainer owns it | Only the maintainer can write the honest first-person account | The parts marked "your words" are rewritten and the draft banner is removed |
| T098 full quickstart validation table on the deployed service, including the SC-001 timing row | Deferred task / skipped live test | Needs the deployment, Discord and Neon | Every row passes; numbers recorded in `research.md` |
| T099 secrets search of the Render logs | Deferred task / skipped live test | Needs the Render deployment | Zero occurrences of the bot token, public key and mirror address in Render's logs (SC-007) |
| T100 R1 results and the live-only unknowns in `research.md` | Deferred task | Needs T012/T013 and live services | R1 "Results" filled in; remaining unknowns resolved |
| T101 confirm the submission list | Deferred task | Maintainer's final check | Public URL reachable, README, `.env.example`, test instructions with the throwaway admin login, AI context files, `AI_NOTES.md` |

## What remains for the maintainer, in a sensible order

All code tasks are complete. What is left needs your accounts. The tasks are still unchecked in `tasks.md`,
each marked "deferred".

1. **Deploy the skeleton and keep it warm:** T007 (Render service), T008 (deploy, check `/health` and the log
   view), T009 (UptimeRobot).
2. **Measure the database (optional but recommended before relying on 2.5 s):** push the local branch
   `spike/db-probe` to GitHub, deploy it as a second Render branch or temporarily, then T011 (Neon variables,
   confirm Neon goes idle), T012 (the R1 protocol), T013 (record results and adjust `Timing.RECORD_DEADLINE`
   if needed), T014 (remove the probe).
3. **Go live with the real code:** T038 (set the variables, save the Interactions Endpoint URL in Discord,
   curl a forged request), then create the admin hash (quickstart, "Create the admin password hash") and set
   `ADMIN_*` (T077).
4. **Live checks:** T050 (commands), T088 (Connect page), T095 (Commands page), T058 (outage and restart).
5. **Finish:** T098 (full validation table and the timing row), T099 (Render log secrets check), T100 (R1
   results), T097 (rewrite `AI_NOTES.md`), T101 (final submission list), then push.
