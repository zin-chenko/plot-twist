CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

ALTER TABLE users ALTER COLUMN username TYPE varchar(254);
ALTER TABLE authorities ALTER COLUMN username TYPE varchar(254);
CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    username varchar(254) UNIQUE NOT NULL REFERENCES users(username),
    verified boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE account_tokens (
    token_hash bytea PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    purpose varchar(16) NOT NULL CHECK (purpose IN ('VERIFY', 'RESET')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX account_tokens_account_idx ON account_tokens(account_id, purpose, created_at DESC);
CREATE INDEX account_tokens_expiry_idx ON account_tokens(expires_at);
CREATE TABLE email_outbox (
    id uuid PRIMARY KEY,
    recipient varchar(254) NOT NULL,
    subject varchar(200) NOT NULL,
    body text NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX email_outbox_ready_idx ON email_outbox(next_attempt_at);
ALTER TABLE oauth2_authorization ALTER COLUMN principal_name TYPE varchar(254);
ALTER TABLE oauth2_authorization_consent ALTER COLUMN principal_name TYPE varchar(254);
ALTER TABLE SPRING_SESSION ALTER COLUMN PRINCIPAL_NAME TYPE varchar(254);
CREATE TABLE login_attempts (
    username varchar(254) PRIMARY KEY REFERENCES users(username),
    failures integer NOT NULL,
    window_start timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz
);
