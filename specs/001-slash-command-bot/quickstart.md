# Quickstart: Discord Slash-Command Bot

**Feature**: `001-slash-command-bot` | **Plan**: [plan.md](plan.md)

How to build, run, and check the service. Nothing here contains a secret: every value below is a
variable name to be supplied by you. Never commit real values.

## Prerequisites

Java 17, Maven 3.9+, Docker, Git. Accounts already prepared: a Discord application with a bot and a
test server, a Neon project, a Render account, a UptimeRobot account (see `DEVELOPMENT_STEPS.md`).

## Environment variables

Set these in a git-ignored `.env` locally and in Render's Environment settings when deployed.
`.env.example` (committed) lists the same names with empty values.

| Variable | Purpose |
|---|---|
| `DISCORD_APPLICATION_ID` | Builds Discord API and invite addresses. |
| `DISCORD_PUBLIC_KEY` | Verifies request signatures. |
| `DISCORD_BOT_TOKEN` | Authorizes bot calls. |
| `MIRROR_WEBHOOK_URL` | Second-channel destination. Secret. |
| `DATABASE_URL` | JDBC address of the Neon **direct** endpoint, with `sslmode=require` and `channelBinding=require`. No credentials in it. |
| `DB_USER`, `DB_PASSWORD` | Database credentials. |
| `ADMIN_USERNAME` | The single admin's sign-in name. |
| `ADMIN_PASSWORD_HASH` | BCrypt hash of the admin password (never the password). |
| `PORT` | Not needed on Render: it assigns this itself, and the container reads it at startup and binds Tomcat to it (see the note on the Dockerfile below). Only set it for a local `docker run` on a port other than the 8080 default. |

The service refuses to start if a required variable is missing, and says which one, never its value.

## Build and test

- Run one test class: `mvn -Dtest=<ClassName> test` (the rule in `CLAUDE.md`).
- Build the WAR: `mvn clean package` produces `target/ROOT.war`.
- Persistence tests need Docker (Testcontainers). If that does not work with your Docker version,
  set `TEST_DATABASE_URL` to a local Postgres from `docker run` (see `research.md` R14).

## Run locally

1. Build the Docker image from the repository's Dockerfile.
2. Run it with the `.env` file passed in and port 8080 published.
3. Check `http://localhost:8080/health` returns `ok`.
4. Discord cannot reach localhost. Start a tunnel to port 8080; it prints a public HTTPS address.
   Free tunnel addresses change on every restart, so you must re-save the address in Discord each
   time. Testing on the deployed service avoids this.

## First-time setup order

1. Deploy the skeleton to Render (Docker, free instance, health check path `/health`; do not set `PORT`,
   Render assigns it and the container reads it at startup — a live deploy found that a hand-set `PORT`
   variable does not reliably change what Render's health check targets).
   Add the UptimeRobot monitor on `/health` at 5-minute intervals.
2. **Measure database latency** using the protocol in `research.md` R1, and record the result there.
3. In Discord's developer portal, save `https://<your-app>/interactions` as the Interactions
   Endpoint URL. Discord tests it on save; it only works once verification is live.
4. Sign in at `https://<your-app>/login` with the admin account.
5. Open Connect, use "Add the bot" if the bot is not in your server, click Refresh, pick the
   server, pick a text channel, save. This registers `/status` and `/report`.
6. Run the checks below.

## Create the admin password hash

`ADMIN_PASSWORD_HASH` must be a BCrypt hash, never the password. From the repository root, build once
and run the helper. It reads the password from standard input, so it never appears in your shell
history or in the process list:

```text
mvn -q package -DskipTests
java -cp "target/ROOT/WEB-INF/lib/*:target/ROOT/WEB-INF/classes" com.example.discordbot.security.HashPassword
```

Type the password, press Enter, and copy the line it prints (it starts with `$2a$10$`) into the
`ADMIN_PASSWORD_HASH` variable on Render. In a `.env` file or a shell, wrap the value in single quotes:
it contains `$` characters that would otherwise be expanded.

## Validation checks (in a test server)

Each maps to the spec's scenarios. Have the dashboard log open.

| Check | Expected | Spec |
|---|---|---|
| Run `/status` | A private reply appears after a brief "thinking"; log shows the command with reply and mirror succeeded; a notification appears in the second channel. | US1.1, SC-001 |
| Run `/report hello` | Private reply; a post in the chosen channel; a notification; not flagged. | US1.2, US1.6 |
| Run `/report this is URGENT` | Flag shown in reply, post, notification, and log. | US1.5, SC-010 |
| Have a second member watch the channel while you run a command | They never see your reply. | US1.7, SC-011 |
| Delete the single `server_connection` row (SQL), run `/status`, then reconnect on the Connect page | "The service is not set up yet." as a private reply and the attempt is logged; after reconnecting, `/status` works again. | US1.4, FR-008 |
| Run `/report` with text at the 1000-character limit, including punctuation and a link | Recorded in full and shown as plain text in the log with the page layout intact; reply and mirror succeed. | Edge case (long text) |
| Run 10 commands in quick succession, mixing `/status` and `/report` | Every one appears exactly once in the log, all replies arrive, and the log stays readable. | Edge case (burst), FR-004, FR-012 |
| Disable `/report` on the config page, run it | "currently unavailable"; no notification; attempt logged. | US1.3, US6.2 |
| Edit the reply text, run again | New text used at once. | US6.3, SC-009 |
| Save an empty reply text | Refused; old text kept. | US6.4 |
| Send `POST /interactions` with no signature, or a wrong one | 401; nothing in the log. | US2.1, SC-002 |
| Replay a captured genuine request after a minute | 401; nothing in the log. | US2.2 |
| Make the second channel unreachable (temporarily change the address), run a command, restore | Reply still arrives; mirror shown pending with attempts; delivered after restoration. | US3.1-3.2, SC-004 |
| Restart the service right after a command | Pending notification still delivered. | US3.3 |
| Visit `/dashboard` and `/api/log` signed out | Redirect / 401; no records. | US4.1, SC-006 |
| Run `/report <b>hi</b> <script>x</script>` | Shown as plain text in the log. | US4.5 |
| Search repo, browser-loaded pages, and logs for the token, public key, mirror address | Zero matches. | SC-007 |
| Let the service idle over 6 minutes, then run a command | Works, or shows "Temporarily unavailable, please try again"; a retry works, and a refused attempt leaves no record. | US2.6, FR-026, SC-012 |
| Run 20 commands, 10 of them right after a 6-minute idle | Each acknowledgement (or refusal notice) within 3 s with no "did not respond" in Discord; each final reply within 10 s (the `reply` action's `updated_at` minus the interaction's `received_at`). Record the numbers in `research.md`. | US2.5, SC-001 |

Not covered by a live row: the wrong-server and unsupported-command outcomes. Commands are registered only for the connected server, so Discord cannot send them; they are covered by unit tests (`CommandRulesTest`, task T039).

## Deploy notes

- One always-on free Render service uses about 744 of the workspace's 750 monthly hours. Do not add
  a second free service to the same workspace.
- Confirm in the first deploy that INFO log lines appear in Render's log view and that the Neon
  console shows the database going idle when the app is quiet (`research.md` R4, R12).
- Rotate any secret that is ever pasted somewhere it should not be.
