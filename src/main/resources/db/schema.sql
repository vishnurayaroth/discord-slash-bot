CREATE TABLE IF NOT EXISTS interactions (
  id text PRIMARY KEY,
  guild_id text,
  channel_id text,
  member_id text NOT NULL,
  member_name text NOT NULL,
  command text NOT NULL,
  text text,
  priority boolean NOT NULL DEFAULT false,
  outcome text NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now(),
  interaction_token text,
  token_expires_at timestamptz
);

CREATE INDEX IF NOT EXISTS interactions_received_at_idx ON interactions (received_at DESC);

CREATE TABLE IF NOT EXISTS actions (
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  interaction_id text NOT NULL REFERENCES interactions (id),
  kind text NOT NULL CHECK (kind IN ('reply', 'post', 'mirror')),
  status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'failed')),
  payload text NOT NULL,
  attempts integer NOT NULL DEFAULT 0,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (interaction_id, kind)
);

CREATE INDEX IF NOT EXISTS actions_pending_idx ON actions (next_attempt_at) WHERE status = 'pending';

CREATE TABLE IF NOT EXISTS command_configs (
  command text PRIMARY KEY,
  enabled boolean NOT NULL DEFAULT true,
  reply_text text NOT NULL CHECK (length(btrim(reply_text)) > 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO command_configs (command, reply_text) VALUES
  ('status', 'The service is operating.'),
  ('report', 'Thanks, your report was received.')
ON CONFLICT (command) DO NOTHING;

CREATE TABLE IF NOT EXISTS server_connection (
  id smallint PRIMARY KEY CHECK (id = 1),
  guild_id text NOT NULL,
  guild_name text NOT NULL,
  channel_id text NOT NULL,
  channel_name text NOT NULL,
  connected_at timestamptz NOT NULL DEFAULT now()
);
