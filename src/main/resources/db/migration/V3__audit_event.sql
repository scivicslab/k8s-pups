-- Audit trail of the dataset entitlement: who changed what, and which session got which dataset.
-- Append-only: rows cannot be updated or deleted through SQL (a trigger refuses it).
CREATE TABLE audit_event (
    id         BIGSERIAL PRIMARY KEY,
    at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type TEXT NOT NULL,   -- dataset.grant, dataset.revoke, file.grant, file.revoke, import.catalog, import.access,
                                -- dataset.delete, session.start, session.stop
    actor      TEXT NOT NULL,   -- the administrator, or 'controller' for events the controller causes itself
    user_name  TEXT,            -- the user the event is about
    session_id TEXT,            -- the tool session (its Pod carries the label session=<id>)
    pod_name   TEXT,
    tool       TEXT,
    dataset    TEXT,
    detail     TEXT NOT NULL DEFAULT ''
);

CREATE INDEX audit_event_user_idx    ON audit_event (user_name, at DESC);
CREATE INDEX audit_event_dataset_idx ON audit_event (dataset, at DESC);
CREATE INDEX audit_event_session_idx ON audit_event (session_id);
CREATE INDEX audit_event_type_idx    ON audit_event (event_type, at DESC);

CREATE FUNCTION audit_event_refuse_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_event_no_update_delete BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_refuse_change();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit_event_refuse_change();
