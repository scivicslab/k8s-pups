-- Records for billing. The controller bills by the cores of a Pod (the CPU limit of the resource profile the user
-- chose, times the time from the moment the session was ready to the moment the Pod was gone) and by the storage
-- reserved for a user or a group (the size of the allocation, per day). Both are kept by user name, so a
-- responsible person can be assigned to the records later, when the registration system of the users exists.

-- ── Pod usage ─────────────────────────────────────────────────────────────────────────────────────────────────────

CREATE TABLE pod_usage (
    id                      BIGSERIAL PRIMARY KEY,
    session_id              TEXT NOT NULL UNIQUE,   -- the tool session (its Pod carries the label session=<id>)
    user_name               TEXT NOT NULL,
    tool                    TEXT NOT NULL,
    pod_name                TEXT NOT NULL,
    profile                 TEXT,                   -- name of the resource profile the user chose (light, standard, ...)
    requests_cpu_millicores INTEGER,                -- CPU request of the Pod, in thousandths of a core
    limits_cpu_millicores   INTEGER,                -- CPU limit of the Pod: the cores that are billed
    created_at              TIMESTAMPTZ NOT NULL,   -- Kubernetes accepted the Pod
    ready_at                TIMESTAMPTZ,            -- the Service of the Pod accepted a connection: billing starts
    ended_at                TIMESTAMPTZ,            -- the Pod was deleted, failed, or was found gone: billing ends
    end_reason              TEXT,
    last_seen_at            TIMESTAMPTZ NOT NULL DEFAULT now(),   -- last time the controller saw the Pod alive
    CHECK (ended_at IS NULL OR ended_at >= created_at)
);

CREATE INDEX pod_usage_user_idx  ON pod_usage (user_name, created_at DESC);
CREATE INDEX pod_usage_open_idx  ON pod_usage (session_id) WHERE ended_at IS NULL;

-- A row is written when the Pod is created and completed twice: ready_at when the session is ready, and ended_at with
-- end_reason when it ends. Nothing that was written is changed, no row is deleted, and the identity of the session
-- cannot be edited, so the records of a billing period cannot drift.
CREATE FUNCTION pod_usage_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
        RAISE EXCEPTION 'pod_usage is append-only';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.session_id IS DISTINCT FROM OLD.session_id
       OR NEW.user_name IS DISTINCT FROM OLD.user_name
       OR NEW.tool IS DISTINCT FROM OLD.tool
       OR NEW.pod_name IS DISTINCT FROM OLD.pod_name
       OR NEW.profile IS DISTINCT FROM OLD.profile
       OR NEW.requests_cpu_millicores IS DISTINCT FROM OLD.requests_cpu_millicores
       OR NEW.limits_cpu_millicores IS DISTINCT FROM OLD.limits_cpu_millicores
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'pod_usage: the identity of a session cannot be changed';
    END IF;
    IF OLD.ready_at IS NOT NULL AND NEW.ready_at IS DISTINCT FROM OLD.ready_at THEN
        RAISE EXCEPTION 'pod_usage: ready_at is written once';
    END IF;
    IF OLD.ended_at IS NOT NULL AND (NEW.ended_at IS DISTINCT FROM OLD.ended_at
                                     OR NEW.end_reason IS DISTINCT FROM OLD.end_reason
                                     OR NEW.last_seen_at IS DISTINCT FROM OLD.last_seen_at) THEN
        RAISE EXCEPTION 'pod_usage: a closed row cannot be changed';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER pod_usage_guard BEFORE UPDATE OR DELETE ON pod_usage
    FOR EACH ROW EXECUTE FUNCTION pod_usage_guard();
CREATE TRIGGER pod_usage_no_truncate BEFORE TRUNCATE ON pod_usage
    FOR EACH STATEMENT EXECUTE FUNCTION pod_usage_guard();

-- The billed time of every session that became ready: from ready_at to ended_at (to now for a Pod that is still
-- running). core_seconds is the CPU limit in cores times the billed seconds.
CREATE VIEW pod_usage_billable AS
SELECT id, session_id, user_name, tool, profile, limits_cpu_millicores, ready_at, ended_at, end_reason,
       EXTRACT(EPOCH FROM (COALESCE(ended_at, now()) - ready_at)) AS billed_seconds,
       limits_cpu_millicores / 1000.0 * EXTRACT(EPOCH FROM (COALESCE(ended_at, now()) - ready_at)) AS core_seconds
FROM pod_usage
WHERE ready_at IS NOT NULL AND limits_cpu_millicores IS NOT NULL;

-- ── Storage reservation ────────────────────────────────────────────────────────────────────────────────────────────

-- Kubernetes quantity (50Gi, 1Ti, 500G, 1.5Ti, 1048576) to bytes. An unknown form is refused, so a size that no PVC
-- could have is not written into a billing record.
CREATE FUNCTION quantity_to_bytes(q TEXT) RETURNS BIGINT AS $$
DECLARE
    m TEXT[];
    factor NUMERIC;
BEGIN
    m := regexp_match(q, '^([0-9]+(?:\.[0-9]+)?)(Ki|Mi|Gi|Ti|Pi|Ei|k|K|M|G|T|P|E)?$');
    IF m IS NULL THEN
        RAISE EXCEPTION 'unsupported quantity: %', q;
    END IF;
    factor := CASE m[2]
        WHEN 'Ki' THEN 1024::numeric
        WHEN 'Mi' THEN 1024::numeric ^ 2
        WHEN 'Gi' THEN 1024::numeric ^ 3
        WHEN 'Ti' THEN 1024::numeric ^ 4
        WHEN 'Pi' THEN 1024::numeric ^ 5
        WHEN 'Ei' THEN 1024::numeric ^ 6
        WHEN 'k' THEN 1000::numeric
        WHEN 'K' THEN 1000::numeric
        WHEN 'M' THEN 1000::numeric ^ 2
        WHEN 'G' THEN 1000::numeric ^ 3
        WHEN 'T' THEN 1000::numeric ^ 4
        WHEN 'P' THEN 1000::numeric ^ 5
        WHEN 'E' THEN 1000::numeric ^ 6
        ELSE 1::numeric
    END;
    RETURN round(m[1]::numeric * factor)::bigint;
END;
$$ LANGUAGE plpgsql IMMUTABLE;

-- Every change of the storage that is reserved for a user or for a group: given, resized, taken away, given back.
-- size is what is reserved after the change; 0 once it is taken away. Written by the triggers below in the statement
-- that changes storage_allocation or storage_group, so a change and its record cannot be apart, whichever code (or
-- administrator's SQL) makes the change.
CREATE TABLE storage_reservation_change (
    id           BIGSERIAL PRIMARY KEY,
    at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    owner_kind   TEXT NOT NULL CHECK (owner_kind IN ('user', 'group')),
    owner_name   TEXT NOT NULL,
    storage_type TEXT NOT NULL,
    size         TEXT NOT NULL,
    size_bytes   BIGINT NOT NULL CHECK (size_bytes >= 0),
    event        TEXT NOT NULL CHECK (event IN ('allocate', 'resize', 'revoke', 'restore'))
);

CREATE INDEX storage_reservation_change_owner_idx ON storage_reservation_change (owner_kind, owner_name, storage_type, at);

CREATE FUNCTION storage_reservation_change_refuse() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'storage_reservation_change is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER storage_reservation_change_no_update_delete BEFORE UPDATE OR DELETE ON storage_reservation_change
    FOR EACH ROW EXECUTE FUNCTION storage_reservation_change_refuse();
CREATE TRIGGER storage_reservation_change_no_truncate BEFORE TRUNCATE ON storage_reservation_change
    FOR EACH STATEMENT EXECUTE FUNCTION storage_reservation_change_refuse();

CREATE FUNCTION storage_reservation_record(kind TEXT, owner TEXT, stype TEXT, sz TEXT, ev TEXT, ts TIMESTAMPTZ)
RETURNS void AS $$
BEGIN
    INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event)
    VALUES (ts, kind, owner, stype, sz, quantity_to_bytes(sz), ev);
END;
$$ LANGUAGE plpgsql;

-- A user's allocation: a new active row is a grant; a row that turns revoked is taken away; a resize of an active
-- row is a resize; a row that turns active again is given back.
CREATE FUNCTION storage_allocation_record() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state = 'active' THEN
            PERFORM storage_reservation_record('user', NEW.user_name, NEW.storage_type, NEW.size,
                CASE WHEN NEW.restored_from IS NULL THEN 'allocate' ELSE 'restore' END, NEW.granted_at);
        END IF;
    ELSE
        IF OLD.state = 'active' AND NEW.state = 'revoked' THEN
            PERFORM storage_reservation_record('user', NEW.user_name, NEW.storage_type, '0', 'revoke',
                COALESCE(NEW.revoked_at, now()));
        ELSIF OLD.state = 'revoked' AND NEW.state = 'active' THEN
            PERFORM storage_reservation_record('user', NEW.user_name, NEW.storage_type, NEW.size, 'restore', now());
        ELSIF NEW.state = 'active' AND OLD.size IS DISTINCT FROM NEW.size THEN
            PERFORM storage_reservation_record('user', NEW.user_name, NEW.storage_type, NEW.size, 'resize', now());
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER storage_allocation_record AFTER INSERT OR UPDATE ON storage_allocation
    FOR EACH ROW EXECUTE FUNCTION storage_allocation_record();

-- A group's storage, the same way. A group is created active; taking its storage away sets revoked, and giving it
-- back sets active again on the same row.
CREATE FUNCTION storage_group_record() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state = 'active' THEN
            PERFORM storage_reservation_record('group', NEW.name, NEW.storage_type, NEW.size, 'allocate', NEW.created_at);
        END IF;
    ELSE
        IF OLD.state = 'active' AND NEW.state = 'revoked' THEN
            PERFORM storage_reservation_record('group', NEW.name, NEW.storage_type, '0', 'revoke',
                COALESCE(NEW.revoked_at, now()));
        ELSIF OLD.state = 'revoked' AND NEW.state = 'active' THEN
            PERFORM storage_reservation_record('group', NEW.name, NEW.storage_type, NEW.size, 'restore', now());
        ELSIF NEW.state = 'active' AND OLD.size IS DISTINCT FROM NEW.size THEN
            PERFORM storage_reservation_record('group', NEW.name, NEW.storage_type, NEW.size, 'resize', now());
        END IF;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER storage_group_record AFTER INSERT OR UPDATE ON storage_group
    FOR EACH ROW EXECUTE FUNCTION storage_group_record();

-- The storage that was reserved when this migration ran. Resizes made before it were not recorded, so the first row
-- of an allocation or a group carries its size at the time of this migration.
INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event)
SELECT granted_at, 'user', user_name, storage_type, size, quantity_to_bytes(size),
       CASE WHEN restored_from IS NULL THEN 'allocate' ELSE 'restore' END
FROM storage_allocation;

INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event)
SELECT revoked_at, 'user', user_name, storage_type, '0', 0, 'revoke'
FROM storage_allocation WHERE state = 'revoked' AND revoked_at IS NOT NULL;

INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event)
SELECT created_at, 'group', name, storage_type, size, quantity_to_bytes(size), 'allocate'
FROM storage_group;

INSERT INTO storage_reservation_change (at, owner_kind, owner_name, storage_type, size, size_bytes, event)
SELECT revoked_at, 'group', name, storage_type, '0', 0, 'revoke'
FROM storage_group WHERE state = 'revoked' AND revoked_at IS NOT NULL;

-- The span of time in which each reserved size held: from one change to the next change of the same storage; the last
-- span is open (to_at is null).
CREATE VIEW storage_reservation_span AS
SELECT owner_kind, owner_name, storage_type, size, size_bytes, at AS from_at,
       LEAD(at) OVER (PARTITION BY owner_kind, owner_name, storage_type ORDER BY at, id) AS to_at
FROM storage_reservation_change;

-- What is billed per day (Japan time). A day counts when the storage was reserved for any part of it, even one
-- second; when the size changed during the day, the largest size of that day counts. A span that ends exactly at
-- midnight does not touch the day that begins there.
CREATE VIEW storage_reservation_daily AS
SELECT s.owner_kind, s.owner_name, s.storage_type, d.day::date AS day, max(s.size_bytes) AS reserved_bytes
FROM storage_reservation_span s
CROSS JOIN LATERAL generate_series(
        date_trunc('day', s.from_at AT TIME ZONE 'Asia/Tokyo'),
        date_trunc('day', (COALESCE(s.to_at, now()) - interval '1 microsecond') AT TIME ZONE 'Asia/Tokyo'),
        interval '1 day') AS d(day)
WHERE s.size_bytes > 0
GROUP BY s.owner_kind, s.owner_name, s.storage_type, d.day;
