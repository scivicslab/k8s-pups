-- Dataset entitlement list: which user may read which file of the restricted datasets (JGA, AGD).
CREATE TABLE dataset_file (
    id       BIGSERIAL PRIMARY KEY,
    dataset  TEXT NOT NULL,
    path     TEXT NOT NULL,  -- relative to the original directory, e.g. JGAD000001/sample1.bam
    UNIQUE (dataset, path)
);

CREATE TABLE dataset_grant (
    user_name  TEXT   NOT NULL,
    file_id    BIGINT NOT NULL REFERENCES dataset_file (id) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,  -- NULL = no expiry
    granted_by TEXT   NOT NULL,
    PRIMARY KEY (user_name, file_id)
);

CREATE INDEX dataset_grant_user_idx ON dataset_grant (user_name);
