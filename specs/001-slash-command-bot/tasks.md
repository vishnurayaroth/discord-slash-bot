---

description: "Task list for Discord Slash-Command Bot with Admin Dashboard"
---

# Tasks: Discord Slash-Command Bot with Admin Dashboard

**Input**: Design documents from `/specs/001-slash-command-bot/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/, quickstart.md

**Tests**: Tests are REQUIRED for tasks that implement behavior governed by constitution Principles I-IV (signature verification, deduplication, retries, response window, secret handling), including the unhappy paths. For all other tasks, tests are OPTIONAL - they are included below only where they guard a spec requirement.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions
- **[Maintainer]**: a manual action only you can do (Render, Neon, UptimeRobot, Discord portal, or Git). Claude does not commit (`CLAUDE.md`).

## Path Conventions

Single Maven module, Java packages under `src/main/java/com/example/discordbot/<package>/` and tests under
`src/test/java/com/example/discordbot/<package>/`. Pages are under `src/main/webapp/WEB-INF/views/`, browser files under
`src/main/webapp/static/`, SQL under `src/main/resources/db/`.

## Working rules (from `CLAUDE.md` and the constitution)

- State a task's success criteria before touching files. A task is done only when its related tests pass, run with
  `mvn -Dtest=<Class> test`; run the full suite only when asked.
- For governed behavior, write the test first and see it fail before implementing.
- Touch only what the task names. Add a dependency only in the task that first needs it.
- Never write a secret, token, webhook address, or request body into code, logs, test output, or docs.
- Every database statement goes through the shared helper in `Database` (T022), which applies the 1 s statement limit. No store sets its own limit (Principle III).

---

## Phase 1: Setup - skeleton deployment and cold-database measurement

**Purpose**: Prove hosting works and measure the database before any feature depends on the 2.5 s deadline (research.md R1, R4).

- [ ] T001 Update `pom.xml`: maven-compiler-plugin 3.16.0, maven-war-plugin 3.5.1 (keep `failOnMissingWebXml` false and `finalName` ROOT), add maven-surefire-plugin 3.6.0; add `jakarta.servlet:jakarta.servlet-api:6.0.0` (provided), `org.postgresql:postgresql:42.7.13`, `com.zaxxer:HikariCP:7.1.0`; keep `maven.compiler.release` 17. Done when `mvn clean package` gives BUILD SUCCESS and `target/ROOT.war`.
- [ ] T002 [P] Create `.env.example` at the repository root: names only with empty values and a one-line comment each: `DISCORD_APPLICATION_ID`, `DISCORD_PUBLIC_KEY`, `DISCORD_BOT_TOKEN`, `MIRROR_WEBHOOK_URL`, `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH`, `PORT` (quickstart.md table).
- [ ] T003 [P] Create `src/main/java/com/example/discordbot/dashboard/HealthServlet.java`: `@WebServlet("/health")` answering GET and HEAD with 200 and body `ok`, no database access (research.md R4, contracts/dashboard-http.md).
- [ ] T004 [P] Create `src/main/java/com/example/discordbot/config/AppLifecycle.java`: a `ServletContextListener` that logs one INFO line at start with `System.Logger` (research.md R12). Later tasks extend it.
- [ ] T005 Create `Dockerfile` and `.dockerignore` at the repository root: multi-stage, build the WAR with Maven on a Java 17 image, copy it as `ROOT.war` into `tomcat:10.1-jdk17-temurin`; cap the JVM heap near 256 MB and lower Tomcat's thread limit as starting values (research.md R16). Done when `docker build` succeeds.
- [ ] T006 Verify locally using the `Dockerfile`: run the image with port 8080 published; `GET /health` and `HEAD /health` (`curl -I`) return 200 and the start-up INFO line appears in `docker logs`.
- [ ] T007 **[Maintainer]** Create the Render web service from the GitHub repository: Docker, Free instance, region matching Neon, env `PORT=8080`, health check path `/health` (research.md R4, R16).
- [ ] T008 **[Maintainer]** Deploy the skeleton. Verify `https://<app>/health` for GET and HEAD, the start-up INFO line in Render's log view, and note how long the first request after a spin-down takes (research.md R12, R16).
- [ ] T009 **[Maintainer]** Add the UptimeRobot HTTP monitor on `/health` at 5-minute intervals and confirm it reports Up. If HEAD is not accepted, switch to a keyword monitor (research.md R4).
- [ ] T010 **[Maintainer]** Spike branch only, never merged: create `src/main/java/com/example/discordbot/dashboard/DbProbeServlet.java`. `GET /probe/db` builds a pool with the research.md R8 settings (max 4, `minimumIdle` 0, `idleTimeout` 60 s, `maxLifetime` 10 min, `connectionTimeout` 1.5 s, no `keepaliveTime`) from `DATABASE_URL`, `DB_USER`, `DB_PASSWORD`, runs a trivial query, and returns JSON with connect and query milliseconds. It exposes nothing sensitive.
- [ ] T011 **[Maintainer]** Set `DATABASE_URL` (direct Neon endpoint, `sslmode=require&channelBinding=require`), `DB_USER`, `DB_PASSWORD` on Render, deploy the spike branch, and confirm the Neon console shows the compute going Idle while the app is quiet. This proves the pool does not refill itself (research.md R4). Variable names are in `.env.example`.
- [ ] T012 **[Maintainer]** Run the research.md R1 protocol against `/probe/db`: 20 cold samples (each after more than 6 idle minutes, confirmed idle in Neon), 100 warm samples, 1 app-cold sample after a restart. Capture p50, p95, max, and how many exceeded 1.5 s and 2.5 s.
- [ ] T013 Record the numbers in `specs/001-slash-command-bot/research.md` under R1 "Results", apply its decision rule, and state the final database deadline. If cold p95 exceeds 2.0 s, stop and take the numbers to the maintainer (accept, pre-warm, or reopen the Principle II amendment) before starting Phase 3.
- [ ] T014 **[Maintainer]** Remove the probe: delete `src/main/java/com/example/discordbot/dashboard/DbProbeServlet.java` from the deployed branch, redeploy the main branch, and confirm `/probe/db` returns 404.

**Checkpoint**: skeleton live, keep-warm running, R1 result recorded. Only now may features rely on the database deadline.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared pieces every story needs. T015, T017-T021, and T023-T028 do not depend on the R1 result and may start while the measurement runs; only T016 and T022 need T013.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [ ] T015 Add to `pom.xml`: `com.fasterxml.jackson.core:jackson-databind:2.22.3`; test scope `org.junit.jupiter:junit-jupiter:6.1.3`, `org.testcontainers:testcontainers-postgresql:2.0.5`, `org.testcontainers:testcontainers-junit-jupiter:2.0.5`. Done when `mvn clean package` succeeds.
- [ ] T016 [P] Create `src/main/java/com/example/discordbot/config/Timing.java`: constants from plan.md and research.md - replay freshness 15 s past and 5 s future (R6), body cap 100 KB, outbound connect 3 s and request 5 s (R7), pool connection wait 1.5 s and statement limit 1 s, commit grace 0.4 s, record deadline (value from T013, default 2.5 s; R2), interaction token lifetime 15 min with 30 s margin, first reply delay 500 ms, retry parameters (`mirror` and `post`: base 10 s, cap 300 s, 20 attempts; `reply`: base 2 s, cap 120 s; jitter 20%; R5). Depends on T013.
- [ ] T017 [P] Write test `src/test/java/com/example/discordbot/config/AppConfigTest.java`: a missing or blank required variable fails start-up with a message that names the variable and never contains any variable's value (Principle IV).
- [ ] T018 Create `src/main/java/com/example/discordbot/config/AppConfig.java`: read every variable listed in `.env.example` once, fail fast on missing required ones (`PORT` optional), typed getters, and a `toString` that prints no values. Makes T017 pass.
- [ ] T019 [P] Create `src/test/java/com/example/discordbot/persistence/PostgresTestSupport.java` and `src/test/java/com/example/discordbot/persistence/SmokeDatabaseTest.java`: start Postgres with Testcontainers (fallback: use `TEST_DATABASE_URL` when set) and run `SELECT 1`. Done when it passes on this machine's Docker 28.4, which also confirms JUnit 6.1 with surefire 3.6.0. If Testcontainers 2.0.5 fails here, use the fallback and record the outcome in research.md R14.
- [ ] T020 [P] Create `src/main/resources/db/schema.sql`: idempotent (`CREATE TABLE IF NOT EXISTS`, indexes) for `interactions`, `actions`, `command_configs`, `server_connection` exactly as data-model.md, including unique `(interaction_id, kind)`, the non-blank `reply_text` check, the `server_connection.id = 1` check, the partial index on pending actions, the `received_at` index, and idempotent inserts of the default `command_configs` rows for `status` and `report` (the snapshot read in T032 expects them to exist).
- [ ] T021 [P] Write test `src/test/java/com/example/discordbot/persistence/SchemaTest.java` (uses T019): the script runs twice without error; a duplicate `(interaction_id, kind)`, a blank `reply_text`, a second `server_connection` row, and an orphan action are all rejected.
- [ ] T022 Create `src/main/java/com/example/discordbot/persistence/Database.java`: HikariCP from `AppConfig` with the research.md R8 settings; apply `schema.sql` in a background thread at start-up, retrying until it succeeds; expose `schemaReady()`; apply the 1 s statement limit to every statement through one shared helper so no store sets its own (Principle III). Write first `src/test/java/com/example/discordbot/persistence/DatabaseConfigTest.java`, asserting the pool settings (max 4, `minimumIdle` 0, `idleTimeout` 60 s, `maxLifetime` 10 min, `connectionTimeout` 1.5 s, no `keepaliveTime`) and that the helper sets the 1 s limit, so nothing can silently keep Neon awake or leave a statement unbounded (constitution Deployment and cost). Depends on T013, T018, T020.
- [ ] T023 [P] Write test `src/test/java/com/example/discordbot/security/SignatureVerifierTest.java` first (Principle I): a valid signature passes; tampered body, wrong key, malformed hex, missing or blank header, timestamp older than 15 s, and timestamp more than 5 s in the future all fail; a batch of 20 mixed bad inputs all fail (SC-002). Generate the key pair in the test.
- [ ] T024 Create `src/main/java/com/example/discordbot/security/SignatureVerifier.java`: wrap the 32-byte hex public key with the prefix `302a300506032b6570032100` and verify Ed25519 over `timestamp + raw body` using `java.security`; apply the freshness window from `Timing`. Makes T023 pass.
- [ ] T025 [P] Write test `src/test/java/com/example/discordbot/discord/InteractionParseTest.java`, then create `src/main/java/com/example/discordbot/discord/Interaction.java`: parse a command payload with Jackson (ignore unknown fields) into id, type, token, guild id, channel id, member id and name, command name, and the `text` option.
- [ ] T026 [P] Write test `src/test/java/com/example/discordbot/interactions/ResponsesTest.java`, then create `src/main/java/com/example/discordbot/interactions/Responses.java`: exact JSON for PONG, the private deferred acknowledgement (`type` 5, `flags` 64), and the private "Temporarily unavailable, please try again." reply (`type` 4, `flags` 64), per contracts/interactions-endpoint.md.
- [ ] T027 [P] Write test `src/test/java/com/example/discordbot/discord/DiscordClientTest.java` first, using a `com.sun.net.httpserver.HttpServer` stub (Principles II, III, IV): 2xx succeeds; 429 is retryable and honors `retry_after`; 5xx and timeouts are retryable; other 4xx are permanent; the first-three-attempts 404 rule for `reply`; every call has connect 3 s and request 5 s timeouts; bodies carry `allowed_mentions` with empty `parse` and content truncated to 2000 characters; `last_error` and thrown messages never contain the request URL, token, or body.
- [ ] T028 Create `src/main/java/com/example/discordbot/discord/DiscordResult.java` and `src/main/java/com/example/discordbot/discord/DiscordClient.java`: edit the deferred reply, post to a channel, and execute the mirror webhook, per contracts/discord-outbound.md; base address injectable for tests. Guild, channel, and command calls are added in US5. Makes T027 pass.

**Checkpoint**: Foundation ready - user story implementation can now begin.

---

## Phase 3: User Story 2 - Requests are authentic, handled once, and answered in time (Priority: P1)

**Goal**: The endpoint rejects forged and stale requests, answers PING, records each command once, and always acknowledges within Discord's window, or truthfully refuses. This is the front door that User Story 1's commands run through, so it comes first.

**Independent Test**: Send a forged request, a tampered one, a stale replay, PING, the same genuine command five times, and a command while the database is made slow and the Discord stub is blocked; check each outcome against US2's scenarios.

### Tests for User Story 2 (REQUIRED - Principles I, II, III) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**
>
> **Type-first step**: before writing T029-T031, create the six type declarations named in T032 (`NewAction`, `Snapshot`, `Plan`, `Planner`, `CommitPermit`, `Recorder`) as signatures only, so the tests compile and then fail on behavior, not on compilation. T032 completes them and adds `InteractionStore`, which implements `Recorder`.

- [ ] T029 [P] [US2] Write `src/test/java/com/example/discordbot/interactions/RecordGateTest.java` with a fake `Recorder`: deadline before commit means abandon, roll back, and return the fallback; commit already in flight at the deadline waits the grace: finishing within it returns Accepted, and the grace expiring first returns Unconfirmed; an Unconfirmed commit that later lands RECORDED calls the completion callback exactly once, and one that later fails or rolls back calls nothing; exactly one side wins under a racing loop; a refused command leaves no rows (research.md R2; FR-026, SC-012).
- [ ] T030 [P] [US2] Write `src/test/java/com/example/discordbot/interactions/InteractionHandlerTest.java` with a fake `Recorder` and planner: invalid or stale signature returns 401 with zero store or client calls; PING returns PONG; a valid command returns the deferred acknowledgement only after the record commits; a duplicate id returns the same acknowledgement and adds no actions; a store failure or slow store returns the "try again" reply and records nothing (FR-026); an Unconfirmed result returns the deferred acknowledgement and, when the late commit lands, starts the actions exactly once with no restart or polling; the acknowledgement returns while a stub Discord client is blocked (slow work deferred); other interaction types return 400 (contracts/interactions-endpoint.md).
- [ ] T031 [P] [US2] Write `src/test/java/com/example/discordbot/persistence/InteractionStoreTest.java` (Testcontainers): the same id delivered five times leaves one row and one set of actions (SC-003); a rolled-back transaction leaves nothing; the interaction token is stored and can be cleared.

### Implementation for User Story 2

- [ ] T032 [US2] Create `src/main/java/com/example/discordbot/persistence/NewAction.java`, `Snapshot.java`, `Plan.java`, `Planner.java`, `CommitPermit.java`, `Recorder.java`, and `InteractionStore.java` in the same package. `Planner.plan(interaction, snapshot)` is a pure function returning a `Plan` (outcome, priority flag, actions). `CommitPermit` has one method, `tryBeginCommit()`. `Recorder` is an interface with `record(interaction, planner, permit)` returning RECORDED or DUPLICATE, so the gate and its tests do not depend on the database. `InteractionStore` implements `Recorder`; its `record` runs one transaction: (1) one joined statement reading the command's settings and the server connection into a `Snapshot`; (2) call the planner; (3) insert the interaction, writing outcome, priority, `interaction_token`, and `token_expires_at`, with `ON CONFLICT (id) DO NOTHING`; (4) only if inserted, insert the actions in one batch; (5) call `permit.tryBeginCommit()` immediately before commit and roll back if it returns false. Statement limit 1 s, applied through the shared helper in `Database` (T022). Returns RECORDED or DUPLICATE. Also `clearToken(id)`. Makes T031 pass. Depends on T020, T022.
- [ ] T033 [US2] Create `src/main/java/com/example/discordbot/interactions/RecordGate.java`: implements `CommitPermit` with the PENDING, COMMITTING, ABANDONED compare-and-set flag (`tryBeginCommit` moves PENDING to COMMITTING); runs `recorder.record(interaction, planner, this)` (a `Recorder`) on a small executor; at the record deadline moves PENDING to ABANDONED, or if already COMMITTING waits the 0.4 s grace; returns Accepted, Duplicate, Refused, or Unconfirmed (the grace expired with the commit outcome still unknown; constitution Principle II exception). It takes a completion callback: if an Unconfirmed commit later finishes RECORDED the gate calls it exactly once with the interaction id, and if it finishes any other way (duplicate, failure, rollback) the gate does not call it. Makes T029 pass. Depends on T016, T032.
- [ ] T034 [US2] Create `src/main/java/com/example/discordbot/interactions/InteractionHandler.java` taking a `Planner` (from `persistence`) as a constructor argument: order per contracts/interactions-endpoint.md (freshness, verify, parse, route, record through the gate, respond): Accepted, Duplicate, and Unconfirmed all get the private deferred acknowledgement and Refused gets the "try again" notice; the handler passes the gate a callback that starts the interaction's actions if an Unconfirmed commit later lands (T047); log only id, command, outcome, reason code, timings (Principle IV). Makes T030 pass. Depends on T024, T025, T026, T033.
- [ ] T035 [US2] Create `src/main/java/com/example/discordbot/interactions/InteractionsServlet.java`: `@WebServlet("/interactions")`, POST only (405 otherwise), read at most 100 KB of raw bytes (413 beyond), pass the two headers and the bytes to the handler, write the JSON with `Content-Type: application/json`. Servlet holds no logic.
- [ ] T036 [US2] Update `src/main/java/com/example/discordbot/config/AppLifecycle.java`: build `AppConfig`, `Database`, `InteractionStore`, `RecordGate`, `DiscordClient`, and the handler; wire a `persistence.Planner` that gives every command the `unsupported` outcome with one `reply` action ("That command is not supported.") until User Story 1 replaces it. Depends on T034, T035.
- [ ] T037 [US2] Run `mvn -Dtest=RecordGateTest,InteractionHandlerTest,InteractionStoreTest,SignatureVerifierTest test` until green (tests are under `src/test/java/com/example/discordbot/`).
- [ ] T038 [US2] **[Maintainer]** Set `DISCORD_APPLICATION_ID`, `DISCORD_PUBLIC_KEY`, `DISCORD_BOT_TOKEN`, `MIRROR_WEBHOOK_URL`, `ADMIN_USERNAME`, `ADMIN_PASSWORD_HASH` on Render; deploy; save `https://<app>/interactions` as the Interactions Endpoint URL and confirm Discord accepts it (US2 scenario 3); send an unsigned and a wrongly signed POST with `curl` and confirm 401 with nothing recorded (US2 scenario 1, SC-002). `ADMIN_*` may be placeholders until US4.

**Checkpoint**: The endpoint is live and trustworthy. Discord accepts the address.

---

## Phase 4: User Story 1 - Members run slash commands and get a response (Priority: P1) 🎯 MVP

**Goal**: `/status` and `/report` are recorded, answered privately, posted to the chosen channel (`/report`), and mirrored, with the priority rule and the disabled, not-set-up, wrong-server, and unsupported outcomes.

**Independent Test**: In a test server run `/status`, `/report hello`, and `/report this is urgent`; check private replies, the mirror notifications, the recorded rows, and that only the last report is flagged (spec US1).

### Tests for User Story 1 (REQUIRED - Principles II, IV; spec FR-003) ⚠️

- [ ] T039 [P] [US1] Write `src/test/java/com/example/discordbot/interactions/CommandRulesTest.java`: the outcome table (not_configured, wrong_server, unsupported, disabled, handled) with the exact action sets from data-model.md; the priority rule on "urgent", "URGENT", "urgently" and absent, never for `/status`; composed texts and flag wording per contracts/discord-outbound.md; truncation to 2000 characters; untrusted text stays inside `content` only.
- [ ] T040 [P] [US1] Write `src/test/java/com/example/discordbot/persistence/StoresTest.java` (Testcontainers): default config rows for `status` and `report` are created; the connection row reads back or is absent; actions are created with the interaction and can be marked succeeded, failed, or attempted, and every update sets `updated_at`.
- [ ] T041 [P] [US1] Write `src/test/java/com/example/discordbot/jobs/ActionRunnerTest.java` with a stub client: a successful `reply` marks the action succeeded and clears the interaction token; `post` and `mirror` use the right destinations; a failed `mirror` never blocks or delays the `reply` (FR-017); the first `reply` attempt starts 500 ms after the acknowledgement; a failure stores a sanitized `last_error` with no URL or token.

### Implementation for User Story 1

- [ ] T042 [P] [US1] Create `src/main/java/com/example/discordbot/persistence/CommandConfigStore.java`: read a command's settings, used by the dashboard in US6 (the default `status` and `report` rows are inserted by `schema.sql`, T020; data-model.md).
- [ ] T043 [P] [US1] Create `src/main/java/com/example/discordbot/persistence/ServerConnectionStore.java`: read the single connection row (write comes in US5).
- [ ] T044 [P] [US1] Create `src/main/java/com/example/discordbot/persistence/ActionStore.java`: load an action with its interaction token and destination, and record an attempt result (succeeded, failed, attempts, `last_error`), setting `updated_at` to the current time on every update (the timing check in T098 reads it). Makes T040 pass.
- [ ] T045 [US1] Create `src/main/java/com/example/discordbot/interactions/CommandRules.java`: implements `persistence.Planner` as a pure function of the interaction and the `Snapshot` (no database access) - decide the outcome, apply the priority rule, compose payloads, and list actions. Makes T039 pass. Depends on T032.
- [ ] T046 [US1] Create `src/main/java/com/example/discordbot/jobs/ActionRunner.java`: run one attempt of an action through `DiscordClient` (`reply` uses the interaction token, `post` the connected channel with the bot token, `mirror` the webhook address), update the action, and clear the interaction token when the `reply` finishes or fails permanently. Makes T041 pass. Depends on T044.
- [ ] T047 [US1] Update `src/main/java/com/example/discordbot/interactions/InteractionHandler.java` and `src/main/java/com/example/discordbot/interactions/InteractionsServlet.java`: after the acknowledgement has been written, and never before, start the actions on a small executor - `reply` after 500 ms, `post` and `mirror` at once, independently. Put this behind one method that starts an interaction's actions by id (the runner loads them from the database). Call it after the acknowledgement for Accepted, and from the gate's completion callback for an Unconfirmed commit that later lands (T033, T034), never for one that fails; the same timings apply. Update `src/main/java/com/example/discordbot/config/AppLifecycle.java` to wire `CommandRules` and the runner in place of the temporary planner from T036.
- [ ] T048 [US1] Add an "Interim setup before the Connect page exists" section to `specs/001-slash-command-bot/quickstart.md`: register the two commands with a `PUT` to the guild commands address using the body from contracts/discord-outbound.md (token referenced as `$DISCORD_BOT_TOKEN`), and insert one `server_connection` row with id 1. Removed in T086.
- [ ] T049 [US1] Run `mvn -Dtest=CommandRulesTest,StoresTest,ActionRunnerTest test` until green (tests are under `src/test/java/com/example/discordbot/`).
- [ ] T050 [US1] **[Maintainer]** Follow the T048 section, then run the US1 rows of the quickstart validation table on the deployed service; also confirm the early-edit race and `allowed_mentions` behavior noted as unverified in research.md (R5, R13) and record the outcome there. Steps are in `specs/001-slash-command-bot/quickstart.md`.

**Checkpoint**: MVP - commands work end to end, privately, with the priority rule.

---

## Phase 5: User Story 3 - Nothing is lost when a downstream channel or the service hiccups (Priority: P2)

**Goal**: Failed replies, posts, and notifications are retried with the decided backoff, survive a restart, and end as visible permanent failures when they cannot succeed.

**Independent Test**: Make the second channel unreachable, run a command, restore it; separately restart the service after a command was accepted but before its notification was sent (spec US3).

### Tests for User Story 3 (REQUIRED - Principle II) ⚠️

- [ ] T051 [P] [US3] Write `src/test/java/com/example/discordbot/jobs/RetryPolicyTest.java`: delay sequence and cap for `mirror`/`post` and for `reply`, jitter within ±20%, 20-attempt limit, `retry_after` respected (the larger value wins), permanent on non-429 4xx, `reply` stops 30 s before token expiry with "follow-up window expired" (research.md R5).
- [ ] T052 [P] [US3] Write `src/test/java/com/example/discordbot/jobs/RetrySchedulerTest.java` with a fake clock and stub client: a mirror that fails twice then succeeds ends succeeded with attempts recorded (SC-004); exhausting the limit marks it failed with the last error; a new scheduler instance reading pending rows reschedules them (FR-016); an idle scheduler makes zero store queries (research.md R4).
- [ ] T053 [P] [US3] Write `src/test/java/com/example/discordbot/persistence/ActionRecoveryTest.java` (Testcontainers): the recovery query returns only `pending` actions and includes their interaction token expiry.

### Implementation for User Story 3

- [ ] T054 [US3] Create `src/main/java/com/example/discordbot/jobs/RetryPolicy.java`: pure functions for next delay, permanent versus retryable, and expiry, using `Timing`. Makes T051 pass.
- [ ] T055 [US3] Create `src/main/java/com/example/discordbot/jobs/RetryScheduler.java`: in-memory timers on a small scheduled executor (2 threads), schedule the next attempt after a retryable failure, mark `failed` at the limit or token expiry, and run one recovery scan at start-up. No periodic polling. Makes T052 pass.
- [ ] T056 [US3] Update `src/main/java/com/example/discordbot/jobs/ActionRunner.java` and `src/main/java/com/example/discordbot/persistence/ActionStore.java`: record `attempts`, `next_attempt_at`, and sanitized `last_error`, and set `updated_at` on every update; add the pending-actions recovery query (makes T053 pass); hand retryable failures to the scheduler. Update `src/main/java/com/example/discordbot/config/AppLifecycle.java` to start the recovery scan once the schema is ready.
- [ ] T057 [US3] Run `mvn -Dtest=RetryPolicyTest,RetrySchedulerTest,ActionRecoveryTest,ActionRunnerTest test` until green (tests are under `src/test/java/com/example/discordbot/`).
- [ ] T058 [US3] **[Maintainer]** Run the US3 rows of the quickstart validation table: unreachable mirror then restore, and a restart right after a command. Steps are in `specs/001-slash-command-bot/quickstart.md`.

**Checkpoint**: Stories 1, 2, and 3 work; nothing accepted is lost.

---

## Phase 6: User Story 4 - Admin signs in and watches activity live (Priority: P2)

**Goal**: A login-protected dashboard with a live log of commands and actions, safe against unauthenticated access, markup in member text, and cross-site requests.

**Independent Test**: Open the dashboard and the log API signed out, sign in with wrong then right credentials, run a command and watch it appear, submit markup as report text, sign out (spec US4).

### Tests for User Story 4 (REQUIRED - Principle IV; FR-018, FR-021, FR-024) ⚠️

- [ ] T059 [P] [US4] Create test support `src/test/java/com/example/discordbot/support/ServletFakes.java`: `java.lang.reflect.Proxy` fakes for request, response, and session (no mocking library).
- [ ] T060 [P] [US4] Write `src/test/java/com/example/discordbot/security/AdminAuthTest.java`: correct password verifies against a BCrypt hash; wrong password and wrong username produce the same generic failure.
- [ ] T061 [P] [US4] Write `src/test/java/com/example/discordbot/security/AdminAuthFilterTest.java` (uses T059): signed-out `/dashboard/*` redirects to `/login`; signed-out `/api/*` returns 401 JSON without redirect (SC-006); a POST under `/dashboard/*` or to `/logout` without a matching CSRF token returns 403 and reaches nothing; signed-in requests with a valid token pass.
- [ ] T062 [P] [US4] Write `src/test/java/com/example/discordbot/dashboard/LogViewTest.java`: JSON shape from contracts/dashboard-http.md; `overall` is derived correctly (complete, in_progress, failed); the output never contains an interaction token, webhook address, or credential; markup in `member` and `text` is preserved as data.
- [ ] T063 [P] [US4] Write `src/test/java/com/example/discordbot/dashboard/LiveLogScriptTest.java`: reads `src/main/webapp/static/js/live-log.js` and asserts it writes with `textContent` and never uses `innerHTML` (FR-021).

### Implementation for User Story 4

- [ ] T064 [US4] Add to `pom.xml`: `jakarta.servlet.jsp.jstl:jakarta.servlet.jsp.jstl-api:3.0.2`, `org.glassfish.web:jakarta.servlet.jsp.jstl:3.0.1`, `at.favre.lib:bcrypt:0.10.2` (research.md R14).
- [ ] T065 [P] [US4] Create `src/main/java/com/example/discordbot/security/AdminAuth.java`: verify username and BCrypt password against `AppConfig`; identical failure for either mistake. Makes T060 pass.
- [ ] T066 [P] [US4] Create `src/main/java/com/example/discordbot/security/CsrfTokens.java`: one token per session, constant-time comparison.
- [ ] T067 [US4] Create `src/main/java/com/example/discordbot/security/AdminAuthFilter.java`: annotated `@WebFilter(urlPatterns = {"/dashboard/*", "/api/*", "/logout"})` so no other file needs to register it; redirect or 401 as in the contract; enforces the CSRF token on every POST. Makes T061 pass. Depends on T065, T066.
- [ ] T068 [P] [US4] Create `src/main/java/com/example/discordbot/security/HashPassword.java`: a `main` that reads a password from standard input (not arguments) and prints a BCrypt hash (cost 10) for `ADMIN_PASSWORD_HASH`; document its use in `specs/001-slash-command-bot/quickstart.md`.
- [ ] T069 [US4] Create `src/main/java/com/example/discordbot/security/SessionConfig.java`: a `@WebListener` `ServletContextListener` that sets the session cookie `HttpOnly`, `Secure`, `SameSite=Lax` and the 30-minute inactivity timeout. It does not touch `AppLifecycle.java`.
- [ ] T070 [P] [US4] Create `src/main/java/com/example/discordbot/dashboard/LoginServlet.java` and `src/main/webapp/WEB-INF/views/login.jsp`: form, generic "Sign-in failed." message, session id replaced on success.
- [ ] T071 [P] [US4] Create `src/main/java/com/example/discordbot/dashboard/LogoutServlet.java`: `POST /logout` ends the session and redirects to `/login`.
- [ ] T072 [P] [US4] Add a "latest N interactions with their actions" query to `src/main/java/com/example/discordbot/persistence/InteractionStore.java` and create `src/main/java/com/example/discordbot/dashboard/LogView.java` that builds the contract JSON. Makes T062 pass.
- [ ] T073 [US4] Create `src/main/java/com/example/discordbot/dashboard/LogApiServlet.java`: `GET /api/log` with `limit` (default 50, max 200). Depends on T072.
- [ ] T074 [US4] Create `src/main/java/com/example/discordbot/dashboard/DashboardServlet.java` and `src/main/webapp/WEB-INF/views/dashboard.jsp`: page shell using `<c:out>`, a logout form with the CSRF token, and links to the config and connect pages.
- [ ] T075 [P] [US4] Create `src/main/webapp/static/js/live-log.js` and `src/main/webapp/static/css/app.css`: poll `/api/log` every 3 s, redraw with `textContent`, pause when the tab is hidden and after 10 minutes without input, send the browser to `/login` on 401 (research.md R9). Makes T063 pass.
- [ ] T076 [US4] Run `mvn -Dtest=AdminAuthTest,AdminAuthFilterTest,LogViewTest,LiveLogScriptTest test` until green (tests are under `src/test/java/com/example/discordbot/`).
- [ ] T077 [US4] **[Maintainer]** Generate the admin hash with T068, set the real `ADMIN_*` values on Render, and run the US4 rows of the quickstart validation table, including a new command appearing within 5 seconds (SC-005) and the markup check. Steps are in `specs/001-slash-command-bot/quickstart.md`.

**Checkpoint**: Stories 1-4 work; the admin can watch everything.

---

## Phase 7: User Story 5 - Admin connects the service to a Discord server (Priority: P2)

**Goal**: The admin adds the bot, picks a server and a channel, and the service proves it can post there and registers the commands.

**Independent Test**: As a signed-in admin connect a test server, pick a channel, run `/report`, and see the post land there; change the channel and see later posts move (spec US5).

**Depends on**: User Story 4 (sign-in, filter, dashboard shell).

### Tests for User Story 5 ⚠️

- [ ] T078 [P] [US5] Write `src/test/java/com/example/discordbot/discord/CommandDefinitionsTest.java`: JSON for `status` and for `report` (required string option `text`, `max_length` 1000, names and descriptions within Discord's limits).
- [ ] T079 [P] [US5] Write `src/test/java/com/example/discordbot/discord/DiscordClientConnectTest.java` with the stub server: list servers, list channels and keep only text channels (type 0), the test message, the bulk command registration body, and the `Authorization: Bot` header; errors carry no token.
- [ ] T080 [P] [US5] Write `src/test/java/com/example/discordbot/dashboard/ConnectServiceTest.java`: nothing is saved when the test message fails; a channel id not in the listed set is rejected; success saves the connection and registers the commands.
- [ ] T081 [P] [US5] Write `src/test/java/com/example/discordbot/persistence/ServerConnectionStoreTest.java` (Testcontainers): saving twice keeps a single row and updates it.

### Implementation for User Story 5

- [ ] T082 [P] [US5] Create `src/main/java/com/example/discordbot/discord/CommandDefinitions.java`. Makes T078 pass.
- [ ] T083 [US5] Extend `src/main/java/com/example/discordbot/discord/DiscordClient.java` with list guilds, list channels, test message, and register commands (bulk overwrite). Makes T079 pass.
- [ ] T084 [P] [US5] Extend `src/main/java/com/example/discordbot/persistence/ServerConnectionStore.java` with a save that keeps one row with id 1. Makes T081 pass.
- [ ] T085 [US5] Create `src/main/java/com/example/discordbot/dashboard/ConnectService.java`: verify by test message, then save, then register, in that order. Makes T080 pass. Depends on T083, T084.
- [ ] T086 [US5] Create `src/main/java/com/example/discordbot/dashboard/ConnectServlet.java` and `src/main/webapp/WEB-INF/views/connect.jsp`: current connection, the "Add the bot" invite link built from the application id with permissions 3072, Refresh to list servers, pick a server then a channel, CSRF token on the form, errors shown; remove the interim section from `specs/001-slash-command-bot/quickstart.md` (added in T048).
- [ ] T087 [US5] Run `mvn -Dtest=CommandDefinitionsTest,DiscordClientConnectTest,ConnectServiceTest,ServerConnectionStoreTest test` until green (tests are under `src/test/java/com/example/discordbot/`).
- [ ] T088 [US5] **[Maintainer]** Run the US5 rows of the quickstart validation table, and time a first-time connect against SC-008 (under 5 minutes). Steps are in `specs/001-slash-command-bot/quickstart.md`.

**Checkpoint**: Stories 1-5 work; no manual seeding is needed.

---

## Phase 8: User Story 6 - Admin configures command behavior (Priority: P3)

**Goal**: The admin views each command's settings, enables or disables it, and edits its reply text with no redeploy; the mirror address is shown only masked.

**Independent Test**: Disable `/report`, run it and see "currently unavailable"; edit the reply text and see it on the next run; save an empty text and see it refused (spec US6).

**Depends on**: User Story 4.

### Tests for User Story 6 (REQUIRED for the masked address - Principle IV) ⚠️

- [ ] T089 [P] [US6] Write `src/test/java/com/example/discordbot/dashboard/ConfigServiceTest.java`: empty or whitespace reply text is refused and the old value kept; an unknown command is refused; the last saved edit wins; a disabled command produces the `disabled` outcome on the very next command (SC-009).
- [ ] T090 [P] [US6] Write `src/test/java/com/example/discordbot/config/MaskedAddressTest.java`: the masked mirror address never reveals the full value and shows "not configured" when absent (FR-025).

### Implementation for User Story 6

- [ ] T091 [P] [US6] Extend `src/main/java/com/example/discordbot/persistence/CommandConfigStore.java` with an update of `enabled` and `reply_text`.
- [ ] T092 [P] [US6] Add a masked-address getter to `src/main/java/com/example/discordbot/config/AppConfig.java`. Makes T090 pass.
- [ ] T093 [US6] Create `src/main/java/com/example/discordbot/dashboard/ConfigService.java`, `src/main/java/com/example/discordbot/dashboard/ConfigServlet.java`, and `src/main/webapp/WEB-INF/views/config.jsp`: each command's enabled flag, reply text, and last update; the masked mirror address; validation errors shown with the old value kept; CSRF token on the form. Makes T089 pass.
- [ ] T094 [US6] Run `mvn -Dtest=ConfigServiceTest,MaskedAddressTest test` until green (tests are under `src/test/java/com/example/discordbot/`).
- [ ] T095 [US6] **[Maintainer]** Run the US6 rows of the quickstart validation table, including a POST without a token being refused (US6 scenario 5). Steps are in `specs/001-slash-command-bot/quickstart.md`.

**Checkpoint**: All six stories work independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Deliverables and end-to-end proof.

- [ ] T096 [P] Create `README.md` at the repository root: what the app does, how to run it locally, every environment variable, how and where it is deployed on Render, a "Testing it" section for reviewers (how to add the bot to a server with the invite link built from the application id and permissions 3072; the throwaway admin username, with its password supplied alongside the submission and never stored in the repository; the commands to try and what to expect in Discord, the second channel, and the dashboard), and the known limits from research.md R3 and R4 (constitution, Development Workflow).
- [ ] T097 [P] **[Maintainer]** Write `AI_NOTES.md` (about one page): tools and models used and how the work was split, two or three decisions you made yourself, the hardest bug or wrong turn the AI led you into (specific and honest), and what you would improve. Only you can write the real account.
- [ ] T098 Run the full `specs/001-slash-command-bot/quickstart.md` validation table on the deployed service and record pass or fail per row, including the timing row (SC-001): run 20 commands, 10 of them right after a 6-minute idle, and record each acknowledgement time from the per-interaction timing log lines (research.md R12) and each final reply time as the `reply` action's `updated_at` minus the interaction's `received_at`; pass means every acknowledgement (or refusal notice) within 3 s with no "did not respond" in Discord and every final reply within 10 s; write the numbers into `specs/001-slash-command-bot/research.md`; fix any defect in the task that owns it.
- [ ] T099 Secrets search (SC-007): search the repository, the pages and scripts the browser receives, and the Render logs for the bot token, the public key, and the mirror address; expect zero matches. Confirm `.env` is git-ignored and `.env.example` holds names only.
- [ ] T100 [P] Update `specs/001-slash-command-bot/research.md`: R1 results, and the outcomes of the "Remaining unknowns" (Testcontainers on Docker 28.4, INFO logging in the container, early-edit race, `allowed_mentions`, Render Singapore on the free plan).
- [ ] T101 **[Maintainer]** Confirm the submission list: public URL reachable, README, `.env.example`, test instructions with a throwaway admin login, the AI context files as used (`CLAUDE.md` and the constitution), and `AI_NOTES.md`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: starts immediately. T013 gates every task that relies on the database deadline: T016, T022, and Phase 3 onward.
- **Foundational (Phase 2)**: T015, T017-T021, and T023-T028 may run while the measurement (T012) runs; T016 and T022 wait for T013. Blocks all user stories.
- **User Stories**: sequential by dependency, see below.
- **Polish (Phase 9)**: after the desired stories are complete.

### User Story Dependencies

- **US2 (P1)** first: the front door; needs only Foundational. Independent test uses fakes for the planner.
- **US1 (P1)** needs US2's endpoint. Until US5 exists, its live test uses the interim setup from T048.
- **US3 (P2)** needs US1's actions and runner.
- **US4 (P2)** needs Foundational and US2's `InteractionStore` (T032), which T072 extends. It edits no file that US1 or US3 edit (US4 no longer touches `AppLifecycle.java`), so after T032 it can run in parallel with them. `AppLifecycle.java` itself is edited only by T036 (US2), T047 (US1), and T056 (US3), in that order.
- **US5 (P2)** and **US6 (P3)** need US4. US6 also reads US1's config store.

### Within Each User Story

- Tests for governed behavior are written first and must fail before implementation.
- Stores before services, services before servlets, servlets before pages.
- Run the story's related tests, then the manual validation, before starting the next story.

### Parallel Opportunities

- Setup: T002, T003, T004 together.
- Foundational: T017, T019, T020, T021, T023, T025, T026, T027 (different files).
- US2: T029, T030, T031 together.
- US1: T039, T040, T041 together; T042, T043, T044 together.
- US4 can proceed alongside US1-US3 with a second worker; its tests T059-T063 run together.

---

## Parallel Example: User Story 2

```text
# Write all three governed test files together, then watch them fail:
Task: "RecordGateTest in src/test/java/com/example/discordbot/interactions/RecordGateTest.java"
Task: "InteractionHandlerTest in src/test/java/com/example/discordbot/interactions/InteractionHandlerTest.java"
Task: "InteractionStoreTest in src/test/java/com/example/discordbot/persistence/InteractionStoreTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 2, then User Story 1)

1. Phase 1: skeleton live, keep-warm running, R1 measured (T001-T014).
2. Phase 2: Foundational (T015-T028).
3. Phase 3: US2 - the trustworthy front door (T029-T038).
4. Phase 4: US1 - commands end to end (T039-T050).
5. **STOP and VALIDATE**: run `/status` and `/report` in the test server; check the log rows and the second channel.
6. Deploy and demo. This is the smallest version that satisfies the brief's core requirements except the dashboard.

### Incremental Delivery

1. Add US3 (durable retries), then US4 (dashboard), then US5 (connect), then US6 (configure).
2. Each story is validated by its own tests and its quickstart rows before the next begins.
3. Finish with Phase 9 (README, `AI_NOTES.md`, full validation, secrets search).

---

## Notes

- **[Maintainer]** tasks are manual: Render, Neon, UptimeRobot, the Discord portal, and every Git action.
- [P] tasks = different files, no dependencies.
- [Story] label maps the task to a user story for traceability.
- Verify tests fail before implementing (governed behavior).
- Commit after each task or logical group: done by you, never by Claude.
- Avoid: vague tasks, same-file conflicts between [P] tasks, cross-story dependencies not listed above.
