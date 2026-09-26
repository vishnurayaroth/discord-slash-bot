# Contract: Dashboard and health routes

**Feature**: `001-slash-command-bot` | **Serves**: FR-018 to FR-025, User Stories 4-6

Pages are JSPs under `WEB-INF/views`, reached only through servlets. Untrusted text (member names,
report text) is shown with `<c:out>` in JSPs and `textContent` in the script (FR-021).

## Access rules

| Route group | Rule |
|---|---|
| `GET` or `HEAD /health` | Public. |
| `GET/POST /login` | Public. |
| Everything under `/dashboard/*` and `/api/*` | Requires a signed-in admin. A filter enforces it. Pages redirect (302) to `/login`. `/api/*` returns **401** with `{"error":"unauthorized"}` and never redirects. |
| Every `POST` except `/login` | Also requires a valid CSRF token (field `csrf`) matching the session. Otherwise 403 and nothing changes (FR-024). |

## Routes

| Method and path | Auth | Purpose | Success | Failure |
|---|---|---|---|---|
| `GET`, `HEAD /health` | none | Keep-warm and Render health check. No database access. | 200, body `ok` | n/a |
| `GET /` | none | Send visitors on. | 302 to `/dashboard` | n/a |
| `GET /login` | none | Sign-in form. | 200 page | n/a |
| `POST /login` | none | Fields `username`, `password`. | On success: session id changed, 302 to `/dashboard` | 200 page with the generic message "Sign-in failed." (never says which part) |
| `POST /logout` | admin + CSRF | End the session. | 302 to `/login` | 403 on bad token |
| `GET /dashboard` | admin | Log page shell; the script starts polling. | 200 page | 302 to `/login` |
| `GET /api/log` | admin | Latest entries. Optional `limit` (default 50, max 200). | 200 JSON below | 401 |
| `GET /dashboard/config` | admin | Show each command's settings. | 200 page | 302 |
| `POST /dashboard/config` | admin + CSRF | Fields `command` (`status` or `report`), `enabled` (true/false), `replyText`. Last saved edit wins. | 302 back with a "Saved" note | 200 page with an error and the old value kept, when `replyText` is empty or only spaces, or `command` is unknown |
| `GET /dashboard/connect` | admin | Show current connection, the "Add the bot" link, and (after Refresh) the servers the bot is in. With `?guild=<id>`, also that server's text channels. | 200 page | 200 page with a Discord error message if a lookup fails |
| `POST /dashboard/connect` | admin + CSRF | Fields `guildId`, `channelId`. Sends a test message, saves the connection, registers the commands. | 302 back with a "Connected" note | 200 page with the error; **nothing saved** if the test message fails |

Session: `HttpOnly`, `Secure`, `SameSite=Lax`, 30 minutes of inactivity. Session id is replaced at
sign-in.

## `GET /api/log` response

```json
{
  "entries": [
    {
      "id": "1234567890123456789",
      "receivedAt": "2026-09-27T10:15:30Z",
      "member": "someuser",
      "command": "report",
      "text": "the printer is on fire, urgent",
      "priority": true,
      "outcome": "handled",
      "overall": "complete",
      "actions": [
        { "kind": "reply",  "status": "succeeded", "attempts": 1, "lastError": null },
        { "kind": "post",   "status": "succeeded", "attempts": 1, "lastError": null },
        { "kind": "mirror", "status": "pending",   "attempts": 3, "lastError": "HTTP 503" }
      ]
    }
  ]
}
```

- Newest first, at most `limit` entries.
- `overall` is derived: `complete` (all succeeded), `in_progress` (any pending), `failed` (any
  permanently failed).
- **Never included:** interaction tokens, the mirror address (even masked here), the bot token, any
  credential. `lastError` holds only a status code and Discord error code, never a URL.
- `member` and `text` are untrusted and must be rendered as plain text.

## Configuration page details

- Shows for each command: enabled, reply text, last updated.
- Shows the second channel as a **masked** address only (for example the first few and last few
  characters), or "not configured". The full address is never sent to the browser (FR-025).
- The invite link is built from the application id (`research.md`, Verified facts) and is safe to
  show.

## Script behavior (`static/js/live-log.js`)

- Poll `/api/log` every 3 s; redraw with `textContent`.
- Pause while the tab is hidden and after 10 minutes without input; show "paused, click to
  resume". A 401 sends the browser to `/login`.
- Reason: keeps the database from being held awake by an idle tab (`research.md` R4, R9).
