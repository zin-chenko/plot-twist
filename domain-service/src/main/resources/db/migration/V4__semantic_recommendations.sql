CREATE TABLE story_vector_jobs (
    story_id uuid PRIMARY KEY,
    generation bigint NOT NULL DEFAULT 1,
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz,
    lease_token uuid,
    dead boolean NOT NULL DEFAULT false,
    last_error varchar(100),
    queued_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX story_vector_jobs_ready_idx ON story_vector_jobs(available_at) WHERE NOT dead;
CREATE TABLE story_vector_state (
    story_id uuid PRIMARY KEY REFERENCES stories(id) ON DELETE CASCADE,
    story_version bigint NOT NULL,
    point_id uuid NOT NULL,
    model_key varchar(64) NOT NULL,
    indexed_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX story_vector_state_model_idx ON story_vector_state(model_key);
CREATE FUNCTION enqueue_story_vector() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO story_vector_jobs(story_id) VALUES (COALESCE(NEW.id,OLD.id))
    ON CONFLICT (story_id) DO UPDATE SET generation=story_vector_jobs.generation+1,
      attempts=0,available_at=now(),dead=false,last_error=NULL,queued_at=now();
    RETURN NULL;
END;
$$;
CREATE TRIGGER stories_vector_insert AFTER INSERT ON stories FOR EACH ROW EXECUTE FUNCTION enqueue_story_vector();
CREATE TRIGGER stories_vector_update AFTER UPDATE OF title,body ON stories FOR EACH ROW EXECUTE FUNCTION enqueue_story_vector();
CREATE TRIGGER stories_vector_delete AFTER DELETE ON stories FOR EACH ROW EXECUTE FUNCTION enqueue_story_vector();
INSERT INTO story_vector_jobs(story_id) SELECT id FROM stories;
CREATE TABLE recommendation_users (account_id uuid PRIMARY KEY, revision bigint NOT NULL DEFAULT 1);
CREATE FUNCTION invalidate_recommendations() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO recommendation_users(account_id) VALUES (COALESCE(NEW.account_id,OLD.account_id))
    ON CONFLICT (account_id) DO UPDATE SET revision=recommendation_users.revision+1;
    RETURN NULL;
END;
$$;
CREATE TRIGGER story_reaction_recommendations AFTER INSERT OR UPDATE OR DELETE ON story_reactions
    FOR EACH ROW EXECUTE FUNCTION invalidate_recommendations();
CREATE TABLE story_read_signals (
    account_id uuid NOT NULL,
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    opened_at timestamptz NOT NULL DEFAULT now(),
    read_at timestamptz,
    PRIMARY KEY(account_id,story_id)
);
CREATE INDEX story_read_signals_recent_idx ON story_read_signals(account_id,read_at DESC) WHERE read_at IS NOT NULL;
CREATE TRIGGER story_read_recommendations AFTER INSERT OR UPDATE ON story_read_signals
    FOR EACH ROW EXECUTE FUNCTION invalidate_recommendations();
CREATE TABLE recommendation_batches (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    signal_revision bigint NOT NULL,
    model_key varchar(64) NOT NULL,
    mode varchar(20) NOT NULL CHECK(mode IN ('personalized','cold_start','fallback')),
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL DEFAULT now()+interval '15 minutes'
);
CREATE INDEX recommendation_batches_user_idx ON recommendation_batches(account_id,created_at DESC);
CREATE INDEX recommendation_batches_expiry_idx ON recommendation_batches(expires_at);
CREATE TABLE recommendation_items (
    batch_id uuid NOT NULL REFERENCES recommendation_batches(id) ON DELETE CASCADE,
    position integer NOT NULL,
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    story_version bigint NOT NULL,
    PRIMARY KEY(batch_id,position),
    UNIQUE(batch_id,story_id)
);
