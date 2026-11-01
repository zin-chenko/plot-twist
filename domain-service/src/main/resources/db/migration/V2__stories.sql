CREATE TABLE stories (
    id uuid PRIMARY KEY,
    author_id uuid NOT NULL,
    title varchar(160) NOT NULL CHECK (length(btrim(title)) > 0),
    body varchar(20000) NOT NULL CHECK (length(btrim(body)) > 0),
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX stories_feed_idx ON stories (created_at DESC, id DESC);
CREATE INDEX stories_author_idx ON stories (author_id, created_at DESC, id DESC);
CREATE TABLE story_reactions (
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    account_id uuid NOT NULL,
    value smallint NOT NULL CHECK (value IN (-1, 1)),
    PRIMARY KEY (story_id, account_id)
);
CREATE TABLE story_comments (
    id uuid PRIMARY KEY,
    story_id uuid NOT NULL REFERENCES stories(id) ON DELETE CASCADE,
    author_id uuid NOT NULL,
    body varchar(2000) NOT NULL CHECK (length(btrim(body)) > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX story_comments_page_idx ON story_comments (story_id, created_at DESC, id DESC);
