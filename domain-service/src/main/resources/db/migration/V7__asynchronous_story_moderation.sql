-- Working copy and last approved publication have separate versions.
ALTER TABLE stories ADD COLUMN published_title varchar(160), ADD COLUMN published_body varchar(20000),
 ADD COLUMN published_version bigint, ADD COLUMN published_updated_at timestamptz,
 ADD COLUMN moderation_status varchar(24) NOT NULL DEFAULT 'PENDING_MODERATION',
 ADD COLUMN moderation_reason varchar(300), ADD COLUMN moderation_hash varchar(64), ADD COLUMN moderation_job_id uuid;
UPDATE stories SET published_title=title,published_body=body,published_version=version,published_updated_at=updated_at,moderation_status='PUBLISHED';
ALTER TABLE stories ADD CONSTRAINT stories_moderation_status_check CHECK(moderation_status IN ('PENDING_MODERATION','PUBLISHED','REJECTED','REVIEW_REQUIRED'));
ALTER TABLE stories ADD CONSTRAINT stories_published_snapshot_check CHECK((published_version IS NULL AND published_title IS NULL AND published_body IS NULL AND published_updated_at IS NULL) OR (published_version IS NOT NULL AND published_title IS NOT NULL AND published_body IS NOT NULL AND published_updated_at IS NOT NULL));
CREATE INDEX stories_published_feed_idx ON stories(created_at DESC,id DESC) WHERE published_version IS NOT NULL;
CREATE INDEX stories_pending_author_idx ON stories(author_id) WHERE moderation_status='PENDING_MODERATION';
CREATE INDEX stories_manual_review_idx ON stories(created_at,id) WHERE moderation_status='REVIEW_REQUIRED';
CREATE TABLE moderation_outbox (
 job_id uuid PRIMARY KEY, story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
 revision bigint NOT NULL, content_hash varchar(64) NOT NULL, title varchar(160) NOT NULL, body varchar(20000) NOT NULL,
 policy_version varchar(40) NOT NULL DEFAULT 'basic-v1', attempts integer NOT NULL DEFAULT 0,
 available_at timestamptz NOT NULL DEFAULT now(), lease_until timestamptz,lease_token uuid,
 sent_at timestamptz,dead boolean NOT NULL DEFAULT false,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(story_id,revision)
);
CREATE INDEX moderation_outbox_ready_idx ON moderation_outbox(available_at) WHERE NOT dead;
CREATE TABLE moderation_decisions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),job_id uuid NOT NULL,story_id uuid,
 revision bigint NOT NULL,content_hash varchar(64) NOT NULL,policy_version varchar(40) NOT NULL,
 rules_hash varchar(64) NOT NULL,outcome varchar(10) NOT NULL CHECK(outcome IN ('APPROVE','BLOCK','REVIEW')),
 rule_ids jsonb NOT NULL,source varchar(16) NOT NULL DEFAULT 'automated',operator_name varchar(100),operator_reason varchar(500),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX moderation_decisions_automatic_idx ON moderation_decisions(job_id) WHERE source='automated';
CREATE INDEX moderation_decisions_retention_idx ON moderation_decisions(created_at);
CREATE FUNCTION prepare_story_moderation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 NEW.moderation_status='PENDING_MODERATION'; NEW.moderation_reason=NULL;
 NEW.moderation_job_id=gen_random_uuid();
 NEW.moderation_hash=encode(sha256(convert_to(NEW.title,'UTF8')||decode('00','hex')||convert_to(NEW.body,'UTF8')),'hex');
 RETURN NEW;
END; $$;
CREATE TRIGGER stories_prepare_moderation BEFORE INSERT OR UPDATE OF title,body,version ON stories FOR EACH ROW EXECUTE FUNCTION prepare_story_moderation();
CREATE FUNCTION queue_story_moderation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 DELETE FROM moderation_outbox WHERE story_id=NEW.id;
 INSERT INTO moderation_outbox(job_id,story_id,revision,content_hash,title,body) VALUES(NEW.moderation_job_id,NEW.id,NEW.version,NEW.moderation_hash,NEW.title,NEW.body);
 RETURN NULL;
END; $$;
CREATE TRIGGER stories_queue_moderation AFTER INSERT OR UPDATE OF title,body,version ON stories FOR EACH ROW EXECUTE FUNCTION queue_story_moderation();
-- Only published snapshots may be indexed. Draft edits never change the public vector.
DROP TRIGGER stories_vector_insert ON stories;
DROP TRIGGER stories_vector_update ON stories;
DROP TRIGGER stories_vector_delete ON stories;
CREATE OR REPLACE FUNCTION enqueue_story_vector() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO story_vector_jobs(story_id,tombstone) VALUES(COALESCE(NEW.id,OLD.id),TG_OP='DELETE' OR NEW.published_version IS NULL)
 ON CONFLICT(story_id) DO UPDATE SET generation=story_vector_jobs.generation+1,tombstone=excluded.tombstone,
 attempts=0,available_at=now(),dead=false,last_error=NULL,queued_at=now();
 RETURN NULL;
END; $$;
CREATE TRIGGER stories_vector_insert AFTER INSERT ON stories FOR EACH ROW WHEN(NEW.published_version IS NOT NULL) EXECUTE FUNCTION enqueue_story_vector();
CREATE TRIGGER stories_vector_update AFTER UPDATE OF published_title,published_body,published_version ON stories FOR EACH ROW
 WHEN(OLD.published_version IS DISTINCT FROM NEW.published_version OR OLD.published_title IS DISTINCT FROM NEW.published_title OR OLD.published_body IS DISTINCT FROM NEW.published_body) EXECUTE FUNCTION enqueue_story_vector();
CREATE TRIGGER stories_vector_delete AFTER DELETE ON stories FOR EACH ROW EXECUTE FUNCTION enqueue_story_vector();
