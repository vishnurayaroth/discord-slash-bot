# Implementation Plan: Discord Slash-Command Bot with Admin Dashboard

**Branch**: `001-slash-command-bot` | **Date**: 2026-09-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-slash-command-bot/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

A small Java web service that receives Discord slash commands over HTTP, proves each request is
genuine, records it, and answers in Discord. It then posts to the admin-chosen channel and mirrors
a notification to a second channel, while an admin dashboard shows a live log and lets the admin
connect a server and edit command settings.

Technical approach, all inside the stack fixed by the constitution:

- **Accept, then acknowledge (Option 1; Principle II clarified in v1.0.2, intent unchanged).** Verify the
  signature, then commit the interaction and its actions in one short transaction with a hard
  2.5 s deadline and a commit gate, and only then send Discord a private deferred acknowledgement.
  If the record cannot be committed in time, reply privately "Temporarily unavailable, please try
  again." and accept nothing.
- **Finish afterwards, durably.** The reply, channel post, and notification run after the
  acknowledgement. Each is a stored action with in-memory retry timers and a start-up recovery
  scan, so accepted work survives a restart and no code polls the database.
- **Stay inside the free tiers.** No database keep-warm (it would exhaust Neon's monthly compute
  allowance); the app is kept awake by a database-free `/health` ping; the pool never refills itself.

Research and evidence: [research.md](research.md). Data: [data-model.md](data-model.md).
Interfaces: [contracts/](contracts/). Run and verify: [quickstart.md](quickstart.md).

## Technical Context

**Language/Version**: Java 17 (OpenJDK 17.0.15 on the development machine), Jakarta Servlet 6.0 and
JSP 3.1 on Tomcat 10.1, built as a Maven WAR named `ROOT.war`

**Primary Dependencies**: `jakarta.servlet-api` 6.0.0 (provided); JSTL API 3.0.2 and implementation
3.0.1; `jackson-databind` 2.22.3; `postgresql` 42.7.13; `HikariCP` 7.1.0; `at.favre.lib:bcrypt`
0.10.2. Everything else uses the JDK (`java.security` Ed25519, `java.net.http`, `System.Logger`,
scheduled executors). Each dependency's need is in `research.md` R14.

**Storage**: PostgreSQL on Neon, direct endpoint with SSL and channel binding; four tables
(`interactions`, `actions`, `command_configs`, `server_connection`); schema applied by one
idempotent script at start-up

**Testing**: JUnit Jupiter 6.1.x through Maven Surefire; Testcontainers (test scope) for the
persistence tests; a JDK `HttpServer` stub for Discord. Run one class at a time with
`mvn -Dtest=<Class> test`.

**Target Platform**: Docker image on Render's free web service (512 MB RAM, 0.1 CPU, spins down
after 15 idle minutes), public HTTPS; Neon free plan for the database

**Project Type**: web-service, single Maven module, with a server-rendered admin dashboard

**Performance Goals**: acknowledge every command within Discord's 3 s (record step at most 2.5 s,
provisional until measured); final private reply within 10 s; new log entries visible within 5 s

**Constraints**: every service on a free tier with no credit card; Neon compute allowance 100
CU-hours per month with a fixed 5-minute suspend; Render 750 workspace hours per month; no secret
ever in the repo, browser output, or logs; the constitution's stack and package layout

**Scale/Scope**: one server, one admin, a few commands per second at most; four tables; about
seven servlets and four JSPs

### Decisions the spec left to planning

| Item (spec reference) | Decision | Where |
|---|---|---|
| Replay freshness window (FR-010) | At most 15 s old, at most 5 s in the future | R6 |
| Retry count and backoff (FR-015) | `mirror`/`post`: 20 attempts, 10 s doubling to 300 s cap, ±20% jitter. `reply`: 2 s doubling to 120 s cap until 30 s before the token expires. Permanent on non-429 4xx. | R5 |
| Outbound call timeouts (Principle III) | Connect 3 s, request 5 s for every Discord call | R7 |
| Database timeout on the request path (your requirement 2) | 2.5 s total deadline from arrival, connection wait 1.5 s, statement limit 1 s, commit gate, 0.4 s grace. **Provisional until R1.** | R2 |
| Log freshness and recovery targets (SC-004, SC-005) | Poll every 3 s; retry cap 300 s with jitter meets the 10-minute recovery target | R5, R9 |

### Your four requirements, and where each is met

| # | Requirement | Section |
|---|---|---|
| 1 | Early measurement of cold-database latency, decide the timeout on evidence | `research.md` R1 (protocol, decision rule; result still to be filled in) |
| 2 | Short explicit database timeout on the request path | R2 |
| 3 | Truthful fallback, nothing accepted or processed | R3, `contracts/interactions-endpoint.md` |
| 4 | Keep-warm strategy and whether it touches the database, weighed against Neon's allowance | R4 (no database keep-warm; evidence and three traps) |

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

The constitution is v1.0.2, with two PATCH clarifications of Principle II (a command refused for not being recorded in time is not an accepted command; a commit already in flight at the deadline is acknowledged, never refused). Option 1 keeps Principle II's intent unchanged.

| Principle or constraint | Before research | After design | How the design satisfies it |
|---|---|---|---|
| I. Verified Requests Only | Pass | **Pass** | Raw-body Ed25519 check before any parsing or state access; 401 with no side effects; 15 s freshness; PING answered. Contract: `interactions-endpoint.md` steps 1-4. |
| II. Persist First, No Silent Loss, No Duplicate Processing | Pass, one open risk (time budget) | **Pass** | Interaction and actions commit in one transaction before the acknowledgement; unique interaction id; stored actions with retries and start-up recovery; failures shown in the log. The commit gate closes the "refused but later committed" duplicate risk (R2). Mirror stays at-least-once, as the constitution states. |
| III. Respect Discord's Response Window | Pass, timeout values open | **Pass** | Always defer; explicit 2.5 s record deadline; every outbound call has a 3 s / 5 s timeout; keep-warm ping for cold starts. |
| IV. Secrets Never Leak | Pass | **Pass** | Environment variables only, read in one class; interaction token stored but never logged or returned and cleared after use; masked mirror address; `last_error` and logs built from status codes only; outbound messages carry no mentions. |
| V. Tested Before Done | Pass | **Pass** | Test list covers every unhappy path in the principle (`research.md` R15). |
| VI. Simplicity and Scope | Pass | **Pass** | JDK first: no Ed25519, HTTP, logging, migration, or mocking library. Every dependency has a stated need (R14). No worker polling, no cursor, no manual retry, no SSE. |
| Stack, architecture, dashboard security, deployment and cost | Pass | **Pass** | Java 17, Tomcat 10.1 with `jakarta.*`, HTTP only, Neon over JDBC with a pool, JSP + JS, Docker on Render free; package layout as the constitution lists; BCrypt, escaping, and CSRF tokens. |

**Gate result: pass before research and after design. No violations, so the Complexity Tracking
table is empty.**

## Project Structure

### Documentation (this feature)

```text
specs/001-slash-command-bot/
├── spec.md              # Feature specification (clarified)
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output: verified facts, decisions, measurement protocol
├── data-model.md        # Phase 1 output: four tables and state transitions
├── quickstart.md        # Phase 1 output: build, run, and validation checks
├── contracts/           # Phase 1 output
│   ├── interactions-endpoint.md
│   ├── dashboard-http.md
│   └── discord-outbound.md
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

The Maven module and the seven packages already exist as empty placeholders. Files below are what
the tasks will add; file names are indicative.

```text
discord-bot/
├── pom.xml                                   # update plugin versions and add dependencies
├── Dockerfile                                # multi-stage build to ROOT.war on Tomcat 10.1
├── .env.example                              # variable names only
├── src/main/java/com/example/discordbot/
│   ├── config/
│   │   ├── AppConfig.java                    # reads the environment once, fails fast
│   │   ├── Timing.java                       # freshness, deadlines, timeouts, retry parameters
│   │   └── AppLifecycle.java                 # wiring, schema init, start-up recovery
│   ├── security/
│   │   ├── SignatureVerifier.java
│   │   ├── AdminAuth.java                    # BCrypt check
│   │   ├── AdminAuthFilter.java
│   │   └── CsrfTokens.java
│   ├── discord/
│   │   ├── DiscordClient.java                # outbound calls (contracts/discord-outbound.md)
│   │   ├── DiscordResult.java                # success / retryable / permanent
│   │   ├── Interaction.java                  # parsed command payload
│   │   └── CommandDefinitions.java
│   ├── interactions/
│   │   ├── InteractionsServlet.java          # thin: bytes and headers in, response out
│   │   ├── InteractionHandler.java           # verify, route, record, respond
│   │   ├── RecordGate.java                   # PENDING / COMMITTING / ABANDONED
│   │   ├── CommandRules.java                 # outcome, priority rule, message text
│   │   └── Responses.java                    # PONG, deferred ack, "try again"
│   ├── persistence/
│   │   ├── Database.java                     # pool, lazy schema init
│   │   ├── InteractionStore.java
│   │   ├── ActionStore.java
│   │   ├── CommandConfigStore.java
│   │   └── ServerConnectionStore.java
│   ├── jobs/
│   │   ├── ActionRunner.java                 # one attempt, then update the action
│   │   ├── RetryScheduler.java               # in-memory timers, recovery scan
│   │   └── RetryPolicy.java
│   └── dashboard/
│       ├── HealthServlet.java
│       ├── LoginServlet.java
│       ├── LogoutServlet.java
│       ├── DashboardServlet.java
│       ├── LogApiServlet.java
│       ├── ConfigServlet.java
│       └── ConnectServlet.java
├── src/main/resources/db/schema.sql          # idempotent; the empty db/migration folder stays unused
├── src/main/webapp/
│   ├── WEB-INF/views/                        # login.jsp, dashboard.jsp, config.jsp, connect.jsp
│   └── static/
│       ├── js/live-log.js
│       └── css/app.css
└── src/test/java/com/example/discordbot/     # one test class per unit above that has logic
```

**Structure Decision**: a single Maven module using the constitution's seven-package layout. Logic
sits in plain classes (`InteractionHandler`, `CommandRules`, `RetryPolicy`, `RecordGate`) so tests
need no servlet container; servlets only adapt bytes, headers, and forms. The empty
`db/migration` placeholder from the skeleton is left alone rather than removed, since no migration
tool is used.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations. Nothing to justify.
