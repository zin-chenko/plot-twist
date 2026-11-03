ALTER TABLE story_comments
  ADD COLUMN parent_id uuid,
  ADD COLUMN root_id uuid,
  ADD COLUMN deleted_at timestamptz,
  ADD COLUMN like_count bigint NOT NULL DEFAULT 0 CHECK(like_count>=0),
  ADD CONSTRAINT story_comments_story_id_unique UNIQUE(story_id,id),
  ADD CONSTRAINT story_comments_thread_shape CHECK(
    (parent_id IS NULL AND root_id IS NULL) OR
    (parent_id IS NOT NULL AND root_id IS NOT NULL AND parent_id<>id AND root_id<>id)),
  ADD CONSTRAINT story_comments_parent_fk FOREIGN KEY(story_id,parent_id) REFERENCES story_comments(story_id,id),
  ADD CONSTRAINT story_comments_root_fk FOREIGN KEY(story_id,root_id) REFERENCES story_comments(story_id,id);
ALTER TABLE story_comments DROP CONSTRAINT story_comments_body_check;
ALTER TABLE story_comments ADD CONSTRAINT story_comments_body_check CHECK(
  (deleted_at IS NULL AND length(btrim(body))>0) OR (deleted_at IS NOT NULL AND body=''));
CREATE INDEX story_comments_roots_date_idx ON story_comments(story_id,created_at DESC,id DESC) WHERE parent_id IS NULL;
CREATE INDEX story_comments_roots_popular_idx ON story_comments(story_id,like_count DESC,created_at DESC,id DESC) WHERE parent_id IS NULL;
CREATE INDEX story_comments_replies_idx ON story_comments(story_id,root_id,created_at,id) WHERE root_id IS NOT NULL;
CREATE INDEX story_comments_parent_idx ON story_comments(story_id,parent_id) WHERE parent_id IS NOT NULL;
CREATE TABLE comment_likes (
  comment_id uuid NOT NULL REFERENCES story_comments(id) ON DELETE CASCADE,
  account_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(comment_id,account_id)
);
CREATE FUNCTION update_comment_like_count() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    UPDATE story_comments SET like_count=like_count+1 WHERE id=NEW.comment_id AND deleted_at IS NULL;
    IF NOT FOUND THEN RAISE EXCEPTION 'Comment is not available' USING ERRCODE='23514'; END IF;
  ELSE
    UPDATE story_comments SET like_count=GREATEST(0,like_count-1) WHERE id=OLD.comment_id;
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER comment_like_count AFTER INSERT OR DELETE ON comment_likes
  FOR EACH ROW EXECUTE FUNCTION update_comment_like_count();
-- Popular order is a bounded, short-lived snapshot; changing a like does not skip/duplicate rows.
CREATE TABLE comment_rank_batches (
  id uuid PRIMARY KEY,
  story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
  account_id uuid NOT NULL,
  expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes'
);
CREATE INDEX comment_rank_batches_expiry_idx ON comment_rank_batches(expires_at);
CREATE INDEX comment_rank_batches_story_idx ON comment_rank_batches(story_id);
CREATE TABLE comment_rank_items (
  batch_id uuid NOT NULL REFERENCES comment_rank_batches(id) ON DELETE CASCADE,
  position integer NOT NULL,
  comment_id uuid NOT NULL REFERENCES story_comments(id) ON DELETE CASCADE,
  PRIMARY KEY(batch_id,position)
);
CREATE INDEX comment_rank_items_comment_idx ON comment_rank_items(comment_id);
