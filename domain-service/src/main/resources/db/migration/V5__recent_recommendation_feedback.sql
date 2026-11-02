ALTER TABLE story_reactions ADD COLUMN reacted_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX story_reactions_recent_idx ON story_reactions(account_id,reacted_at DESC);
CREATE FUNCTION touch_story_reaction() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.value IS DISTINCT FROM NEW.value THEN NEW.reacted_at=now(); END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER story_reaction_time BEFORE UPDATE OF value ON story_reactions
    FOR EACH ROW EXECUTE FUNCTION touch_story_reaction();
DROP TRIGGER story_reaction_recommendations ON story_reactions;
CREATE TRIGGER story_reaction_recommendations AFTER INSERT OR DELETE ON story_reactions
    FOR EACH ROW EXECUTE FUNCTION invalidate_recommendations();
CREATE TRIGGER story_reaction_recommendations_change AFTER UPDATE OF value ON story_reactions
    FOR EACH ROW WHEN (OLD.value IS DISTINCT FROM NEW.value) EXECUTE FUNCTION invalidate_recommendations();
