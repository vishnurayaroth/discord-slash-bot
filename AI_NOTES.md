# AI notes

> **DRAFT, not yet the maintainer's account.** Claude prepared this from the record of the session so
> that the facts are right. The maintainer must read it, correct it, and rewrite the parts marked
> **(your words)** in their own voice before submitting. Every event below really happened in this
> project; nothing was invented.

## Tools and how the work was split

- **Claude Code** with **Claude Sonnet 5** (`claude-sonnet-5`) did the drafting and coding.
- **Spec Kit** (v0.8.11) provided the spec-driven flow: constitution, specify, clarify, plan, tasks,
  analyze, implement. Its files are in `.specify/` and `.claude/skills/`.
- **Four parallel research subagents** looked up current facts (Neon, Render and UptimeRobot, Discord,
  Java library versions) and reported with sources; their findings are in `specs/001-slash-command-bot/research.md`.
- **Split.** The maintainer chose the stack, made the decisions below, approved each Spec Kit artifact before
  the next step, ran the git commands for the first push, and owns every step that needs an external account
  (Render, Neon, Discord, UptimeRobot). Claude wrote the constitution, spec, plan, tasks, code and tests, and
  ran the local Docker checks. `specs/001-slash-command-bot/skipped-tests.md` lists what was verified locally and
  what still needs the live services.
- **Context files used, exactly as committed:** `CLAUDE.md` and `.specify/memory/constitution.md`.

## Decisions the maintainer made **(your words)**

1. **Stack: Java, JSP and JavaScript** instead of the usual Node/TypeScript route, on plain Servlets with
   Tomcat. The maintainer asked whether Spring Boot was used and whether adding it would help; the answer was
   that it would trim dashboard boilerplate but not the hard parts, and would cost cold-start time and memory on a
   512 MB, 0.1 CPU free host, so the servlet stack stayed.
2. **"Option 1" for record-before-acknowledge.** The service records a command before it acknowledges it, and
   refuses ("Temporarily unavailable, please try again.") when it cannot record in time. The maintainer chose this
   over amending the constitution so the acknowledgement could come first, accepting that a cold database may
   occasionally refuse a command in exchange for "acknowledged means recorded".
3. **Behavior choices in `/speckit-clarify`:** the "simple rule" is one fixed rule (a report containing "urgent"
   is flagged high priority), every command is acknowledged immediately and then answered, and replies are
   private to the member who ran the command.

## The hardest bug or wrong turn the AI led into **(your words)**

Pick the one you consider hardest and write it in your own voice. These three are real; the facts are exact.

**Candidate A: a design flaw the AI wrote into its own plan, caught before any code existed.**
The plan let a command be acknowledged even if its database commit was still in flight at the deadline, but gave
no way to start that command's work if the commit later landed (retries are timers, and the design deliberately
never polls the database). A member would have stayed on "thinking..." until the next restart. The second
`/speckit-analyze` pass found it (finding C3); the fix was a third gate result and a completion callback,
tested with a race test. The same analysis passes also found that the constitution's wording ("record before
any reply") contradicted the "try again" reply the plan itself required (findings D1 and D5), which led to two
small constitution amendments.

**Candidate B: an assumption in the research that a test disproved.**
The research note said to "leave HikariCP's `keepaliveTime` unset (off)" so the pool would not keep Neon awake.
The library's actual default is 2 minutes. `DatabaseConfigTest` failed with `expected: <0> but was: <120000>`;
the setting is now explicit and the research was corrected.

**Candidate C: a bug no unit test could catch.**
All unit tests passed, yet the real container failed to start with `No suitable driver`: under Tomcat's class
loader the PostgreSQL driver is not found unless it is named. It surfaced only when the real Docker image was
run against a local Postgres; the pool configuration now names the driver and a test asserts it.

## What I would improve with more time **(your words)**

- Run the live checks that are still open (`specs/001-slash-command-bot/skipped-tests.md`) and measure the cold
  database latency to confirm or change the 2.5 s record deadline.
- Make refused commands visible in the dashboard (today they appear only in logs) and add a manual "retry"
  for permanently failed notifications.
- Add continuous integration that runs the tests on every push, and an automated test that the JSP pages escape
  member text (today only the script has an automated guard).
- The stretch goals from the brief: interactive buttons, a modal for `/report`, an AI triage step, and
  multi-server support with a rule-builder.
