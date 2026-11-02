CREATE INDEX user_follows_followers_page_idx ON user_follows(following_id, created_at DESC, follower_id DESC);
CREATE INDEX user_follows_following_page_idx ON user_follows(follower_id, created_at DESC, following_id DESC);

-- Durable unique opens: recommendation signals have a separate 90-day retention policy.
CREATE TABLE story_views (
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    account_id uuid NOT NULL,
    viewed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, account_id)
);
-- Recover only history that still exists; expired recommendation data cannot be reconstructed.
INSERT INTO story_views(story_id, account_id, viewed_at)
SELECT r.story_id, r.account_id, r.opened_at
FROM story_read_signals r JOIN stories s ON s.id=r.story_id
WHERE r.account_id<>s.author_id AND s.published_version IS NOT NULL;
