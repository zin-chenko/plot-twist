CREATE TABLE media_objects (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL,
    object_key varchar(200) UNIQUE NOT NULL,
    sha256 char(64) NOT NULL,
    size_bytes integer NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 1048576),
    state varchar(10) NOT NULL CHECK (state IN ('PENDING', 'ACTIVE', 'DELETING')),
    delete_after timestamptz NOT NULL DEFAULT now() + interval '1 hour',
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX media_cleanup_idx ON media_objects(state, delete_after) WHERE state <> 'ACTIVE';
CREATE TABLE user_profiles (
    account_id uuid PRIMARY KEY,
    display_name varchar(80) NOT NULL,
    given_name varchar(80),
    family_name varchar(80),
    bio varchar(2000),
    birth_date date,
    photo_id uuid REFERENCES media_objects(id),
    version bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
