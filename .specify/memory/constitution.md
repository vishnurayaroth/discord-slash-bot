<!--
Sync Impact Report
Version change: 1.0.1 -> 1.0.2 (PATCH: makes explicit an exception the design already had,
  documented in specs/001-slash-command-bot/research.md R2 and in the spec Assumptions "A
  write still finishing at the deadline"; found by the second /speckit-analyze pass, D5)
Modified principles: II (clarified, not renamed). One bullet added after the refusal
  bullet: a commit already in flight at the deadline is awaited for a short grace period
  and, if the outcome is still unknown, acknowledged rather than refused.
Added sections: none
Removed sections: none
Templates requiring updates:
  ✅ .specify/templates/plan-template.md - no change
  ✅ .specify/templates/spec-template.md - no change
  ✅ .specify/templates/tasks-template.md - no change
  ✅ specs/001-slash-command-bot/spec.md - no change; its Assumptions state the behavior
  ✅ specs/001-slash-command-bot/plan.md - two sentences naming the constitution version
     now say v1.0.2
  ⚠ CLAUDE.md - still open: its Identity line names "MuzPrinterRelay"
Follow-up TODOs: none new
-->

<!--
Sync Impact Report (earlier entry)
Version change: 1.0.0 -> 1.0.1 (PATCH: clarification of what Principle II already meant;
  the fallback behavior comes from planning research R3 and the maintainer's Option 1 decision)
Modified principles: II (clarified, not renamed). Its first bullet is now three bullets that
  define an "accepted command", the refusal of a command that cannot be recorded in time, and
  the exclusion of PING/PONG answers and 401 rejections from recording.
Added sections: none
Removed sections: none
Templates requiring updates:
  ✅ .specify/templates/plan-template.md - no change; its Constitution Check reads this file
  ✅ .specify/templates/spec-template.md - no change
  ✅ .specify/templates/tasks-template.md - no change; its test wording already covers I-IV
  ⚠ specs/001-slash-command-bot/spec.md - needs matching changes (FR-004, FR-013, new
     FR-026, SC-001, new SC-012); approved by the maintainer, applied in the same pass
  ✅ specs/001-slash-command-bot/plan.md - two sentences that said the constitution was
     unchanged at v1.0.0 now say v1.0.1
  ⚠ CLAUDE.md - still open from 1.0.0: its Identity line names "MuzPrinterRelay"
Follow-up TODOs: none new
-->

<!--
Sync Impact Report (earlier entry)
Version change: (unfilled template) -> 1.0.0
Modified principles: none renamed; all placeholders filled for the first time
Added sections: Core Principles I-VI; Stack, Architecture & Deployment Constraints;
  Development Workflow & Quality Gates; Governance
Removed sections: none
Templates requiring updates:
  ✅ .specify/templates/tasks-template.md - "tests are OPTIONAL" replaced: tests are now
     REQUIRED for behavior governed by Principles I-IV, optional otherwise
  ✅ .specify/templates/plan-template.md - no change; its Constitution Check gate reads this file
  ✅ .specify/templates/spec-template.md - no change; no mandatory section added or removed
  ⚠ CLAUDE.md - its Identity line names "MuzPrinterRelay", not this project. Left untouched;
     the maintainer decides whether to correct it.
Follow-up TODOs (values deliberately deferred to plan.md, not invented here):
  - Replay freshness window length (Principle I)
  - Retry count and backoff policy (Principle II)
  - Per-call outbound timeout values (Principle III)
-->

# Discord Slash-Command Bot Constitution

## Core Principles

### I. Verified Requests Only (NON-NEGOTIABLE)

Every request to the interactions endpoint MUST be authenticated before anything else happens.

- The Ed25519 signature (`X-Signature-Ed25519`) MUST be verified over the timestamp header
  (`X-Signature-Timestamp`) followed by the raw request body bytes, using the application's
  public key. Verification MUST run before the body is parsed or any state is read or changed.
- A missing, malformed, or invalid signature MUST return 401 and cause no side effects.
- A request whose timestamp is outside a bounded freshness window MUST be rejected as a replay.
  The window length is set in the plan.
- A valid PING (interaction type 1) MUST be answered with a PONG (type 1).

Rationale: Discord refuses the endpoint otherwise, and the service must not be foolable by
forged or replayed requests.

### II. Persist First, No Silent Loss, No Duplicate Processing

- Each accepted command MUST be recorded in the database, keyed by its Discord interaction
  id under a uniqueness constraint, before any reply, mirror post, or AI call.
- A command that cannot be recorded within the time allowed for responding is refused, not
  accepted. It gets a private refusal reply and causes no other effect: no record, no post,
  no mirror, no AI call. A refused command MUST NOT be recorded or processed later, even if
  the underlying write finishes late.
- If a commit is already in flight when the time allowed runs out, the service waits a
  short grace period. If the commit completes, the command is accepted. If the outcome is
  still unknown, the command is acknowledged rather than refused, because a refusal could
  be followed by a late record and a duplicate command. This is the only exception to the
  two rules above.
- Answers to Discord's verification PING and rejections of unverified requests (401)
  involve no command and are not recorded.
- A repeated delivery of an interaction id MUST NOT cause the command to be processed twice.
- A failure of a downstream service (mirror channel, AI provider, Discord API) or a brief
  outage of this service MUST NOT lose an interaction. Its state and last error are stored
  and delivery is retried until it succeeds or reaches a recorded terminal failure.
  Retry count and backoff are set in the plan.
- Failures and retries MUST be visible in the admin dashboard.
- Tradeoff, stated openly: mirror delivery is at-least-once. Discord webhooks offer no
  idempotency key, so a crash between sending a mirror post and recording it can produce one
  repeat post on retry. Command processing itself stays once-only.

Rationale: an unattended service must not drop work or repeat it because of a retry.

### III. Respect Discord's Response Window

- The endpoint MUST answer within Discord's roughly 3-second window. Work that can exceed it
  (AI call, mirror retries, slow queries) MUST be deferred: acknowledge with a deferred
  response, finish in the background, then follow up on the interaction.
- Every outbound call MUST have an explicit timeout so a slow dependency cannot hold a
  request past the window. Timeout values are set in the plan.
- Free-tier cold starts are a known risk. The keep-warm mitigation is part of deployment,
  not optional.

Rationale: a late reply shows the user "The application did not respond" and is lost.

### IV. Secrets Never Leak (NON-NEGOTIABLE)

- The bot token, application public key, mirror webhook URLs, database credentials, AI API
  key, and admin password hash MUST come from environment variables only.
- These values, and Discord interaction tokens, MUST NOT appear in the repository, in JSP or
  JavaScript, in any client-visible output, in logs, in error pages, or in API responses. The
  dashboard MAY show a masked form of a URL, never the full value.
- `.env` MUST be git-ignored. `.env.example` lists variable names only, with no real values.

Rationale: a leaked token or webhook lets anyone act as the bot or post to the channel.

### V. Tested Before Done

- Before touching files, state the task's success criteria in testable terms, taken from the
  spec's acceptance scenarios.
- A task is done only when its related tests pass, run with `mvn -Dtest=<Class> test`.
  The full suite runs only when asked. No passing test output, no "done".
- Behavior governed by Principles I-IV MUST have automated tests that include the unhappy
  paths: forged signature, tampered body, stale timestamp, duplicate delivery, downstream
  service unavailable, and slow work deferred.

Rationale: correctness comes first, and the unhappy paths are where this service earns trust.

### VI. Simplicity and Scope

- Write the minimum code that satisfies the spec. Nothing speculative; YAGNI is a hard rule.
- Touch only what the task requires. Do not refactor, rename, or reorganize code you did not
  write, and clean up only your own mess.
- Prefer the JDK standard library over a new dependency. Every dependency MUST have a
  stated need.
- When the spec is ambiguous, update the spec. Do not invent requirements.
- When priorities conflict, the order is correctness, then simplicity, then performance.

Rationale: a small, readable codebase is easier to verify and to trust.

## Stack, Architecture & Deployment Constraints

**Stack (fixed unless amended):**

- Java 17. Its built-in Ed25519 is used for signature verification; no crypto library added.
- Maven, packaged as a WAR named `ROOT.war`, running on Tomcat 10.1 (Jakarta Servlet 6.0 and
  JSP 3.1). Code MUST use `jakarta.*` packages, never `javax.*`.
- Interactions are HTTP only. No gateway or websocket connection, and no Discord bot
  framework (for example JDA).
- PostgreSQL (Neon) over JDBC with a connection pool and SSL required.
- Admin dashboard: server-rendered JSP with JavaScript for dynamic behavior. Adding a
  frontend framework or build step requires a Complexity Tracking justification.
- Mirror notifications go to a second channel through a Discord channel webhook.

**Architecture:** one package per responsibility under `com.example.discordbot`:
`config` (the only place environment variables are read; fails fast when a required value is
missing), `security` (signature verification, login filter, password checks), `discord`
(Discord API client and payload models), `interactions` (endpoint, router, one handler per
command), `persistence` (all SQL), `dashboard` (pages and JSON API), `jobs` (retry worker).
Servlets stay thin. JSPs live under `WEB-INF/views` and are reached only through servlets.
Static files live under `static/`.

**Dashboard security:** every dashboard page and API sits behind the admin login. The admin
password is stored only as a BCrypt hash. Untrusted text (command input, user names) MUST be
escaped on output. State-changing requests MUST be protected against CSRF.

**Deployment and cost:** a Docker image running on Render's free tier. Every service used
(Discord, Neon, Render, the keep-warm monitor, and the AI provider if used) MUST be on a free
tier with no credit card; if a service asks for a card, switch. Provider free-tier limits
(idle sleep, memory, database suspend) MUST be checked against the provider's current terms
and the app configured to fit them, including a health endpoint for the keep-warm monitor.

## Development Workflow & Quality Gates

- Spec-driven: read the relevant `specs/NNN-*/spec.md` and `plan.md` before editing. If no
  spec exists, create one with `/speckit-specify` before coding.
- The plan's Constitution Check MUST pass before research and again after design. A deviation
  needs a Complexity Tracking entry with justification. Principles I and IV admit no
  deviation.
- Commits: work is never committed unless explicitly asked. The maintainer commits in small,
  logical, clearly described commits, because a clear history is a deliverable.
- Before submission, all of these MUST exist: a deployed, reachable public URL; a README
  covering what the app does, how to run it locally, its environment variables, and how it was
  deployed; `.env.example`; test instructions with a throwaway admin login; the AI context
  files exactly as used (`CLAUDE.md` and this constitution); and `AI_NOTES.md`, kept up to
  date while work proceeds, including the hardest bug or wrong turn.
- Definition of done is Principle V.

## Governance

This constitution governs what is built: stack, architecture, security, and testing rules.
The behaviour rules in `CLAUDE.md` govern how work is done. If the two appear to conflict,
raise the conflict; do not guess.

- **Amendment:** change this file only through `/speckit-constitution`, with the reason
  stated, the version bumped, the Sync Impact Report updated, and dependent templates checked.
  The maintainer approves every amendment.
- **Versioning (semantic):** MAJOR for removing or incompatibly redefining a principle or
  constraint; MINOR for adding a principle or section or materially expanding guidance; PATCH
  for clarifications and wording fixes.
- **Compliance:** every plan includes a Constitution Check, and every change, including
  AI-authored ones, is reviewed against this file. Unjustified complexity is rejected.

**Version**: 1.0.2 | **Ratified**: 2026-09-27 | **Last Amended**: 2026-09-27
