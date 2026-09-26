# Data Model: Discord Slash-Command Bot with Admin Dashboard

**Feature**: `001-slash-command-bot` | **Date**: 2026-09-27 | **Spec**: [spec.md](spec.md)

Storage is PostgreSQL (Neon). Four tables. The admin account has no table: its credentials come
from the environment (FR-019), and sign-in sessions are held in server memory.

Design rule from the constitution (Principle II): an interaction and all of its actions are
written in **one transaction**, before the acknowledgement is sent. If that transaction cannot
commit in time, the command is not accepted (see the fallback in `research.md`, R3).

## Entity: `interactions` (spec: Command Interaction)

One row per command a member ran. The primary key is Discord's interaction id, so a repeated
delivery collides on the key and is detected as a duplicate (FR-012).

| Field | Type | Rules |
|---|---|---|
| `id` | text, **primary key** | Discord's interaction id. Text, not a number, so it is never mangled. |
| `guild_id` | text, nullable | Server the command was run in. Null for direct messages. |
| `channel_id` | text, nullable | Channel it was run in. |
| `member_id` | text, required | Discord user id of the member. |
| `member_name` | text, required | Member's username at the time. Untrusted text: always escaped on output (FR-021). |
| `command` | text, required | Command name as received (`status`, `report`, or an unrecognized name). |
| `text` | text, nullable | Free text of `/report`. Untrusted. Discord caps a string option at a fixed length, so no extra cap is needed. |
| `priority` | boolean, required, default false | True only for `/report` whose text contains "urgent" in any letter case (FR-003). Always false for other commands. |
| `outcome` | text, required | One of `handled`, `disabled`, `not_configured`, `wrong_server`, `unsupported`. Decided at record time (FR-007, FR-008). |
| `received_at` | timestamp with time zone, required | When the service accepted it. |
| `interaction_token` | text, nullable | Discord's follow-up credential. **Sensitive** (Principle IV): never logged, never returned by any API or page. Cleared as soon as the reply action finishes or the token expires. |
| `token_expires_at` | timestamp with time zone, nullable | `received_at` plus the token's lifetime. Follow-ups after this are impossible. |

Indexes: primary key on `id`; descending index on `received_at` for the log view.

There is no stored overall status. It is derived from the actions: all succeeded = complete,
any pending = in progress, any failed = failed.

## Entity: `actions` (spec: Action)

One row per thing the service must do for an interaction. Created in the same transaction as
the interaction, so an accepted interaction always has its work recorded (FR-014, FR-016).

| Field | Type | Rules |
|---|---|---|
| `id` | bigint, generated, primary key | |
| `interaction_id` | text, required, foreign key to `interactions.id` | |
| `kind` | text, required | `reply` (private follow-up to the member), `post` (message to the admin-chosen channel), `mirror` (notification to the second channel). |
| `status` | text, required | `pending` (waiting for its first or next attempt), `succeeded`, `failed` (permanent). |
| `payload` | text, required | The exact message text to send, composed at record time so every retry sends the same thing even if settings change later. |
| `attempts` | integer, required, default 0 | Number of delivery attempts made. |
| `next_attempt_at` | timestamp with time zone, required | When the retry worker may try next. |
| `last_error` | text, nullable | Short reason for the last failure. Never contains a token, a webhook address, or a request body. |
| `created_at`, `updated_at` | timestamp with time zone, required | |

Constraints: unique on (`interaction_id`, `kind`), so an action can never be created twice for
the same interaction. Partial index on (`next_attempt_at`) where `status = 'pending'`, for the
retry worker.

Which actions exist per outcome:

| Outcome | `reply` | `post` | `mirror` |
|---|---|---|---|
| `handled`, `/status` | yes | no | yes |
| `handled`, `/report` | yes | yes | yes |
| `disabled` | yes ("currently unavailable") | no | no |
| `not_configured` | yes ("not set up") | no | no |
| `wrong_server` | yes ("not set up for this server") | no | no |
| `unsupported` | yes ("not supported") | no | no |

State transitions of an action:

```text
pending --attempt succeeds--------------------------> succeeded
pending --attempt fails, retries remain-------------> pending  (attempts + 1, next_attempt_at pushed out)
pending --attempt fails, non-retryable or exhausted-> failed    (last_error kept, visible to admin)
```

`failed` and `succeeded` are final. Retry counts, delays, and limits are in `research.md` (R5).

## Entity: `command_configs` (spec: Command Configuration)

One row per known command. Rows for `status` and `report` are created on first use with default
reply text.

| Field | Type | Rules |
|---|---|---|
| `command` | text, primary key | `status` or `report`. |
| `enabled` | boolean, required, default true | When false, the command replies "currently unavailable" and sends no notification (FR-007). |
| `reply_text` | text, required | Must not be empty or only whitespace (User Story 6, scenario 4). Enforced both in the page and by a database check. |
| `updated_at` | timestamp with time zone, required | The last saved edit wins when two edits are close together. |

## Entity: `server_connection` (spec: Server Connection)

At most one row, because one server is supported. A check constraint pins the key to 1.

| Field | Type | Rules |
|---|---|---|
| `id` | smallint, primary key, must equal 1 | |
| `guild_id` | text, required | The connected server. |
| `guild_name` | text, required | Shown in the dashboard. |
| `channel_id` | text, required | The channel the `post` action goes to. |
| `channel_name` | text, required | Shown in the dashboard. |
| `connected_at` | timestamp with time zone, required | Updated when the admin changes the channel. |

## Not stored in the database

- **Admin account**: username and password hash come from environment variables; sign-in
  sessions live in server memory. A service restart signs the admin out, which is acceptable
  for a single operator.
- **Second channel address**: supplied by the operator in the environment (Assumptions in the
  spec) and never written to the database or shown in full.
- **Rejected requests**: a request that fails verification produces no row. It leaves at most
  a log line with no secret or request content.

## Relationships

```text
interactions 1 ──── 0..3 actions          (at most one of each kind)
server_connection  (0 or 1 row)   read when recording an interaction, to decide the outcome
command_configs    (2 rows)       read when recording an interaction, to decide the outcome
```

The outcome lookup reads `server_connection` and `command_configs` inside the same short
transaction that inserts the interaction, so the whole record step is one round of database work
inside the time budget.

## Volume

A small community: at most a few commands per second at peak (spec Assumptions). Records are
kept for the life of the deployment, so a small free database is enough. The log view reads the
newest rows only.
