# Feature Specification: Discord Slash-Command Bot with Admin Dashboard

**Feature Branch**: `001-slash-command-bot`

**Created**: 2026-09-27

**Status**: Draft

**Input**: User description: "A small admin-managed service that connects to one Discord server and reacts to slash commands, with a login-protected dashboard. Members run commands such as /report <text> and /status; the service records each command, applies a simple rule, responds in Discord, and mirrors a notification to a second channel. An admin signs in to see a live log of every command and action and to configure command behavior. The service runs unattended, so it must reject forged or replayed requests, never process the same interaction twice, never silently lose an interaction, respect Discord's roughly 3-second response window, and never expose secrets."

## Clarifications

### Session 2026-09-27

- Q: What is the "simple rule" a command applies? → A: One fixed content rule. A `/report`
  whose text contains "urgent" (any letter case) is flagged high priority in the reply, the
  channel post, the second-channel notification, and the log. The word is fixed and not
  admin-editable.
- Q: How should the service handle Discord's ~3-second response window for commands? → A:
  Always defer. Every command is acknowledged immediately, and the reply, channel post, and
  notification follow afterwards as a follow-up.
- Q: Who can see the service's reply to a command? → A: Private replies. Only the member who
  ran the command sees the reply, for both `/status` and `/report`.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Members run slash commands and get a response (Priority: P1)

A member of the connected Discord server types `/status` or `/report <text>`. The service
confirms the request really came from Discord, records the command, applies the command's
rule, answers the member privately in Discord (an immediate acknowledgement, then the reply), and
sends a notification to a second channel.

**Why this priority**: This is the whole point of the product. Without it nothing else has
value, and it is the first thing anyone will try.

**Independent Test**: In a test server, run `/status`, `/report hello`, and
`/report this is urgent`. Check that each gets a reply in Discord, that a notification
appears in the second channel, that each command is recorded, and that only the last report
is flagged high priority.

**Acceptance Scenarios**:

1. **Given** the service is connected to a server and both commands are registered,
   **When** a member runs `/status`, **Then** the member sees a reply confirming the service
   is operating, the command is recorded, and a notification appears in the second channel.
2. **Given** the same setup, **When** a member runs `/report` with some text, **Then** the
   member sees a reply confirming the report was received, the report text is recorded, a
   message is posted to the admin-chosen channel, and a notification appears in the second
   channel.
3. **Given** an admin has disabled a command, **When** a member runs it, **Then** the member
   is told the command is currently unavailable, the attempt is recorded, and nothing is
   sent to the second channel.
4. **Given** no server or channel has been connected yet, **When** a member runs a command,
   **Then** the member is told the service is not set up yet, and the attempt is recorded.
5. **Given** the same setup as scenario 1, **When** a member runs `/report` with text
   containing "urgent" in any letter case, **Then** the report is flagged high priority, and
   the flag appears in the member's reply, the post to the chosen channel, the notification in
   the second channel, and the log.
6. **Given** the same setup, **When** a member runs `/report` with text that does not contain
   "urgent", **Then** the report is not flagged anywhere.
7. **Given** other members are present in the channel, **When** a member runs any command,
   **Then** only that member can see the reply; the other members cannot.

---

### User Story 2 - Requests are authentic, handled once, and answered in time (Priority: P1)

The service can be trusted to run unattended. It rejects requests that do not truly come
from Discord, ignores replays, does the work for a given interaction only once even if
Discord delivers it several times, and always acknowledges within Discord's response window.

**Why this priority**: Discord will not accept the service's address unless it verifies
requests, and the service must not be foolable. This is what "working" means for an
unattended service.

**Independent Test**: Send the service a forged request, a tampered request, an old request
replayed, the same genuine interaction several times, and a command whose processing is
slow. Check the outcome of each against the scenarios below.

**Acceptance Scenarios**:

1. **Given** a request with a missing or invalid signature, **When** it arrives, **Then** it
   is rejected, no command is recorded, and nothing is sent to Discord or the second channel.
2. **Given** a genuine request that was captured earlier, **When** it is replayed after it
   has gone stale, **Then** it is rejected the same way.
3. **Given** Discord sends its address-verification check with a valid signature, **When** it
   arrives, **Then** the service acknowledges it correctly so Discord accepts the address;
   the same check with an invalid signature is rejected.
4. **Given** the same interaction is delivered more than once, **When** the repeats arrive,
   **Then** there is exactly one record, one reply, and one notification in the second
   channel.
5. **Given** any accepted command, **When** a member runs it, **Then** the member sees an
   acknowledgement within Discord's response window and the final reply follows afterwards,
   with no "did not respond" error even if the follow-up work takes longer than the window.
6. **Given** the service cannot record a command in time (for example the data store is
   slow or unavailable), **When** a member runs it, **Then** the member sees the private
   notice "Temporarily unavailable, please try again.", nothing is recorded, posted, or
   mirrored, and running the command again later works normally.

---

### User Story 3 - Nothing is lost when a downstream channel or the service hiccups (Priority: P2)

If the second channel (or Discord itself) is briefly unavailable, or the service restarts
partway through, the work already accepted is not dropped. The failure is visible to the
admin, and delivery is retried until it succeeds or is clearly marked as failed.

**Why this priority**: The product is only trustworthy if a brief outage does not silently
lose notifications. It builds on User Story 1.

**Independent Test**: Make the second channel unreachable, run a command, then restore the
channel. Separately, restart the service after a command was accepted but before its
notification was sent.

**Acceptance Scenarios**:

1. **Given** the second channel is unavailable, **When** a member runs a command, **Then**
   the member still gets their reply, the command is recorded, and its notification is shown
   as failed or pending with the reason.
2. **Given** a notification is pending after a failure, **When** the second channel becomes
   available again, **Then** the notification is delivered automatically and the record shows
   it succeeded and how many attempts it took.
3. **Given** a command was accepted but the service restarted before its notification was
   sent, **When** the service is running again, **Then** the pending notification is still
   delivered.
4. **Given** delivery keeps failing, **When** the retry limit is reached, **Then** the record
   is marked as permanently failed with the last error, and the admin can see it.

---

### User Story 4 - Admin signs in and watches activity live (Priority: P2)

An admin signs in to the dashboard and sees a live log of every command and every action the
service took (reply, post, notification), including failures and retries. Nobody who is not
signed in can see anything.

**Why this priority**: The admin needs to see what the service is doing and whether it is
healthy. It is also the main way the work can be inspected.

**Independent Test**: Try the dashboard address while signed out, then sign in with correct
and incorrect credentials, run a command in Discord, and watch it appear.

**Acceptance Scenarios**:

1. **Given** a visitor who is not signed in, **When** they open any dashboard page or data
   address, **Then** they are sent to the sign-in page and no records are shown.
2. **Given** wrong credentials, **When** the admin tries to sign in, **Then** access is
   denied with a message that does not reveal which part was wrong.
3. **Given** a signed-in admin viewing the log, **When** a member runs a command, **Then** it
   appears in the log within a few seconds without the admin reloading the page.
4. **Given** the log, **When** the admin looks at an entry, **Then** they can see the time,
   member, command, text, actions taken, status of each action, and any failure or retry
   detail.
5. **Given** a member submitted text containing markup or script-like content, **When** it is
   shown in the log, **Then** it appears as plain text and does not run.
6. **Given** a signed-in admin, **When** they sign out, **Then** the dashboard is no longer
   accessible until they sign in again.

---

### User Story 5 - Admin connects the service to a Discord server (Priority: P2)

A signed-in admin adds the bot to a Discord server and picks the channel the bot may post
to. The admin can change the channel later.

**Why this priority**: The service cannot post anywhere until it is connected, but the core
command handling can be demonstrated without it, so it ranks below User Story 1.

**Independent Test**: As a signed-in admin, connect a test server, pick a channel, then run
`/report` and check the post lands in that channel.

**Acceptance Scenarios**:

1. **Given** a signed-in admin with a server that does not have the bot, **When** they start
   connecting, **Then** they are guided to add the bot to the server.
2. **Given** the bot is in the server, **When** the admin opens channel selection, **Then**
   they see the channels the bot can post to and can choose one.
3. **Given** a channel is chosen, **When** the service posts for a command, **Then** the post
   goes to that channel.
4. **Given** a connected server, **When** the admin picks a different channel, **Then**
   later posts go to the new channel.

---

### User Story 6 - Admin configures command behavior (Priority: P3)

A signed-in admin views the current settings for each command and changes how it behaves,
without redeploying anything.

**Why this priority**: The product works with sensible defaults, so configurability comes
last. It is still required so behavior is not hard-coded.

**Independent Test**: As admin, disable `/report`, run it in Discord, and see the
"unavailable" reply. Change the reply text and see the new text on the next run.

**Acceptance Scenarios**:

1. **Given** a signed-in admin, **When** they open the configuration page, **Then** they see
   each command with its current settings.
2. **Given** a command, **When** the admin disables it, **Then** the next run by a member
   gets the "currently unavailable" reply.
3. **Given** a command, **When** the admin edits its reply text and saves, **Then** the next
   run uses the new text.
4. **Given** the admin tries to save an empty reply text, **When** they submit, **Then** the
   change is refused with a clear message and the old value stays.
5. **Given** a change request that did not come from the signed-in admin's own use of the
   dashboard (a signed-out visitor, or another website acting while the admin is signed in),
   **When** it is submitted, **Then** it is refused and no setting changes.

---

### Edge Cases

- A `/report` with very long text, or text containing unusual characters or markup: it must
  be recorded and shown safely without breaking the log.
- A command name the service does not recognize: it is recorded and the member is told it is
  not supported.
- A command run from a server other than the connected one: the member is told the service
  is not set up for that server, and nothing is sent to the second channel.
- A burst of many commands at once: every one is recorded and the log stays usable.
- The second channel's address is wrong or the channel was deleted: notifications show as
  failed with the reason, while replies to members are unaffected.
- The admin's sign-in expires while the log is open: the admin is asked to sign in again and
  no records are exposed.
- Two admin edits to the same command's settings close together: the latest saved edit wins
  and the page shows the saved values.
- Report text with "urgent" in different letter case (for example "URGENT") or inside a longer
  word (for example "urgently") is flagged. `/status` is never flagged.
- The final reply cannot be delivered after the acknowledgement: the failure is recorded and
  retried, and the admin can see it.
- The record cannot be written in time: the command is refused with a private notice and
  leaves no trace; it is never processed later, even if the write finishes late. Refused
  commands appear only in the service's technical logs, not in the dashboard, because
  there was nowhere to record them.

## Requirements *(mandatory)*

### Functional Requirements

**Commands and responses**

- **FR-001**: The service MUST offer at least two slash commands in the connected server:
  `/status` and `/report <text>`.
- **FR-002**: `/status` MUST reply confirming the service is operating.
- **FR-003**: `/report` MUST require free text, record it, and reply confirming receipt. If
  the text contains "urgent" in any letter case, the report MUST be flagged high priority and
  the flag MUST appear in the reply, the post to the chosen channel, the second-channel
  notification, and the log; otherwise the report MUST NOT be flagged.
- **FR-004**: Every accepted command MUST be recorded with its identifier, command, member,
  server, time, any text, and outcome. A command is accepted once it has passed verification
  and its record has been committed (FR-026).
- **FR-005**: The service MUST respond in Discord for every handled command with a reply to
  the member, and for `/report` MUST also post to the admin-chosen channel. Every reply to a
  member MUST be visible only to that member.
- **FR-006**: The service MUST send a notification to a second channel for every handled
  command.
- **FR-007**: A disabled command MUST tell the member it is unavailable, be recorded, and
  send no notification to the second channel.
- **FR-008**: A command received before a server and channel are connected, or from an
  unconnected server, MUST tell the member the service is not set up, and be recorded.

**Authenticity, once-only handling, and timing**

- **FR-009**: The service MUST verify Discord's signature on every incoming request and MUST
  reject missing or invalid signatures with no side effects.
- **FR-010**: The service MUST reject replayed requests that are no longer fresh.
- **FR-011**: The service MUST correctly answer Discord's address-verification check.
- **FR-012**: The service MUST process each interaction only once, however many times it is
  delivered.
- **FR-013**: The service MUST respond to every command within Discord's response window.
  An accepted command is acknowledged immediately and its reply and any post are delivered
  afterwards as a follow-up, so that no accepted command's outcome depends on finishing
  inside the window. A refused command (FR-026) is answered with its notice inside the same
  window.

**Durability and visibility of failures**

- **FR-014**: An accepted interaction MUST be recorded before any reply or notification is
  attempted, so that accepted work is not lost.
- **FR-026**: If a command cannot be recorded within the time allowed for responding, the
  service MUST refuse it: reply privately "Temporarily unavailable, please try again.",
  record nothing, and send no post or notification. A refused command MUST NOT be recorded
  or processed later, even if the underlying write finishes late. A later attempt by the
  member is treated as a new command and handled normally.
- **FR-015**: If a reply, post, or notification fails, the failure MUST be stored and shown
  to the admin, and delivery MUST be retried automatically until it succeeds or a retry limit
  is reached, after which it is marked permanently failed.
- **FR-016**: Work that was accepted but not finished MUST survive a service restart and be
  completed afterwards.
- **FR-017**: A failed notification MUST NOT prevent or delay the reply to the member.

**Dashboard and administration**

- **FR-018**: The dashboard and every data address behind it MUST require the admin to sign
  in, and MUST offer sign-out.
- **FR-019**: There MUST be a single admin account whose credentials are supplied by the
  operator at deployment, and the password MUST NOT be kept in readable form.
- **FR-020**: The dashboard MUST show a live log of every command and action, with time,
  member, command, text, priority flag, each action's status, and failure and retry detail, updating without
  a manual reload.
- **FR-021**: Text supplied by members MUST be shown as inert plain text.
- **FR-022**: The admin MUST be able to add the bot to a server, choose from the channels it
  can post to, and change that choice later.
- **FR-023**: The admin MUST be able to view each command's settings, enable or disable it,
  and edit its reply text, with changes taking effect on the next command and no redeploy.
- **FR-024**: Admin actions that change data MUST only be accepted from the signed-in admin
  and MUST not be triggerable by other websites on the admin's behalf.

**Secrets**

- **FR-025**: The service MUST NOT expose the bot token, the application's public key, the
  second channel's address, the credentials of any service it uses, or the admin password in the repository, in
  anything sent to a browser, or in logs. The dashboard MAY show a masked form of the second
  channel's address.

### Key Entities *(include if feature involves data)*

- **Command Interaction**: One command run by a member, identified by Discord's unique
  identifier. Holds the command, member, server, time, text, whether it was flagged high
  priority, and overall outcome.
- **Action**: Something the service did or tried for an interaction (reply, post, or
  notification), with its status, attempt count, and last error.
- **Command Configuration**: The settings for one command: whether it is enabled and its
  reply text.
- **Server Connection**: The connected Discord server and the channel the bot posts to.
- **Admin Account**: The single signed-in operator of the dashboard.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In normal operation, 100% of valid commands receive a response within
  Discord's 3-second window: an acknowledgement, followed by the final reply within 10
  seconds of being run, or, only when the command cannot be recorded in time, the refusal
  notice from FR-026.
- **SC-002**: In a test of at least 20 requests with missing, wrong, or tampered signatures
  and stale replays, 100% are rejected and none is recorded as a command.
- **SC-003**: When the same genuine interaction is delivered 5 times, exactly one record, one
  reply, and one notification result in 100% of trials.
- **SC-004**: When the second channel is unavailable and later restored, 100% of missed
  notifications are delivered within 10 minutes of restoration, and none is silently dropped.
- **SC-005**: After a command is handled, it appears in the admin's live log within 5
  seconds without a reload.
- **SC-006**: A signed-out visitor sees zero records on any dashboard page or data address.
- **SC-007**: A search of the repository, browser-delivered pages, and service logs finds
  zero occurrences of the bot token, the public key, or the second channel's address.
- **SC-008**: A first-time admin can sign in, connect a server, and choose a channel in under
  5 minutes.
- **SC-009**: Disabling a command or changing its reply text takes effect on the very next
  command, with no redeploy.
- **SC-010**: In a test of reports with and without "urgent" in mixed letter case, 100% are
  flagged or left unflagged correctly, and the flag appears in the reply, channel post,
  notification, and log.
- **SC-011**: In a test with a second member present in the channel, 0 replies are visible
  to anyone other than the member who ran the command.
- **SC-012**: In a test where the record cannot be written in time, 100% of refused commands
  produce the private notice and leave zero records, posts, and notifications, including
  when the write finishes late.

## Assumptions

- **One server, one admin.** The service serves a single connected server and a single admin
  account. Multi-server support is out of scope.
- **The "simple rule" is one fixed content rule.** A `/report` whose text contains "urgent"
  (any letter case) is flagged high priority. The word is fixed in the service and is not
  editable by the admin. Admin-configurable settings remain enable/disable and reply text.
- **The second channel is a separate Discord channel** whose destination is supplied by the
  operator at deployment and is not editable in the dashboard, because it is a secret.
- **`/report` posts to the admin-chosen channel; `/status` replies only.** This gives the
  chosen channel a defined purpose.
- **Only accepted work is guaranteed.** While the service is completely down it cannot
  receive requests. The guarantee is that anything it has already accepted is not lost.
  Keeping the service available is an operational matter outside these behavior guarantees.
- **A write still finishing at the deadline.** If a record is already being committed when
  the response time runs out, the service waits a moment for it. If the outcome is still
  unknown it acknowledges rather than refuses, so a member never gets a refusal for a
  command that is later processed. Such a command is completed only if its record lands.
- **Rejected requests are not commands.** A request that fails verification produces no
  command record; at most it leaves an operational note that contains no secrets or request
  content.
- **Volume is small.** A community server with at most a few commands per second at peak.
- **Retention.** All records are kept for the life of the deployment; there is no deletion
  feature.
- **Timing targets** (5-second log freshness, 10-second final reply, 10-minute recovery) are reasonable defaults to
  be finalized in planning, alongside the freshness window for replays and the retry limit.
- **Out of scope for this spec:** interactive buttons, modal forms, AI summarising or triage
  of command text, multi-server support, and a rule-builder UI or admin-editable rules. Each
  may become its own spec.
- **Deliverables** such as the README, `.env.example`, and `AI_NOTES.md` are governed by the
  project constitution, not by this feature spec.
