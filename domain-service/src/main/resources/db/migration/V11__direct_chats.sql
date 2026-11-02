CREATE TABLE direct_chats (
    id uuid PRIMARY KEY,
    user_low uuid NOT NULL,
    user_high uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    last_sequence bigint NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
    low_read_sequence bigint NOT NULL DEFAULT 0,
    high_read_sequence bigint NOT NULL DEFAULT 0,
    low_visible boolean NOT NULL DEFAULT false,
    high_visible boolean NOT NULL DEFAULT false,
    UNIQUE(user_low,user_high),
    CHECK(user_low<user_high),
    CHECK(low_read_sequence BETWEEN 0 AND last_sequence),
    CHECK(high_read_sequence BETWEEN 0 AND last_sequence),
    CHECK(last_sequence=0 OR (low_visible AND high_visible))
);
CREATE INDEX direct_chats_low_inbox_idx ON direct_chats(user_low,updated_at DESC,id DESC) WHERE low_visible;
CREATE INDEX direct_chats_high_inbox_idx ON direct_chats(user_high,updated_at DESC,id DESC) WHERE high_visible;
CREATE TABLE direct_messages (
    chat_id uuid NOT NULL REFERENCES direct_chats(id) ON DELETE CASCADE,
    sequence bigint NOT NULL CHECK(sequence>0),
    sender_id uuid NOT NULL,
    client_message_id uuid NOT NULL,
    body varchar(4000) NOT NULL CHECK(length(btrim(body))>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(chat_id,sequence),
    UNIQUE(chat_id,sender_id,client_message_id)
);
CREATE INDEX direct_messages_unread_idx ON direct_messages(chat_id,sender_id,sequence);
