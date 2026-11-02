-- Index child foreign keys and retention scans as snapshot/read volumes grow.
CREATE INDEX recommendation_items_story_idx ON recommendation_items(story_id);
CREATE INDEX story_read_signals_story_idx ON story_read_signals(story_id);
CREATE INDEX story_read_signals_retention_idx ON story_read_signals(opened_at);

-- Deletion must not scan the backlog of live stories while inference is offline.
ALTER TABLE story_vector_jobs ADD COLUMN tombstone boolean NOT NULL DEFAULT false;
UPDATE story_vector_jobs j SET tombstone=true WHERE NOT EXISTS(SELECT 1 FROM stories s WHERE s.id=j.story_id);
CREATE INDEX story_vector_jobs_deleted_ready_idx ON story_vector_jobs(available_at) WHERE tombstone AND NOT dead;
CREATE OR REPLACE FUNCTION enqueue_story_vector() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO story_vector_jobs(story_id,tombstone) VALUES (COALESCE(NEW.id,OLD.id),TG_OP='DELETE')
    ON CONFLICT (story_id) DO UPDATE SET generation=story_vector_jobs.generation+1,
      tombstone=excluded.tombstone,attempts=0,available_at=now(),dead=false,last_error=NULL,queued_at=now();
    RETURN NULL;
END;
$$;
