# Contract: Interactions endpoint

**Feature**: `001-slash-command-bot` | **Serves**: FR-001 to FR-017, User Stories 1-3

The one address Discord calls. Everything here is normative; tests in `research.md` (R15) check
each row.

## `POST /interactions`

Public (no session). Authenticated only by Discord's signature.

### Request

| Part | Rule |
|---|---|
| Method | POST only. Any other method returns 405. |
| Header `X-Signature-Ed25519` | Required. Hex of the 64-byte signature. |
| Header `X-Signature-Timestamp` | Required. Unix seconds as text. |
| Body | JSON, read as **raw bytes** before any parsing. At most 100 KB, otherwise 413 and nothing else happens. |

### Processing order (must not be reordered)

1. Reject a wrong method (405) or oversize body (413).
2. Read the raw body bytes and the two headers. A missing header or non-hex signature is a failed
   check (step 4).
3. **Freshness:** the timestamp must be at most 15 s old and at most 5 s in the future
   (`research.md` R6). Otherwise it is a failed check.
4. **Verify** the Ed25519 signature over `timestamp + raw body` using the application public key.
   Any failed check returns **401**, plain text body `invalid request signature`, and causes **no
   side effects**: no database access, no outbound call, no record.
5. Parse the JSON. If it is not valid JSON (with a valid signature), return 400.
6. Route on `type`.

### Responses

Every success is HTTP 200 with `Content-Type: application/json`.

| Situation | Response body | Side effects |
|---|---|---|
| `type` 1 (PING) | `{"type":1}` | None. |
| `type` 2 (command), accepted | `{"type":5,"data":{"flags":64}}` (private deferred acknowledgement) | Interaction and its actions committed **before** this response is sent; work then starts (see below). |
| `type` 2, **duplicate** interaction id | `{"type":5,"data":{"flags":64}}` | None. No new actions. |
| `type` 2, commit still in flight after the grace period, outcome unknown (`research.md` R2) | `{"type":5,"data":{"flags":64}}` | Acknowledged, not refused. If the record later lands, its actions start then; if it never lands, nothing was recorded and nothing happens. |
| `type` 2, record could not be committed in time, or the database failed (`research.md` R2, R3) | `{"type":4,"data":{"content":"Temporarily unavailable, please try again.","flags":64}}` | None. Nothing accepted, recorded, posted, or mirrored. |
| Any other `type` (buttons, modals, and so on are out of scope) | 400, no body of note | None. |
| Failed signature or freshness | 401 | None. |

### What "accepted" triggers

After the acknowledgement is written, and never before it, the service starts the actions listed
for the outcome in `data-model.md`:

- `reply`: first attempt 500 ms after the acknowledgement; edits the deferred message with the
  final text.
- `post`, `mirror`: first attempt immediately, independently of `reply` (FR-017).
- Failures are recorded and retried under `research.md` R5.

### Outcome decided at record time

Read together with the interaction insert, in the same short transaction:

| Condition | `outcome` | Reply text | Actions |
|---|---|---|---|
| No server connected | `not_configured` | "The service is not set up yet." | `reply` |
| `guild_id` differs from the connected server | `wrong_server` | "The service is not set up for this server." | `reply` |
| Command name unknown | `unsupported` | "That command is not supported." | `reply` |
| Command disabled | `disabled` | "This command is currently unavailable." | `reply` |
| `/status`, enabled | `handled` | configured reply text | `reply`, `mirror` |
| `/report`, enabled | `handled` | configured reply text, plus " Flagged HIGH PRIORITY." when flagged | `reply`, `post`, `mirror` |

Priority rule (FR-003): for `/report` only, `priority` is true when the text contains "urgent" in
any letter case. Checked case-insensitively as a substring, so "urgently" counts.

### Timing

- Whole record step: 2.5 s from arrival, plus up to 0.4 s grace when a commit is already in flight
  (`research.md` R2). Provisional until R1.
- The response body is tiny; nothing slow happens before it.

### Logging (Principle IV)

May log: interaction id, command name, outcome, refusal reason code, step durations.
Must never log: the signature, the raw body, any token, the interaction token, the webhook address.

### Notes

- Discord occasionally sends deliberately invalid signatures and expects 401 (verified). Step 4
  covers it.
- The endpoint URL must be saved in Discord's developer portal only after this endpoint is deployed
  and verifying, because Discord tests it on save.
