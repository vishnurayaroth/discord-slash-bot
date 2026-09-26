# Contract: Calls the service makes to Discord

**Feature**: `001-slash-command-bot` | **Serves**: FR-001, FR-005, FR-006, FR-013, FR-015, FR-022

Base URL `https://discord.com/api/v10`. Facts are from Discord's documentation as read on
2026-09-27 (see `research.md`). All calls use **connect timeout 3 s, request timeout 5 s**
(R7). Bot calls send `Authorization: Bot <token>`. The interaction-token and webhook calls carry
their credential in the URL and send no authorization header.

## Calls

| Purpose | Method and path | Credential | Success |
|---|---|---|---|
| Edit the deferred private reply (`reply`) | `PATCH /webhooks/{application_id}/{interaction_token}/messages/@original` | interaction token in URL | 200 |
| Post to the admin-chosen channel (`post`) | `POST /channels/{channel_id}/messages` | bot token | 200 |
| Notify the second channel (`mirror`) | `POST` to the address in `MIRROR_WEBHOOK_URL` | address itself | 204 |
| List servers the bot is in (dashboard) | `GET /users/@me/guilds` | bot token | 200 |
| List a server's channels (dashboard) | `GET /guilds/{guild_id}/channels`, keep type 0 (text) | bot token | 200 |
| Test message when connecting (dashboard) | `POST /channels/{channel_id}/messages` | bot token | 200 |
| Register the commands (on connect) | `PUT /applications/{application_id}/guilds/{guild_id}/commands` (bulk overwrite; idempotent) | bot token | 200 |

## Message body rules (every message the service sends)

- JSON with `content` (at most 2000 characters, truncated defensively) and
  `"allowed_mentions": {"parse": []}` so report text cannot ping `@everyone` or a role (R13).
- The private reply is an **edit** of the deferred message; it cannot change privacy (Discord fixes
  it at the first acknowledgement, which sets `flags` 64).
- Composed text, for retries, is stored in `actions.payload` and re-sent unchanged.

Example composed messages (wording is part of the contract; the tests check the flag text):

| Action | Content |
|---|---|
| `mirror` for `/status` | `/status was run by <member>.` |
| `mirror` for `/report` | `[HIGH PRIORITY] ` (only if flagged) + `/report from <member>: <text>` |
| `post` for `/report` | Same text as the `mirror` for `/report`. |
| `reply` | The configured reply text, plus ` Flagged HIGH PRIORITY.` when flagged. |

`<member>` and `<text>` are untrusted; they go inside `content` only, never as embeds or markup.

## Commands registered

| Name | Description | Options |
|---|---|---|
| `status` | Check that the service is running | none |
| `report` | Send a report to the admins | `text`: string, **required**, `max_length` 1000, description "What to report" |

Names are lowercase, 1-32 characters; descriptions 1-100 (Discord's limits).

## Result classification (drives retries, `research.md` R5)

| Result | Class | Action |
|---|---|---|
| 2xx | Success | Mark `succeeded`, clear the interaction token when the `reply` finishes. |
| Network error, timeout, 5xx | Retryable | Back off. |
| 429 | Retryable | Wait at least `retry_after` seconds (body) or `Retry-After` (header), whichever is larger. |
| 404 on the **first three `reply` attempts within 10 s** | Retryable | Expected when the edit races the acknowledgement (unverified; confirm live). |
| Other 4xx (400, 401, 403, 404 elsewhere) | Permanent | Mark `failed` immediately; `last_error` = status plus Discord error code. |
| `reply` and the token has expired (`token_expires_at − 30 s`) | Permanent | Mark `failed`, reason "follow-up window expired". |

## Redaction

`last_error` and every log line are built only from the status code and Discord's numeric error
code. They never include the request URL (which contains the interaction token or webhook token),
the request body, response bodies, or headers.
