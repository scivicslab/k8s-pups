-- Dataset level: approvals are given per dataset; file-level grants (dataset_grant) stay for exceptions.
CREATE TABLE dataset (
    name        TEXT PRIMARY KEY,   -- e.g. JGAD000001; also the directory name under the original directory
    description TEXT NOT NULL DEFAULT ''
);

INSERT INTO dataset (name) SELECT DISTINCT dataset FROM dataset_file ON CONFLICT DO NOTHING;

ALTER TABLE dataset_file
    ADD CONSTRAINT dataset_file_dataset_fk FOREIGN KEY (dataset) REFERENCES dataset (name) ON DELETE CASCADE;

CREATE TABLE dataset_access (
    user_name  TEXT NOT NULL,
    dataset    TEXT NOT NULL REFERENCES dataset (name) ON DELETE CASCADE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,         -- NULL = no expiry
    granted_by TEXT NOT NULL,
    PRIMARY KEY (user_name, dataset)
);

CREATE INDEX dataset_access_dataset_idx ON dataset_access (dataset);
