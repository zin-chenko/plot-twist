-- Private metadata only. Message bodies remain solely in direct_messages.
CREATE TABLE chat_event_outbox (
  id uuid PRIMARY KEY,
  chat_id uuid NOT NULL REFERENCES direct_chats(id) ON DELETE CASCADE,
  recipient_low uuid,
  recipient_high uuid,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_token uuid,
  lease_until timestamptz,
  attempts integer NOT NULL DEFAULT 0,
  CHECK(recipient_low IS NOT NULL OR recipient_high IS NOT NULL)
);
CREATE INDEX chat_event_outbox_available_idx ON chat_event_outbox(available_at,created_at);
