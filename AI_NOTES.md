# AI notes

## Tools and how I split the work

I used **Claude Code**, running on **Claude Sonnet 5**, for essentially all the drafting and coding. I
worked through **Spec Kit**'s flow end to end: constitution, specify, clarify, plan, tasks, analyze, then
implement. Claude also ran four research subagents in parallel early on to check current facts about Neon,
Render, UptimeRobot, Discord's API, and Java library versions against their official docs, since I didn't
want the plan built on stale or remembered assumptions; those findings, with sources, are in
`specs/001-slash-command-bot/research.md`.

Roughly how it split: I made the calls that mattered — the stack, the "Option 1" concurrency decision below,
and every clarification Spec Kit asked for — and approved each artifact (constitution, spec, plan, tasks)
before moving to the next step. Claude wrote essentially all the code and tests, ran the local Docker
verification for each phase, and made the phase-by-phase commits once I told it to implement everything and
commit as it went. When it came time to actually deploy, I was the one clicking through Render and Neon's
dashboards and pasting back whatever failed; Claude diagnosed each failure from the logs I gave it and wrote
the fixes. `specs/001-slash-command-bot/skipped-tests.md` has the full record of what got verified locally
versus what needed the live services.

Context files used, exactly as committed: `CLAUDE.md` and `.specify/memory/constitution.md`.

## Decisions I made myself

1. **Kept the plain servlet stack instead of adding Spring.** Partway through I asked whether Spring Boot
   would make this easier. The answer was that it would trim some dashboard boilerplate but not the actually
   hard parts (signature verification, the record-before-acknowledge logic, retries), and it would cost
   startup time and memory on a free host with 512 MB and 0.1 CPU — exactly the resource I was most worried
   about. I decided it wasn't worth it and stayed with Servlets and JSP on Tomcat.
2. **"Option 1" for the record-before-acknowledge ordering.** The constitution's rule is that a command is
   recorded in the database before anything else happens. The alternative was to acknowledge Discord first
   and record afterward, which would be safer against Discord's 3-second timeout but weaker against losing
   work. I chose to keep recording first and accept that a cold, sleeping database might occasionally force
   the service to refuse a command with "try again" rather than risk ever losing one silently.
3. **The three behavior calls in `/speckit-clarify`.** The brief's "applies a simple rule" was underspecified,
   so I decided it means one fixed rule: a `/report` containing the word "urgent" gets flagged high priority,
   nothing more elaborate. I also decided every command should be acknowledged immediately rather than only
   when work runs long, and that replies should be private to whoever ran the command rather than posted
   publicly in the channel.

## The hardest wrong turn: three failed deploys, one of which leaked a real password

The hardest part of the whole project wasn't the interaction-handling logic — the tests caught almost
everything there before I ever touched Render. It was getting the very first deployment to actually go live.

**First failure.** The deploy just hung and then timed out, with Tomcat logging
`Invalid shutdown command [HEAD / HTTP/1.1] received`, over and over. The Dockerfile Claude had written used
the stock Tomcat config, which leaves an internal "shutdown port" (8005) listening on every network
interface, not just inside the container. Render's own deploy process scans the container for an open port
and found that one first, sent it a plain HTTP request, and Tomcat correctly refused it — but that refusal
kept happening for over a minute until Render gave up. The fix was to disable that port entirely, which is
apparently standard practice for Tomcat in a container and something neither of us had thought about
up front.

**Second failure.** With that fixed, the deploy still timed out, but this time Render told us exactly why:
it was checking `port 10000`, not port 8080, even though I'd set `PORT=8080` in Render's dashboard like the
docs said to. Setting that variable by hand just didn't change what Render's health check actually targeted.
The real fix was to stop hardcoding a port at all and have the container read whatever `$PORT` value Render
actually gives it at startup, and configure Tomcat to that.

**Third failure, and the part I'm least proud of.** Once the container was finally binding to the right port,
it still failed — but silently, with Tomcat's log only saying "one or more listeners failed to start, see
the container log file," and that file doesn't exist anywhere I can reach on Render. Claude added logging so
the app would print its own real startup exception to the console instead of hiding it in a file. That's
what finally showed the actual problem: I had pasted Neon's own connection string — the one with the
username and password baked right into it — into `DATABASE_URL`, instead of converting it to the separate
JDBC-URL-plus-username-plus-password form the app expected. The Postgres driver rejected the malformed URL,
and its error message echoed the whole rejected string back into the log, including the password. That
password was now sitting in plaintext in a chat log. I rotated it in Neon immediately.

**How I noticed each one, and how they were fixed:** I noticed all three the same way — Render simply
wouldn't go live, and I pasted the deploy log back and asked what was wrong. Each fix was made in the code
(the Dockerfile, an entrypoint script, and a startup-time check on `DATABASE_URL` that now rejects a
Neon-style raw connection string with a one-line error instead of a driver stack trace that could leak a
credential), verified against a real local Docker container before I touched Render again, and committed.
The last fix also means this specific mistake can't leak a password again: the check fires before anything
tries to actually connect.

## What I'd improve or add with more time

- Run the live checks that are still open in `specs/001-slash-command-bot/skipped-tests.md` — an actual
  outage-and-restart test, the Connect and Commands pages end to end, and the full timing pass — and measure
  real cold-database latency against Neon rather than relying on the provisional 2.5-second guess.
- Make a refused command (the "temporarily unavailable" case) visible somewhere in the dashboard instead of
  only in application logs, and add a manual retry button for a notification that's permanently failed.
- Add CI so the test suite runs automatically on every push, instead of me remembering to run it locally.
- The stretch goals from the brief that I deliberately left out of scope for this version: interactive
  buttons, a modal form for `/report`, an AI triage step, and multi-server support with an admin-editable
  rule builder.
