# Discord Slash-Command Bot

A small, admin-managed service that connects to one Discord server and reacts to slash commands, with a
login-protected dashboard. It is built to run unattended: it verifies every request, never handles the
same interaction twice, does not silently lose work when a downstream service hiccups, and never exposes
its secrets.

- **Deployed URL:** `<add the Render URL after deploying>`
- **Stack:** Java 17, Jakarta Servlets and JSP on Tomcat 10.1, Maven (WAR), PostgreSQL on Neon, Docker on
  Render's free tier. No Spring, no front-end framework.

## What it does

1. An **admin** signs in to the dashboard, adds the bot to a Discord server, and picks the channel the bot
   may post to. The bot's `/status` and `/report` commands are registered for that server.
2. **Members** run `/status` or `/report <text>`. Discord sends each command to `/interactions` as a signed
   HTTP request.
3. For each command the service **verifies the signature, records it, applies a simple rule, replies
   privately** to the member, posts `/report` to the chosen channel, and **mirrors a notification** to a
   second Discord channel.
4. The dashboard shows a **live log** of every command and action (including failures and retries) and lets
   the admin **enable/disable each command and edit its reply text**, with no redeploy.

The rule: a `/report` whose text contains "urgent" (any letter case) is flagged **HIGH PRIORITY** in the
reply, the channel post, the notification, and the log.

## How it stays trustworthy

| Concern | What the service does |
|---|---|
| Forged or replayed requests | Ed25519 signature over `timestamp + raw body` on every request; timestamps older than 15 s or more than 5 s ahead are rejected; failures return 401 with no side effects. |
| Same interaction twice | The Discord interaction id is a database primary key; repeats are acknowledged and ignored. |
| Nothing lost | An interaction and its actions are committed in one transaction *before* the acknowledgement. Failed replies, posts and notifications are retried with backoff, survive a restart, and end as a visible permanent failure if they cannot succeed. |
| Discord's 3-second window | Every accepted command is acknowledged immediately (a private "thinking..." state) and the reply follows. If the record cannot be committed within 2.5 s the member gets "Temporarily unavailable, please try again." and nothing is accepted. |
| Secrets | Environment variables only; never in the repo, in browser output, or in logs. The dashboard shows the second channel's address masked. |
| Dashboard access | Sign-in with a BCrypt-hashed password, HttpOnly/Secure/SameSite session cookie, CSRF token on every POST, untrusted text always escaped. |

## Run it locally

Prerequisites: Java 17, Maven 3.9+, Docker.

```text
mvn -q package -DskipTests            # builds target/ROOT.war
docker build -t discord-bot .         # the same image Render builds
```

Run the image with the environment variables below (an env file works: `docker run --env-file .env -p 8080:8080 discord-bot`),
then open `http://localhost:8080/health`. Discord cannot reach localhost, so to receive real commands use a
tunnel (for example `cloudflared`) or test on the deployed service.

### Environment variables

Copy `.env.example` to `.env` (git-ignored) and fill it in. The service refuses to start if a required
variable is missing and says which one, never its value.

| Variable | Purpose |
|---|---|
| `DISCORD_APPLICATION_ID` | Builds Discord API and invite addresses |
| `DISCORD_PUBLIC_KEY` | Verifies request signatures |
| `DISCORD_BOT_TOKEN` | Authorizes bot calls |
| `MIRROR_WEBHOOK_URL` | Second-channel destination (a Discord channel webhook). Secret. |
| `DATABASE_URL` | JDBC address of the Neon **direct** endpoint, for example `jdbc:postgresql://<host>/<db>?sslmode=require&channelBinding=require`. **Not** Neon's own `postgresql://user:pass@host/db` connection string — it needs the `jdbc:` prefix, and credentials go in `DB_USER`/`DB_PASSWORD` below, never in this value. The app checks this at start-up and refuses to start otherwise. |
| `DB_USER`, `DB_PASSWORD` | Database credentials |
| `ADMIN_USERNAME` | The single admin's sign-in name |
| `ADMIN_PASSWORD_HASH` | BCrypt hash of the admin password. Create it with the helper in `specs/001-slash-command-bot/quickstart.md` ("Create the admin password hash"); wrap it in single quotes in a shell or `.env` because it contains `$`. |
| `PORT` | Not needed on Render: it assigns this itself, and the container reads it at startup and binds Tomcat to it. Only set it for a local `docker run` if you want a port other than the 8080 default. |

### Tests

```text
mvn -Dtest=<ClassName> test           # one class (this project's rule)
mvn test                              # everything
```

Persistence tests need Docker (they start a throwaway Postgres with Testcontainers). To use your own
Postgres instead, set `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`.

## Deploy (Render + Neon, all free, no card)

1. **Neon:** create a project (choose a region close to Render's). Use the **direct** connection (not the
   pooled one). Turn the connection string into the `DATABASE_URL` form above and keep the user and password
   separate.
2. **Discord:** create an application and bot in the Developer Portal; note the application id and public key;
   reset and copy the bot token. Create a second channel and a webhook on it for `MIRROR_WEBHOOK_URL`.
3. **Render:** New Web Service from this repository, environment **Docker**, **Free** instance. Set the health
   check path to `/health`. Add every variable above except `PORT`, which Render assigns itself; the container
   reads it at startup and binds Tomcat to it.
4. **Discord portal:** set the Interactions Endpoint URL to `https://<your-app>/interactions`. Discord tests it
   when you save; it only succeeds once the service is live.
5. **UptimeRobot:** add an HTTP monitor on `/health` every 5 minutes. Render's free service sleeps after 15
   idle minutes and takes about a minute to wake, which is far longer than Discord's 3-second window; the ping
   keeps it awake. `/health` never touches the database.
6. Sign in to `/login`, open **Server**, follow the steps to add the bot, choose the server and a channel.

Free-tier facts behind these choices were read from the providers' documentation on 2026-09-27 and are
recorded, with sources, in `specs/001-slash-command-bot/research.md`. Check them again before relying on them.

## Testing it (for reviewers)

**Get the bot into a server.** Either accept the invitation to the maintainer's test server, or add the bot to
your own with this link (replace `APPLICATION_ID` with the application id supplied with the submission):

```text
https://discord.com/oauth2/authorize?client_id=APPLICATION_ID&scope=bot%20applications.commands&permissions=3072
```

**Sign in.** Open the deployed URL. The throwaway admin username is `<username supplied with the submission>`
and its password is supplied with the submission (it is never stored in this repository).

If you added the bot to your own server: open **Server**, click *Refresh the list of servers*, choose your
server and a text channel, and click *Connect and register commands*. This replaces the previous connection (one
server at a time). Notifications still go to the second channel configured by the maintainer.

**Try these** and check all three places: the private reply in Discord, the second channel, and the dashboard log.

| Run in Discord | Expect |
|---|---|
| `/status` | A private reply "The service is operating."; a note in the second channel; a log row with reply and mirror succeeded |
| `/report the printer is out of paper` | A private reply; a post in the chosen channel; a notification; not flagged |
| `/report the server room is on fire, urgent` | Same, with **HIGH PRIORITY** in the reply, post, notification and log |
| On **Commands**: disable `/report`, run it again | "This command is currently unavailable." and no notification |
| On **Commands**: save an empty reply text | Refused; the old text is kept |

## Known limits

- **A refused command is not in the dashboard.** If the record cannot be committed in time (for example the
  free database is waking up), the member sees "Temporarily unavailable, please try again." and the attempt
  appears only in the service's logs, because there was nowhere to record it.
- **Neon's monthly compute allowance** would be exhausted if the database never slept, so nothing polls it and
  the pool never keeps connections alive. If commands arrive at least every 5 minutes all month, the database
  will not sleep either.
- **Render free hours:** one always-on service uses about 744 of the workspace's 750 monthly hours. Do not run a
  second free service in the same workspace.
- **Notifications are at-least-once:** a crash between sending a notification and recording it can produce one
  repeat post. Command processing itself happens once.
- **One server and one admin.** Sessions are held in memory, so a restart signs the admin out.
- **Out of scope for this version:** interactive buttons, modal forms, AI triage of report text, multi-server
  support and a rule-builder UI.

## Project layout

```text
src/main/java/com/example/discordbot/
  config/         environment, timing constants, start-up wiring
  security/       signature check, admin sign-in, CSRF, session hardening
  discord/        Discord API client and payload models
  interactions/   the /interactions endpoint, the record gate, command rules
  persistence/    all SQL (pool, stores)
  jobs/           action runner, dispatcher, retry policy and scheduler
  dashboard/      login, log API, connect and command pages
src/main/webapp/  JSP views (WEB-INF/views) and static files
src/main/resources/db/schema.sql
```

## How this was built

The project follows Spec Kit's spec-driven flow. Read these to see the reasoning:

- `.specify/memory/constitution.md`: the non-negotiable rules the code is held to
- `specs/001-slash-command-bot/spec.md`: what it must do and why
- `specs/001-slash-command-bot/plan.md`, `research.md`, `data-model.md`, `contracts/`: how, with sources
- `specs/001-slash-command-bot/tasks.md` and `skipped-tests.md`: the task list and what was verified where
- `CLAUDE.md` and `AI_NOTES.md`: how AI was used
