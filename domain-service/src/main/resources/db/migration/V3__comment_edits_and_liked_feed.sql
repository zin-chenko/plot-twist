ALTER TABLE story_comments ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);
ALTER TABLE story_comments ADD COLUMN updated_at timestamptz;
UPDATE story_comments SET updated_at = created_at;
ALTER TABLE story_comments ALTER COLUMN updated_at SET DEFAULT now();
ALTER TABLE story_comments ALTER COLUMN updated_at SET NOT NULL;
CREATE INDEX story_reactions_liked_idx ON story_reactions(account_id, story_id) WHERE value = 1;
