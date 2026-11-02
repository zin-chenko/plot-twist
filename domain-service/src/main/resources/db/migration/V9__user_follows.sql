CREATE TABLE user_follows (
    follower_id uuid NOT NULL,
    following_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, following_id),
    CHECK (follower_id <> following_id)
);
-- Both directions support profile counts; identity lives in auth, not user_profiles.
CREATE INDEX user_follows_following_idx ON user_follows(following_id, follower_id);
