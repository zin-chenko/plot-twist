CREATE TABLE story_attachments (
 id uuid PRIMARY KEY,owner_id uuid NOT NULL,object_key varchar(180) NOT NULL UNIQUE,
 kind varchar(10) NOT NULL CHECK(kind IN ('IMAGE','GIF','VIDEO')),content_type varchar(40) NOT NULL,
 size_bytes bigint NOT NULL CHECK(size_bytes>0 AND size_bytes<=52428800),width integer NOT NULL,height integer NOT NULL,
 duration_seconds double precision,sha256 varchar(64) NOT NULL,
 state varchar(20) NOT NULL CHECK(state IN ('UPLOADING','READY','DELETE_PENDING')),
 created_at timestamptz NOT NULL DEFAULT now(),delete_after timestamptz,lease_token uuid,
 CHECK(width>0 AND height>0 AND width<=12000 AND height<=12000),
 CHECK(sha256 ~ '^[0-9a-f]{64}$'),
 CHECK((kind='IMAGE' AND content_type IN ('image/jpeg','image/png') AND duration_seconds IS NULL)
  OR (kind='GIF' AND content_type='image/gif' AND duration_seconds>0 AND duration_seconds<=60)
  OR (kind='VIDEO' AND content_type IN ('video/mp4','video/webm') AND duration_seconds>0 AND duration_seconds<=120))
);
CREATE INDEX story_attachments_owner_idx ON story_attachments(owner_id);
CREATE INDEX story_attachments_cleanup_idx ON story_attachments(created_at,delete_after);
CREATE TABLE story_attachment_refs (
 story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
 revision bigint NOT NULL,attachment_id uuid NOT NULL REFERENCES story_attachments(id),
 position integer NOT NULL CHECK(position>=0 AND position<8),PRIMARY KEY(story_id,revision,attachment_id)
);
CREATE INDEX story_attachment_refs_attachment_idx ON story_attachment_refs(attachment_id);
CREATE FUNCTION prune_story_attachment_refs() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 DELETE FROM story_attachment_refs WHERE story_id=NEW.id AND revision<>NEW.version
 AND (NEW.published_version IS NULL OR revision<>NEW.published_version);
 RETURN NULL;
END; $$;
CREATE TRIGGER stories_prune_attachments AFTER UPDATE OF version,published_version ON stories FOR EACH ROW EXECUTE FUNCTION prune_story_attachment_refs();
-- New policy understands embedded-media references. Old in-flight decisions cannot approve it.
ALTER TABLE moderation_outbox ALTER COLUMN policy_version SET DEFAULT 'basic-v2';
UPDATE moderation_outbox SET policy_version='basic-v2',sent_at=NULL,dead=false,attempts=0,available_at=now(),lease_until=NULL,lease_token=NULL;
