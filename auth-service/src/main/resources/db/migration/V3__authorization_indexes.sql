-- Equality lookups use hash indexes: a JWT may exceed PostgreSQL's B-tree entry limit.
ALTER TABLE oauth2_authorization ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX oauth_created_idx ON oauth2_authorization(created_at);
CREATE INDEX oauth_code_idx ON oauth2_authorization USING hash(authorization_code_value);
CREATE INDEX oauth_access_idx ON oauth2_authorization USING hash(access_token_value);
CREATE INDEX oauth_refresh_idx ON oauth2_authorization USING hash(refresh_token_value);
CREATE INDEX oauth_id_token_idx ON oauth2_authorization USING hash(oidc_id_token_value);
CREATE INDEX oauth_state_idx ON oauth2_authorization USING hash(state);
CREATE INDEX oauth_user_code_idx ON oauth2_authorization USING hash(user_code_value);
CREATE INDEX oauth_device_code_idx ON oauth2_authorization USING hash(device_code_value);
